package com.flummox.bingeanime

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlin.math.roundToInt
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlin.math.cos
import kotlin.math.sin

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
    private const val ACCENT = 0xFF38BDF8.toInt()
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
    const val K_SEARCH_SOURCE = "bingeanime_search_source"
    const val K_ROW_ORDER = "bingeanime_row_order"
    const val K_ROW_PREFIX = "bingeanime_row_"
    const val K_YEAR_FILTER_ON = "bingeanime_year_filter_on"
    const val K_YEAR_FLOOR = "bingeanime_year_floor"
 
    fun getConcurrency(): Int = (getKey<Int>(K_CONCURRENCY) ?: 6).coerceIn(1, 30)
    fun isPrefetchEnabled(): Boolean = getKey<Boolean>(K_PREFETCH) ?: true
    fun isPrefilterEnabled(): Boolean = getKey<Boolean>(K_PREFILTER) ?: true
    fun isVerbose(): Boolean = getKey<Boolean>(K_VERBOSE) ?: false
    fun isSrcAniKoto(): Boolean = getKey<Boolean>(K_SRC_ANIKOTO) ?: true
    fun isSrcAniZone(): Boolean = getKey<Boolean>(K_SRC_ANIZONE) ?: true
    fun isSrcOtakutsu(): Boolean = getKey<Boolean>(K_SRC_OTAKUTSU) ?: true

    fun isYearFilterEnabled(): Boolean = getKey<Boolean>(K_YEAR_FILTER_ON) ?: false
    fun getYearFloor(): Int = getKey<Int>(K_YEAR_FLOOR) ?: 2010
    fun getYearFloorIfEnabled(): Int? =
        if (isYearFilterEnabled()) getYearFloor() else null

    // "anilist" (default, recommended) or "animeschedule"
    fun getSearchSource(): String =
        getKey<String>(K_SEARCH_SOURCE) ?: "anilist"
    fun setSearchSource(src: String) = setKey(K_SEARCH_SOURCE, src)
        fun getRowOrder(): List<String> {
        val stored = getKey<String>(K_ROW_ORDER) ?: ""
        val parts = stored.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ROWS.map { it.second }
        val seen = parts.toSet()
        val extras = ROWS.map { it.second }.filter { it !in seen }
        return parts + extras
    }

    fun setRowOrder(order: List<String>) { setKey(K_ROW_ORDER, order.joinToString("|")) }

    // Rows ON by default on fresh install. Everything else is available
// via Settings → Homepage but starts OFF. This keeps cold-start
// Shikimori traffic at 20 requests (vs 28 if all were on) and
// surfaces the mainstream rows first.
private val DEFAULT_ON_ROWS = setOf(
    "Trending", "Top Anime Series", "Top Anime Movies", "Donghua",
    "Shounen", "Seinen", "Shoujo", "Josei", "Kids",
    "Adventure", "Comedy", "Fantasy", "Romance",
    "Slice of Life", "Sports",
    "Ecchi", "School", "Thriller"
)

fun isRowEnabled(name: String): Boolean =
    getKey<Boolean>(K_ROW_PREFIX + name) ?: (name in DEFAULT_ON_ROWS)

fun resetHomeToDefaults() {
    setKey(K_ROW_ORDER, ROWS.map { it.second }.joinToString("|"))
    for ((_, label) in ROWS) setKey(K_ROW_PREFIX + label, label in DEFAULT_ON_ROWS)
}

    // ── helpers ──
    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

