package com.flummox.bingeanime

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
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

object BingeAnimeSettings {

    // ── colors ──
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

    // ── prefs ──
    const val K_CONCURRENCY = "bingeanime_concurrency"
    const val K_PREFETCH = "bingeanime_prefetch"
    const val K_PREFILTER = "bingeanime_prefilter"
    const val K_VERBOSE = "bingeanime_verbose"
    const val K_SRC_ANIKOTO = "bingeanime_src_anikoto"
    const val K_SRC_ANIZONE = "bingeanime_src_anizone"
    const val K_SRC_OTAKUTSU = "bingeanime_src_otakutsu"
    const val K_ROW_ORDER = "bingeanime_row_order"
    const val K_ROW_PREFIX = "bingeanime_row_"

    fun getConcurrency(): Int = (getKey<Int>(K_CONCURRENCY) ?: 6).coerceIn(1, 30)
    fun isPrefetchEnabled(): Boolean = getKey<Boolean>(K_PREFETCH) ?: true
    fun isPrefilterEnabled(): Boolean = getKey<Boolean>(K_PREFILTER) ?: true
    fun isVerbose(): Boolean = getKey<Boolean>(K_VERBOSE) ?: false
    fun isSrcAniKoto(): Boolean = getKey<Boolean>(K_SRC_ANIKOTO) ?: true
    fun isSrcAniZone(): Boolean = getKey<Boolean>(K_SRC_ANIZONE) ?: true
    fun isSrcOtakutsu(): Boolean = getKey<Boolean>(K_SRC_OTAKUTSU) ?: true

    fun getRowOrder(): List<String> {
        val stored = getKey<String>(K_ROW_ORDER) ?: ""
        val parts = stored.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return BingeAnimeProvider.ROWS.map { it.second }
        val seen = parts.toSet()
        val extras = BingeAnimeProvider.ROWS.map { it.second }.filter { it !in seen }
        return parts + extras
    }

    fun setRowOrder(order: List<String>) { setKey(K_ROW_ORDER, order.joinToString("|")) }

    fun isRowEnabled(name: String): Boolean = getKey<Boolean>(K_ROW_PREFIX + name) ?: true

    fun resetHomeToDefaults() {
        setKey(K_ROW_ORDER, BingeAnimeProvider.ROWS.map { it.second }.joinToString("|"))
        for ((_, label) in BingeAnimeProvider.ROWS) setKey(K_ROW_PREFIX + label, true)
    }

    // ── helpers ──
    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, ctx: Context,
                      strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    private fun stagger(v: View, i: Int, base: Long = 60L) {
        v.alpha = 0f
        v.translationY = 28f
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(base * i).setDuration(380)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun squareTile(ctx: Context, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(SURFACE, 18, ctx, 1, BORDER_HI)
            isClickable = true
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))
            setOnClickListener { onClick() }
            addView(View(ctx), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(TextView(ctx).apply {
                text = label
                setTextColor(TEXT)
                textSize = 13f
                letterSpacing = 0.18f
                setTypeface(typeface, Typeface.BOLD)
            })
        }

