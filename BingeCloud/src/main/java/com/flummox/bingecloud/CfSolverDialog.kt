package com.flummox.bingecloud

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CfResult(val html: String, val cookie: String)

object CfSolverDialog {

    private const val TOTAL_TIMEOUT_MS = 120_000L
    private const val SILENT_GRACE_MS = 8_000L

    // Must match MlsbdApi.MLSBD_UA and CloudflareHelper.CF_UA exactly.
    private const val CF_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(activity: Activity, url: String): CfResult? =
        withContext(Dispatchers.Main) { solve(activity, url) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solve(activity: Activity, url: String): CfResult? {
        if (activity.isFinishing) return null
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) return null

        val deferred = CompletableDeferred<CfResult?>()

        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

        // ── root: FrameLayout so we can stack WebView / reveal-UI / overlay ──
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#0A0D14"))
        }

        // ── layer 1: WebView (fills) ──
        val wv = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = CF_UA
            setBackgroundColor(Color.parseColor("#0A0D14"))
            addJavascriptInterface(CfBridge { html ->
                if (!deferred.isCompleted) {
                    val cookie = try {
                        CookieManager.getInstance().getCookie(url) ?: ""
                    } catch (_: Exception) { "" }
                    BCLog.d("[CF] page captured (${html.length} chars, cookie ${cookie.length})")
                    deferred.complete(CfResult(html, cookie))
                }
            }, "CfBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    BCLog.d("[CF] onPageFinished")
                    view?.evaluateJavascript(JS_INSTALL_OBSERVER, null)
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        BCLog.d("[CF] main frame error: ${error?.description}")
                    }
                }
                override fun onRenderProcessGone(
                    view: WebView?, detail: RenderProcessGoneDetail?
                ): Boolean {
                    BCLog.e("[CF] render process gone")
                    if (!deferred.isCompleted) deferred.complete(null)
                    return true
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        root.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── layer 2: reveal UI (hidden until we need the user) ──
        val revealUi = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F1520"))
            setPadding(28, 32, 28, 32)
        }
        val title = TextView(activity).apply {
            text = "Verifying browser"
            setTextColor(Color.parseColor("#E6EDF5"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(title)
        val cancelBtn = Button(activity).apply {
            text = "Cancel"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(24, 12, 24, 12)
        }
        topBar.addView(cancelBtn)
        revealUi.addView(topBar)
        revealUi.addView(TextView(activity).apply {
            text = "Tap the checkbox, then wait. This only happens once per site."
            setTextColor(Color.parseColor("#8296AD"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#0F1520"))
            setPadding(28, 0, 28, 20)
        })
        root.addView(revealUi)

        // ── layer 3: opaque overlay during silent phase ──
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#0A0D14"))
        }
        val pill = TextView(activity).apply {
            text = "Verifying…"
            setTextColor(Color.parseColor("#8296AD"))
            textSize = 13f
            setPadding(36, 20, 36, 20)
            setBackgroundColor(Color.parseColor("#141B28"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        overlay.addView(pill)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        dlg.setContentView(root)

        dlg.setOnDismissListener {
            try { wv.stopLoading() } catch (_: Exception) {}
            try { wv.destroy() } catch (_: Exception) {}
            if (!deferred.isCompleted) {
                BCLog.d("[CF] dialog dismissed before capture")
                deferred.complete(null)
            }
        }
        cancelBtn.setOnClickListener { dlg.dismiss() }

        dlg.show()
        dlg.window?.let { w ->
            w.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            w.setBackgroundDrawableResource(android.R.color.black)
        }

        // ── reveal control ──
        var revealed = false
        fun reveal(reason: String) {
            if (revealed) return
            revealed = true
            BCLog.d("[CF] reveal ($reason)")
            revealUi.visibility = View.VISIBLE
            overlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    overlay.visibility = View.GONE
                    try { wv.invalidate(); root.invalidate() } catch (_: Exception) {}
                }
                .start()
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val graceRunnable = Runnable {
            if (!deferred.isCompleted) reveal("grace expired")
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (deferred.isCompleted) return
                try {
                    wv.evaluateJavascript(JS_DETECT_CF, null)
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 900L)
            }
        }

        // ── JS status bridge: silent auto-pass vs forced interaction ──
        val statusBridge = object {
            @JavascriptInterface
            fun onCfState(state: String) {
                mainHandler.post {
                    if (deferred.isCompleted) return@post
                    when (state) {
                        "interactive" -> reveal("cf interactive widget")
                        "cf_still_present" -> {
                            // Waiting silently; grace runnable will fire if needed
                        }
                        "page_ok" -> {
                            // Bridge will complete deferred via HTML push.
                        }
                    }
                }
            }
        }
        wv.addJavascriptInterface(statusBridge, "CfStatus")

        wv.loadUrl(url)

        mainHandler.postDelayed(graceRunnable, SILENT_GRACE_MS)
        mainHandler.postDelayed(pollRunnable, 1_500L)

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { deferred.await() }

        mainHandler.removeCallbacks(graceRunnable)
        mainHandler.removeCallbacks(pollRunnable)
        try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {}
        return result
    }

    private const val JS_INSTALL_OBSERVER = """
    (function() {
        if (window.__cf_observer_installed) return;
        window.__cf_observer_installed = true;
        window.__cf_push = function() {
            try {
                var html = document.documentElement.outerHTML || "";
                if (window.CfBridge && window.CfBridge.onHtml) {
                    window.CfBridge.onHtml(html);
                }
            } catch (e) {}
        };
        try {
            var obs = new MutationObserver(function() { window.__cf_push(); });
            obs.observe(document.documentElement, { childList: true, subtree: true });
        } catch (e) {}
        setTimeout(window.__cf_push, 300);
        setTimeout(window.__cf_push, 1200);
        setTimeout(window.__cf_push, 2500);
        setTimeout(window.__cf_push, 5000);
    })();
    """

    private const val JS_DETECT_CF = """
    (function() {
        try {
            var html = (document.documentElement.outerHTML || "").toLowerCase();
            var iframe = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
            var interactive = false;
            if (iframe) {
                var r = iframe.getBoundingClientRect();
                interactive = r.width > 0 && r.height > 0 && r.top < window.innerHeight;
            }
            var cfMarkers = (
                html.indexOf('just a moment') !== -1 ||
                html.indexOf('checking your browser') !== -1 ||
                html.indexOf('cf-chl') !== -1 ||
                html.indexOf('enable javascript and cookies') !== -1 ||
                html.indexOf('verify you are human') !== -1
            );
            if (interactive && window.CfStatus && window.CfStatus.onCfState) {
                window.CfStatus.onCfState('interactive');
            } else if (cfMarkers && window.CfStatus && window.CfStatus.onCfState) {
                window.CfStatus.onCfState('cf_still_present');
            } else if (!cfMarkers && window.CfStatus && window.CfStatus.onCfState) {
                window.CfStatus.onCfState('page_ok');
            }
        } catch (e) {}
    })();
    """
}

class CfBridge(private val callback: (String) -> Unit) {

    private var lastLen: Int = -1

    @JavascriptInterface
    fun onHtml(html: String) {
        try {
            if (html.length != lastLen) {
                BCLog.d("[CF] html push len=${html.length}")
                lastLen = html.length
            }
            if (html.length < 800) return
            if (isCfChallenge(html)) return
            callback(html)
        } catch (e: Throwable) {
            BCLog.e("[CF] bridge threw: ${e.message}")
        }
    }
}
