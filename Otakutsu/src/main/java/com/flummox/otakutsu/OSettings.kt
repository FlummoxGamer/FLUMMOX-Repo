package com.flummox.otakutsu

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object OSettings {

    private const val BG = 0xFF070707.toInt()
    private const val SURFACE = 0xFF121212.toInt()
    private const val SURFACE_2 = 0xFF161616.toInt()
    private const val BORDER = 0xFF242424.toInt()
    private const val BORDER_HI = 0xFF3A3A3A.toInt()
    private const val TEXT = 0xFFEDEDED.toInt()
    private const val SUBTEXT = 0xFF7A7A7A.toInt()
    private const val ACTIVE = 0xFF4ADE80.toInt()
    private const val RED = 0xFFE57373.toInt()
    private const val LOG_TEXT = 0xFFB8B8B8.toInt()

    private const val K_TITLE_LANG = "otakutsu_title_lang"
    private const val K_PREFETCH = "otakutsu_prefetch"

    // ── prefs ──
    fun getTitleLang(): String = getKey<String>(K_TITLE_LANG) ?: "english"
    fun setTitleLang(v: String) {
        setKey(K_TITLE_LANG, v)
        OtakutsuProvider.clearSectionCache()
    }
    fun isPrefetchEnabled(): Boolean = getKey<Boolean>(K_PREFETCH) ?: true
    fun setPrefetchEnabled(v: Boolean) { setKey(K_PREFETCH, v) }

    // ── helpers ──
    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, ctx: Context, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    private fun ripple(shape: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, shape)

    private fun stagger(v: View, i: Int, baseDelay: Long = 70L) {
        v.alpha = 0f
        v.translationY = 28f
        v.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(baseDelay * i)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun makeTile(ctx: Context, emoji: String, label: String, onClick: () -> Unit): LinearLayout {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(SURFACE, 20, ctx, 1, BORDER_HI))
            isClickable = true
            setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
            setOnClickListener { onClick() }
        }
        tile.addView(TextView(ctx).apply {
            text = emoji
            textSize = 22f
            setPadding(0, 0, dp(ctx, 18), 0)
        })
        tile.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT)
            textSize = 15f
            letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tile.addView(TextView(ctx).apply {
            text = "›"
            setTextColor(SUBTEXT)
            textSize = 22f
        })
        return tile
    }

    private fun baseRoot(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(BG, 0, ctx)
    }

    private fun subWindow(ctx: Context, title: String, body: (LinearLayout, Dialog) -> Unit) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = baseRoot(ctx).apply {
            setPadding(dp(ctx, 24), dp(ctx, 40), dp(ctx, 24), dp(ctx, 24))
        }

        val header = TextView(ctx).apply {
            text = title
            setTextColor(TEXT)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        root.addView(header)

        val subtitle = TextView(ctx).apply {
            text = "FLUMMOX REPO · OTAKUTSU"
            setTextColor(SUBTEXT)
            textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 24))
        }
        root.addView(subtitle)

        body(root, dlg)

        val close = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(BG)
            background = ripple(shape(TEXT, 14, ctx))
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 16) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))
        dlg.setOnShowListener {
            listOf(header, subtitle).forEachIndexed { i, v -> stagger(v, i) }
        }
        dlg.show()
    }

    // ══════════════════════════════════════════════════════════
    // MAIN SCREEN
    // ══════════════════════════════════════════════════════════
    fun show(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = baseRoot(ctx).apply {
            setPadding(dp(ctx, 24), dp(ctx, 40), dp(ctx, 24), dp(ctx, 24))
        }

        val title = TextView(ctx).apply {
            text = "OTAKUTSU"
            setTextColor(TEXT)
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "FLUMMOX REPO · EXTENSION"
            setTextColor(SUBTEXT)
            textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 24))
        }
        root.addView(subtitle)

        // Badge
        val badge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 18, ctx, 1, BORDER_HI)
            setPadding(dp(ctx, 24), dp(ctx, 20), dp(ctx, 24), dp(ctx, 20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 20) }
        }
        badge.addView(TextView(ctx).apply {
            text = "EXTENSION"
            setTextColor(TEXT)
            textSize = 14f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val dot = View(ctx).apply {
            background = shape(ACTIVE, 5, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply {
                rightMargin = dp(ctx, 10)
            }
        }
        badge.addView(dot)
        badge.addView(TextView(ctx).apply {
            text = "ACTIVE"
            setTextColor(ACTIVE)
            textSize = 13f
            letterSpacing = 0.20f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(badge)

        // Spacer to center tiles
        root.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 0.3f))

        // Tiles
        val tileSettings = makeTile(ctx, "⚙️", "SETTINGS") { showSettings(ctx) }
        val tileTitles = makeTile(ctx, "🌐", "TITLES") { showTitles(ctx) }
        val tileLogs = makeTile(ctx, "📋", "LOGS") { showLogs(ctx) }
        root.addView(tileSettings)
        root.addView(tileTitles)
        root.addView(tileLogs)

        root.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 0.3f))

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(BG)
            background = ripple(shape(TEXT, 14, ctx))
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            )
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(closeBtn)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            dot.animate().alpha(0.25f).setDuration(1200).withEndAction {
                dot.animate().alpha(1f).setDuration(1200).withEndAction {
                    if (dlg.isShowing) {
                        dot.animate().alpha(0.25f).setDuration(1200).withEndAction(null).start()
                    }
                }.start()
            }.start()
            listOf(title, subtitle, badge, tileSettings, tileTitles, tileLogs, closeBtn)
                .forEachIndexed { i, v -> stagger(v, i) }
        }
        dlg.show()
    }

    // ══════════════════════════════════════════════════════════
    // SETTINGS
    // ══════════════════════════════════════════════════════════
    private fun showSettings(ctx: Context) {
        subWindow(ctx, "SETTINGS") { root, dlg ->

            val prefetchRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(SURFACE, 14, ctx, 1, BORDER)
                setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 12) }
            }
            val pfCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            pfCol.addView(TextView(ctx).apply {
                text = "Smart prefetch"
                setTextColor(TEXT)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            pfCol.addView(TextView(ctx).apply {
                text = "Pre-cache next episode in background"
                setTextColor(SUBTEXT)
                textSize = 11f
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            prefetchRow.addView(pfCol)
            val prefetchSwitch = Switch(ctx).apply {
                isChecked = isPrefetchEnabled()
                setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
                    setPrefetchEnabled(b)
                }
            }
            prefetchRow.addView(prefetchSwitch)
            root.addView(prefetchRow)

            val clearRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(SURFACE, 14, ctx, 1, BORDER)
                setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 12) }
            }
            val clCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            clCol.addView(TextView(ctx).apply {
                text = "Clear cache"
                setTextColor(TEXT)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            clCol.addView(TextView(ctx).apply {
                text = "Home, anime, watch, prefetch cache"
                setTextColor(SUBTEXT)
                textSize = 11f
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            clearRow.addView(clCol)
            clearRow.addView(Button(ctx).apply {
                text = "CLEAR"
                textSize = 12f
                letterSpacing = 0.14f
                setTextColor(RED)
                background = ripple(shape(0x22E57373, 12, ctx))
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
                minHeight = 0; minWidth = 0
                setOnClickListener {
                    OCache.clear()
                    OtakutsuProvider.clearSectionCache()
                    Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(clearRow)

            root.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        }
    }

    // ══════════════════════════════════════════════════════════
    // TITLES
    // ══════════════════════════════════════════════════════════
    private fun showTitles(ctx: Context) {
        subWindow(ctx, "TITLES") { root, dlg ->
            root.addView(TextView(ctx).apply {
                text = "Preferred display language for search results."
                setTextColor(SUBTEXT)
                textSize = 11f
                letterSpacing = 0.08f
                setPadding(0, dp(ctx, 4), 0, dp(ctx, 16))
            })

            val options = listOf(
                "english" to "English",
                "romaji" to "Romaji",
                "native" to "Native"
            )
            val current = getTitleLang()

            for ((key, label) in options) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ripple(shape(SURFACE, 14, ctx, 1, if (key == current) BORDER_HI else BORDER))
                    isClickable = true
                    setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 10) }
                    setOnClickListener {
                        setTitleLang(key)
                        dlg.dismiss()
                        showTitles(ctx)
                    }
                }
                row.addView(TextView(ctx).apply {
                    text = label
                    setTextColor(if (key == current) TEXT else SUBTEXT)
                    textSize = 15f
                    letterSpacing = 0.10f
                    setTypeface(typeface, if (key == current) Typeface.BOLD else Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (key == current) {
                    row.addView(TextView(ctx).apply {
                        text = "✓"
                        setTextColor(ACTIVE)
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
                root.addView(row)
            }

            root.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        }
    }

    // ══════════════════════════════════════════════════════════
    // LOGS
    // ══════════════════════════════════════════════════════════
    private fun showLogs(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = baseRoot(ctx).apply {
            setPadding(dp(ctx, 24), dp(ctx, 40), dp(ctx, 24), dp(ctx, 24))
        }

        val header = TextView(ctx).apply {
            text = "LOGS"
            setTextColor(TEXT)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        root.addView(header)

        val countText = TextView(ctx).apply {
            text = "${OLog.count()} LINES"
            setTextColor(SUBTEXT)
            textSize = 10f
            letterSpacing = 0.20f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 20))
        }
        root.addView(countText)

        // Verbose toggle row
        val verboseRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 14, ctx, 1, BORDER)
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }
        val vCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        vCol.addView(TextView(ctx).apply {
            text = "Verbose logging"
            setTextColor(TEXT)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        vCol.addView(TextView(ctx).apply {
            text = "Capture full URLs, tokens, and chunk data"
            setTextColor(SUBTEXT)
            textSize = 11f
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        verboseRow.addView(vCol)

        val verboseSwitch = Switch(ctx).apply {
            isChecked = OLog.isVerbose()
        }
        verboseRow.addView(verboseSwitch)
        root.addView(verboseRow)

        // Log view
        val logView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(LOG_TEXT)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            setTextIsSelectable(true)
            text = OLog.allSanitized()
        }
        val logScroll = ScrollView(ctx).apply {
            background = shape(SURFACE_2, 14, ctx, 1, BORDER)
            isVerticalScrollBarEnabled = false
            isFillViewport = false
        }
        logScroll.addView(logView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val scrollbar = OLogScrollbar(ctx, logScroll)
        val logRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logRow.addView(logScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        logRow.addView(scrollbar, LinearLayout.LayoutParams(dp(ctx, 8), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(ctx, 6)
        })
        root.addView(logRow)

        // Buttons
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 14), 0, 0)
        }
        fun btn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.10f
            setTextColor(color)
            background = ripple(shape(SURFACE, 12, ctx, 1, BORDER))
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
            minHeight = 0; minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 44), 1f).apply {
                leftMargin = dp(ctx, 3); rightMargin = dp(ctx, 3)
            }
            setOnClickListener { onClick() }
        }

        fun refresh() {
            if (OLog.isVerbose()) {
                logView.text = OLog.allVerboseSanitized()
                countText.text = "${OLog.countVerbose()} VERBOSE LINES"
            } else {
                logView.text = OLog.allSanitized()
                countText.text = "${OLog.count()} LINES"
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        verboseSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            OLog.setVerbose(b)
            refresh()
        }

        btnRow.addView(btn("REFRESH", TEXT) { refresh() })
        btnRow.addView(btn("SAVE", TEXT) {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val fname = "otakutsu_log_$ts.txt"
                val content = if (OLog.isVerbose()) OLog.allVerboseSanitized() else OLog.allSanitized()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
                } else {
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, fname).writeText(content)
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(btn("COPY", TEXT) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val content = if (OLog.isVerbose()) OLog.allVerboseSanitized() else OLog.allSanitized()
            cm.setPrimaryClip(ClipData.newPlainText("Otakutsu Logs", content))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(btn("CLEAR", RED) {
            OLog.clear()
            logView.text = "(cleared)"
            countText.text = "0 LINES"
        })
        root.addView(btnRow)

        // Footer note
        root.addView(TextView(ctx).apply {
            text = "Logs are local. Verbose mode captures full URLs, tokens, and chunk data — off by default."
            setTextColor(SUBTEXT)
            textSize = 10f
            letterSpacing = 0.04f
            setPadding(0, dp(ctx, 14), 0, 0)
        })

        val close = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(BG)
            background = ripple(shape(TEXT, 14, ctx))
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 16) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            listOf(header, countText, verboseRow, logRow, btnRow, close)
                .forEachIndexed { i, v -> stagger(v, i, baseDelay = 60L) }
        }

        dlg.show()
    }
}