    // ── root ──
    fun show(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 20))
        }

        val title = TextView(ctx).apply {
            text = "BINGEANIME"
            setTextColor(TEXT); textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "FLUMMOX REPO · EXTENSION"
            setTextColor(SUBTEXT); textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 28))
        }
        root.addView(subtitle)

        val rowH = dp(ctx, 140)

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 14) }
        }
        row1.addView(
            squareTile(ctx, "SETTINGS") { openSettings(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f).apply { rightMargin = dp(ctx, 14) }
        )
        row1.addView(
            squareTile(ctx, "SOURCES") { openSources(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f)
        )
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(
            squareTile(ctx, "LOGS") { openLogs(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f).apply { rightMargin = dp(ctx, 14) }
        )
        row2.addView(
            squareTile(ctx, "HOMEPAGE") { openHomepage(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f)
        )
        root.addView(row2)

        root.addView(View(ctx), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val close = Button(ctx).apply {
            text = "CLOSE"; textSize = 13f; letterSpacing = 0.18f
            setTextColor(BG)
            background = shape(TEXT, 14, ctx)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 24) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            listOf(title, subtitle, row1, row2, close)
                .forEachIndexed { i, v -> stagger(v, i) }
        }
        dlg.show()
    }

    // ── sub-window scaffold ──
    private fun subWindow(ctx: Context, title: String, body: (LinearLayout, Dialog) -> Unit) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 20))
        }

        val header = TextView(ctx).apply {
            text = title; setTextColor(TEXT); textSize = 26f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
        }
        root.addView(header)

        val sub = TextView(ctx).apply {
            text = "FLUMMOX REPO · BINGEANIME"
            setTextColor(SUBTEXT); textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 20))
        }
        root.addView(sub)

        val scroll = ScrollView(ctx)
        val bodyRoot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(bodyRoot)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        body(bodyRoot, dlg)

        val close = Button(ctx).apply {
            text = "CLOSE"; textSize = 13f; letterSpacing = 0.18f
            setTextColor(BG); background = shape(TEXT, 14, ctx)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 16) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener { listOf(header, sub).forEachIndexed { i, v -> stagger(v, i) } }
        dlg.show()
    }

    // ── settings tile ──
    private fun openSettings(ctx: Context) {
        subWindow(ctx, "SETTINGS") { body, _ ->
            body.addView(toggleRow(ctx, "Smart prefetch",
                "Pre-cache next episode in background", isPrefetchEnabled()) {
                setKey(K_PREFETCH, it)
            })
            body.addView(toggleRow(ctx, "Smart link ranking",
                "Rank mirrors by confidence (🟢🟡🔴)", isPrefilterEnabled()) {
                setKey(K_PREFILTER, it)
            })
            body.addView(stepperRow(ctx, "Concurrency",
                "Parallel source scrapers (1-30)", 1, 30, getConcurrency()) {
                setKey(K_CONCURRENCY, it)
            })
            body.addView(actionRow(ctx, "Clear cache",
                "Wipes AniList + scraper caches", "CLEAR") {
                BCCache.clear()
                Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
            })
        }
    }

    // ── sources tile ──
    private fun openSources(ctx: Context) {
        subWindow(ctx, "SOURCES") { body, dlg ->
            body.addView(toggleRow(ctx, "AniKoto",
                "Sub / Dub / HSub streams", isSrcAniKoto()) { setKey(K_SRC_ANIKOTO, it) })
            body.addView(toggleRow(ctx, "AniZone",
                "Sub streams, HLS", isSrcAniZone()) { setKey(K_SRC_ANIZONE, it) })
            body.addView(toggleRow(ctx, "Otakutsu",
                "Sub / Dub, M3U8", isSrcOtakutsu()) { setKey(K_SRC_OTAKUTSU, it) })
        }
    }

    // ── homepage tile ──
    private fun openHomepage(ctx: Context) {
        subWindow(ctx, "HOMEPAGE") { body, _ ->
            val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            fun render() {
                holder.removeAllViews()
                val order = getRowOrder()
                val total = order.size
                for ((idx, name) in order.withIndex()) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(SURFACE, 12, ctx, 1, BORDER)
                        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(ctx, 6) }
                    }
                    row.addView(TextView(ctx).apply {
                        text = "${idx + 1}"; setTextColor(SUBTEXT); textSize = 12f
                        gravity = Gravity.CENTER
                        val s = dp(ctx, 26)
                        layoutParams = LinearLayout.LayoutParams(s, s)
                        background = shape(SURFACE_2, 12, ctx)
                    })
                    row.addView(TextView(ctx).apply {
                        text = name; setTextColor(TEXT); textSize = 13f
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(ctx, 10); rightMargin = dp(ctx, 6)
                        }
                    })
                    val sw = Switch(ctx)
                    sw.isChecked = isRowEnabled(name)
                    sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean ->
                        setKey(K_ROW_PREFIX + name, v)
                    }
                    row.addView(sw)
                    row.addView(arrowBtn(ctx, "▲", idx > 0) {
                        val cur = getRowOrder().toMutableList()
                        val i = cur.indexOf(name)
                        if (i > 0) { cur[i] = cur[i - 1]; cur[i - 1] = name
                            setRowOrder(cur); render() }
                    })
                    row.addView(arrowBtn(ctx, "▼", idx < total - 1) {
                        val cur = getRowOrder().toMutableList()
                        val i = cur.indexOf(name)
                        if (i >= 0 && i < cur.size - 1) { cur[i] = cur[i + 1]; cur[i + 1] = name
                            setRowOrder(cur); render() }
                    })
                    holder.addView(row)
                }
            }
            render()

            body.addView(actionRow(ctx, "Reset home",
                "Restore default rows, order, toggles", "RESET") {
                resetHomeToDefaults()
                render()
                Toast.makeText(ctx, "Home reset", Toast.LENGTH_SHORT).show()
            })
            body.addView(holder)
        }
    }

    // ── logs tile ──
    private fun openLogs(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 20))
        }
        header.addView(TextView(ctx).apply {
            text = "LOGS"; setTextColor(TEXT); textSize = 22f
            letterSpacing = 0.18f; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countText = TextView(ctx).apply {
            text = "${BLog.count()} LINES"; setTextColor(SUBTEXT); textSize = 11f
            letterSpacing = 0.18f; setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(countText)
        root.addView(header)

        // verbose toggle
        val verboseRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(ctx, 16); rightMargin = dp(ctx, 16); bottomMargin = dp(ctx, 12) }
        }
        val vCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        vCol.addView(TextView(ctx).apply {
            text = "Verbose logging"; setTextColor(TEXT); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        vCol.addView(TextView(ctx).apply {
            text = "Log full URLs, scraper steps — off by default"
            setTextColor(SUBTEXT); textSize = 10f
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        verboseRow.addView(vCol)
        val verboseSw = Switch(ctx).apply { isChecked = BLog.isVerbose() }
        verboseRow.addView(verboseSw)
        root.addView(verboseRow)

        val logView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(LOG_TEXT)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            setTextIsSelectable(true)
            text = BLog.allSanitized()
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

        val scrollbar = LogScrollbar(ctx, logScroll)
        val logRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { leftMargin = dp(ctx, 16); rightMargin = dp(ctx, 16) }
        }
        logRow.addView(logScroll, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        logRow.addView(scrollbar, LinearLayout.LayoutParams(
            dp(ctx, 8), ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { leftMargin = dp(ctx, 6) })
        root.addView(logRow)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 8))
        }
        fun lbtn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
            text = label; textSize = 11f; letterSpacing = 0.12f
            setTextColor(color)
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10))
            minHeight = 0; minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 44), 1f).apply {
                leftMargin = dp(ctx, 4); rightMargin = dp(ctx, 4)
            }
            setOnClickListener { onClick() }
        }

        fun refresh() {
            logView.text = BLog.allSanitized()
            countText.text = "${BLog.count()} LINES"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        verboseSw.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            BLog.setVerbose(b)
            setKey(K_VERBOSE, b)
            refresh()
        }

        btnRow.addView(lbtn("REFRESH", TEXT) { refresh() })
        btnRow.addView(lbtn("SAVE", TEXT) {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(java.util.Date())
                val fname = "bingeanime_log_$ts.txt"
                val content = BLog.allSanitized()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = ctx.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        ctx.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(content.toByteArray())
                        }
                        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
                } else {
                    val dir = android.os.Environment
                        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    java.io.File(dir, fname).writeText(content)
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(lbtn("COPY", TEXT) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BingeAnime Logs", BLog.allSanitized()))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(lbtn("CLEAR", RED) {
            BLog.clear()
            logView.text = "(cleared)"
            countText.text = "0 LINES"
        })
        root.addView(btnRow)

        val close = Button(ctx).apply {
            text = "CLOSE"; textSize = 13f; letterSpacing = 0.18f
            setTextColor(BG); background = shape(TEXT, 14, ctx)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply {
                leftMargin = dp(ctx, 20); rightMargin = dp(ctx, 20)
                topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 24)
            }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))
        dlg.setOnShowListener {
            listOf(header, verboseRow, logRow, btnRow, close)
                .forEachIndexed { i, v -> stagger(v, i, 50L) }
        }
        dlg.show()
    }

    // ── reusable rows ──
    private fun toggleRow(ctx: Context, label: String, desc: String?,
                          initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        row.addView(col)
        val sw = Switch(ctx); sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun stepperRow(ctx: Context, label: String, desc: String?,
                           min: Int, max: Int, initial: Int,
                           onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        row.addView(col)
        var cur = initial
        val valTxt = TextView(ctx).apply {
            text = "$cur"; setTextColor(ACTIVE); textSize = 18f
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        fun mk(sym: String, delta: Int) = Button(ctx).apply {
            text = sym; textSize = 16f; setTextColor(TEXT)
            background = shape(SURFACE_2, 20, ctx); isAllCaps = false
            minWidth = dp(ctx, 40); minHeight = dp(ctx, 40)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                cur = (cur + delta).coerceIn(min, max)
                valTxt.text = "$cur"
                onChange(cur)
            }
        }
        row.addView(mk("−", -1)); row.addView(valTxt); row.addView(mk("+", 1))
        return row
    }

    private fun actionRow(ctx: Context, label: String, desc: String?,
                          btnText: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = btnText; textSize = 12f
            setTextColor(ACTIVE)
            background = shape(SURFACE_2, 16, ctx, 1, BORDER)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            minHeight = 0; minWidth = 0
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun arrowBtn(ctx: Context, sym: String, enabled: Boolean,
                         onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = sym; textSize = 14f
        setTextColor(if (enabled) ACTIVE else 0xFF3A4555.toInt())
        background = shape(SURFACE_2, 8, ctx)
        gravity = Gravity.CENTER
        val s = dp(ctx, 30)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = dp(ctx, 4) }
        isClickable = enabled
        if (enabled) setOnClickListener { onClick() }
    }
}
