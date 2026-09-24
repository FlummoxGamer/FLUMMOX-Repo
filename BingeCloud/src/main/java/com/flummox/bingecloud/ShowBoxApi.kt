package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope


private const val SB_IV = "wEiphTn!"
private const val SB_KEY = "123d6cedf626dy54233aa1w6"
private const val SB_APP_KEY = "moviebox"
private const val SB_APP_ID = "com.tdo.showbox"
private const val SB_APP_VERSION = "11.7"
private const val SB_VERSION_CODE = "131"
private const val SB_API_PRIMARY = "https://showboxssl.shegu.net/api/api_client/"
private const val SB_API_FALLBACK = "https://showboxapissl.stsoso.com/api/api_client/"
private const val SB_FEBBOX = "https://www.febbox.com"

private const val SB_CLIENT_CERT = """
-----BEGIN CERTIFICATE-----
MIIEFTCCAv2gAwIBAgIUCrILmXOevO03gUhhbEhG/wZb2uAwDQYJKoZIhvcNAQEL
BQAwgagxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQH
Ew1TYW4gRnJhbmNpc2NvMRkwFwYDVQQKExBDbG91ZGZsYXJlLCBJbmMuMRswGQYD
VQQLExJ3d3cuY2xvdWRmbGFyZS5jb20xNDAyBgNVBAMTK01hbmFnZWQgQ0EgM2Q0
ZDQ4ZTQ2ZmI3MGM1NzgxZmI0N2VhNzk4MjMxZDMwHhcNMjQwNjA0MDkxMTAwWhcN
MzkwNjAxMDkxMTAwWjAiMQswCQYDVQQGEwJVUzETMBEGA1UEAxMKQ2xvdWRmbGFy
ZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJhpMlr/+IatuBqpuZuA
6QvqdI2QiFb1UMVujb/xiaBC/vqJMlMenLSDysk8xd4fLeC+GC8AyWf1IMJIz6d9
rBjOhN4D+MxvgphufkdIVqs63SqKcrr/ZL0JaRpxxEg/pKqSjH55Ik71keB8tt0m
mQ76WK1swMydOAqn6DIKVAi7wF9acWyX/6Ly+cmxfueLDZvkLigXl3gMHbuoa5Y+
CadqKl2qlijhnvjpuEbAvyDyXWe838TUi0PYMMVuOu7PV4By2LINsm+gKv83od4k
RCSWTrLKlgfqneqnudMrqeWckNUHGVB+3Lruw1ebB/Rs4gJ59VhJYpbNmM2mYT0r
VQkCAwEAAaOBuzCBuDATBgNVHSUEDDAKBggrBgEFBQcDAjAMBgNVHRMBAf8EAjAA
MB0GA1UdDgQWBBSF9Jkz4ZkbS5+LANO3YGWZRuX/PDAfBgNVHSMEGDAWgBTj01Q6
MJPAjpPqCEcv8rjxAUTO9jBTBgNVHR8ETDBKMEigRqBEhkJodHRwOi8vY3JsLmNs
b3VkZmxhcmUuY29tL2U1YTYzNzc5LTQ3NWQtNGI5OS04YzQxLTIwMjE5MmZhNjNj
ZC5jcmwwDQYJKoZIhvcNAQELBQADggEBALD+9MsfANm7fbzYH5/lXl07hwn2KSN8
PH7zxyo87ED62IL9U7YOnhb3rqLS1RXUzyHEmb9kzYgzKzzNrELdKH77vNk172Vk
iRQwGD0MZiYNERWhmmBtjV1oxllz74fL4+aZTYAespIbOekmFn9NZJ+XSdyF9RqS
fzDiz27GP5ZSHHI6xwdUP+a87N/RnfI4UwGxyXvPpHfoAZWjoXDqLKKwEL36/Sqi
nGcp970y0gnZ2zI2ehqivsF7BATMZqvU+LJKCH8NEE2bnbCJ6qlPHZWZFNKYWBOe
I1Crf0gNAWD/q3HKGMVZiyxlhU6SsQS4/08tDXXQjWYfl6i3oviexSk=
-----END CERTIFICATE-----
"""

