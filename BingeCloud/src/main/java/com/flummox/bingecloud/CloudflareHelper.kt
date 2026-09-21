package com.flummox.bingecloud

import android.app.Activity
import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

// ── context holder for CF WebView ──
object BingeCloudCtx {
    var context: Context? = null
}

// Must match CfSolverDialog.CF_UA and MlsbdApi.MLSBD_UA exactly —
// cf_clearance is bound to the UA that solved it.
private const val CF_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val CF_INDICATORS = listOf(
    "just a moment",
    "checking your browser",
    "cf-challenge",
    "cf_chl_opt",
    "cf_chl_prog",
    "enable javascript and cookies to continue",
    "attention required! | cloudflare",
    "challenges.cloudflare.com"
)

internal fun isCfChallenge(html: String): Boolean {
    val lower = html.lowercase()
    return CF_INDICATORS.any { lower.contains(it) }
}

// ── locate current foreground activity ──
internal fun currentActivity(): Activity? {
    // Option 1: CommonActivity.INSTANCE (Kotlin object) or .getActivity()
    try {
        val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
        val instanceField = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val instance = instanceField.get(null)
        if (instance is Activity) return instance
        try {
            val m = cls.getMethod("getActivity")
            val a = m.invoke(instance)
            if (a is Activity) return a
        } catch (_: Throwable) {}
    } catch (_: Throwable) {}

    // Option 2: unwrap BingeCloudCtx.context through ContextWrapper chain
    var ctx: Context? = BingeCloudCtx.context
    var depth = 0
    while (ctx != null && depth < 12) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        depth++
    }
    return null
}

// ── main CF-aware GET ──
suspend fun cloudflareGet(url: String, referer: String? = null): String? {
    val startMs = System.currentTimeMillis()
    val domain = try { URI(url).host ?: "" } catch (_: Exception) { "" }

    // ── 1. stored per-domain cookie ──
val storedCookie = if (domain.isNotEmpty()) Settings.getCookieForDomain(domain) else null
if (!storedCookie.isNullOrBlank()) {
    try {
        val res = app.get(
            url, referer = referer,
            headers = mapOf("User-Agent" to CF_UA, "Cookie" to storedCookie)
        )
        if (res.code in 200..299 && !isCfChallenge(res.text)) {
            BCLog.d("[CF] stored cookie worked for $domain (${System.currentTimeMillis() - startMs}ms)")
            return res.text
        }
        BCLog.d("[CF] stored cookie rejected for $domain (HTTP ${res.code}, cf=${isCfChallenge(res.text)})")
    } catch (e: Exception) {
        BCLog.d("[CF] stored cookie threw for $domain: ${e.message}")
    }
} else {
    BCLog.d("[CF] no stored cookie for $domain")
}

    // ── 2. plain GET ──
    var shouldSolve = false
    try {
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to CF_UA))
        if (res.code in 200..299 && !isCfChallenge(res.text)) {
           BCLog.d("[CF] plain GET ok for $domain (${System.currentTimeMillis() - startMs}ms)")
           return res.text
        }
        BCLog.d("[CF] plain GET returned ${res.code} for $domain")
       // Only solve on genuine CF signals — not on 500 / 404 / etc.
       shouldSolve = res.code == 403 || res.code == 503 || isCfChallenge(res.text)
   } catch (e: Exception) {
       BCLog.d("[CF] plain GET threw for $domain: ${e.message}")
       // Network errors, OOM guards, TLS failures — not CF. Do not solve.
       shouldSolve = false
   }

   if (!shouldSolve) {
      BCLog.d("[CF] not a CF challenge for $domain — skipping solver")
      return null
   }

    // ── 3. interactive solver ──
    return try {
        val activity = currentActivity()
        if (activity == null) {
            BCLog.d("[CF] no activity for solver ($domain)")
            return null
        }
        BCLog.d("[CF] invoking solver for $domain")
        val result = CfSolverDialog.resolve(activity, url)
        if (result != null && result.html.isNotBlank()) {
            if (result.cookie.isNotBlank() && domain.isNotEmpty()) {
                Settings.saveCookieForDomain(domain, result.cookie)
            }
            BCLog.d("[CF] solver ok for $domain (${System.currentTimeMillis() - startMs}ms)")
            result.html
        } else {
            BCLog.d("[CF] solver returned null for $domain")
            null
        }
    } catch (e: Exception) {
        BCLog.e("[CF] solver failed for $domain: ${e.message}")
        null
    }
}

suspend fun cloudflareGetDoc(url: String, referer: String? = null): Document? {
    val html = cloudflareGet(url, referer) ?: return null
    return Jsoup.parse(html, url)
}
