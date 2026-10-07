package com.flummox.bingeanime

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

// Serves downloaded .vtt files over http://127.0.0.1:{port}/.
// CloudStream's subtitle loader only accepts http(s):// URLs —
// file:// is silently dropped by its OkHttp-based fetcher.
object SubServer {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && port > 0) return port

        synchronized(this) {
            val again = serverSocket
            if (again != null && !again.isClosed && port > 0) return port

            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            serverSocket = s
            port = s.localPort
            BLog.d("sub server listening on 127.0.0.1:$port")

            Thread {
                while (!s.isClosed) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    Thread { handle(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true; name = "SubServer-accept" }.start()

            return port
        }
    }

    fun urlFor(filename: String): String = "http://127.0.0.1:${ensureStarted()}/$filename"

    private fun handle(client: Socket) {
        try {
            val input = client.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            // "GET /abc.vtt HTTP/1.1"
            val path = requestLine.split(" ").getOrNull(1).orEmpty()
            val filename = path.removePrefix("/").substringBefore("?").substringBefore("#")
            if (filename.isBlank() || filename.contains("..") || filename.contains("/")) {
                writeStatus(client, "400 Bad Request")
                return
            }
            val ctx = BingeAnimeCtx.context
            if (ctx == null) { writeStatus(client, "503 Service Unavailable"); return }
            val file = File(File(ctx.filesDir, "anikage_subs"), filename)
            if (!file.exists() || file.length() == 0L) {
                BLog.v("sub server: 404 $filename")
                writeStatus(client, "404 Not Found")
                return
            }
            val bytes = file.readBytes()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/vtt; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = client.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
            BLog.v("sub server: 200 $filename (${bytes.size}b)")
        } catch (e: Exception) {
            BLog.v("sub server: handle failed ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun writeStatus(client: Socket, status: String) {
        try {
            client.getOutputStream().write(
                "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
        } catch (_: Exception) {}
    }
}