private const val SB_CLIENT_KEY = """
-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCYaTJa//iGrbga
qbmbgOkL6nSNkIhW9VDFbo2/8YmgQv76iTJTHpy0g8rJPMXeHy3gvhgvAMln9SDC
SM+nfawYzoTeA/jMb4KYbn5HSFarOt0qinK6/2S9CWkaccRIP6Sqkox+eSJO9ZHg
fLbdJpkO+litbMDMnTgKp+gyClQIu8BfWnFsl/+i8vnJsX7niw2b5C4oF5d4DB27
qGuWPgmnaipdqpYo4Z746bhGwL8g8l1nvN/E1ItD2DDFbjruz1eActiyDbJvoCr/
N6HeJEQklk6yypYH6p3qp7nTK6nlnJDVBxlQfty67sNXmwf0bOICefVYSWKWzZjN
pmE9K1UJAgMBAAECggEAQFvnxjKiJWkVPbkfJjHU91GtnxwB3sqfrYdmN0ANUE4K
MwydYikinj2q87iEi6wZ6PYM60hHRG1oRHKPsZgphJ4s0D3YIagS+0Bpdbtv0cW9
IBovoZR4WzUum1qgOqwZYmgZCM0pNjOPwr6XT6Ldbkw8BxvN/HmFcUZ/ECZ5XugW
cKqKoy0HSlxwXT4PUAgLVfL4KvWy4A4yJJF24zgRKE4QYveOR4nUFvoRdxhuAyYW
xsajItj6sc6Jyr9FJzdw5Ra9EFwcWFM4uDdjHoaQrjwKId9fkCA+9eUCERWKTxCR
P8mU4p2cAJYO+ME9fZfs8H2uqGNj13XUzoT6JzM8UwKBgQDUFZWcfmlgCM2BjU9c
8qhYjD2egT3qxWJLYSUTUZfdOGgB6lxTqnOhsy93xYmVInz6r9XEZsLVoQj/wcZk
p7y+MxjiWNcBcUmviwHee42fe6BQZHaYlAFtlAKNSiHumfq6AtXpZvkQZJWTSRyW
lI4LBEL6fSuqpk88EH9FXJbChwKBgQC3+F/1Qi3EoeohhWD+jMO0r8IblBd7jYbp
2zs17KQsCEyc1qyIaE+a8Ud8zUqsECKWBuSFsQ2qrR3jZW6DZOw8hmp1foYC+Jjr
C/BHyWsyYxrCoxpvSJMXCY6ulyFHjIZboopRVi/jgfowteMW6WyxvOMqVAqZtxRW
HyFbsa+/7wKBgQCGHRwd+SZjr01dZmHQcjaYwB5bNHlWE/nDlyvd2pQBNaE3zN8T
nU8/6tLSl50YLNYBpN22NBFzDEFnkj8F+bh2QlOzFuDnrZ8eHfZRnaoCNyg6jj0c
4UNB6v3uIPnyK3cM16wzy4Umo6SenfYxFsH4H3rHcg4B/OdQIVKKJzHC0wKBgQCj
QxhlX0WeqtJMzUE2pVVIlHF+Z/4u93ozLwts34USTosu5JRYublrl5QJfWY3LFqF
KbjDrEykmt1bYDijAn1jeSYg/xeOq2+JqB6klms7XBfzgyuCdrWSTDkDV7uA84SI
7cYySHpXPJH7iG7vdlevpCE0/0ApCgBSLW49IYMGoQKBgAxVRqAhLdA0RO+nTAC/
whOL5RGy5M2oXKfqNkzEt2k5og7xXY7ZoYTye5Byb3+wLpEJXW+V8FlfXk/u5ZI7
oFuZne+lYcCPMNDXdku6wKdf9gSnOSHOGMu8TvHcud4uIDYmFH5qabJL5GDoQi7Q
12XvK21e6GNOEaRRlTHz0qUB
-----END PRIVATE KEY-----
"""

// ── FebBox request headers. Detects raw ui value vs full cookie string. ──
private fun sbFebBoxHeaders(): Map<String, String> {
    val base = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en",
        "Referer" to "$SB_FEBBOX/"
    )
    val raw = Settings.getFebBoxToken()
    if (raw.isBlank()) return base
    val cookieHeader = if (raw.contains("=")) raw else "ui=$raw"
    return base + ("Cookie" to cookieHeader)
}

private fun sbMd5Hex(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun sbEncrypt(plain: String): String {
    val keyBytes = SB_KEY.toByteArray(Charsets.UTF_8)
    val key24 = ByteArray(24)
    System.arraycopy(keyBytes, 0, key24, 0, minOf(keyBytes.size, 24))
    val cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key24, "DESede"),
        IvParameterSpec(SB_IV.toByteArray(Charsets.UTF_8))
    )
    return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
}

private fun sbRandomToken(): String {
    val chars = "0123456789abcdef"
    return (1..32).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}

private fun sbExpiry(): Long = System.currentTimeMillis() / 1000 + 43200

private val sbHttpClient: OkHttpClient by lazy {
    try {
        val certPem = SB_CLIENT_CERT.replace(Regex("-----[^-]+-----|\\s+"), "")
        val certBytes = Base64.decode(certPem, Base64.DEFAULT)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        val keyPem = SB_CLIENT_KEY.replace(Regex("-----[^-]+-----|\\s+"), "")
        val keyBytes = Base64.decode(keyPem, Base64.DEFAULT)
        val privateKey: PrivateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, "".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, "".toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tmf.trustManagers[0] as X509TrustManager)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    } catch (e: Exception) {
        BCLog.e("ShowBox cert setup failed: ${e.message}")
        OkHttpClient()
    }
}

