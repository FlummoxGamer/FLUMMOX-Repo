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

    private const val MAX_STREAMS = 20
    private const val PREFETCH = 3
    private const val CACHE_LIMIT = 24L * 1024 * 1024
    private const val HLS = "application/vnd.apple.mpegurl"
    private const val TS = "video/mp2t"

    private val XOR_KEY = intArrayOf(
        157, 42, 241, 71, 179, 142, 92, 112,
        166, 25, 228, 59, 216, 98, 15, 197
    ).map { it.toByte() }.toByteArray()

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val CDN_REFERER = "https://flixcloud.cc/"

    private class Entry(val id: String, val masterUrl: String,
                        val masterBody: String, val pk: ByteArray) {
        val segments = ConcurrentHashMap<String, List<String>>()
        val playlists = ConcurrentHashMap<String, String>()
    }

    private var server: ServerSocket? = null
    @Volatile private var port = 0
    @Volatile private var running = false
    private val pool = Executors.newCachedThreadPool()
    private val streams = ConcurrentHashMap<String, Entry>()
    private val order = ArrayDeque<String>()

    private val cache = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var cacheBytes = 0L
    private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
        .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    @Synchronized
    private fun ensureServer(): Int {
        if (running && port > 0) return port
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s; port = s.localPort; running = true
            Thread {
                while (running) {
                    try {
                        val conn = s.accept()
                        pool.execute { handle(conn) }
                    } catch (_: Exception) {}
                }
            }.apply { isDaemon = true; name = "ReWasmSrv" }.start()
            port
        } catch (_: Exception) { 0 }
    }

    @Synchronized
    fun register(masterUrl: String, masterBody: String, pk: ByteArray): String? {
        val p = ensureServer(); if (p == 0) return null
        val id = MessageDigest.getInstance("MD5").digest(masterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        if (!streams.containsKey(id)) {
            order.addLast(id)
            while (order.size > MAX_STREAMS) order.removeFirstOrNull()?.let { streams.remove(it) }
        }
        streams[id] = Entry(id, masterUrl, masterBody, pk)
        return "http://127.0.0.1:$p/$id/master.m3u8"
    }

    private fun handle(conn: Socket) {
        try {
            conn.soTimeout = 15000
            val rd = BufferedReader(InputStreamReader(conn.getInputStream()))
            val line = rd.readLine() ?: return
            val parts = line.split(" ")
            val rawPath = parts.getOrNull(1) ?: return
            while (rd.readLine()?.isNotEmpty() == true) {}

            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
            var segs = path.split("/").filter { it.isNotEmpty() }
            if (segs.size >= 2 && segs[0] == segs[1]) segs = segs.drop(1)
            if (segs.size < 2) return err(conn, 404)

            val entry = streams[segs[0]] ?: return err(conn, 404)
            when (segs[1]) {
                "master.m3u8" -> serveMaster(conn, entry, query)
                "p" -> servePlaylist(conn, entry, dec(segs.drop(2).joinToString("/")))
                "s" -> serveSegment(conn, dec(segs.drop(2).joinToString("/")))
                "k" -> serveKey(conn, dec(segs.drop(2).joinToString("/")))
                else -> err(conn, 404)
            }
        } catch (_: Exception) {} finally { try { conn.close() } catch (_: Exception) {} }
    }

    private fun serveMaster(conn: Socket, entry: Entry, query: String) {
        val lang = Regex("""(?:^|&)lang=([^&]*)""").find(query)?.groupValues?.get(1) ?: "sub"
        val rewritten = rewriteMaster(entry, lang) ?: return err(conn, 404)
        send(conn, rewritten.toByteArray(Charsets.UTF_8), HLS)
    }

    private fun rewriteMaster(entry: Entry, lang: String): String? = try {
        val lines = entry.masterBody.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val audioIdx = lines.indices.filter {
            lines[it].startsWith("#EXT-X-MEDIA") && lines[it].contains("TYPE=AUDIO")
        }
        val keep = mutableSetOf<Int>()
        if (audioIdx.isNotEmpty()) {
            val english = audioIdx.filter { i ->
                val l = Regex("""LANGUAGE="([^"]*)"""").find(lines[i])?.groupValues?.get(1) ?: ""
                val n = Regex("""NAME="([^"]*)"""").find(lines[i])?.groupValues?.get(1) ?: ""
                l.startsWith("en") || n.contains("English", true)
            }
            val want = if (lang == "dub") english else audioIdx.filter { it !in english }
            keep.addAll(if (want.isEmpty()) audioIdx else want)
        }
        val out = StringBuilder()
        val baseUri = URI(entry.masterUrl.substringBefore("?"))
        var pendingVariant = false
        for (i in lines.indices) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA") -> {
                    if (line.contains("TYPE=AUDIO")) {
                        if (i in keep) out.append(route(line, baseUri, entry, "p")).append('\n')
                    } else out.append(rewriteAbs(line, baseUri)).append('\n')
                }
                line.startsWith("#EXT-X-SESSION-KEY") -> out.append(route(line, baseUri, entry, "k")).append('\n')
                line.startsWith("#EXT-X-STREAM-INF") -> { pendingVariant = true; out.append(line).append('\n') }
                pendingVariant && !line.startsWith("#") -> {
                    val abs = resolve(line, baseUri) ?: return null
                    out.append(pathFor(entry, abs, "p")).append('\n'); pendingVariant = false
                }
                else -> out.append(line).append('\n')
            }
        }
        out.toString()
    } catch (_: Exception) { null }

    private fun servePlaylist(conn: Socket, entry: Entry, target: String) {
        try {
            entry.playlists[target]?.let {
                send(conn, it.toByteArray(Charsets.UTF_8), HLS)
                prefetchFirst(entry, target); return
            }
            val body = fetch(target) ?: return err(conn, 404)
            val plain = ReanimeWasm.decryptPlaylist(body, entry.pk) ?: return err(conn, 404)
            val base = URI(target.substringBefore("?"))
            val out = StringBuilder()
            val segs = mutableListOf<String>()
            for (raw in plain.split("\n")) {
                val line = raw.trim(); if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-KEY") -> out.append(route(line, base, entry, "k")).append('\n')
                    line.startsWith("#EXT-X-MAP") -> out.append(route(line, base, entry, "s")).append('\n')
                    !line.startsWith("#") -> {
                        val abs = resolve(line, base) ?: line
                        segs.add(abs); out.append(pathFor(entry, abs, "s")).append('\n')
                    }
                    else -> out.append(line).append('\n')
                }
            }
            entry.segments[target] = segs
            entry.playlists[target] = out.toString()
            send(conn, out.toString().toByteArray(Charsets.UTF_8), HLS)
            prefetchFirst(entry, target)
        } catch (_: Exception) { err(conn, 404) }
    }

    private fun serveSegment(conn: Socket, target: String) {
        prefetchAround(target)
        cache[target]?.let { return send(conn, it, TS) }
        try {
            client.newCall(req(target)).execute().use { r ->
                if (!r.isSuccessful) return err(conn, r.code)
                val b = r.body ?: return err(conn, 404)
                streamSeg(conn, target, b.byteStream(), b.contentLength())
            }
        } catch (_: Exception) {}
    }

    private fun serveKey(conn: Socket, target: String) {
        cache[target]?.let { return send(conn, it, "application/octet-stream") }
        val bytes = fetchBytes(target) ?: return err(conn, 404)
        cachePut(target, bytes)
        send(conn, bytes, "application/octet-stream")
    }

    private fun streamSeg(conn: Socket, target: String, src: InputStream, total: Long) {
        try {
            val out = conn.getOutputStream()
            val head = ByteArray(16)
            var hl = 0
            while (hl < 16) {
                val n = src.read(head, hl, 16 - hl); if (n < 0) break; hl += n
            }
            val hdr = imgHeader(head)
            val xor = hdr in 1 until hl && head[hdr] != 0x47.toByte()
            val outLen = if (total >= 0) total - hdr else -1L
            headResp(out, outLen, TS)
            val cap = if (outLen in 0..CACHE_LIMIT) ByteArrayOutputStream(maxOf(64, outLen.toInt())) else null
            writeChunk(out, cap, head, 0, hl, hdr, xor, 0)
            val buf = ByteArray(64 * 1024)
            var rawPos = hl
            while (true) {
                val n = src.read(buf); if (n < 0) break
                writeChunk(out, cap, buf, 0, n, hdr, xor, rawPos)
                rawPos += n
            }
            out.flush()
            val got = cap?.toByteArray()
            if (got != null && outLen >= 0 && got.size.toLong() == outLen) cachePut(target, got)
        } catch (_: Exception) {}
    }

    private fun writeChunk(out: OutputStream, cap: ByteArrayOutputStream?,
                           buf: ByteArray, off: Int, len: Int,
                           hdr: Int, xor: Boolean, rawStart: Int) {
        var i = off; var rawPos = rawStart; val end = off + len
        while (i < end) {
            if (rawPos < hdr) {
                val skip = minOf(hdr - rawPos, end - i)
                i += skip; rawPos += skip; continue
            }
            if (xor) {
                for (j in i until end) {
                    buf[j] = (buf[j].toInt() xor XOR_KEY[(rawPos - hdr) and 15].toInt()).toByte()
                    rawPos++
                }
                out.write(buf, i, end - i); cap?.write(buf, i, end - i); i = end
            } else {
                out.write(buf, i, end - i); cap?.write(buf, i, end - i)
                rawPos += end - i; i = end
            }
        }
    }

    private fun imgHeader(d: ByteArray): Int {
        if (d.size >= 12 &&
            d[0] == 0x52.toByte() && d[1] == 0x49.toByte() && d[2] == 0x46.toByte() && d[3] == 0x46.toByte() &&
            d[8] == 0x57.toByte() && d[9] == 0x45.toByte() && d[10] == 0x42.toByte() && d[11] == 0x50.toByte()
        ) return 12
        if (d.size >= 8 &&
            d[0] == 0x89.toByte() && d[1] == 0x50.toByte() && d[2] == 0x4e.toByte() && d[3] == 0x47.toByte() &&
            d[4] == 0x0d.toByte() && d[5] == 0x0a.toByte() && d[6] == 0x1a.toByte() && d[7] == 0x0a.toByte()
        ) return 8
        return 0
    }

    private fun unwrap(d: ByteArray): ByteArray {
        val hdr = imgHeader(d); if (hdr == 0) return d
        val p = d.copyOfRange(hdr, d.size)
        if (p.isEmpty() || p[0] == 0x47.toByte()) return p
        val o = p.copyOf()
        for (i in o.indices) o[i] = (o[i].toInt() xor XOR_KEY[i and 15].toInt()).toByte()
        return o
    }

    private fun prefetchFirst(entry: Entry, url: String) {
        val segs = entry.segments[url] ?: return
        for (i in 1..minOf(PREFETCH, segs.size - 1)) enqueue(segs[i])
    }

    private fun prefetchAround(target: String) {
        for (entry in streams.values) {
            for (segs in entry.segments.values) {
                val idx = segs.indexOf(target); if (idx < 0) continue
                val end = minOf(idx + PREFETCH, segs.size - 1)
                for (i in idx + 1..end) enqueue(segs[i])
                return
            }
        }
    }

    private fun enqueue(url: String) {
        if (cache.containsKey(url)) return
        if (!inFlight.add(url)) return
        pool.execute {
            try {
                val raw = fetchBytes(url) ?: return@execute
                cachePut(url, unwrap(raw))
            } catch (_: Exception) {} finally { inFlight.remove(url) }
        }
    }

    private fun cachePut(url: String, bytes: ByteArray) {
        synchronized(cache) {
            if (cache.containsKey(url)) return
            cache[url] = bytes
            cacheBytes += bytes.size
            while (cacheBytes > CACHE_LIMIT && cache.isNotEmpty()) {
                val it = cache.entries.iterator(); if (!it.hasNext()) break
                val e = it.next(); cacheBytes -= e.value.size; it.remove()
            }
        }
    }

    private fun rewriteAbs(line: String, base: URI): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = resolve(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="$abs"""")
    }

    private fun route(line: String, base: URI, entry: Entry, r: String): String {
        val m = Regex("""URI="([^"]+)"""").find(line) ?: return line
        val abs = resolve(m.groupValues[1], base) ?: return line
        return line.replaceRange(m.range, """URI="${pathFor(entry, abs, r)}"""")
    }

    private fun pathFor(entry: Entry, abs: String, route: String): String =
        "/${entry.id}/$route/${URLEncoder.encode(abs, "UTF-8")}"

    private fun resolve(ref: String, base: URI): String? = try { base.resolve(ref).toString() } catch (_: Exception) { null }
    private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun req(url: String): Request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", UA)
        .addHeader("Referer", CDN_REFERER)
        .get().build()

    private fun fetch(url: String): String? = try {
        client.newCall(req(url)).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) { null }

    private fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(req(url)).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (_: Exception) { null }

    private fun headResp(out: OutputStream, len: Long, ct: String) {
        val h = if (len >= 0)
            "HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nContent-Length: $len\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        else
            "HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(conn: Socket, bytes: ByteArray, ct: String) {
        try {
            val out = conn.getOutputStream()
            headResp(out, bytes.size.toLong(), ct); out.write(bytes); out.flush()
        } catch (_: Exception) {}
    }

    private fun err(conn: Socket, code: Int) {
        try {
            val out = conn.getOutputStream()
            out.write("HTTP/1.1 $code Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)); out.flush()
        } catch (_: Exception) {}
    }
                                 }
