package com.flummox.bingeanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AniwavesExt {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private val JSON_MEDIA = "application/json".toMediaType()

    data class Extracted(
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<Pair<String, String>> = emptyList()
    )

    suspend fun extract(embedUrl: String): Extracted? {
        val host = try { java.net.URI(embedUrl).host.lowercase() }
        catch (_: Exception) { return null }
        return when {
            host.contains("mfw09") || host.contains("byse") -> extractMfw09(embedUrl)
            host.contains("echovideo") -> extractEchovideo(embedUrl)
            else -> { BLog.d("aniwaves: unknown embed host $host"); null }
        }
    }

    // ── echovideo — JW Player, MSE blob, best-effort regex only. Expected to fail. ──
    private suspend fun extractEchovideo(embedUrl: String): Extracted? {
        val html = try {
            app.get(embedUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://aniwaves.ru/"
            )).text
        } catch (e: Exception) { BLog.d("aniwaves echo: ${e.message}"); return null }

        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.let {
            BLog.d("aniwaves echo: ${it.value.take(80)}")
            return Extracted(it.value, mapOf("Referer" to "https://play.echovideo.ru/"))
        }
        Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.let {
            return Extracted(it.groupValues[1].replace("\\/", "/"),
                mapOf("Referer" to "https://play.echovideo.ru/"))
        }
        BLog.d("aniwaves echo: no m3u8 in ${html.length}b html (JW Player / MSE blob)")
        return null
    }

    // ── mfw09 / Byse ──
    private suspend fun extractMfw09(embedUrl: String): Extracted? {
        val code = Regex("""mfw\d+\.org/e/([A-Za-z0-9]+)""").find(embedUrl)
            ?.groupValues?.get(1) ?: return null
        val apiBase = "https://mfw09.org"

        val embedHeaders = mapOf(
            "User-Agent" to UA,
            "X-Embed-Origin" to "aniwaves.ru",
            "X-Embed-Referer" to "https://aniwaves.ru/",
            "X-Embed-Parent" to embedUrl
        )

        // 0. access challenge + attest (required before captcha)
        var viewerToken: String? = null
        try {
            val chResp = app.post("$apiBase/api/videos/access/challenge",
                requestBody = "{}".toRequestBody(JSON_MEDIA),
                headers = embedHeaders + mapOf("Content-Type" to "application/json"))
            val chJson = JSONObject(chResp.text)
            val challengeId = chJson.optString("challenge_id")
            val nonce = chJson.optString("nonce")
            val viewerHint = chJson.optString("viewer_hint")
            BLog.d("aniwaves mfw: challenge id=${challengeId.take(8)}")

            val attestBodies = listOf(
                JSONObject().apply {
                    put("challenge_id", challengeId)
                    put("nonce", nonce)
                    put("viewer_hint", viewerHint)
                }.toString(),
                JSONObject().apply {
                    put("challenge_id", challengeId); put("nonce", nonce)
                }.toString(),
                JSONObject().apply { put("viewer_hint", viewerHint) }.toString()
            )
            for (body in attestBodies) {
                try {
                    val atResp = app.post("$apiBase/api/videos/access/attest",
                        requestBody = body.toRequestBody(JSON_MEDIA),
                        headers = embedHeaders + mapOf("Content-Type" to "application/json"))
                    val atJson = JSONObject(atResp.text)
                    val tok = atJson.optString("token").takeIf { it.isNotBlank() }
                    if (tok != null) { viewerToken = tok; BLog.d("aniwaves mfw: attest OK"); break }
                    BLog.d("aniwaves mfw: attest miss: ${atResp.text.take(120)}")
                } catch (e: Exception) { BLog.d("aniwaves mfw: attest err: ${e.message}") }
            }
        } catch (e: Exception) { BLog.d("aniwaves mfw: challenge: ${e.message}") }

        val authedHeaders = if (viewerToken != null)
            embedHeaders + mapOf("Authorization" to "Bearer $viewerToken") else embedHeaders

        // 1. captcha (= PoW challenge generator; no user interaction)
        val captchaResp = try {
            app.post("$apiBase/api/videos/$code/embed/captcha",
                requestBody = "{}".toRequestBody(JSON_MEDIA),
                headers = authedHeaders + mapOf("Content-Type" to "application/json"))
        } catch (e: Exception) { BLog.d("aniwaves mfw captcha: ${e.message}"); return null }

        val captchaJson = try { JSONObject(captchaResp.text) }
        catch (_: Exception) { return null }
        val powNonce = captchaJson.optString("pow_nonce").takeIf { it.isNotBlank() } ?: return null
        val powDiff  = captchaJson.optInt("pow_difficulty", 12)
        val powToken = captchaJson.optString("pow_token").takeIf { it.isNotBlank() } ?: return null
        BLog.d("aniwaves mfw: pow diff=$powDiff")

        // 2. solve PoW
        val solution = solvePow(powNonce, powDiff) ?: run {
            BLog.d("aniwaves mfw: pow failed"); return null
        }
        BLog.d("aniwaves mfw: pow sol=$solution")

        // 3. verify — try a few body shapes
        val verifyBodies = listOf(
            JSONObject().apply {
                put("token", powToken); put("solution", solution.toString()); put("pow_nonce", powNonce)
            }.toString(),
            JSONObject().apply {
                put("pow_token", powToken); put("solution", solution.toString())
            }.toString(),
            JSONObject().apply { put("solution", solution.toString()) }.toString()
        )
        var captchaToken: String? = null
        for (body in verifyBodies) {
            try {
                val r = app.post("$apiBase/api/videos/$code/embed/captcha/verify",
                    requestBody = body.toRequestBody(JSON_MEDIA),
                    headers = authedHeaders + mapOf("Content-Type" to "application/json"))
                val j = try { JSONObject(r.text) } catch (_: Exception) { null }
                val tok = j?.optString("token")?.takeIf { it.isNotBlank() }
                if (tok != null) { captchaToken = tok; break }
                BLog.d("aniwaves mfw verify miss: ${r.text.take(120)}")
            } catch (e: Exception) { BLog.d("aniwaves mfw verify: ${e.message}") }
        }
        val token = captchaToken ?: run { BLog.d("aniwaves mfw: no captcha token"); return null }

        // 4. playback
        val pbResp = try {
            app.post("$apiBase/api/videos/$code/embed/playback",
                requestBody = "{}".toRequestBody(JSON_MEDIA),
                headers = authedHeaders + mapOf(
                    "Content-Type" to "application/json",
                    "X-Captcha-Token" to token))
        } catch (e: Exception) { BLog.d("aniwaves mfw playback: ${e.message}"); return null }

        val pbJson = try { JSONObject(pbResp.text) } catch (_: Exception) { return null }
        val pb = pbJson.optJSONObject("playback") ?: run {
            BLog.d("aniwaves mfw: no playback: ${pbResp.text.take(200)}"); return null
        }
        val iv = pb.optString("iv").takeIf { it.isNotBlank() } ?: return null
        val payload = pb.optString("payload").takeIf { it.isNotBlank() } ?: return null
        val kpArr = pb.optJSONArray("key_parts") ?: return null
        val version = pb.optInt("version", 0)
        val keyParts = (0 until kpArr.length()).map { kpArr.optString(it) }

        val plaintext = tryDecrypt(iv, payload, keyParts, version) ?: run {
            BLog.d("aniwaves mfw: decrypt failed v=$version parts=${keyParts.size}")
            return null
        }
        BLog.d("aniwaves mfw: plaintext=${plaintext.take(180)}")

        val manifestUrl = parseManifest(plaintext) ?: run {
            BLog.d("aniwaves mfw: no manifest in plaintext"); return null
        }
        val subs = parseSubs(plaintext, apiBase)

        return Extracted(manifestUrl, mapOf("Referer" to "$apiBase/"), subs)
    }

    private fun parseManifest(plaintext: String): String? = try {
        val o = JSONObject(plaintext)
        o.optString("url").takeIf { it.isNotBlank() }
            ?: o.optString("file").takeIf { it.isNotBlank() }
            ?: o.optJSONArray("sources")?.optJSONObject(0)?.let { s ->
                s.optString("file").takeIf { it.isNotBlank() }
                    ?: s.optString("url").takeIf { it.isNotBlank() }
            }
    } catch (_: Exception) {
        plaintext.takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }

    private fun parseSubs(plaintext: String, apiBase: String): List<Pair<String, String>> = try {
        val arr = JSONObject(plaintext).optJSONArray("tracks") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val k = t.optString("kind")
            if (k != "captions" && k != "subtitles") return@mapNotNull null
            val f = t.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val l = t.optString("label").ifBlank { "Unknown" }
            val full = if (f.startsWith("http")) f else "$apiBase/$f"
            l to full
        }
    } catch (_: Exception) { emptyList() }

    // ── PoW: sha256(nonce + counter), leading zero bits ──
    private fun solvePow(nonce: String, difficulty: Int): Long? {
        val md = MessageDigest.getInstance("SHA-256")
        val nb = nonce.toByteArray(Charsets.UTF_8)
        val nonceHex: ByteArray? = try {
            if (nonce.length % 2 == 0 && nonce.all { it in "0123456789abcdefABCDEF" }) {
                ByteArray(nonce.length / 2) {
                    ((Character.digit(nonce[it * 2], 16) shl 4) or
                     Character.digit(nonce[it * 2 + 1], 16)).toByte()
                }
            } else null
        } catch (_: Exception) { null }

        val ctrUtf8 = { c: Long -> c.toString().toByteArray(Charsets.UTF_8) }
        val ctrBe = { c: Long -> byteArrayOf(
            (c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()) }

        val strategies: List<(Long) -> ByteArray> = listOf(
            { c -> nb + ctrUtf8(c) },
            { c -> ctrUtf8(c) + nb },
            { c -> (nonceHex ?: nb) + ctrBe(c) },
            { c -> (nonceHex ?: nb) + ctrUtf8(c) }
        )

        for ((i, strat) in strategies.withIndex()) {
            var counter = 0L
            while (counter < 5_000_000L) {
                md.reset(); md.update(strat(counter))
                if (leadingZeroBits(md.digest()) >= difficulty) {
                    BLog.d("aniwaves mfw: pow strategy $i won")
                    return counter
                }
                counter++
            }
        }
        return null
    }

    private fun leadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) { count += 8; continue }
            var mask = 0x80
            while (mask > 0) {
                if ((v and mask) != 0) return count
                count++; mask = mask shr 1
            }
            break
        }
        return count
    }

    // ── AES-256-GCM decrypt — try 4 key derivations ──
    private fun tryDecrypt(ivB64: String, payloadB64: String, keyParts: List<String>, version: Int): String? {
        val iv  = b64Decode(ivB64)  ?: return null
        val ct  = b64Decode(payloadB64) ?: return null
        val decoded = keyParts.mapNotNull { b64Decode(it) }
        val concat  = decoded.fold(ByteArray(0)) { a, b -> a + b }

        val candidates = listOf<Pair<String, ByteArray>>(
            "sha256(decoded-concat)" to MessageDigest.getInstance("SHA-256").digest(concat),
            "first32(decoded-concat)" to concat.copyOf(32),
            "sha256(raw-concat)" to MessageDigest.getInstance("SHA-256")
                .digest(keyParts.joinToString("").toByteArray(Charsets.UTF_8)),
            "sha256(colon-joined)" to MessageDigest.getInstance("SHA-256")
                .digest(keyParts.joinToString(":").toByteArray(Charsets.UTF_8))
        )
        for ((name, key) in candidates) {
            if (key.size < 32) continue
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"),
                    GCMParameterSpec(128, iv))
                val plain = c.doFinal(ct)
                BLog.d("aniwaves mfw: decrypt OK via $name v=$version")
                return String(plain, Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun b64Decode(s: String): ByteArray? = try {
        val n = s.replace('-', '+').replace('_', '/')
        val p = n + "=".repeat((4 - n.length % 4) % 4)
        Base64.decode(p, Base64.DEFAULT)
    } catch (_: Exception) { null }
}