// Themed switch — overrides Material purple default.
// Thumb: white when on, gray when off. Track: dark gray / near-black.
private fun themedSwitch(ctx: Context): Switch = Switch(ctx).apply {
    thumbTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(TEXT, SUBTEXT)
    )
    trackTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(0xFF3A3A3A.toInt(), 0xFF1A1A1A.toInt())
    )
}

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

    // ── vector glyph view ──
    class GlyphView(context: Context, private val kind: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var pulse = 1f

    init {
        ValueAnimator.ofFloat(0.75f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 3f
        paint.strokeWidth = r * 0.22f
        paint.alpha = (pulse * 255).toInt()

        val fill = Paint(paint).apply { style = Paint.Style.FILL }

        when (kind) {
            "SETTINGS" -> {
                // three horizontal sliders with offset dots
                val ys = listOf(cy - r * 0.65f, cy, cy + r * 0.65f)
                val xs = listOf(cx + r * 0.35f, cx - r * 0.35f, cx + r * 0.1f)
                for (i in ys.indices) {
                    canvas.drawLine(cx - r, ys[i], cx + r, ys[i], paint)
                    canvas.drawCircle(xs[i], ys[i], r * 0.28f, fill)
                }
            }
            "SOURCES" -> {
                // broadcast: dot at lower-left + two arcs radiating up-right
                val ox = cx - r * 0.55f
                val oy = cy + r * 0.55f
                canvas.drawCircle(ox, oy, r * 0.24f, fill)
                for (i in 1..2) {
                    val rad = r * 0.55f * i + r * 0.15f
                    val rect = RectF(ox - rad, oy - rad, ox + rad, oy + rad)
                    canvas.drawArc(rect, -90f, 90f, false, paint)
                }
            }
            "LOGS" -> {
                // terminal prompt: > and _
                val chev = Path()
                chev.moveTo(cx - r * 0.7f, cy - r * 0.55f)
                chev.lineTo(cx - r * 0.1f, cy)
                chev.lineTo(cx - r * 0.7f, cy + r * 0.55f)
                canvas.drawPath(chev, paint)
                canvas.drawLine(cx + r * 0.05f, cy + r * 0.55f,
                    cx + r * 0.7f, cy + r * 0.55f, paint)
            }
            "HOMEPAGE" -> {
                // torii gate — curved top beam + straight lower beam + two pillars
                val top = Path()
                top.moveTo(cx - r * 1.05f, cy - r * 0.7f)
                top.quadTo(cx, cy - r * 1.0f, cx + r * 1.05f, cy - r * 0.7f)
                canvas.drawPath(top, paint)
                canvas.drawLine(cx - r * 0.72f, cy - r * 0.2f,
                    cx + r * 0.72f, cy - r * 0.2f, paint)
                canvas.drawLine(cx - r * 0.6f, cy - r * 0.7f,
                    cx - r * 0.6f, cy + r * 0.85f, paint)
                canvas.drawLine(cx + r * 0.6f, cy - r * 0.7f,
                    cx + r * 0.6f, cy + r * 0.85f, paint)
            }
        }
    }
    }
    

    // ── tile factory with glyph + press animation ──
    private fun tile(ctx: Context, label: String, onClick: () -> Unit): FrameLayout {
        val tile = FrameLayout(ctx).apply {
            background = shape(SURFACE, 18, ctx, 1, BORDER_HI)
            isClickable = true
            clipChildren = false
        }

        // glyph centered in tile, larger
        val glyph = GlyphView(ctx, label)
        tile.addView(glyph, FrameLayout.LayoutParams(
            dp(ctx, 76), dp(ctx, 76),
            Gravity.CENTER
        ))

        // label bottom
        tile.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT)
            textSize = 13f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(ctx, 16)
                bottomMargin = dp(ctx, 14)
            }
        })

        // accent underline that grows on press
        val underline = View(ctx).apply {
            background = shape(ACCENT, 2, ctx)
            alpha = 0f
        }
        tile.addView(underline, FrameLayout.LayoutParams(
            dp(ctx, 24), dp(ctx, 2),
            Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = dp(ctx, 16)
            bottomMargin = dp(ctx, 10)
        })

        // press animation — scale + underline expand
        tile.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    glyph.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start()
                    underline.animate().alpha(1f).setDuration(120).start()
                    val lp = underline.layoutParams as FrameLayout.LayoutParams
                    ValueAnimator.ofInt(dp(ctx, 24), dp(ctx, 60)).apply {
                        duration = 180
                        addUpdateListener {
                            lp.width = it.animatedValue as Int
                            underline.layoutParams = lp
                        }
                        start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    glyph.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                    underline.animate().alpha(0f).setDuration(180).start()
                    if (e.action == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }
        tile.setOnClickListener { onClick() }
        return tile
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

        val rowH = dp(ctx, 160)

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 14) }
        }
        row1.addView(tile(ctx, "SETTINGS") { openSettings(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f).apply { rightMargin = dp(ctx, 14) })
        row1.addView(tile(ctx, "SOURCES") { openSources(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f))
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(tile(ctx, "LOGS") { openLogs(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f).apply { rightMargin = dp(ctx, 14) })
        row2.addView(tile(ctx, "HOMEPAGE") { openHomepage(ctx) },
            LinearLayout.LayoutParams(0, rowH, 1f))
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

    //──Sub Window──
    private fun subWindow(
    ctx: Context,
    title: String,
    closeLabel: String = "CLOSE",
    onClose: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    body: (LinearLayout, Dialog) -> Unit
) {
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
        text = closeLabel; textSize = 13f; letterSpacing = 0.18f
        setTextColor(BG); background = shape(TEXT, 14, ctx)
        isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
        ).apply { topMargin = dp(ctx, 16) }
            setOnClickListener {
        onClose?.invoke()
        dlg.dismiss()
    }
}
root.addView(close)

    dlg.setContentView(root)
    dlg.window?.setLayout(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
    dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))
        dlg.setOnDismissListener { onDismiss?.invoke() }
    dlg.setOnShowListener { listOf(header, sub).forEachIndexed { i, v -> stagger(v, i) } }
    dlg.show()
    }
    private fun openSettings(ctx: Context) {
        subWindow(ctx, "SETTINGS") { body, _ ->
            // Search source — switch between AniList (default/recommended)
            // and AnimeSchedule. The toggle flips primary/fallback order.
            val isAschedule = getSearchSource() == "animeschedule"
            body.addView(toggleRow(
                ctx,
                "Use AnimeSchedule for search",
                "OFF — AniList (recommended) · ON — AnimeSchedule",
                isAschedule
            ) {
                setSearchSource(if (it) "animeschedule" else "anilist")
            })
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
                "Wipes in-memory + disk caches", "CLEAR") {
                BCCache.clear()
                // Wipe persisted Shikimori rows so next home load
                // refetches from scratch. Otherwise the on-disk files
                // would reload into memory on next boot and the clear
                // would appear to do nothing.
                try {
                    java.io.File(ctx.filesDir, "shikimori_rows").deleteRecursively()
                } catch (_: Exception) {}
                Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
           })
        }
    }

    private fun openSources(ctx: Context) {
        subWindow(ctx, "SOURCES") { body, _ ->
            body.addView(toggleRow(ctx, "AniKoto",
                "Sub / Dub / HSub streams", isSrcAniKoto()) { setKey(K_SRC_ANIKOTO, it) })
            body.addView(toggleRow(ctx, "AniZone",
                "Sub streams, HLS", isSrcAniZone()) { setKey(K_SRC_ANIZONE, it) })
            body.addView(toggleRow(ctx, "Otakutsu",
                "Sub / Dub, M3U8", isSrcOtakutsu()) { setKey(K_SRC_OTAKUTSU, it) })
        }
    }

    private fun openHomepage(ctx: Context) {
    // Snapshot state at open. If the dialog is dismissed any other
    // way than SAVE & CLOSE, revert every change. Prevents accidental
    // row toggles from sticking when the user hits back/tap-outside.
    val snapOrder = getRowOrder()
    val snapEnabled = ROWS.mapNotNull { (_, label) ->
        if (isRowEnabled(label)) label else null
    }.toSet()
    val snapYearOn = isYearFilterEnabled()
    val snapYearFloor = getYearFloor()

    val initialEnabled = snapEnabled
    var confirmed = false

    subWindow(
        ctx,
        "HOMEPAGE",
        closeLabel = "SAVE & CLOSE",
        onClose = {
            confirmed = true
            val currentEnabled = ROWS.mapNotNull { (_, label) ->
                if (isRowEnabled(label)) label else null
            }.toSet()
            val newlyEnabled = currentEnabled - initialEnabled
            if (newlyEnabled.isNotEmpty()) {
                BLog.d("home changed: prefetching ${newlyEnabled.size} newly enabled rows")
                ShikimoriApi.prefetchRowsNow(newlyEnabled.toList())
            }
            // Defer reload — the dialog is still dismissing when
            // this callback fires. Post to next frame so the
            // activity is fully interactive.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    com.lagradost.cloudstream3.MainActivity
                        .reloadHomeEvent.invoke(true)
                } catch (_: Exception) {}
            }, 250L)
        },
        onDismiss = {
            if (!confirmed) {
                // Revert to snapshot.
                setRowOrder(snapOrder)
                for ((_, label) in ROWS) {
                    setKey(K_ROW_PREFIX + label, label in snapEnabled)
                }
                setKey(K_YEAR_FILTER_ON, snapYearOn)
                setKey(K_YEAR_FLOOR, snapYearFloor)
            }
        }
    ) { body, _ ->

        // ── row list holder defined first so Reset can re-render ──
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
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
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
                val sw = themedSwitch(ctx)
                sw.isChecked = isRowEnabled(name)
                sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean ->
                    setKey(K_ROW_PREFIX + name, v)
                }
                row.addView(sw)
                row.addView(arrowBtn(ctx, "▲", idx > 0) {
                    val cur = getRowOrder().toMutableList()
                    val i = cur.indexOf(name)
                    if (i > 0) {
                        cur[i] = cur[i - 1]; cur[i - 1] = name
                        setRowOrder(cur); render()
                    }
                })
                row.addView(arrowBtn(ctx, "▼", idx < total - 1) {
                    val cur = getRowOrder().toMutableList()
                    val i = cur.indexOf(name)
                    if (i in 0 until cur.size - 1) {
                        cur[i] = cur[i + 1]; cur[i + 1] = name
                        setRowOrder(cur); render()
                    }
                })
                holder.addView(row)
            }
        }

        // ── Reset ──
        body.addView(actionRow(ctx, "Reset home",
            "Restore default rows, order, toggles", "RESET") {
            resetHomeToDefaults()
            render()
            Toast.makeText(ctx, "Home reset to defaults",
                Toast.LENGTH_SHORT).show()
        })

        // ── Year filter ──
        body.addView(labelBlock(ctx, "Year filter",
            "Hide anime released before a chosen year"))

        val yearEnabled = isYearFilterEnabled()
        val yearDescText = TextView(ctx).apply {
            text = if (yearEnabled) "Showing ${getYearFloor()} and newer"
                   else "Off — showing all years"
            setTextColor(SUBTEXT); textSize = 11f
            setPadding(dp(ctx, 3), dp(ctx, 3), 0, 0)
        }

        val yearToggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val yCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        yCol.addView(TextView(ctx).apply {
            text = "Filter by year"
            setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        yCol.addView(yearDescText)
        yearToggleRow.addView(yCol)

        val pickerContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (yearEnabled) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }
        pickerContainer.addView(TextView(ctx).apply {
            text = "Tap or scroll a year"
            setTextColor(SUBTEXT); textSize = 10f
            letterSpacing = 0.18f
            setPadding(0, 0, 0, dp(ctx, 6))
        })
        pickerContainer.addView(yearScrollPicker(ctx, getYearFloor()) { picked ->
            setKey(K_YEAR_FLOOR, picked)
            yearDescText.text = "Showing $picked and newer"
        })

        val yearSwitch = themedSwitch(ctx).apply { isChecked = yearEnabled }
        yearSwitch.setOnCheckedChangeListener { _, checked ->
            setKey(K_YEAR_FILTER_ON, checked)
            yearDescText.text = if (checked)
                "Showing ${getYearFloor()} and newer"
            else
                "Off — showing all years"
            pickerContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }
        yearToggleRow.addView(yearSwitch)
        body.addView(yearToggleRow)
        body.addView(pickerContainer)

        // ── Rows list ──
        body.addView(labelBlock(ctx, "Rows",
            "Position 1 shows first. Toggle, then SAVE & CLOSE to apply."))
        render()
        body.addView(holder)
    }
    }


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
        val verboseSw = themedSwitch(ctx).apply { isChecked = BLog.isVerbose() }
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
            BLog.setVerbose(b); setKey(K_VERBOSE, b); refresh()
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
            BLog.clear(); logView.text = "(cleared)"; countText.text = "0 LINES"
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
        val sw = themedSwitch(ctx); sw.isChecked = initial
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
            text = "$cur"; setTextColor(TEXT); textSize = 18f
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
                valTxt.text = "$cur"; onChange(cur)
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
            setTextColor(TEXT)
            background = shape(SURFACE_2, 16, ctx, 1, BORDER)
            isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            minHeight = 0; minWidth = 0
            setOnClickListener { onClick() }
        })
        return row
    }

    // Horizontal scroll-wheel year picker. Years scroll left/right,
