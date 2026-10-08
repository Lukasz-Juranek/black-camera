package com.lj.blackcamera

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny HTTP server:
 *   /          page with the live view
 *   /stream    MJPEG stream (browsers, VLC, OBS, Home Assistant, ...). /video and /mjpeg work too
 *   /snapshot  a single JPEG. /shot.jpg and /snapshot.jpg work too
 */
class MjpegServer(private val port: Int) {
    private val server = ServerSocket()
    private val pool = Executors.newCachedThreadPool()
    private val open = Collections.synchronizedSet(HashSet<Socket>())

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        thread(name = "http-accept", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                open += client
                pool.execute {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // Viewer went away or sent garbage.
                    } finally {
                        open -= client
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        while (reader.readLine()?.isNotEmpty() == true) Unit // headers aren't needed
        val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        when (path) {
            "/", "/index.html" -> send(out, "200 OK", "text/html; charset=utf-8", PAGE.toByteArray())
            "/stream", "/video", "/mjpeg" -> stream(out)
            "/snapshot", "/snapshot.jpg", "/shot.jpg" -> snapshot(out)
            else -> send(out, "404 Not Found", "text/plain", "Not found\n".toByteArray())
        }
    }

    private fun stream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray()
        )
        out.flush()
        Stream.demand.incrementAndGet()
        Stream.addClients(1)
        try {
            var seq = Stream.latest()
            while (true) {
                val (n, jpeg) = Stream.next(seq, 5_000) ?: continue
                seq = n
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            Stream.addClients(-1)
            Stream.demand.decrementAndGet()
        }
    }

    private fun snapshot(out: OutputStream) {
        // Frames are only encoded while someone is watching, so ask for a fresh one.
        Stream.demand.incrementAndGet()
        val frame = try {
            Stream.next(Stream.latest(), 5_000)?.second
        } finally {
            Stream.demand.decrementAndGet()
        }
        if (frame == null) send(out, "503 Service Unavailable", "text/plain", "No frame from the camera\n".toByteArray())
        else send(out, "200 OK", "image/jpeg", frame)
    }

    private fun send(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        const val BOUNDARY = "blackcameraframe"

        val PAGE = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Black Camera</title>
            <style>
              html,body{margin:0;height:100%;background:#000;color:#7a7a7a;font:14px sans-serif}
              img{display:block;width:100vw;height:calc(100vh - 32px);object-fit:contain}
              p{margin:0;height:32px;line-height:32px;text-align:center}
              a{color:#fff;margin:0 10px}
            </style></head>
            <body><img src="/stream" alt="camera">
            <p>Black Camera <a href="/stream">stream</a><a href="/snapshot" download="snapshot.jpg">snapshot</a></p>
            </body></html>
        """.trimIndent()
    }
}
