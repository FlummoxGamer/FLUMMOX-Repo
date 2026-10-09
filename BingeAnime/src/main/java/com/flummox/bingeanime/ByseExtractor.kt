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

open class ByseExtractor : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://mfw09.org"
    override val requiresReferer = false

    private val webViewLock = Mutex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8 = webViewLock.withLock { extractWithWebView(url) } ?: run {
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

    private suspend fun extractWithWebView(embedUrl: String): String? =
        withContext(Dispatchers.Main) {
            val activity = currentActivity() ?: run {
                BLog.e("byse: no activity")
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
                        if (u.contains(".m3u8") && (
                                u.contains("sprintcdn") ||
                                u.contains("echovideo") ||
                                u.contains("dpopdrop") ||
                                u.contains("owphbf24") ||
                                u.contains("r66nv9ed")
                            )
                        ) {
                            BLog.v("byse: intercept ${u.take(140)}")
                            if (!deferred.isCompleted) deferred.complete(u)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Nudge the player if autostart didn't fire
                        view.evaluateJavascript(
                            "(function(){try{var v=document.querySelector('video');" +
                            "if(v&&v.paused){v.muted=true;v.play().catch(function(){});}}catch(e){}})()",
                            null
                        )
                    }
                }

                activity.addContentView(
                    wv,
                    ViewGroup.LayoutParams(1, 1)
                )
                wv.loadUrl(embedUrl)

                withTimeoutOrNull(45_000L) { deferred.await() }
            } catch (e: Exception) {
                BLog.e("byse: webview ${e.message}")
                null
            } finally {
                try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
            }
        }

    private fun currentActivity(): Activity? {
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
}