private suspend fun sbQuery(query: String): String? {
    val encrypted = sbEncrypt(query)
    val verify = sbMd5Hex(sbMd5Hex(SB_APP_KEY) + SB_KEY + encrypted)
    val bodyJson = """{"app_key":"${sbMd5Hex(SB_APP_KEY)}","verify":"$verify","encrypt_data":"$encrypted"}"""
    val b64Body = Base64.encodeToString(bodyJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val form = FormBody.Builder()
        .add("data", b64Body)
        .add("appid", "27")
        .add("platform", "android")
        .add("version", SB_VERSION_CODE)
        .add("medium", "Website")
        .add("token", sbRandomToken())
        .build()

    for (base in listOf(SB_API_PRIMARY, SB_API_FALLBACK)) {
        try {
            val req = Request.Builder()
                .url(base)
                .header("Platform", "android")
                .header("Accept", "charset=utf-8")
                .header("Cookie", "ci=168aec549ca68e")
                .header("User-Agent", "okhttp/3.2.0")
                .post(form)
                .build()
            val res = sbHttpClient.newCall(req).execute()
            val text = res.body?.string() ?: continue
            if (text.isNotBlank() && !text.trim().startsWith("<")) return text
        } catch (e: Exception) {
            BCLog.d("ShowBox api $base failed: ${e.message}")
        }
    }
    return null
}

data class SBItem(
    val id: Int,
    val title: String,
    val poster: String?,
    val boxType: Int,
    val imdbRating: String?,
    val qualityTag: String?
)

suspend fun sbSearch(query: String): List<SBItem> {
    val q = """{"childmode":"0","app_version":"$SB_APP_VERSION","module":"Search3","channel":"Website","page":"1","lang":"en","type":"all","keyword":"${query.replace("\"", "\\\"")}","pagelimit":"15","expired_date":"${sbExpiry()}","platform":"android","appid":"$SB_APP_ID"}"""
    val json = sbQuery(q) ?: return emptyList()
    return try {
        val root = JSONObject(json)
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<SBItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: continue
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val poster = o.optString("poster").ifBlank { o.optString("poster_2") }.ifBlank { null }
            out.add(SBItem(
                id = id,
                title = title,
                poster = poster,
                boxType = o.optInt("box_type", 2),
                imdbRating = o.optString("imdb_rating").ifBlank { null },
                qualityTag = o.optString("quality_tag").ifBlank { null }
            ))
        }
        BCLog.d("ShowBox search '$query' → ${out.size}")
        out
    } catch (e: Exception) {
        BCLog.e("ShowBox search parse: ${e.message}"); emptyList()
    }
}

suspend fun sbExternalShareKey(mediaId: Int, boxType: Int): String? {
    val url = "$SB_FEBBOX/mbp/to_share_page?box_type=$boxType&mid=$mediaId&json=1"
    return try {
        val json = app.get(url, headers = sbFebBoxHeaders()).text
        val data = JSONObject(json).optJSONObject("data")
        val link = data?.optString("link")?.takeIf { it.isNotBlank() }
            ?: data?.optString("share_link")?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
        BCLog.d("ShowBox shareKey=$link (logged=${Settings.getFebBoxToken().isNotBlank()})")
        link
    } catch (e: Exception) {
        BCLog.e("ShowBox share fail: ${e.message}"); null
    }
}

suspend fun sbFileList(shareKey: String, parentId: Long? = null): JSONArray? {
    val url = if (parentId != null)
        "$SB_FEBBOX/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1"
    else
        "$SB_FEBBOX/file/file_share_list?share_key=$shareKey"
    return try {
        val json = app.get(url, headers = sbFebBoxHeaders()).text
        val root = JSONObject(json)
        val data = root.optJSONObject("data")
        val files = data?.optJSONArray("file_list")
        BCLog.d("ShowBox fileList: ${files?.length() ?: 0} files, data keys=${data?.keys()?.asSequence()?.toList()}")
        if (files != null && files.length() > 0) {
            val first = files.optJSONObject(0)
            BCLog.d("ShowBox file[0]: ${first?.toString()?.take(300)}")
        } else if (data != null) {
            BCLog.d("ShowBox data dump: ${data.toString().take(500)}")
        }
        files
    } catch (e: Exception) {
        BCLog.e("ShowBox fileList fail: ${e.message}"); null
    }
}

// ── best-effort local IPv4 so FebBox picks a nearby CDN edge ──
private fun sbLocalIpv4(): String? = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { addr ->
            !addr.isLoopbackAddress &&
            !addr.isLinkLocalAddress &&
            addr is java.net.Inet4Address
        }?.hostAddress
} catch (_: Exception) { null }


