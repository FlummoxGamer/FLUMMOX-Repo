package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

private const val MB_SECRET_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
private const val MB_SECRET_ALT_B64 = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"
private const val MB_VERSION_CODE = 50020126L
private const val MB_VERSION_NAME = "4.0.02.0831.03"
private const val MB_PACKAGE = "com.community.mbox.in"
private const val MB_INSTALL_STORE = "official"
private const val MB_UA = "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val MB_HOSTS = listOf(
    "api6.aoneroom.com", "api5.aoneroom.com", "api4.aoneroom.com",
    "api4sg.aoneroom.com", "api3.aoneroom.com"
)

private const val MB_BOOTSTRAP_HOST = "apig.inmoviebox.com"
private const val MB_BOOTSTRAP_PATH = "/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

private val mbDeviceIdLock = Any()
private var mbDeviceIdCache: String? = null

private fun deviceId(): String {
    return mbDeviceIdCache ?: synchronized(mbDeviceIdLock) {
        mbDeviceIdCache ?: run {
            val bytes = ByteArray(16)
            Random.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }.also { mbDeviceIdCache = it }
        }
    }
}

private fun clientInfo(): String {
    return """{"package_name":"$MB_PACKAGE","version_name":"$MB_VERSION_NAME","version_code":$MB_VERSION_CODE,"os":"android","os_version":"14","device_id":"${deviceId()}","install_store":"$MB_INSTALL_STORE","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
}

private fun md5Hex(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

private fun b64DecodeBytes(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
private fun b64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

private val SECRET_KEY_BYTES: ByteArray by lazy { b64DecodeBytes(MB_SECRET_B64) }
private val SECRET_KEY_ALT_BYTES: ByteArray by lazy { b64DecodeBytes(MB_SECRET_ALT_B64) }

private fun generateXClientToken(ts: Long): String {
    val tsStr = ts.toString()
    return "$tsStr,${md5Hex(tsStr.reversed().toByteArray(Charsets.UTF_8))}"
}

private fun buildCanonicalString(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
    val parsed = try { URI(url) } catch (_: Exception) { null }
    val path = parsed?.path ?: ""
    val query = parsed?.query?.takeIf { it.isNotBlank() }?.let { q ->
        q.split("&").mapNotNull { p ->
            val parts = p.split("=")
            if (parts.isEmpty()) null else parts[0] to (parts.getOrNull(1) ?: "")
        }.sortedBy { it.first }.joinToString("&") { (k, v) -> "$k=$v" }
    } ?: ""
    val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
    val bodyBytes = body?.toByteArray(Charsets.UTF_8)
    val bodyHash = if (bodyBytes != null) {
        val trimmed = if (bodyBytes.size > 0x19000) bodyBytes.copyOfRange(0, 0x19000) else bodyBytes
        md5Hex(trimmed)
    } else ""
    val bodyLength = bodyBytes?.size?.toString() ?: ""
    return "${method.uppercase(Locale.ROOT)}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
}

private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, useAltKey: Boolean = false, ts: Long = System.currentTimeMillis()): String {
    val canonical = buildCanonicalString(method, accept, contentType, url, body, ts)
    val secret = if (useAltKey) SECRET_KEY_ALT_BYTES else SECRET_KEY_BYTES
    val mac = Mac.getInstance("HmacMD5")
    mac.init(SecretKeySpec(secret, "HmacMD5"))
    return "$ts|2|${b64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))}"
}

private fun buildHeaders(method: String, url: String, contentType: String, accept: String, body: String?, bearer: String?): Map<String, String> {
    val ts = System.currentTimeMillis()
    val map = mutableMapOf(
        "user-agent" to MB_UA,
        "accept" to accept,
        "content-type" to contentType,
        "connection" to "keep-alive",
        "x-client-token" to generateXClientToken(ts),
        "x-tr-signature" to generateXTrSignature(method, accept, contentType, url, body, false, ts),
        "x-client-info" to clientInfo(),
        "x-client-status" to "0"
    )
    if (!bearer.isNullOrBlank()) map["Authorization"] = "Bearer $bearer"
    return map
}

private var mbSession: String? = null

private fun parseJwtExp(token: String): Long = try {
    val parts = token.split(".")
    if (parts.size != 3) 0L else {
        val padding = when (parts[1].length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
        val decoded = Base64.decode(parts[1] + padding, Base64.URL_SAFE or Base64.NO_WRAP)
        JSONObject(String(decoded)).optLong("exp", 0L) * 1000L
    }
} catch (_: Exception) { 0L }

fun restoreMbSession() {
    val tok = Settings.getMbToken() ?: return
    if (Settings.getMbTokenExp() > System.currentTimeMillis() + 60 * 60 * 1000L) {
        mbSession = tok
        BCLog.d("MB session restored (exp in ${(Settings.getMbTokenExp() - System.currentTimeMillis()) / 60000}min)")
    }
}

private suspend fun bootstrapToken(): String? {
    val url = "https://$MB_BOOTSTRAP_HOST$MB_BOOTSTRAP_PATH"
    return try {
        val res = app.get(url, headers = buildHeaders("GET", url, "application/json", "application/json", null, null))
        if (res.code !in 200..299) return null
        val xUser = res.headers["x-user"] ?: res.headers["X-User"] ?: return null
        val tok = JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
        if (tok != null) {
            mbSession = tok
            val exp = parseJwtExp(tok)
            if (exp > 0) Settings.saveMbToken(tok, exp)
        }
        tok
    } catch (e: Exception) { BCLog.e("MB bootstrap failed: ${e.message}"); null }
}

private suspend fun ensureSession(): String? {
    mbSession?.let { return it }
    restoreMbSession()
    mbSession?.let { return it }
    return bootstrapToken()
}

private suspend fun mbGet(path: String, query: String? = null, retried: Boolean = false): JSONObject? {
    val cacheKey = "mb:get:$path?${query ?: ""}"
    BCCache.get(cacheKey)?.let { return try { JSONObject(it) } catch (_: Exception) { null } }

    val session = ensureSession() ?: run { BCLog.e("MB: no session for GET $path"); return null }
    for (host in MB_HOSTS) {
        try {
            val fullUrl = if (query.isNullOrBlank()) "https://$host$path" else "https://$host$path?$query"
            val res = app.get(fullUrl, headers = buildHeaders("GET", fullUrl, "application/json", "application/json", null, session))
            if (res.code in 200..299) {
                BCCache.put(cacheKey, res.text)
                return try { JSONObject(res.text) } catch (e: Exception) { BCLog.e("MB JSON parse: ${e.message}"); null }
            }
            if ((res.code == 401 || res.code == 403 || res.code == 441) && !retried) {
                mbSession = null
                return mbGet(path, query, true)
            }
        } catch (e: Exception) { BCLog.e("MB GET $host err: ${e.message}") }
    }
    return null
}

data class MBSubject(val subjectId: String, val title: String, val year: Int?, val type: Int)
data class MBStream(
    val url: String,
    val realUrl: String,
    val quality: String,
    val size: String?,
    val signCookie: String? = null,
    val audio: String? = null,
    val durationSec: Long = 0L,
    val captions: List<Pair<String, String>> = emptyList()
)

    private fun extractPolicyResource(signCookie: String?): String? {
    if (signCookie.isNullOrBlank()) return null

    // ── New format (2026): Edge-Cache-Cookie=urlprefix=<b64url>:sign=<md5>:t=<unix> ──
    val urlPrefixMatch = Regex("""urlprefix=([A-Za-z0-9_\-]+)""").find(signCookie)
    if (urlPrefixMatch != null) {
        val b64 = urlPrefixMatch.groupValues[1]
        val normalized = b64.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            val decoded = String(Base64.decode(padded, Base64.DEFAULT)).trim()
            if (!decoded.startsWith("http")) null
            else {
                val trimmed = decoded.trimEnd('*', '/')
                val finalUrl = if (trimmed.endsWith(".mpd", true)) trimmed else "$trimmed/index.mpd"
                BCLog.d("extractPolicyResource new-format → $finalUrl")
                finalUrl
            }
        } catch (e: Exception) {
            BCLog.e("extractPolicyResource (urlprefix): ${e.message}"); null
        }
    }

    // ── Old format: CloudFront-Policy=<b64url JSON>; CloudFront-Signature=...; ... ──
    val match = Regex("CloudFront-Policy=([^;]+)").find(signCookie) ?: return null
    val policyRaw = match.groupValues[1]
    
    var decoded: String? = null
    val urlSafe = policyRaw.replace('-', '+').replace('~', '/').replace('_', '=')
    val paddedUrlSafe = if (urlSafe.length % 4 > 0) urlSafe + "=".repeat(4 - urlSafe.length % 4) else urlSafe
    decoded = try { String(Base64.decode(paddedUrlSafe, Base64.DEFAULT)) } catch (_: Exception) { null }

    if (decoded == null) {
        val std = policyRaw.replace('-', '+').replace('_', '/')
        val paddedStd = if (std.length % 4 > 0) std + "=".repeat(4 - std.length % 4) else std
        decoded = try { String(Base64.decode(paddedStd, Base64.DEFAULT)) } catch (_: Exception) { null }
    }
    if (decoded == null) return null

    return try {
        val root = JSONObject(decoded)
        val statement = root.optJSONArray("Statement")?.optJSONObject(0) ?: return null
        val resource = statement.optString("Resource").takeIf { it.isNotBlank() } ?: return null
        val trimmed = resource.trimEnd('*', '/')
        if (trimmed.endsWith(".mpd", true)) trimmed else "$trimmed/index.mpd"
    } catch (e: Exception) {
        BCLog.e("extractPolicyResource: ${e.message}"); null
    }
}

suspend fun mbSearch(query: String, page: Int = 1): List<MBSubject> {
    val cacheKey = "mb:search:$query:$page"
    BCCache.get(cacheKey)?.let { return parseSearchResults(it) }

    val session = ensureSession() ?: return emptyList()
    val jsonBody = JSONObject().apply {
        put("page", page)
        put("perPage", 20)
        put("keyword", query)
        put("restrictKid", 1)
    }.toString()

    for (host in MB_HOSTS) {
        try {
            val url = "https://$host/wefeed-mobile-bff/subject-api/search/v2"
            val res = app.post(url,
                headers = buildHeaders("POST", url, "application/json; charset=utf-8", "application/json", jsonBody, session),
                requestBody = jsonBody.toRequestBody(JSON_MEDIA))
            if (res.code !in 200..299) {
                if (res.code == 401 || res.code == 403 || res.code == 441) { mbSession = null; return mbSearch(query, page) }
                continue
            }
            BCCache.put(cacheKey, res.text)
            return parseSearchResults(res.text)
        } catch (e: Exception) { BCLog.e("MB search $host err: ${e.message}") }
    }
    return emptyList()
}

private fun parseSearchResults(text: String): List<MBSubject> {
    val json = try { JSONObject(text) } catch (_: Exception) { return emptyList() }
    val results = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
    val out = mutableListOf<MBSubject>()
    for (i in 0 until results.length()) {
        val subs = results.optJSONObject(i)?.optJSONArray("subjects") ?: continue
        for (j in 0 until subs.length()) {
            val s = subs.optJSONObject(j) ?: continue
            val id = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val title = s.optString("title").takeIf { it.isNotBlank() } ?: continue
            out.add(MBSubject(id, title, null, s.optInt("subjectType", 1)))
        }
    }
    BCLog.d("MB search: ${out.size} results")
    return out
}

suspend fun mbDetail(subjectId: String): JSONObject? =
    mbGet("/wefeed-mobile-bff/subject-api/get", "subjectId=$subjectId")

suspend fun mbLanguages(originalSubjectId: String): List<Pair<String, String>> {
    val detail = try { mbDetail(originalSubjectId) } catch (_: Exception) { null }
    val dubs = detail?.optJSONObject("data")?.optJSONArray("dubs")

    if (dubs == null || dubs.length() == 0) return listOf(originalSubjectId to "Original")

    var originalLabel: String? = null
    val dubEntries = mutableListOf<Pair<String, String>>()
    for (i in 0 until dubs.length()) {
        val d = dubs.optJSONObject(i) ?: continue
        val id = d.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
        val lan = d.optString("lanName").takeIf { it.isNotBlank() } ?: continue
        if (id == originalSubjectId) { originalLabel = lan; continue }
        dubEntries.add(id to lan)
    }
    val out = mutableListOf<Pair<String, String>>()
    out.add(originalSubjectId to (originalLabel ?: "Original"))
    out.addAll(dubEntries)
    BCLog.d("MB langs: ${out.map { it.second }}")
    return out
}

suspend fun mbPlay(subjectId: String, season: Int = 0, episode: Int = 0, audioLabel: String? = null): List<MBStream> {
    val q = "subjectId=$subjectId&se=$season&ep=$episode"
    val json = mbGet("/wefeed-mobile-bff/subject-api/play-info", q) ?: return emptyList()
    val root = json.optJSONObject("data") ?: json
val arr = root.optJSONArray("streams") ?: root.optJSONArray("videos") ?: root.optJSONArray("list") ?: return emptyList()

val captionsList = mutableListOf<Pair<String, String>>()
val captionsArr = root.optJSONArray("captions")
    ?: root.optJSONArray("subtitle")
    ?: root.optJSONArray("subtitles")
if (captionsArr != null) {
    for (i in 0 until captionsArr.length()) {
        val c = captionsArr.optJSONObject(i) ?: continue
        val lang = c.optString("language").ifBlank { c.optString("lang") }.ifBlank { "Unknown" }
        val url = c.optString("url").ifBlank { c.optString("file") }
        if (url.isNotBlank()) captionsList.add(lang to url)
    }
}
if (captionsList.isNotEmpty()) BCLog.d("MB captions: ${captionsList.map { it.first }}")

val out = mutableListOf<MBStream>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val url = o.optString("url").ifBlank { o.optString("playUrl").ifBlank { o.optString("src") } }
        if (url.isBlank()) continue
        val resolutionsStr = o.optString("resolutions").ifBlank { null }
        val quality = resolutionsStr?.split(",")
           ?.mapNotNull { it.trim().removeSuffix("p").removeSuffix("P").toIntOrNull() }
           ?.maxOrNull()
           ?.let { "${it}p" }
           ?: o.optString("quality").ifBlank { "Auto" }

        var dur = o.optLong("duration", 0L)
        if (dur <= 0) dur = o.optLong("durationSeconds", 0L)
        if (dur <= 0) dur = o.optLong("length", 0L)
        if (dur <= 0) dur = o.optLong("durationMs", 0L).let { if (it > 0) it / 1000 else 0 }

        val signCookie = o.optString("signCookie").ifBlank { null }
        val rawSafe = signCookie?.replace("Cookie", "C00kie")?.replace("cookie", "c00kie") ?: "NULL"
        BCLog.d("MB signCookie len=${signCookie?.length ?: 0} raw=$rawSafe")
        val realUrl = extractPolicyResource(signCookie) ?: url
        
        val urlHead = realUrl.take(120)
        BCLog.d("MB raw [$audioLabel] dur=${dur}s fmt=${o.optString("format")} codec=${o.optString("codecName")} size=${o.optString("size")} realUrl=$urlHead")

        out.add(MBStream(
    url = url,
    realUrl = realUrl,
    quality = quality,
    size = o.optString("size").ifBlank { null },
    signCookie = signCookie,
    audio = audioLabel,
    durationSec = dur,
    captions = captionsList
))
    }
    BCLog.d("MB play [$audioLabel]: ${out.size} streams")
    return out
}
