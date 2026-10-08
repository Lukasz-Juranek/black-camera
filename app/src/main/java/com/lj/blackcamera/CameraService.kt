package com.lj.blackcamera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the camera and the HTTP server. It runs in the foreground so streaming
 * keeps going with the screen off or the app in the background.
 */
class CameraService : LifecycleService() {
    private var server: MjpegServer? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var encoder: ExecutorService
    private val buffer = ByteArrayOutputStream(256 * 1024)

    override fun onCreate() {
        super.onCreate()
        encoder = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> {
                if (server != null) bindCamera()
                return START_NOT_STICKY
            }
            ACTION_TORCH -> {
                camera?.cameraControl?.enableTorch(Stream.torch.value)
                return START_NOT_STICKY
            }
        }
        if (server != null) return START_NOT_STICKY

        val port = Prefs.port(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(port),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            )
        } catch (e: Exception) {
            // No camera permission, or Android refused a background start.
            Stream.error.value = "Couldn't start: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        server = try {
            MjpegServer(port).also { it.start() }
        } catch (e: IOException) {
            Stream.error.value = "Port $port is busy. Pick another one."
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        Stream.error.value = null
        Stream.running.value = true
        bindCamera()
        return START_NOT_STICKY
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Stream.error.value = "Camera unavailable"
                return@addListener
            }
            provider.unbindAll()
            Stream.torch.value = false

            val quality = Prefs.quality(this)
            val turn = Prefs.turn(this)
            // The size is given in the turned frame, so portrait asks for a tall picture.
            val size = if (turn.portrait) Size(quality.height, quality.width) else Size(quality.width, quality.height)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setTargetRotation(turn.surface)
                .build()
            val jpegQuality = Prefs.jpeg(this)
            analysis.setAnalyzer(encoder) { image -> encode(image, jpegQuality) }

            val wanted = if (Prefs.lens(this) == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val selector = when {
                provider.hasCamera(wanted) -> wanted
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            this.analysis = analysis
            camera = try {
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Stream.error.value = "Camera error: ${e.message}"
                null
            }
            Stream.hasTorch.value = camera?.cameraInfo?.hasFlashUnit() == true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun encode(image: ImageProxy, jpegQuality: Int) {
        image.use {
            Stream.size.value = "${image.width}×${image.height}"
            if (Stream.demand.get() == 0) return
            val bitmap = image.toBitmap()
            buffer.reset()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, buffer)
            bitmap.recycle()
            Stream.publish(buffer.toByteArray())
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackCamera:stream")
            .apply { setReferenceCounted(false); acquire() }
        // Keeps Wi-Fi from dozing while the screen is off, which would stall the stream.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "BlackCamera:stream").apply { setReferenceCounted(false); acquire() }
    }

    private fun notification(port: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Camera is streaming")
            .setContentText("http://${Stream.localIp() ?: "phone-ip"}:$port")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        camera?.cameraControl?.enableTorch(false)
        camera = null
        analysis?.clearAnalyzer()
        encoder.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        Stream.running.value = false
        Stream.torch.value = false
        Stream.hasTorch.value = false
        Stream.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "stream"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_RECONFIGURE = "reconfigure"
        private const val ACTION_TORCH = "torch"

        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, CameraService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, CameraService::class.java).setAction(ACTION_STOP))

        /** Re-opens the camera after the lens, quality or rotation changed. */
        fun reconfigure(c: Context) {
            if (Stream.running.value) c.startService(Intent(c, CameraService::class.java).setAction(ACTION_RECONFIGURE))
        }

        fun setTorch(c: Context, on: Boolean) {
            Stream.torch.value = on
            if (Stream.running.value) c.startService(Intent(c, CameraService::class.java).setAction(ACTION_TORCH))
        }
    }
}
