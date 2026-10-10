package com.flummox.bingeanime

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

object ReanimeWasmServer {

    private const val STREAM_CAP = 20
    private const val LOOKAHEAD = 3
    private const val SEGMENT_CACHE_BYTES = 24L * 1024 * 1024
    private const val MIME_HLS = "application/vnd.apple.mpegurl"
    private const val MIME_TS = "video/mp2t"

    private val SEGMENT_MASK: ByteArray =
        "9d2af147b38e5c70a619e43bd8620fc5".chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val EMBED_REFERER = "https://flixcloud.cc/"

    private const val ROUTE_PLAYLIST = "playlist"
    private const val ROUTE_SEGMENT = "seg"
    private const val ROUTE_KEY = "key"

    data class Registered(val url: String, val hasDub: Boolean)

    private class Channel(
        val id: String,
        val sourceUrl: String,
        val masterBody: String,
        val schedule: ByteArray
    ) {
        val childSegments = ConcurrentHashMap<String, List<String>>()
        val rendered = ConcurrentHashMap<String, String>()
    }

    private var listener: ServerSocket? = null
    @Volatile private var listenerPort = 0
    @Volatile private var listenerLive = false
    private val workers = Executors.newCachedThreadPool()
    private val channels = ConcurrentHashMap<String, Channel>()
    private val channelOrder = ArrayDeque<String>()

    private val segmentCache = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var segmentBytes = 0L
    private val pending = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
        .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    @Synchronized
    private fun ensureListener(): Int {
        if (listenerLive && listenerPort > 0) return listenerPort
        return try {
            val sock = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            listener = sock
            listenerPort = sock.localPort
            listenerLive = true
            Thread {
                while (listenerLive) {
                    try {
                        val client = sock.accept()
                        workers.execute { dispatch(client) }
                    } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; name = "ReWasmSrv" }.start()
            listenerPort
        } catch (_: Exception) { 0 }
    }

    @Synchronized
    fun register(sourceUrl: String, masterBody: String, schedule: ByteArray): Registered? {
        val p = ensureListener(); if (p == 0) return null
        val id = MessageDigest.getInstance("MD5").digest(sourceUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        if (!channels.containsKey(id)) {
            channelOrder.addLast(id)
            while (channelOrder.size > STREAM_CAP) {
                channelOrder.removeFirstOrNull()?.let { channels.remove(it) }
            }
        }
        channels[id] = Channel(id, sourceUrl, masterBody, schedule)
        return Registered("http://127.0.0.1:$p/$id/master.m3u8", hasEnglishTrack(masterBody))
    }

    private fun hasEnglishTrack(master: String): Boolean {
        for (line in master.lineSequence()) {
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            if (!line.contains("TYPE=AUDIO")) continue
            val lang = Regex("""LANGUAGE="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            val name = Regex("""NAME="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            if (lang.startsWith("en", ignoreCase = true) ||
                name.contains("english", ignoreCase = true)) return true
        }
        return false
    }

    private fun dispatch(client: Socket) {
        try {
            client.soTimeout = 15000
            val rd = BufferedReader(InputStreamReader(client.getInputStream()))
            val head = rd.readLine() ?: return
            val tokens = head.split(" ")
            val rawTarget = tokens.getOrNull(1) ?: return
            while (rd.readLine()?.isNotEmpty() == true) {}

            val pathOnly = rawTarget.substringBefore("?")
            val query = rawTarget.substringAfter("?", "")
            var path = pathOnly.split("/").filter { it.isNotEmpty() }
            if (path.size >= 2 && path[0] == path[1]) path = path.drop(1)
            if (path.size < 2) return sendStatus(client, 404)

            val ch = channels[path[0]] ?: return sendStatus(client, 404)
            when (path[1]) {
                "master.m3u8" -> emitMaster(client, ch, query)
                ROUTE_PLAYLIST -> emitPlaylist(client, ch, urlDecode(path.drop(2).joinToString("/")))
                ROUTE_SEGMENT -> emitSegment(client, urlDecode(path.drop(2).joinToString("/")))
                ROUTE_KEY -> emitKey(client, urlDecode(path.drop(2).joinToString("/")))
                else -> sendStatus(client, 404)
            }
        } catch (_: Exception) {} finally { try { client.close() } catch (_: Exception) {} }
    }

    private fun emitMaster(client: Socket, ch: Channel, query: String) {
        val lang = Regex("""(?:^|&)lang=([^&]*)""").find(query)?.groupValues?.get(1) ?: "sub"
        val rebuilt = tagMaster(ch, lang) ?: return sendStatus(client, 404)
        sendBody(client, rebuilt.toByteArray(Charsets.UTF_8), MIME_HLS)
    }

    private fun tagMaster(ch: Channel, lang: String): String? = try {
        val lines = ch.masterBody.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val audioLines = lines.indices.filter {
            lines[it].startsWith("#EXT-X-MEDIA") && lines[it].contains("TYPE=AUDIO")
        }
        val keep = mutableSetOf<Int>()
        if (audioLines.isNotEmpty()) {
            val english = audioLines.filter { i ->
                val l = Regex("""LANGUAGE="([^"]*)"""").find(lines[i])?.groupValues?.get(1).orEmpty()
                val n = Regex("""NAME="([^"]*)"""").find(lines[i])?.groupValues?.get(1).orEmpty()
                l.startsWith("en", true) || n.contains("English", true)
            }
            val wanted = if (lang == "dub") english else audioLines.filter { it !in english }
            keep.addAll(if (wanted.isEmpty()) audioLines else wanted)
        }
        val sb = StringBuilder()
        val base = URI(ch.sourceUrl.substringBefore("?"))
        var awaitingVariant = false
        for (i in lines.indices) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA") -> {
                    if (line.contains("TYPE=AUDIO")) {
                        if (i in keep) sb.append(tagUri(line, base, ch, ROUTE_PLAYLIST)).append('\n')
                    } else sb.append(absoluteUri(line, base)).append('\n')
                }
                line.startsWith("#EXT-X-SESSION-KEY") ->
                    sb.append(tagUri(line, base, ch, ROUTE_KEY)).append('\n')
                line.startsWith("#EXT-X-STREAM-INF") -> { awaitingVariant = true; sb.append(line).append('\n') }
                awaitingVariant && !line.startsWith("#") -> {
                    val abs = uriResolve(line, base) ?: return null
                    sb.append(proxyPath(ch, abs, ROUTE_PLAYLIST)).append('\n')
                    awaitingVariant = false
                }
                else -> sb.append(line).append('\n')
            }
        }
        sb.toString()
    } catch (_: Exception) { null }

    private fun emitPlaylist(client: Socket, ch: Channel, target: String) {
        try {
            ch.rendered[target]?.let {
                sendBody(client, it.toByteArray(Charsets.UTF_8), MIME_HLS)
                primeAfterPlaylist(ch, target); return
            }
            val body = fetchText(target) ?: return sendStatus(client, 404)
            val plain = ReanimeWasm.decryptPlaylist(body, ch.schedule) ?: return sendStatus(client, 404)
            val base = URI(target.substringBefore("?"))
            val sb = StringBuilder()
            val segments = mutableListOf<String>()
            for (raw in plain.split("\n")) {
                val line = raw.trim(); if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-KEY") ->
                        sb.append(tagUri(line, base, ch, ROUTE_KEY)).append('\n')
                    line.startsWith("#EXT-X-MAP") ->
                        sb.append(tagUri(line, base, ch, ROUTE_SEGMENT)).append('\n')
                    !line.startsWith("#") -> {
                        val abs = uriResolve(line, base) ?: line
                        segments.add(abs)
                        sb.append(proxyPath(ch, abs, ROUTE_SEGMENT)).append('\n')
                    }
                    else -> sb.append(line).append('\n')
                }
            }
            val rendered = sb.toString()
            ch.childSegments[target] = segments
            ch.rendered[target] = rendered
            sendBody(client, rendered.toByteArray(Charsets.UTF_8), MIME_HLS)
            primeAfterPlaylist(ch, target)
        } catch (_: Exception) { sendStatus(client, 404) }
    }