// ── fetch direct download URL for a given fid ──
// Response shape: {"code":1,"msg":"success","data":[{"download_url":"...","path":"...",...}]}
suspend fun sbGetDownloadUrl(shareKey: String, fid: Long): String? {
    val userIp = sbLocalIpv4()?.let { "&user_ip=$it" } ?: ""
    val url = "$SB_FEBBOX/file/file_download?fid=$fid&share_key=$shareKey$userIp"
    return try {
        val json = app.get(url, headers = sbFebBoxHeaders()).text
        val root = JSONObject(json)
        val code = root.optInt("code", -1)
        if (code != 0 && code != 1) {
            BCLog.d("ShowBox dl fid=$fid code=$code msg=${root.optString("msg").take(80)}")
            return null
        }
        val arr = root.optJSONArray("data")
        val entry = arr?.optJSONObject(0)
        val dl = entry?.optString("download_url")?.takeIf { it.isNotBlank() }
        if (dl != null) BCLog.d("ShowBox dl fid=$fid → ${dl.take(100)}")
        else BCLog.d("ShowBox dl fid=$fid: no download_url in data[0]")
        dl
    } catch (e: Exception) {
        BCLog.e("ShowBox dl fid=$fid fail: ${e.message}"); null
    }
}

suspend fun showBoxExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val hits = sbSearch(q.title)
    if (hits.isEmpty()) return emptyList()

    val expectedType = if (q.type == "series") 2 else 1
    var best: SBItem? = null
    var bestScore = 0
    for (item in hits) {
        if (!titleMatches(q.title, item.title)) continue
        var score = 1
        if (item.boxType == expectedType) score += 3
        if (q.year.isNotBlank() && item.title.contains(q.year)) score += 2
        if (score > bestScore) { bestScore = score; best = item }
    }
    val subject = best ?: return emptyList()
    BCLog.d("ShowBox matched '${subject.title}' (score=$bestScore)")

    val shareKey = sbExternalShareKey(subject.id, subject.boxType) ?: return emptyList()
    var fileList = sbFileList(shareKey) ?: return emptyList()
    BCLog.d("ShowBox top-level: ${fileList.length()} entries, type=${q.type}, season=${q.season}")

    if (q.type == "series" && q.season > 0) {
        var seasonFid: Long? = null
        for (i in 0 until fileList.length()) {
            val f = fileList.optJSONObject(i) ?: continue
            if (f.optString("file_name").equals("season ${q.season}", true)) {
                seasonFid = f.optLong("fid"); break
            }
        }
        if (seasonFid != null) {
            fileList = sbFileList(shareKey, seasonFid) ?: return emptyList()
        }
    }

        // collect candidates first, then resolve all download URLs in parallel
    data class Cand(val name: String, val fid: Long, val inlinePath: String?, val quality: String, val size: String?)
    val candidates = mutableListOf<Cand>()
    for (i in 0 until fileList.length()) {
        val f = fileList.optJSONObject(i) ?: continue
        val name = f.optString("file_name")
        if (q.type == "series" && q.season > 0 && q.episode > 0) {
            // Word boundaries prevent "s01e1" matching inside "s01e10".."s01e19"
            val pat = Regex(
                """(?:^|[^0-9])s0*${q.season}\s*e0*${q.episode}(?:[^0-9]|$)""",
                RegexOption.IGNORE_CASE
           )
           if (!pat.containsMatchIn(name)) continue
        }
        val fid = f.optLong("fid", 0L)
        val inlinePath = f.optString("path").takeIf { it.isNotBlank() }
        val quality = f.optString("quality").ifBlank {
            Regex("""(\d{3,4})[pP]""").find(name)?.groupValues?.get(1)?.plus("p") ?: "Auto"
        }
        val size = f.optString("file_size").ifBlank { f.optString("size").ifBlank { null } }
        candidates.add(Cand(name, fid, inlinePath, quality, size))
    }

    val resolved = coroutineScope {
    candidates.map { c ->
        async {
            val url = c.inlinePath ?: if (c.fid > 0) sbGetDownloadUrl(shareKey, c.fid) else null
            c to url
        }
    }.map { it.await() } 
    }

    val out = mutableListOf<ScrapedMirror>()
    for ((c, url) in resolved) {
        if (url == null) {
            BCLog.d("ShowBox skip (no path/fid): ${c.name}")
            continue
        }
        val label = "ShowBox ${c.quality}${if (c.size != null) " [${c.size}]" else ""}"
        out.add(ScrapedMirror(c.quality, label, url.replace("\\/", "/"), "SHOWBOX"))
    }
    BCLog.d("ShowBox: ${out.size} mirrors")
    return out
}