// the centered one is "selected". Neighbours fade and shrink.
private fun yearScrollPicker(
    ctx: Context,
    initialYear: Int,
    onPicked: (Int) -> Unit
): android.widget.HorizontalScrollView {
    val minYear = 1960
    // Cap at 2015. Anything higher thins most genre rows to a
    // handful of entries — bad first impression.
    val maxYear = 2015
    val itemW = dp(ctx, 84)
    val screenW = ctx.resources.displayMetrics.widthPixels
    val sidePad = ((screenW - itemW) / 2).coerceAtLeast(0)

    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(View(ctx), LinearLayout.LayoutParams(sidePad, 1))
    for (y in minYear..maxYear) {
        row.addView(TextView(ctx).apply {
            text = y.toString()
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(itemW, dp(ctx, 72))
            tag = y
        })
    }
    row.addView(View(ctx), LinearLayout.LayoutParams(sidePad, 1))

    val hsv = android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 76)
        )
        addView(row)
    }

    fun updateAlphas(scrollX: Int) {
        val centerViewport = screenW / 2f
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child is TextView && child.tag is Int) {
                val childViewport = child.left + child.width / 2f - scrollX
                val dist = kotlin.math.abs(childViewport - centerViewport)
                val maxDist = itemW * 2f
                val a = (1f - dist / maxDist).coerceIn(0.35f, 1f)
                child.alpha = a
                child.textSize = 18f + (a - 0.35f) * 5f
                // Centered year gets accent color, neighbours stay TEXT.
                child.setTextColor(TEXT)
            }
        }
    }

    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var pending: Runnable? = null

    // Center on initial year after layout settles. Two posts —
    // first schedules after measure, second after the row's
    // children are measured so itemW and child.left are real.
    hsv.post {
        hsv.post {
            val idx = (initialYear - minYear).coerceIn(0, maxYear - minYear)
            hsv.scrollTo(idx * itemW, 0)
            updateAlphas(idx * itemW)
        }
    }
    hsv.setOnScrollChangeListener { _, scrollX, _, _, _ ->
        updateAlphas(scrollX)
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val idx = (scrollX.toFloat() / itemW)
                .roundToInt()
                .coerceIn(0, maxYear - minYear)
            hsv.smoothScrollTo(idx * itemW, 0)
            onPicked(minYear + idx)
        }
        pending = r
        handler.postDelayed(r, 150)
    }

    return hsv
}

        private fun arrowBtn(ctx: Context, sym: String, enabled: Boolean,
                             onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = sym; textSize = 14f
            setTextColor(if (enabled) TEXT else 0xFF3A4555.toInt())
            background = shape(SURFACE_2, 8, ctx)
            gravity = Gravity.CENTER
            val s = dp(ctx, 30)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = dp(ctx, 4) }
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }

    private fun labelBlock(ctx: Context, title: String, subtitle: String?): LinearLayout =
        LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 6))
        addView(TextView(ctx).apply {
            text = title; setTextColor(TEXT); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(ctx).apply {
                text = subtitle; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
    }

}
