package com.flummox.bingeanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object ReanimeWasm {
    const val BASE = "https://reanime.to"
    const val EMBED = "https://flixcloud.cc"

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val TTL_SEARCH = 30L * 60 * 1000
    private const val TTL_SERVERS = 6L * 60 * 60 * 1000

    data class Hit(
        val slug: String, val anilistId: Int, val title: String,
        val altTitles: List<String>, val year: Int?, val poster: String?
    )
    data class Srv(val name: String, val url: String, val dataType: String)
    data class Resolved(
        val masterUrl: String, val masterBody: String,
        val pk: ByteArray, val subs: List<Pair<String, String>>
    )

    private fun jsonHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$BASE/"
    )
    private fun embedHeaders() = mapOf("User-Agent" to UA, "Referer" to "$BASE/")
    private fun cdnHeaders() = mapOf("User-Agent" to UA, "Referer" to "$EMBED/")

    suspend fun search(query: String): List<Hit> {
        val ck = "reanime2:s:${query.lowercase()}"
        BCCache.get(ck, TTL_SEARCH)?.let { cached ->
            return try { parseSearch(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        return try {
            val url = "$BASE/api/v1/search?limit=25&q=${URLEncoder.encode(query, "UTF-8")}"
            val res = app.get(url, headers = jsonHeaders())
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseSearch(JSONObject(res.text))
        } catch (e: Exception) { BLog.e("reanime search: ${e.message}"); emptyList() }
    }

    private fun parseSearch(root: JSONObject): List<Hit> {
        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("anime_id").takeIf { it.isNotBlank() } ?: continue
            val alId = o.optInt("anilist_id", 0).takeIf { it > 0 } ?: continue
            val t = o.optJSONObject("title")
            val en = t?.optString("english")?.takeIf { it.isNotBlank() }
            val ro = t?.optString("romaji")?.takeIf { it.isNotBlank() }
            val na = t?.optString("native")?.takeIf { it.isNotBlank() }
            val primary = en ?: ro ?: na ?: continue
            val cover = o.optJSONObject("cover_image")
                ?.optString("large")?.takeIf { it.startsWith("http") }
            out.add(Hit(
                slug, alId, primary,
                listOfNotNull(en, ro, na).distinct(),
                o.optInt("season_year", 0).takeIf { it > 0 }, cover
            ))
        }
        return out
    }

    suspend fun servers(anilistId: Int, ep: Int): List<Srv> {
        val ck = "reanime2:srv:$anilistId:$ep"
        BCCache.get(ck, TTL_SERVERS)?.let { cached ->
            return try { parseServers(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        return try {
            val url = "$BASE/api/flix/$anilistId/$ep"
            val res = app.get(url, headers = jsonHeaders() + mapOf("Referer" to "$BASE/watch/"))
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseServers(JSONObject(res.text))
        } catch (e: Exception) { BLog.e("reanime servers: ${e.message}"); emptyList() }
    }

    private fun parseServers(root: JSONObject): List<Srv> {
        if (!root.optBoolean("success", false)) return emptyList()
        val arr = root.optJSONArray("servers") ?: return emptyList()
        val out = mutableListOf<Srv>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("serverName").takeIf { it.isNotBlank() } ?: continue
            val url = o.optString("dataLink").takeIf { it.startsWith("http") } ?: continue
            val dt = o.optString("dataType").ifBlank { "sub" }
            out.add(Srv(name, url, dt))
        }
        return out
    }

    fun pickBest(hits: List<Hit>, query: String, year: Int?): Hit? {
        if (hits.isEmpty()) return null
        fun norm(s: String) = s.lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val qn = norm(query)
        hits.filter { h -> h.altTitles.any { norm(it) == qn } }.let {
            if (it.isNotEmpty()) return it.firstOrNull { y -> year != null && y.year == year } ?: it.first()
        }
        hits.filter { h -> h.altTitles.any { t ->
            val n = norm(t); n.isNotBlank() && (n.contains(qn) || qn.contains(n))
        } }.let {
            if (it.isNotEmpty()) return it.firstOrNull { y -> year != null && y.year == year } ?: it.first()
        }
        val qw = qn.split(" ").filter { it.isNotBlank() }.toSet()
        if (qw.isEmpty()) return null
        return hits.mapNotNull { h ->
            val best = h.altTitles.maxOfOrNull { t ->
                val tw = norm(t).split(" ").filter { it.isNotBlank() }.toSet()
                if (tw.isEmpty()) 0f else qw.intersect(tw).size.toFloat() / qw.size
            } ?: 0f
            if (best >= 0.7f) h to best else null
        }.maxByOrNull { it.second }?.first
    }

    suspend fun resolve(embedUrl: String): Resolved? = try {
        val page = app.get(embedUrl, headers = embedHeaders()).text
        val region = page.substringAfter("node_ids", "")
        if (region.isBlank()) { BLog.e("reanime resolve: no node_ids"); null } else {

        val seed = Regex("""obfuscation_seed\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        val wasmB64 = Regex("""w_payload\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        if (seed == null || wasmB64 == null) { BLog.e("reanime: no seed/wasm"); null } else {

        val e = shaChain(seed)
        val s2 = shaChain(e)
        val kfB64 = Regex(""""?kf_${Regex.escape(e.substring(8, 16))}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        val ivfB64 = Regex(""""?ivf_${Regex.escape(e.substring(16, 24))}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        val token = Regex(""""?${Regex.escape(e.substring(48, 64))}_${Regex.escape(e.substring(56, 64))}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        val keyFrag2B64 = Regex(""""?${Regex.escape(s2.substring(0, 16))}_${Regex.escape(s2.substring(16, 24))}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1)
        if (kfB64 == null || ivfB64 == null || token == null || keyFrag2B64 == null) {
            BLog.e("reanime: missing fields"); null
        } else {

        val tokenResp = app.get("$EMBED/api/m3u8/$token", headers = embedHeaders()).text
        val vidField = shaHex(token + "vid").substring(0, 10)
        val keyField = shaHex(token + "key").substring(0, 10)
        val encVideo = Regex(""""$vidField"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1)
        val encKey = Regex(""""?$keyField"?\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1)
        if (encVideo == null || encKey == null) { BLog.e("reanime: no video/key"); null } else {

        val wasm = MiniVm(Base64.decode(wasmB64, Base64.DEFAULT))
        val f1 = b64d(kfB64); val f2 = b64d(keyFrag2B64); val f3 = b64d(encKey)
        val k = f1.size
        if (k == 0 || f2.size != k || f3.size != k) { BLog.e("reanime: frag mismatch"); null } else {

        val base = 1000
        wasm.writeMem(base, f1)
        wasm.writeMem(base + k, f2)
        wasm.writeMem(base + 2 * k, f3)
        val seedInt = seed.take(8).toLongOrNull(16)?.toInt()
        if (seedInt == null) { BLog.e("reanime: seed parse"); null } else {

        wasm.invoke("_s", seedInt)
        wasm.invoke("_r", base, base + k, base + 2 * k, base + 3 * k, k)
        val keySeed = wasm.readMem(base + 3 * k, k)
        if (keySeed.all { it == 0.toByte() }) { BLog.e("reanime: wasm empty"); null } else {

        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val pbkdf2 = pbkdf2Sha256(keySeed, seedBytes, 1000, 32)
        val xored = ByteArray(32) { (pbkdf2[it].toInt() xor seedBytes[it % seedBytes.size].toInt()).toByte() }
        val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)
        val iv = b64d(ivfB64)
        if (iv.size != 16) { BLog.e("reanime: bad iv"); null } else {

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val padded = cipher.doFinal(b64d(encVideo))
        val pad = padded.last().toInt() and 0xFF
        val cut = if (pad in 1..16 && padded.size > pad) padded.size - pad else padded.size
        val masterUrl = String(padded.copyOfRange(0, cut), Charsets.UTF_8).trim()
        if (!masterUrl.startsWith("http")) { BLog.e("reanime: bad url"); null } else {

        val pk = derivePk(wasm.dataSection)
        if (pk == null) { BLog.e("reanime: bad pk"); null } else {

        val masterRaw = app.get(masterUrl, headers = cdnHeaders()).text.trim()
        val masterBody = decryptPlaylist(masterRaw, pk)
        if (masterBody == null) { BLog.e("reanime: master decrypt"); null }
        else Resolved(masterUrl, masterBody, pk, extractSubs(region))
        }}}}}}}}}}
    } catch (e: Exception) { BLog.e("reanime resolve: ${e.message}"); null }

    fun decryptPlaylist(body: String, pk: ByteArray): String? = try {
        val trimmed = body.trim()
        if (trimmed.startsWith("#EXTM3U")) trimmed else {
            val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
            val raw = Base64.decode(padded, Base64.DEFAULT)
            val out = ByteArray(raw.size) { (raw[it].toInt() xor pk[it % pk.size].toInt()).toByte() }
            val text = String(out, Charsets.UTF_8)
            if (text.startsWith("#EXTM3U")) text else null
        }
    } catch (_: Exception) { null }

    private fun derivePk(d: ByteArray): ByteArray? {
        if (d.size < 64) return null
        return ByteArray(32) { (d[it].toInt() xor d[it + 32].toInt()).toByte() }
    }

    private fun pbkdf2Sha256(pw: ByteArray, salt: ByteArray, iter: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pw, "HmacSHA256"))
        val blocks = (len + 31) / 32
        val out = ByteArray(blocks * 32)
        val idx = ByteArray(4)
        for (i in 1..blocks) {
            idx[0] = (i ushr 24).toByte(); idx[1] = ((i ushr 16) and 0xFF).toByte()
            idx[2] = ((i ushr 8) and 0xFF).toByte(); idx[3] = (i and 0xFF).toByte()
            mac.reset()
            var u = mac.doFinal(salt + idx)
            val t = u.copyOf()
            repeat(iter - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, 32)
        }
        return out.copyOf(len)
    }

    private fun shaChain(seed: String): String {
        var e = seed
        for (i in 0 until 3) e = shaHex(e + i)
        return e
    }

    private fun shaHex(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    private fun extractSubs(region: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val m = Regex("""subtitles\s*:\s*\[([^\]]*)\]""").find(region) ?: return out
        for (om in Regex("""\{([^{}]*)\}""").findAll(m.groupValues[1])) {
            val o = om.groupValues[1]
            val url = Regex(""""?url"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1) ?: continue
            if (!url.startsWith("http")) continue
            val lang = Regex(""""?language"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1) ?: "Subtitle"
            out.add(subLabel(url, lang) to url)
        }
        return out
    }

    private fun subLabel(url: String, lang: String): String {
        val l = url.lowercase()
        return when {
            l.contains("_eng_3.ass") -> "$lang (Signs)"
            l.contains("_eng_4.ass") -> "$lang (Signs + Songs)"
            l.contains("_eng_5.ass") -> "$lang (Signs Alt)"
            l.contains("_eng_6.ass") -> "$lang (Signs + Songs Alt)"
            l.endsWith(".ass") -> "$lang (ASS)"
            l.endsWith(".srt") -> "$lang (SRT)"
            else -> lang
        }
    }

    // Minimal WASM interpreter for the key-derivation module shipped
    // with each embed. Handles only the opcode subset that module uses.
    private class MiniVm(bytes: ByteArray) {
        private class Fn(val locals: IntArray, val code: ByteArray)
        private class Lbl(val isLoop: Boolean, val body: Int, val end: Int)

        private val mem = ByteArray(1 shl 18)
        private val globals = IntArray(16)
        private val funcs = ArrayList<Fn>()
        private val exports = HashMap<String, Int>()
        var dataSection: ByteArray = ByteArray(0); private set

        init {
            require(bytes.size >= 8 && bytes[0] == 0x00.toByte() && bytes[1] == 0x61.toByte())
            var p = 8
            while (p < bytes.size) {
                val id = bytes[p].toInt() and 0xFF
                val (sz, afterLen) = lebU(bytes, p + 1)
                val end = afterLen + sz
                if (end > bytes.size) break
                when (id) {
                    7 -> parseExports(bytes, afterLen, end)
                    10 -> parseCode(bytes, afterLen, end)
                    11 -> parseData(bytes, afterLen, end)
                }
                p = end
            }
        }

        fun writeMem(o: Int, d: ByteArray) {
            if (o < 0 || o + d.size > mem.size) return
            System.arraycopy(d, 0, mem, o, d.size)
        }
        fun readMem(o: Int, l: Int): ByteArray {
            if (o < 0 || o + l > mem.size) return ByteArray(l)
            return mem.copyOfRange(o, o + l)
        }
        fun invoke(n: String, vararg a: Int) {
            val fn = funcs.getOrNull(exports[n] ?: return) ?: return
            run(fn, a)
        }

        private fun parseExports(b: ByteArray, s: Int, e: Int) {
            var p = s
            val (cnt, ac) = lebU(b, p); p = ac
            repeat(cnt) {
                if (p >= e) return
                val nl = b[p].toInt() and 0xFF; p++
                if (p + nl > e) return
                val name = String(b, p, nl, Charsets.UTF_8); p += nl
                if (p >= e) return
                val kind = b[p].toInt() and 0xFF; p++
                val (idx, ai) = lebU(b, p); p = ai
                if (kind == 0) exports[name] = idx
            }
        }

        private fun parseCode(b: ByteArray, s: Int, e: Int) {
            var p = s
            val (cnt, ac) = lebU(b, p); p = ac
            repeat(cnt) {
                if (p >= e) return
                val (bsz, ab) = lebU(b, p); p = ab
                val be = p + bsz
                if (be > e) return
                var q = p
                val (ng, ag) = lebU(b, q); q = ag
                val locals = ArrayList<Int>()
                repeat(ng) {
                    if (q >= be) return
                    val (c, ac2) = lebU(b, q); q = ac2
                    if (q >= be) return
                    val ty = b[q].toInt() and 0xFF; q++
                    repeat(c) { locals.add(ty) }
                }
                funcs.add(Fn(locals.toIntArray(), b.copyOfRange(q, be)))
                p = be
            }
        }

        private fun parseData(b: ByteArray, s: Int, e: Int) {
            var p = s
            val (cnt, ac) = lebU(b, p); p = ac
            repeat(cnt) {
                if (p >= e) return
                val flag = b[p].toInt() and 0xFF; p++
                if (flag != 0x00 && flag != 0x02) return@repeat
                if (p >= e || b[p].toInt() and 0xFF != 0x41) return@repeat
                val (off, ao) = lebS(b, p + 1); p = ao
                if (p < e && b[p].toInt() and 0xFF == 0x0b) p++
                val (sz, asz) = lebU(b, p); p = asz
                if (p + sz > e) return
                if (off in 0 until mem.size && off + sz <= mem.size) {
                    System.arraycopy(b, p, mem, off, sz)
                }
                if (sz > dataSection.size) dataSection = b.copyOfRange(p, p + sz)
                p += sz
            }
        }

        private fun run(fn: Fn, args: IntArray) {
            val code = fn.code
            val locals = IntArray(args.size + fn.locals.size)
            for (i in args.indices) locals[i] = args[i]

            val elseOf = HashMap<Int, Int>()
            val endOf = HashMap<Int, Int>()
            run {
                val ctl = ArrayDeque<Int>()
                var i = 0
                while (i < code.size) {
                    when (code[i].toInt() and 0xFF) {
                        0x02, 0x03, 0x04 -> { ctl.addLast(i); i = lebU(code, i + 1).second }
                        0x05 -> { ctl.lastOrNull()?.let { elseOf[it] = i }; i++ }
                        0x0b -> { ctl.removeLastOrNull()?.let { endOf[it] = i }; i++ }
                        0x41, 0x42 -> i = lebS(code, i + 1).second
                        0x43 -> i += 5
                        0x44 -> i += 9
                        0x0e -> {
                            val (n, an) = lebU(code, i + 1); i = an
                            repeat(n + 1) { i = lebU(code, i).second }
                        }
                        0x0c, 0x0d, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x3f, 0x40 ->
                            i = lebU(code, i + 1).second
                        0x11 -> { i = lebU(code, i + 1).second; i = lebU(code, i).second }
                        in 0x28..0x3e -> { i = lebU(code, i + 1).second; i = lebU(code, i).second }
                        else -> i++
                    }
                }
            }

            val stack = ArrayDeque<Int>()
            val labels = ArrayDeque<Lbl>()
            var p = 0

            fun branch(d: Int) {
                val idx = labels.size - 1 - d
                if (idx < 0) { p = code.size; return }
                val t = labels.elementAt(idx)
                if (t.isLoop) {
                    while (labels.size > idx + 1) labels.removeLastOrNull()
                    p = t.body
                } else {
                    while (labels.size > idx) labels.removeLastOrNull()
                    p = t.end + 1
                }
            }

            while (p < code.size) {
                when (code[p].toInt() and 0xFF) {
                    0x00 -> return
                    0x01 -> p++
                    0x02, 0x03 -> {
                        val isLoop = (code[p].toInt() and 0xFF) == 0x03
                        val at = lebU(code, p + 1).second
                        labels.addLast(Lbl(isLoop, at, endOf[p] ?: (code.size - 1)))
                        p = at
                    }
                    0x04 -> {
                        val cond = stack.removeLastOrNull() ?: 0
                        val endIdx = endOf[p] ?: (code.size - 1)
                        val elseIdx = elseOf[p]
                        val at = lebU(code, p + 1).second
                        if (cond != 0) { labels.addLast(Lbl(false, at, endIdx)); p = at }
                        else if (elseIdx != null) { labels.addLast(Lbl(false, elseIdx + 1, endIdx)); p = elseIdx + 1 }
                        else p = endIdx + 1
                    }
                    0x05 -> { val l = labels.removeLastOrNull(); p = (l?.end ?: (code.size - 1)) + 1 }
                    0x0b -> { labels.removeLastOrNull(); p++ }
                    0x0c -> { val (d, a) = lebU(code, p + 1); p = a; branch(d) }
                    0x0d -> {
                        val (d, a) = lebU(code, p + 1); p = a
                        if ((stack.removeLastOrNull() ?: 0) != 0) branch(d)
                    }
                    0x0f -> return
                    0x1a -> { stack.removeLastOrNull(); p++ }
                    0x1b -> {
                        p++
                        val c = stack.removeLastOrNull() ?: 0
                        val b2 = stack.removeLastOrNull() ?: 0
                        val a2 = stack.removeLastOrNull() ?: 0
                        stack.addLast(if (c != 0) a2 else b2)
                    }
                    0x20 -> { val (i, j) = lebU(code, p + 1); p = j; stack.addLast(locals.getOrElse(i) { 0 }) }
                    0x21 -> { val (i, j) = lebU(code, p + 1); p = j; if (i < locals.size) locals[i] = stack.removeLastOrNull() ?: 0 }
                    0x22 -> { val (i, j) = lebU(code, p + 1); p = j; if (i < locals.size) locals[i] = stack.lastOrNull() ?: 0 }
                    0x23 -> { val (i, j) = lebU(code, p + 1); p = j; stack.addLast(globals.getOrElse(i) { 0 }) }
                    0x24 -> { val (i, j) = lebU(code, p + 1); p = j; if (i < globals.size) globals[i] = stack.removeLastOrNull() ?: 0 }
                    0x2d -> {
                        val (_, j1) = lebU(code, p + 1); val (off, j2) = lebU(code, j1); p = j2
                        val addr = (stack.removeLastOrNull() ?: 0) + off
                        stack.addLast(if (addr in mem.indices) mem[addr].toInt() and 0xFF else 0)
                    }
                    0x3a -> {
                        val (_, j1) = lebU(code, p + 1); val (off, j2) = lebU(code, j1); p = j2
                        val v = stack.removeLastOrNull() ?: 0
                        val addr = (stack.removeLastOrNull() ?: 0) + off
                        if (addr in mem.indices) mem[addr] = (v and 0xFF).toByte()
                    }
                    0x41 -> { val (v, j) = lebS(code, p + 1); p = j; stack.addLast(v) }
                    0x45 -> { p++; stack.addLast(if ((stack.removeLastOrNull() ?: 0) == 0) 1 else 0) }
                    0x46 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x == y) 1 else 0) }
                    0x47 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x != y) 1 else 0) }
                    0x48 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x < y) 1 else 0) }
                    0x49 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) < 0) 1 else 0) }
                    0x4a -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) > 0) 1 else 0) }
                    0x4b, 0x4d -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) <= 0) 1 else 0) }
                    0x4e -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (x >= y) 1 else 0) }
                    0x4f -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(if (Integer.compareUnsigned(x, y) >= 0) 1 else 0) }
                    0x6a -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x + y) }
                    0x6b -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x - y) }
                    0x6c -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x * y) }
                    0x71 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x and y) }
                    0x72 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x or y) }
                    0x73 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x xor y) }
                    0x74 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x shl (y and 31)) }
                    0x75 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x shr (y and 31)) }
                    0x76 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; stack.addLast(x ushr (y and 31)) }
                    0x77 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; val s = y and 31; stack.addLast(if (s == 0) x else (x shl s) or (x ushr (32 - s))) }
                    0x78 -> { p++; val y = stack.removeLastOrNull() ?: 0; val x = stack.removeLastOrNull() ?: 0; val s = y and 31; stack.addLast(if (s == 0) x else (x ushr s) or (x shl (32 - s))) }
                    else -> return
                }
            }
        }

        private fun lebU(b: ByteArray, off: Int): Pair<Int, Int> {
            var v = 0; var shift = 0; var j = off
            while (j < b.size) {
                val x = b[j].toInt() and 0xFF; j++
                v = v or ((x and 0x7F) shl shift)
                shift += 7
                if (x and 0x80 == 0) break
                if (shift > 35) break
            }
            return v to j
        }

        private fun lebS(b: ByteArray, off: Int): Pair<Int, Int> {
            var v = 0; var shift = 0; var j = off; var last = 0
            while (j < b.size) {
                val x = b[j].toInt() and 0xFF; j++
                last = x
                v = v or ((x and 0x7F) shl shift)
                shift += 7
                if (x and 0x80 == 0) break
                if (shift > 35) break
            }
            if (shift < 32 && (last and 0x40) != 0) v = v or (-1 shl shift)
            return v to j
        }
    }
}

