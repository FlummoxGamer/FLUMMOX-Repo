package com.flummox.bingeanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val MEGAPLAY_ENC_IV = "W0;27ToaUpl_P%'c"
private const val MEGAPLAY_ENC_KEY = "i?LMTAx0Q6,:}50U"
private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

private val MEGAPLAY_PROXY_MAP: Map<String, String> = mapOf(
    "vibeplayer.site" to "nanobyte.bigdreamsmalldih.site",
    "vault-01.uwucdn.top" to "uwu1.bigdreamsmalldih.site",
    "vault-02.uwucdn.top" to "uwu2.bigdreamsmalldih.site",
    "vault-03.uwucdn.top" to "uwu3.bigdreamsmalldih.site",
    "vault-04.uwucdn.top" to "uwu4.bigdreamsmalldih.site",
    "vault-05.uwucdn.top" to "uwu5.bigdreamsmalldih.site",
    "vault-06.uwucdn.top" to "uwu6.bigdreamsmalldih.site",
    "vault-07.uwucdn.top" to "uwu7.bigdreamsmalldih.site",
    "vault-08.uwucdn.top" to "uwu8.bigdreamsmalldih.site",
    "vault-09.uwucdn.top" to "uwu9.bigdreamsmalldih.site",
    "vault-10.uwucdn.top" to "uwu10.bigdreamsmalldih.site",
    "vault-11.uwucdn.top" to "uwu11.bigdreamsmalldih.site",
    "vault-12.uwucdn.top" to "uwu12.bigdreamsmalldih.site",
    "vault-13.uwucdn.top" to "uwu13.bigdreamsmalldih.site",
    "vault-14.uwucdn.top" to "uwu14.bigdreamsmalldih.site",
    "vault-15.uwucdn.top" to "uwu15.bigdreamsmalldih.site",
    "vault-16.uwucdn.top" to "uwu16.bigdreamsmalldih.site",
    "vault-99.uwucdn.top" to "uwu17.bigdreamsmalldih.site",
    "vault-10.owocdn.top" to "10.bigdreamsmalldih.site",
    "vault-11.owocdn.top" to "11.bigdreamsmalldih.site",
    "vault-12.owocdn.top" to "12.bigdreamsmalldih.site",
    "vault-13.owocdn.top" to "13.bigdreamsmalldih.site",
    "vault-14.owocdn.top" to "14.bigdreamsmalldih.site",
    "vault-15.owocdn.top" to "15.bigdreamsmalldih.site",
    "vault-16.owocdn.top" to "16.bigdreamsmalldih.site",
    "vault-99.owocdn.top" to "99.bigdreamsmalldih.site"
)

private fun megaplayProxyHost(url: String): String {
    var cur = url
    for ((old, new) in MEGAPLAY_PROXY_MAP) cur = cur.replace(old, new)
    return cur
}

private fun b64UrlNoPad(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun signMegaPlayUrl(url: String): String {
    if (url.contains("token=")) return url
    val m = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE).find(url)
        ?: return url
    val payload = "${(System.currentTimeMillis() / 1000) + 600}|${m.groupValues[1]}/${m.groupValues[2]}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    val token = "${b64UrlNoPad(payload.toByteArray(Charsets.UTF_8))}.${b64UrlNoPad(sig)}"
    val sep = if (url.contains("?")) "&" else "?"
    return "$url$sep" + "token=$token"
}

private fun decryptMegaPlaySources(enc: String): String? = try {
    val normalized = enc.replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    val data = Base64.decode(padded, Base64.DEFAULT)
    val keyBytes = MEGAPLAY_ENC_KEY.toByteArray(Charsets.UTF_8)
    val key = keyBytes + ByteArray(32 - keyBytes.size)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        IvParameterSpec(MEGAPLAY_ENC_IV.toByteArray(Charsets.UTF_8))
    )
    val json = String(cipher.doFinal(data), Charsets.UTF_8).trim()
    when {
        json.startsWith("{") -> JSONObject(json).optString("file").takeIf { it.isNotBlank() }
        json.startsWith("[") -> {
            val arr = JSONArray(json)
            if (arr.length() > 0)
                arr.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
            else null
        }
        else -> null
    }
} catch (e: Exception) {
    BLog.e("megaplay decrypt failed: ${e.message}"); null
}

suspend fun megaplayExtract(
    embedUrl: String,
    mirrorLabel: String
): List<ScrapedMirror> {
    val type = if (embedUrl.contains("/dub", true)) "dub" else "sub"

    val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Referer" to "https://megaplay.buzz/"
    )

    val host = try {
        java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "https://megaplay.buzz" }

    val ajaxHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to host,
        "Referer" to embedUrl
    )
    val playbackHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to host,
        "Referer" to "$host/"
    )

    val doc = try {
        app.get(embedUrl, headers = pageHeaders).document
    } catch (e: Exception) {
        BLog.v("megaplay page fetch failed: ${e.message}"); return emptyList()
    }

    val playerEl = doc.selectFirst("#megaplay-player")
    val streamId = run {
        playerEl?.attr("data-id")?.takeIf { it.isNotBlank() }?.let { return@run it }
        playerEl?.attr("data-realid")?.takeIf { it.isNotBlank() }?.let { return@run it }
        Regex("/stream/s-\\d+/(\\d+)").find(embedUrl)?.groupValues?.get(1)
    } ?: return emptyList()

    val srcUrl = "$host/stream/getSources?id=$streamId&type=$type"
    val root = try {
        JSONObject(app.get(srcUrl, headers = ajaxHeaders, referer = embedUrl).text)
    } catch (e: Exception) {
        BLog.v("megaplay sources fetch failed: ${e.message}"); return emptyList()
    }

    val m3u8Raw = run {
        val sources = root.opt("sources")
        when (sources) {
            is JSONObject -> sources.optString("file").takeIf { it.isNotBlank() }
            is JSONArray -> if (sources.length() > 0)
                sources.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
            else null
            else -> null
        }
    } ?: root.optString("enc").takeIf { it.isNotBlank() }?.let { decryptMegaPlaySources(it) }

    if (m3u8Raw.isNullOrBlank()) return emptyList()

    val proxied = megaplayProxyHost(m3u8Raw)
    val signed = signMegaPlayUrl(proxied)
    if (!signed.contains(".m3u8")) return emptyList()

    val captions = mutableListOf<Pair<String, String>>()
    root.optJSONArray("tracks")?.let { arr ->
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val kind = t.optString("kind")
            if (kind != "captions" && kind != "subtitles") continue
            val file = t.optString("file").takeIf { it.isNotBlank() } ?: continue
            val full = if (file.startsWith("http")) file else "$host/$file"
            val label = t.optString("label").takeIf { it.isNotBlank() } ?: "Unknown"
            captions.add(label to full)
        }
    }

    BLog.d("megaplay emit [$mirrorLabel] $type id=$streamId")
    return listOf(ScrapedMirror(
        quality = "Auto",
        mirror = mirrorLabel,
        url = signed,
        source = "ANIKAGE",
        headers = playbackHeaders,
        captions = captions
    ))
}
