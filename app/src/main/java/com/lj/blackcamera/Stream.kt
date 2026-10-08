package com.lj.blackcamera

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/** State shared between the service, the HTTP server and the screen. */
object Stream {
    val running = MutableStateFlow(false)
    val clients = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val torch = MutableStateFlow(false)
    val hasTorch = MutableStateFlow(false)
    val size = MutableStateFlow("")

    /** Anything that needs frames: stream viewers, snapshot requests, the in-app preview. Frames aren't encoded without it. */
    val demand = AtomicInteger(0)

    private val lock = Object()
    private var jpeg: ByteArray? = null
    private var seq = 0L

    fun publish(frame: ByteArray) = synchronized(lock) {
        jpeg = frame
        seq++
        lock.notifyAll()
    }

    /** Waits for a frame newer than [after]. Returns its number and bytes, or null on timeout. */
    fun next(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (seq <= after || jpeg == null) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            lock.wait(left)
        }
        seq to jpeg!!
    }

    /** Number of the newest frame. Waiting past it skips a stale frame left from before anyone was watching. */
    fun latest() = synchronized(lock) { seq }

    fun clear() = synchronized(lock) { jpeg = null }

    fun addClients(delta: Int) = synchronized(clients) { clients.value += delta }

    /** The phone's address on the local network, preferring Wi-Fi and hotspot over mobile data. */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                when {
                    it.name.startsWith("wlan") -> 0
                    it.name.startsWith("ap") || it.name.startsWith("swlan") -> 1
                    it.name.startsWith("eth") -> 2
                    else -> 3
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
