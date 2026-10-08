package com.lj.blackcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCamera()) {
                CameraService.start(this)
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // "Don't ask again" was chosen: the only way left is the app's settings page.
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            CameraTheme { CameraScreen(onStart = ::start) }
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        val needed = buildList {
            if (!hasCamera()) add(Manifest.permission.CAMERA)
            // The notification shows the address and a Stop button. Streaming works without it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) CameraService.start(this) else askPermission.launch(needed.toTypedArray())
    }
}
