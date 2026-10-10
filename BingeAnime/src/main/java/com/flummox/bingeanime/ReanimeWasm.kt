package com.flummox.bingeanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
            out.add(Hit(slug, alId, primary,
                listOfNotNull(en, ro, na).distinct(),
                o.optInt("season_year", 0).takeIf { it > 0 }, cover))
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

        val f1 = b64d(kfB64); val f2 = b64d(keyFrag2B64); val f3 = b64d(encKey)
        val seedInt = seed.take(8).toLongOrNull(16)?.toInt()
        if (seedInt == null || f1.isEmpty() || f2.size != f1.size || f3.size != f1.size) {
            BLog.e("reanime: frag mismatch"); null
        } else {

        val vm = Vm(Base64.decode(wasmB64, Base64.DEFAULT))
        val k = f1.size
        val slot = 1000
        vm.load(slot, f1)
        vm.load(slot + k, f2)
        vm.load(slot + 2 * k, f3)
        vm.call("_s", seedInt)
        vm.call("_r", slot, slot + k, slot + 2 * k, slot + 3 * k, k)
        val keySeed = vm.dump(slot + 3 * k, k)
        if (keySeed.all { it == 0.toByte() }) { BLog.e("reanime: vm empty"); null } else {

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

        val pk = derivePk(vm.payload())
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
}

// Minimal WebAssembly executor for the key-derivation module used by
// the embed player. Branch targets are resolved once at parse time so
// execute() runs without a frame stack.
private class Vm(raw: ByteArray) {

    private class Routine(
        val slots: Int,
        val code: ByteArray,
        val brDest: IntArray,
        val ifElse: IntArray,
        val ifEnd: IntArray,
        val elseEnd: IntArray
    )

    private class Control(
        val brDest: IntArray,
        val ifElse: IntArray,
        val ifEnd: IntArray,
        val elseEnd: IntArray
    )

    private val arena = ByteArray(1 shl 18)
    private val registers = IntArray(16)
    private val routines = ArrayList<Routine>()
    private val exportMap = HashMap<String, Int>()
    private val operand = IntArray(4096)
    private var payload = ByteArray(0)

    init { parse(raw) }

    fun payload(): ByteArray = payload

    fun load(offset: Int, data: ByteArray) {
        if (offset < 0 || offset + data.size > arena.size) return
        System.arraycopy(data, 0, arena, offset, data.size)
    }

    fun dump(offset: Int, size: Int): ByteArray {
        if (offset < 0 || offset + size > arena.size) return ByteArray(size)
        return arena.copyOfRange(offset, offset + size)
    }

    fun call(name: String, vararg argv: Int) {
        val idx = exportMap[name] ?: return
        val routine = routines.getOrNull(idx) ?: return
        execute(routine, argv)
    }

    private fun parse(raw: ByteArray) {
        if (raw.size < 8 || raw[0] != 0x00.toByte() || raw[1] != 0x61.toByte()) return
        var at = 8
        while (at < raw.size) {
            val sid = raw[at].toInt() and 0xFF
            val (len, after) = uleb(raw, at + 1)
            val end = after + len
            if (end > raw.size) break
            when (sid) {
                7 -> readExports(raw, after, end)
                10 -> readCode(raw, after, end)
                11 -> readData(raw, after, end)
            }
            at = end
        }
    }

    private fun readExports(b: ByteArray, from: Int, to: Int) {
        var at = from
        val (count, ac) = uleb(b, at); at = ac
        repeat(count) {
            if (at >= to) return
            val nl = b[at].toInt() and 0xFF; at++
            if (at + nl > to) return
            val name = String(b, at, nl, Charsets.UTF_8); at += nl
            if (at >= to) return
            val kind = b[at].toInt() and 0xFF; at++
            val (idx, ai) = uleb(b, at); at = ai
            if (kind == 0) exportMap[name] = idx
        }
    }

    private fun readCode(b: ByteArray, from: Int, to: Int) {
        var at = from
        val (count, ac) = uleb(b, at); at = ac
        repeat(count) {
            if (at >= to) return
            val (blen, ab) = uleb(b, at); at = ab
            val bodyEnd = at + blen
            if (bodyEnd > to) return
            var p = at
            val (groups, ag) = uleb(b, p); p = ag
            var totalSlots = 0
            repeat(groups) {
                if (p >= bodyEnd) return
                val (n, an) = uleb(b, p); p = an
                if (p >= bodyEnd) return
                p++
                totalSlots += n
            }
            val code = b.copyOfRange(p, bodyEnd)
            val ctrl = buildControl(code)
            routines.add(Routine(totalSlots, code, ctrl.brDest, ctrl.ifElse, ctrl.ifEnd, ctrl.elseEnd))
            at = bodyEnd
        }
    }

    private fun readData(b: ByteArray, from: Int, to: Int) {
        var at = from
        val (count, ac) = uleb(b, at); at = ac
        repeat(count) {
            if (at >= to) return
            val flag = b[at].toInt() and 0xFF; at++
            if (flag != 0x00 && flag != 0x02) return@repeat
            if (at >= to || b[at].toInt() and 0xFF != 0x41) return@repeat
            val (offset, ao) = sleb(b, at + 1); at = ao
            if (at < to && b[at].toInt() and 0xFF == 0x0b) at++
            val (sz, asz) = uleb(b, at); at = asz
            if (at + sz > to) return
            if (offset in 0 until arena.size && offset + sz <= arena.size) {
                System.arraycopy(b, at, arena, offset, sz)
            }
            if (sz > payload.size) payload = b.copyOfRange(at, at + sz)
            at += sz
        }
    }

    private fun buildControl(code: ByteArray): Control {
        val n = code.size
        val brDest = IntArray(n) { -1 }
        val ifElse = IntArray(n) { -1 }
        val ifEnd = IntArray(n) { -1 }
        val elseEnd = IntArray(n) { -1 }

        class Scope(val pc: Int, val isLoop: Boolean, val isIf: Boolean) {
            val pendingBr = ArrayList<Int>()
            var elsePc = -1
        }

        val scopes = ArrayList<Scope>()
        var pc = 0
        while (pc < n) {
            when (val op = code[pc].toInt() and 0xFF) {
                0x02, 0x03 -> {
                    scopes.add(Scope(pc, op == 0x03, false))
                    pc += 2
                }
                0x04 -> {
                    scopes.add(Scope(pc, false, true))
                    pc += 2
                }
                0x05 -> {
                    val top = scopes.lastOrNull()
                    if (top != null && top.isIf) {
                        top.elsePc = pc
                        ifElse[top.pc] = pc
                    }
                    pc++
                }
                0x0b -> {
                    val scope = scopes.removeLastOrNull()
                    if (scope != null) {
                        val exit = if (scope.isLoop) scope.pc + 2 else pc + 1
                        for (bp in scope.pendingBr) brDest[bp] = exit
                        if (scope.isIf) ifEnd[scope.pc] = pc
                        if (scope.elsePc >= 0) elseEnd[scope.elsePc] = pc
                    }
                    pc++
                }
                0x0c, 0x0d -> {
                    val (depth, after) = uleb(code, pc + 1)
                    val scope = if (depth < scopes.size) scopes[scopes.size - 1 - depth] else null
                    if (scope != null) scope.pendingBr.add(pc) else brDest[pc] = n
                    pc = after
                }
                0x41, 0x42 -> pc = sleb(code, pc + 1).second
                0x43 -> pc += 5
                0x44 -> pc += 9
                0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x3f, 0x40 -> pc = uleb(code, pc + 1).second
                0x10 -> pc = uleb(code, pc + 1).second
                0x11 -> { pc = uleb(code, pc + 1).second; pc = uleb(code, pc).second }
                in 0x28..0x3e -> { pc = uleb(code, pc + 1).second; pc = uleb(code, pc).second }
                else -> pc++
            }
        }
        return Control(brDest, ifElse, ifEnd, elseEnd)
    }

    private fun execute(r: Routine, args: IntArray) {
        val code = r.code
        val brDest = r.brDest
        val ifElse = r.ifElse
        val ifEnd = r.ifEnd
        val elseEnd = r.elseEnd
        val locals = IntArray(args.size + r.slots)
        System.arraycopy(args, 0, locals, 0, args.size)

        var sp = 0
        var pc = 0

        fun push(v: Int) { if (sp < operand.size) operand[sp++] = v }
        fun pop(): Int = if (sp > 0) operand[--sp] else 0
        fun peek(): Int = if (sp > 0) operand[sp - 1] else 0

        while (pc < code.size) {
            when (val op = code[pc].toInt() and 0xFF) {
                0x00 -> return
                0x01 -> pc++
                0x02, 0x03 -> pc += 2
                0x04 -> {
                    val cond = pop()
                    val entry = pc + 2
                    if (cond != 0) { pc = entry }
                    else {
                        val alt = ifElse[pc]
                        pc = if (alt >= 0) alt + 1 else ifEnd[pc] + 1
                    }
                }
                0x05 -> pc = elseEnd[pc] + 1
                0x0b -> pc++
                0x0c -> pc = brDest[pc]
                0x0d -> {
                    val opPc = pc
                    pc = uleb(code, pc + 1).second
                    if (pop() != 0) pc = brDest[opPc]
                }
                0x0f -> return
                0x1a -> { pop(); pc++ }
                0x1b -> {
                    pc++
                    val c = pop(); val b2 = pop(); val a2 = pop()
                    push(if (c != 0) a2 else b2)
                }
                0x20 -> { val (i, j) = uleb(code, pc + 1); pc = j; push(locals.getOrElse(i) { 0 }) }
                0x21 -> { val (i, j) = uleb(code, pc + 1); pc = j; if (i < locals.size) locals[i] = pop() }
                0x22 -> { val (i, j) = uleb(code, pc + 1); pc = j; if (i < locals.size) locals[i] = peek() }
                0x23 -> { val (i, j) = uleb(code, pc + 1); pc = j; push(registers.getOrElse(i) { 0 }) }
                0x24 -> { val (i, j) = uleb(code, pc + 1); pc = j; if (i < registers.size) registers[i] = pop() }
                0x2d -> {
                    val (_, j1) = uleb(code, pc + 1); val (off, j2) = uleb(code, j1); pc = j2
                    val addr = pop() + off
                    push(if (addr in arena.indices) arena[addr].toInt() and 0xFF else 0)
                }
                0x3a -> {
                    val (_, j1) = uleb(code, pc + 1); val (off, j2) = uleb(code, j1); pc = j2
                    val v = pop()
                    val addr = pop() + off
                    if (addr in arena.indices) arena[addr] = (v and 0xFF).toByte()
                }
                0x41 -> { val (v, j) = sleb(code, pc + 1); pc = j; push(v) }
                0x45 -> { pc++; push(if (pop() == 0) 1 else 0) }
                0x46 -> { pc++; val y = pop(); val x = pop(); push(if (x == y) 1 else 0) }
                0x47 -> { pc++; val y = pop(); val x = pop(); push(if (x != y) 1 else 0) }
                0x48 -> { pc++; val y = pop(); val x = pop(); push(if (x < y) 1 else 0) }
                0x49 -> { pc++; val y = pop(); val x = pop(); push(if (x.toUInt() < y.toUInt()) 1 else 0) }
                0x4a -> { pc++; val y = pop(); val x = pop(); push(if (x.toUInt() > y.toUInt()) 1 else 0) }
                0x4b, 0x4d -> { pc++; val y = pop(); val x = pop(); push(if (x.toUInt() <= y.toUInt()) 1 else 0) }
                0x4e -> { pc++; val y = pop(); val x = pop(); push(if (x >= y) 1 else 0) }
                0x4f -> { pc++; val y = pop(); val x = pop(); push(if (x.toUInt() >= y.toUInt()) 1 else 0) }
                0x6a -> { pc++; val y = pop(); val x = pop(); push(x + y) }
                0x6b -> { pc++; val y = pop(); val x = pop(); push(x - y) }
                0x6c -> { pc++; val y = pop(); val x = pop(); push(x * y) }
                0x71 -> { pc++; val y = pop(); val x = pop(); push(x and y) }
                0x72 -> { pc++; val y = pop(); val x = pop(); push(x or y) }
                0x73 -> { pc++; val y = pop(); val x = pop(); push(x xor y) }
                0x74 -> { pc++; val y = pop(); val x = pop(); push(x shl (y and 31)) }
                0x75 -> { pc++; val y = pop(); val x = pop(); push(x shr (y and 31)) }
                0x76 -> { pc++; val y = pop(); val x = pop(); push(x ushr (y and 31)) }
                0x77 -> { pc++; val y = pop(); val x = pop(); val s = y and 31
                    push(if (s == 0) x else (x shl s) or (x ushr (32 - s))) }
                0x78 -> { pc++; val y = pop(); val x = pop(); val s = y and 31
                    push(if (s == 0) x else (x ushr s) or (x shl (32 - s))) }
                else -> return
            }
        }
    }
}

private fun uleb(b: ByteArray, off: Int): Pair<Int, Int> {
    var v = 0; var s = 0; var i = off
    while (i < b.size) {
        val x = b[i].toInt() and 0xFF; i++
        v = v or ((x and 0x7F) shl s); s += 7
        if (x and 0x80 == 0) break
        if (s > 35) break
    }
    return v to i
}

private fun sleb(b: ByteArray, off: Int): Pair<Int, Int> {
    var v = 0; var s = 0; var i = off; var last = 0
    while (i < b.size) {
        val x = b[i].toInt() and 0xFF; i++
        last = x
        v = v or ((x and 0x7F) shl s); s += 7
        if (x and 0x80 == 0) break
        if (s > 35) break
    }
    if (s < 32 && (last and 0x40) != 0) v = v or (-1 shl s)
    return v to i
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

    coroutineScope {
        edges.map { edge ->
            async {
                try {
                    val r = ReanimeWasm.resolve(edge.url) ?: return@async
                    val reg = ReanimeWasmServer.register(r.masterUrl, r.masterBody, r.pk)
                        ?: return@async

                    val subUrl = "${reg.url}?lang=sub"
                    val subMirror = ScrapedMirror(
                        quality = "Auto",
                        mirror = "${edge.names} · SUB",
                        url = subUrl,
                        source = "REANIME",
                        headers = emptyMap(),
                        captions = emptyList()
                    )
                    if (collected.putIfAbsent(subUrl, subMirror) == null) {
                        try { onLink?.invoke(subMirror, LinkScore.prelimScore(subMirror)) } catch (_: Exception) {}
                    }

                    if (reg.hasDub) {
                        val dubUrl = "${reg.url}?lang=dub"
                        val dubMirror = ScrapedMirror(
                            quality = "Auto",
                            mirror = "${edge.names} · DUB",
                            url = dubUrl,
                            source = "REANIME",
                            headers = emptyMap(),
                            captions = emptyList()
                        )
                        if (collected.putIfAbsent(dubUrl, dubMirror) == null) {
                            try { onLink?.invoke(dubMirror, LinkScore.prelimScore(dubMirror)) } catch (_: Exception) {}
                        }
                    }

                    if (onSub != null) {
                        for ((label, url) in r.subs) {
                            if (!BingeAnimeSettings.subLangMatches(label)) continue
                            if (subsSeen.add(url)) {
                                try { onSub.invoke(url, label) } catch (_: Exception) {}
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