    private fun emitSegment(client: Socket, target: String) {
        primeAfterSegment(target)
        segmentCache[target]?.let { return sendBody(client, it, MIME_TS) }
        try {
            http.newCall(buildRequest(target)).execute().use { resp ->
                if (!resp.isSuccessful) return sendStatus(client, resp.code)
                val body = resp.body ?: return sendStatus(client, 404)
                pipeSegment(client, target, body.byteStream(), body.contentLength())
            }
        } catch (_: Exception) {}
    }

    private fun emitKey(client: Socket, target: String) {
        segmentCache[target]?.let { return sendBody(client, it, "application/octet-stream") }
        val blob = fetchBlob(target) ?: return sendStatus(client, 404)
        storeSegment(target, blob)
        sendBody(client, blob, "application/octet-stream")
    }

    private class MaskCursor {
        private var phase = 0
        fun apply(buf: ByteArray, len: Int) {
            var i = 0
            while (i < len) {
                buf[i] = (buf[i].toInt() xor SEGMENT_MASK[phase].toInt()).toByte()
                phase = (phase + 1) and 15
                i++
            }
        }
    }

    private fun pipeSegment(client: Socket, target: String, src: InputStream, total: Long) {
        try {
            val out = client.getOutputStream()
            val headBuf = ByteArray(16)
            var headRead = 0
            while (headRead < 16) {
                val n = src.read(headBuf, headRead, 16 - headRead)
                if (n < 0) break
                headRead += n
            }
            val prefix = containerPrefix(headBuf)
            val masking = prefix in 1 until headRead && headBuf[prefix] != 0x47.toByte()
            val bodyLength = if (total >= 0) total - prefix else -1L
            writeResponseHead(out, bodyLength, MIME_TS)

            val sink = if (bodyLength in 0..SEGMENT_CACHE_BYTES)
                ByteArrayOutputStream(maxOf(64, bodyLength.toInt())) else null
            val cursor = MaskCursor()

            if (headRead > prefix) {
                val slice = headBuf.copyOfRange(prefix, headRead)
                if (masking) cursor.apply(slice, slice.size)
                out.write(slice)
                sink?.write(slice)
            }

            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(chunk)
                if (n < 0) break
                if (masking) cursor.apply(chunk, n)
                out.write(chunk, 0, n)
                sink?.write(chunk, 0, n)
            }
            out.flush()
            val captured = sink?.toByteArray()
            if (captured != null && bodyLength >= 0 && captured.size.toLong() == bodyLength) {
                storeSegment(target, captured)
            }
        } catch (_: Exception) {}
    }

    private fun containerPrefix(d: ByteArray): Int {
        if (d.size >= 12 &&
            d[0] == 0x52.toByte() && d[1] == 0x49.toByte() &&
            d[2] == 0x46.toByte() && d[3] == 0x46.toByte() &&
            d[8] == 0x57.toByte() && d[9] == 0x45.toByte() &&
            d[10] == 0x42.toByte() && d[11] == 0x50.toByte()) return 12
        if (d.size >= 8 &&
            d[0] == 0x89.toByte() && d[1] == 0x50.toByte() &&
            d[2] == 0x4e.toByte() && d[3] == 0x47.toByte() &&
            d[4] == 0x0d.toByte() && d[5] == 0x0a.toByte() &&
            d[6] == 0x1a.toByte() && d[7] == 0x0a.toByte()) return 8
        return 0
    }

    private fun stripContainer(d: ByteArray): ByteArray {
        val prefix = containerPrefix(d)
        if (prefix == 0) return d
        val body = d.copyOfRange(prefix, d.size)
        if (body.isEmpty() || body[0] == 0x47.toByte()) return body
        val cursor = MaskCursor()
        cursor.apply(body, body.size)
        return body
    }

    private fun primeAfterPlaylist(ch: Channel, url: String) {
        val segs = ch.childSegments[url] ?: return
        for (i in 1..minOf(LOOKAHEAD, segs.size - 1)) queueFetch(segs[i])
    }

    private fun primeAfterSegment(target: String) {
        for (ch in channels.values) {
            for (segs in ch.childSegments.values) {
                val idx = segs.indexOf(target); if (idx < 0) continue
                val end = minOf(idx + LOOKAHEAD, segs.size - 1)
                for (i in idx + 1..end) queueFetch(segs[i])
                return
            }
        }
    }

    private fun queueFetch(url: String) {
        if (segmentCache.containsKey(url)) return
        if (!pending.add(url)) return
        workers.execute {
            try {
                val raw = fetchBlob(url) ?: return@execute
                storeSegment(url, stripContainer(raw))
            } catch (_: Exception) {} finally { pending.remove(url) }
        }
    }

    private fun storeSegment(url: String, blob: ByteArray) {
        synchronized(segmentCache) {
            if (segmentCache.containsKey(url)) return
            segmentCache[url] = blob
            segmentBytes += blob.size
            while (segmentBytes > SEGMENT_CACHE_BYTES && segmentCache.isNotEmpty()) {
                val it = segmentCache.entries.iterator()
                if (!it.hasNext()) break
                val oldest = it.next()
                segmentBytes -= oldest.value.size
                it.remove()
            }
        }
    }

    private fun absoluteUri(line: String, base: URI): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = uriResolve(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="$abs"""")
    }

    private fun tagUri(line: String, base: URI, ch: Channel, route: String): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = uriResolve(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${proxyPath(ch, abs, route)}"""")
    }

    private fun proxyPath(ch: Channel, abs: String, route: String): String =
        "/${ch.id}/$route/${URLEncoder.encode(abs, "UTF-8")}"

    private fun uriResolve(ref: String, base: URI): String? =
        try { base.resolve(ref).toString() } catch (_: Exception) { null }

    private fun urlDecode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", USER_AGENT)
        .addHeader("Referer", EMBED_REFERER)
        .get().build()

    private fun fetchText(url: String): String? = try {
        http.newCall(buildRequest(url)).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (_: Exception) { null }

    private fun fetchBlob(url: String): ByteArray? = try {
        http.newCall(buildRequest(url)).execute().use {
            if (it.isSuccessful) it.body?.bytes() else null
        }
    } catch (_: Exception) { null }

    private fun writeResponseHead(out: OutputStream, length: Long, mime: String) {
        val header = if (length >= 0)
            "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        else
            "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
    }

    private fun sendBody(client: Socket, blob: ByteArray, mime: String) {
        try {
            val out = client.getOutputStream()
            writeResponseHead(out, blob.size.toLong(), mime)
            out.write(blob)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun sendStatus(client: Socket, code: Int) {
        try {
            val out = client.getOutputStream()
            out.write("HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }
                                 }
