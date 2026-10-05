package com.wzm.launcher.server

import com.wzm.launcher.LauncherConfig
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * EmbeddedLocalServer — minimal HTTP server listening ONLY on 127.0.0.1:18081.
 * Runs on a background thread, never on main thread.
 * Implements endpoints required for MVP:
 *   GET /health  -> 200 OK
 *   GET /        -> 200 simple HTML
 *   GET /__hits  -> 200 JSON list of hits
 *   POST /__reset -> 200 resets hits
 *
 * No external dependencies (no NanoHTTPD) to keep build minimal.
 */
class EmbeddedLocalServer(
    private val host: String = LauncherConfig.HOST,
    private val port: Int = LauncherConfig.PORT
) : LocalServer, OfflineContentServer {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val hits = CopyOnWriteArrayList<Hit>()
    private val hitCounter = AtomicInteger(0)

    data class Hit(val id: Int, val method: String, val path: String, val timestamp: Long)

    override fun getPort(): Int = port
    override fun getHost(): String = host
    override fun isRunning(): Boolean = running.get()

    @Synchronized
    override fun start() {
        if (running.get()) return
        val addr = InetAddress.getByName(host)
        serverSocket = ServerSocket(port, 50, addr)
        running.set(true)
        serverThread = thread(name = "EmbeddedLocalServer", isDaemon = true) {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    thread(isDaemon = true) { handleSocket(socket) }
                } catch (e: IOException) {
                    if (running.get()) e.printStackTrace()
                    break
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        try { serverThread?.join(2000) } catch (_: InterruptedException) {}
        serverThread = null
    }

    private fun handleSocket(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5000
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val output = BufferedOutputStream(s.getOutputStream())

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendResponse(output, 400, "Bad Request", "text/plain", "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore("?")
                var line: String?
                var contentLength = 0
                while (input.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    if (line!!.lowercase().startsWith("content-length:")) {
                        contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    try { input.read(buf, 0, contentLength) } catch (_: Exception) {}
                }

                val hit = Hit(hitCounter.incrementAndGet(), method, path, System.currentTimeMillis())
                hits.add(hit)
                if (hits.size > 1000) hits.removeAt(0)

                when {
                    method == "GET" && path == "/health" -> {
                        sendResponse(output, 200, "OK", "text/plain", handleHealth())
                    }
                    method == "GET" && path == "/" -> {
                        sendResponse(output, 200, "OK", "text/html", handleRoot())
                    }
                    method == "GET" && path == "/__hits" -> {
                        sendResponse(output, 200, "OK", "application/json", handleHits())
                    }
                    method == "POST" && path == "/__reset" -> {
                        val body = handleReset()
                        sendResponse(output, 200, "OK", "application/json", body)
                    }
                    method == "GET" && path == "/__reset" -> {
                        val body = handleReset()
                        sendResponse(output, 200, "OK", "application/json", body)
                    }
                    else -> {
                        sendResponse(output, 404, "Not Found", "text/plain", "Not Found: $path")
                    }
                }
            } catch (e: Exception) {
                try {
                    val out = BufferedOutputStream(s.getOutputStream())
                    sendResponse(out, 500, "Internal Error", "text/plain", "Error: ${e.message}")
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, message: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $message\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    override fun handleRoot(): String = """
        <html><head><title>Project Rezone</title></head>
        <body><h1>WZM Offline Server</h1>
        <p>Servidor offline funcionando em $host:$port</p>
        <p><a href="/health">/health</a> | <a href="/__hits">/__hits</a></p>
        <p>CDNI real ainda não implementado (stub funcional).</p>
        </body></html>
    """.trimIndent()

    override fun handleHits(): String {
        val json = hits.joinToString(",", "[", "]") { h ->
            """{"id":${h.id},"method":"${h.method}","path":"${h.path}","ts":${h.timestamp}}"""
        }
        return json
    }

    override fun handleReset(): String {
        hits.clear()
        hitCounter.set(0)
        return """{"reset":true,"count":0}"""
    }

    fun getHitsCount(): Int = hits.size
    fun getHitsSnapshot(): List<Hit> = hits.toList()
}