suspend fun reanimeWasmExtract(
    q: StreamQuery,
    onLink: (suspend (ScrapedMirror, Int) -> Unit)? = null,
    onSub: (suspend (String, String) -> Unit)? = null
): AniKageScrape {
    val yr = q.year.toIntOrNull()
    BLog.d("reanime: '${q.title}' ($yr)")

    val hit = ReanimeWasm.pickBest(ReanimeWasm.search(q.title), q.title, yr) ?: run {
        BLog.d("reanime: no match"); return AniKageScrape(emptyList(), emptyList())
    }

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    val servers = ReanimeWasm.servers(hit.anilistId, ep)
    if (servers.isEmpty()) { BLog.d("reanime: no servers E$ep"); return AniKageScrape(emptyList(), emptyList()) }

    data class Edge(val url: String, val names: String)
    val edges = servers.groupBy { it.url }.map { (url, list) ->
        Edge(url, list.map { it.name }.distinct().joinToString("/"))
    }

    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val subsSeen = ConcurrentHashMap.newKeySet<String>()
    val emitted = AtomicInteger(0)

    coroutineScope {
        edges.map { edge ->
            async {
                try {
                    val r = ReanimeWasm.resolve(edge.url) ?: return@async
                    val baseUrl = ReanimeWasmServer.register(r.masterUrl, r.masterBody, r.pk)
                        ?: return@async

                    for (lang in listOf("sub", "dub")) {
                        val url = "$baseUrl?lang=$lang"
                        val m = ScrapedMirror(
                            quality = "Auto",
                            mirror = "${edge.names} · ${lang.uppercase()}",
                            url = url,
                            source = "REANIME",
                            headers = emptyMap(),
                            captions = emptyList()
                        )
                        if (collected.putIfAbsent(url, m) != null) continue
                        try { onLink?.invoke(m, LinkScore.prelimScore(m)) } catch (_: Exception) {}
                        emitted.incrementAndGet()
                    }

                    if (onSub != null) {
                        for ((label, subUrl) in r.subs) {
                            if (subsSeen.add(subUrl)) {
                                try { onSub.invoke(subUrl, label) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { BLog.e("reanime edge: ${e.message}") }
            }
        }.awaitAll()
    }

    BLog.d("reanime: ${collected.size} mirrors, ${subsSeen.size} subs")
    return AniKageScrape(collected.values.toList(), emptyList())
}
