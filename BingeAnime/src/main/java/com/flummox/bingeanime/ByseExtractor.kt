package com.flummox.bingeanime

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Shared helper — locate current foreground Activity.
private fun aniwavesCurrentActivity(): Activity? {
    try {
        val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
        val inst = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        if (inst is Activity) return inst
        try {
            val a = cls.getMethod("getActivity").invoke(inst)
            if (a is Activity) return a
        } catch (_: Throwable) {}
    } catch (_: Throwable) {}
    var ctx: android.content.Context? = BingeAnimeCtx.context
    var d = 0
    while (ctx != null && d < 12) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        d++
    }
    return null
}

// Shared WebView driver — loads an embed URL, waits for a matching .m3u8 request.
private suspend fun aniwavesRunWebView(
    tag: String,
    embedUrl: String,
    hostFilters: List<String>
): String? = withContext(Dispatchers.Main) {
    val activity = aniwavesCurrentActivity() ?: run {
        BLog.e("$tag: no activity")
        return@withContext null
    }
    val deferred = CompletableDeferred<String?>()
    val wv = WebView(activity)
    try {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                if (u.contains(".m3u8") && hostFilters.any { u.contains(it) }) {
                    BLog.v("$tag: intercept ${u.take(140)}")
                    if (!deferred.isCompleted) deferred.complete(u)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(
                    "(function(){try{var v=document.querySelector('video');" +
                    "if(v&&v.paused){v.muted=true;v.play().catch(function(){});}}catch(e){}})()",
                    null
                )
            }
        }

        activity.addContentView(wv, ViewGroup.LayoutParams(1, 1))
        BLog.d("$tag: webview load ${embedUrl.take(100)}")
        wv.loadUrl(embedUrl)

        withTimeoutOrNull(45_000L) { deferred.await() }
    } catch (e: Exception) {
        BLog.e("$tag: ${e.message}"); null
    } finally {
        try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Exception) {}
        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════════
// Byse (mfw09.org) — attest + invisible Turnstile + AES-GCM dance
// all run inside their own JS. We just intercept the resulting m3u8.
// ═══════════════════════════════════════════════════════════════
open class ByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://mfw09.org"
    override val requiresReferer = false

    private val lock = Mutex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        BLog.d("byse: getUrl $url")
        val m3u8 = lock.withLock {
            aniwavesRunWebView("byse", url, listOf(
                "sprintcdn", "echovideo.to", "dpopdrop", "owphbf24", "r66nv9ed"
            ))
        } ?: run {
            BLog.e("byse: no m3u8 captured from $url")
            return
        }
        BLog.d("byse: captured ${m3u8.take(120)}")
        callback.invoke(newExtractorLink(
            source = name,
            name = "Byse",
            url = m3u8,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "$mainUrl/"
        })
    }
}
// ═══════════════════════════════════════════════════════════════
// Echovideo (play.echovideo.ru) — plain HTTP extractor.
// GET the embed page, regex the m3u8 out of the inline JW Player
// config. No WebView. No worker. Just HTML parsing.
// ═══════════════════════════════════════════════════════════════
open class EchovideoExtractor : ExtractorApi() {
    override val name = "Echovideo"
    override val mainUrl = "https://play.echovideo.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        BLog.d("echovideo: getUrl ${url.take(120)}")

        val html = try {
            com.lagradost.cloudstream3.app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 " +
                        "Mobile Safari/537.36",
                    "Referer" to "https://aniwaves.ru/"
                )
            ).text
        } catch (e: Exception) {
            BLog.e("echovideo: page fetch failed: ${e.message}")
            return
        }

        BLog.d("echovideo: html len=${html.length}")

        // Ordered probes — most specific to least specific.
        val patterns = listOf(
            // JW Player sources array: {"file": "https://...m3u8..."}
            Regex("""["']file["']\s*:\s*["']([^"']*\.m3u8[^"']*)["']"""),
            // JW Player src field
            Regex("""["']src["']\s*:\s*["']([^"']*\.m3u8[^"']*)["']"""),
            // Direct source tag: <source src="...m3u8...">
            Regex("""<source[^>]+src=["']([^"']*\.m3u8[^"']*)["']"""),
            // Any URL ending in .m3u8 with or without query
            Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*"""),
            // echovideo cdn pattern: /cdn/{hash}?t.m3u8
            Regex("""https?://[^"'\s<>]+/cdn/[a-f0-9]+\?t\.m3u8[^"'\s<>]*"""),
            // sprintcdn pattern
            Regex("""https?://[^"'\s<>]+/hls2/\d+/\d+/[^"'\s<>]+/master\.m3u8[^"'\s<>]*""")
        )

        var hit: String? = null
        var hitWhich = -1
        for ((i, rx) in patterns.withIndex()) {
            val m = rx.find(html) ?: continue
            val candidate = (if (m.groupValues.size > 1) m.groupValues[1] else m.value)
                .replace("\\/", "/")
                .trim()
            if (candidate.startsWith("http")) {
                hit = candidate
                hitWhich = i
                break
            }
        }

        if (hit == null) {
            BLog.e("echovideo: no m3u8 in html (len=${html.length})")
            // Dump a small chunk around 'jwplayer' or 'sources' to help
            // us see what the config actually looks like.
            val idx = listOf(
                html.indexOf("jwplayer", ignoreCase = true),
                html.indexOf("sources", ignoreCase = true),
                html.indexOf("file", ignoreCase = true)
            ).filter { it >= 0 }.minOrNull()
            if (idx != null) {
                val start = (idx - 80).coerceAtLeast(0)
                val end = (idx + 200).coerceAtMost(html.length)
                BLog.d("echovideo: ctx '${html.substring(start, end).replace("\n", " ")}'")
            } else {
                BLog.d("echovideo: head='${html.take(200).replace("\n", " ")}'")
            }
            return
        }

        BLog.d("echovideo: pattern $hitWhich matched → ${hit.take(140)}")

        callback.invoke(newExtractorLink(
            source = name,
            name = "Echovideo",
            url = hit,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://play.echovideo.ru/"
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 " +
                    "Mobile Safari/537.36",
                "Referer" to "https://play.echovideo.ru/"
            )
        })
        BLog.d("echovideo: emitted")
    }
}
