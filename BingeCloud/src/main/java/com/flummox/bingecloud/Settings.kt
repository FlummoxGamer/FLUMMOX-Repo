package com.flummox.bingecloud

import kotlinx.coroutines.launch
import com.flummox.bingecore.CloudflareShield
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

// ══════════════════════════════════════════════════════════════
// ── ROW SPEC ──
// ══════════════════════════════════════════════════════════════
data class RowSpec(
    val key: String,
    val type: String,
    val catalogId: String,
    val name: String,
    val defaultGenre: String? = null,
    val sourceLabel: String
)

// ══════════════════════════════════════════════════════════════
// ── SETTINGS ──
// ══════════════════════════════════════════════════════════════
object Settings {

    // ── preference keys ──
    const val K_CONCURRENCY = "bingecloud_concurrency"
    const val K_CF_DOMAINS = "bingecloud_cf_domains"
    const val K_CF_COOKIE_PREFIX = "bingecloud_cf_cookie_"
    const val K_FEBBOX_TOKEN = "bingecloud_febbox_token"
    const val K_MB_TOKEN = "bingecloud_mb_token"
    const val K_MB_TOKEN_EXP = "bingecloud_mb_token_exp"
    const val K_SRC_VM = "bingecloud_src_vm"
    const val K_SRC_MD = "bingecloud_src_md"
    const val K_SRC_HDH = "bingecloud_src_hdh"
    const val K_SRC_FEBBOX = "bingecloud_src_febbox"
    const val K_SRC_MOVIEBOX = "bingecloud_src_moviebox"
    const val K_SRC_ANIKOTO = "bingecloud_src_anikoto"
    const val K_SRC_SHOWBOX = "bingecloud_src_showbox"
    const val K_SRC_MLSBD = "bingecloud_src_mlsbd"
    const val K_QUALITY = "bingecloud_quality"
    const val K_PREFILTER = "bingecloud_prefilter"
    const val K_PREFETCH = "bingecloud_prefetch"
    const val K_ROW_ORDER = "bingecloud_row_order"
    const val K_ROW_TRENDING_MOVIES = "bingecloud_row_trending_movies"
    const val K_ROW_TRENDING_SERIES = "bingecloud_row_trending_series"
    const val K_ROW_TVDB_MOVIES = "bingecloud_row_tvdb_movies"
    const val K_ROW_TVDB_SERIES = "bingecloud_row_tvdb_series"
    const val K_ROW_TVDB_GENRES_MOVIES = "bingecloud_row_tvdb_genres_movies"
    const val K_ROW_TVDB_GENRES_SERIES = "bingecloud_row_tvdb_genres_series"
    const val K_ROW_TOP_ANIME = "bingecloud_row_top_anime"
    const val K_ROW_AIRING_ANIME = "bingecloud_row_airing_anime"
    const val K_ROW_UPCOMING_ANIME = "bingecloud_row_upcoming_anime"
    const val K_ROW_TOP_ANIME_MOVIES = "bingecloud_row_top_anime_movies"
    const val K_ROW_TOP_ANIME_SERIES = "bingecloud_row_top_anime_series"
    const val K_ROW_MOST_POPULAR_ANIME = "bingecloud_row_most_popular_anime"
    const val K_ROW_MOST_FAV_ANIME = "bingecloud_row_most_fav_anime"
    const val K_ROW_BEST_2020S = "bingecloud_row_best_2020s"
    const val K_ROW_BEST_2010S = "bingecloud_row_best_2010s"
    const val K_ROW_BEST_2000S = "bingecloud_row_best_2000s"
    const val K_ROW_BEST_90S = "bingecloud_row_best_90s"
    const val K_ROW_BEST_80S = "bingecloud_row_best_80s"
    const val K_ROW_HINDI_MOVIES = "bingecloud_row_hindi_movies"
    const val K_ROW_HINDI_SERIES = "bingecloud_row_hindi_series"
    const val K_ROW_BANGLA = "bingecloud_row_bangla"
    const val K_ROW_ANIME_SCHEDULE = "bingecloud_row_anime_schedule"

// ── Streaming platforms (one row per platform, mixed movie + series) ──
    const val K_ROW_STREAM_NETFLIX = "bingecloud_row_stream_netflix"
    const val K_ROW_STREAM_PRIME = "bingecloud_row_stream_prime"
    const val K_ROW_STREAM_DISNEY = "bingecloud_row_stream_disney"
    const val K_ROW_STREAM_MAX = "bingecloud_row_stream_max"
    const val K_ROW_STREAM_APPLETV = "bingecloud_row_stream_appletv"
    const val K_ROW_STREAM_JIOHOTSTAR = "bingecloud_row_stream_jiohotstar"
    const val K_ROW_STREAM_JIOCINEMA = "bingecloud_row_stream_jiocinema"
    const val K_ROW_STREAM_SONYLIV = "bingecloud_row_stream_sonyliv"
    const val K_ROW_STREAM_ZEE5 = "bingecloud_row_stream_zee5"

    const val K_VERBOSE_LOG = "bingecloud_verbose_log"

    // ══════════════════════════════════════════════════════════
    // ── ALL ROWS ──
    // ══════════════════════════════════════════════════════════
    val ALL_ROWS: List<RowSpec> = listOf(
    // ── Trending ──
    RowSpec(K_ROW_TRENDING_MOVIES, "movie", "tmdb.trending", "Trending Movies", "Day", "TMDB • Today"),
    RowSpec(K_ROW_TRENDING_SERIES, "series", "tmdb.trending", "Trending Series", "Day", "TMDB • Today"),

    // ── Streaming platforms ──
    RowSpec(K_ROW_STREAM_NETFLIX, "movie", "tmdb.provider.8", "Netflix", null, "Netflix"),
    RowSpec(K_ROW_STREAM_PRIME, "movie", "tmdb.provider.9", "Prime Video", null, "Prime Video"),
    RowSpec(K_ROW_STREAM_DISNEY, "movie", "tmdb.provider.337", "Disney+", null, "Disney+"),
    RowSpec(K_ROW_STREAM_MAX, "movie", "tmdb.provider.1899", "Max", null, "Max"),
    RowSpec(K_ROW_STREAM_APPLETV, "movie", "tmdb.provider.350", "Apple TV+", null, "Apple TV+"),
    RowSpec(K_ROW_STREAM_JIOHOTSTAR, "movie", "tmdb.provider.122", "JioHotstar", null, "JioHotstar"),
    RowSpec(K_ROW_STREAM_JIOHOTSTAR, "movie", "tmdb.provider.122", "JioHotstar", null, "JioHotstar"),
    RowSpec(K_ROW_STREAM_SONYLIV, "movie", "tmdb.provider.237", "SonyLIV", null, "SonyLIV"),
    // ── Indian ──
    RowSpec(K_ROW_HINDI_MOVIES, "movie", "tmdb.language", "Hindi Movies", "Hindi", "TMDB • Hindi"),
    RowSpec(K_ROW_HINDI_SERIES, "series", "tmdb.language", "Hindi Series", "Hindi", "TMDB • Hindi"),
    RowSpec(K_ROW_BANGLA, "movie", "justwatch.bengali", "Bangla", null, "JustWatch • Bangla"),

    // ── Anime ──
    RowSpec(K_ROW_TOP_ANIME, "anime", "mal.top_anime", "Top Anime", null, "MAL"),
    RowSpec(K_ROW_AIRING_ANIME, "anime", "mal.airing", "Airing Now", null, "MAL"),
    RowSpec(K_ROW_MOST_POPULAR_ANIME, "anime", "mal.most_popular", "Most Popular Anime", null, "MAL"),
    RowSpec(K_ROW_MOST_FAV_ANIME, "anime", "mal.most_favorites", "Most Favorited Anime", null, "MAL"),
    RowSpec(K_ROW_UPCOMING_ANIME, "anime", "mal.upcoming", "Upcoming Anime", null, "MAL"),
    RowSpec(K_ROW_TOP_ANIME_MOVIES, "anime", "mal.top_movies", "Top Anime Movies", null, "MAL"),
    RowSpec(K_ROW_TOP_ANIME_SERIES, "anime", "mal.top_series", "Top Anime Series", null, "MAL"),
    RowSpec(K_ROW_ANIME_SCHEDULE, "anime", "mal.schedule", "Airing Schedule", "Monday", "MAL"),

    // ── TVDB ──
    RowSpec(K_ROW_TVDB_MOVIES, "movie", "tvdb.trending", "TVDB Trending Movies", "genre=Action", "TVDB"),
    RowSpec(K_ROW_TVDB_SERIES, "series", "tvdb.trending", "TVDB Trending Series", "genre=Action", "TVDB"),
    RowSpec(K_ROW_TVDB_GENRES_MOVIES, "movie", "tvdb.genres", "TVDB Genre Movies", "genre=Action", "TVDB"),
    RowSpec(K_ROW_TVDB_GENRES_SERIES, "series", "tvdb.genres", "TVDB Genre Series", "genre=Action", "TVDB"),

    // ── Best Anime of decade ──
    RowSpec(K_ROW_BEST_2020S, "anime", "mal.20sDecade", "Best Anime of 2020s", "genre=Action", "MAL"),
    RowSpec(K_ROW_BEST_2010S, "anime", "mal.10sDecade", "Best Anime of 2010s", "genre=Action", "MAL"),
    RowSpec(K_ROW_BEST_2000S, "anime", "mal.00sDecade", "Best Anime of 2000s", "genre=Action", "MAL"),
    RowSpec(K_ROW_BEST_90S, "anime", "mal.90sDecade", "Best Anime of 90s", "genre=Action", "MAL"),
    RowSpec(K_ROW_BEST_80S, "anime", "mal.80sDecade", "Best Anime of 80s", "genre=Action", "MAL"),
)

private val DEFAULT_ON_ROWS = setOf(
    K_ROW_TRENDING_MOVIES,
    K_ROW_TRENDING_SERIES,
    K_ROW_STREAM_NETFLIX,
    K_ROW_STREAM_PRIME,
    K_ROW_STREAM_DISNEY,
    K_ROW_STREAM_SONYLIV,
    K_ROW_HINDI_MOVIES,
    K_ROW_HINDI_SERIES,
    K_ROW_TOP_ANIME,
    K_ROW_AIRING_ANIME,
)

    // ── row order ──
    fun getRowOrder(): List<String> {
    val stored = getKey<String>(K_ROW_ORDER) ?: ""
    val parts = stored.split("|").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    if (parts.isEmpty()) return ALL_ROWS.map { it.key }
    val seen = parts.toSet()
    val extras = ALL_ROWS.map { it.key }.filter { it !in seen }

    // Insert newly-added rows at their canonical ALL_ROWS position
    // instead of appending at the end. Keeps row order sensible
    // when new rows ship in later versions.
    for (extra in extras) {
        val targetIdx = ALL_ROWS.indexOfFirst { it.key == extra }
        if (targetIdx < 0) { parts.add(extra); continue }
        var insertAt = parts.size
        for (i in parts.indices.reversed()) {
            val curIdx = ALL_ROWS.indexOfFirst { it.key == parts[i] }
            if (curIdx in 0 until targetIdx) { insertAt = i + 1; break }
        }
        parts.add(insertAt.coerceIn(0, parts.size), extra)
    }
    // Drop rows that no longer exist in ALL_ROWS (removed or merged).
    return parts.filter { key -> ALL_ROWS.any { it.key == key } }
    }

    fun setRowOrder(order: List<String>) {
        setKey(K_ROW_ORDER, order.joinToString("|"))
    }

    fun getRowSpecByKey(key: String): RowSpec? = ALL_ROWS.firstOrNull { it.key == key }

    fun isRowEnabled(key: String): Boolean =
        getKey<Boolean>(key) ?: (key in DEFAULT_ON_ROWS)

    fun resetHomeToDefaults() {
        setKey(K_ROW_ORDER, ALL_ROWS.map { it.key }.joinToString("|"))
        for (spec in ALL_ROWS) {
        setKey(spec.key, spec.key in DEFAULT_ON_ROWS)
        }
    }

    private fun showResetConfirmDialog(ctx: Context, onConfirm: () -> Unit) {
    val dlg = AlertDialog.Builder(ctx).create()
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg(ctx)
        setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 16))
    }
    root.addView(TextView(ctx).apply {
        text = "⚠️  Reset home to defaults?"
        setTextColor(TEXT)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
    })
    root.addView(TextView(ctx).apply {
        text = "This will restore all home catalogs to their default rows, order and toggles. Your sources, cookies and other settings won't be affected."
        setTextColor(SUBTEXT)
        textSize = 13f
        setPadding(0, dp(ctx, 12), 0, dp(ctx, 20))
    })
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    btnRow.addView(Button(ctx).apply {
        text = "Cancel"
        textSize = 14f
        setTextColor(TEXT)
        background = bg(ROW, 14, ctx)
        isAllCaps = false
        setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
        minHeight = 0; minWidth = 0
        setOnClickListener { dlg.dismiss() }
    })
    btnRow.addView(Button(ctx).apply {
        text = "Reset"
        textSize = 14f
        setTextColor(RED)
        background = bg(0x22F87171, 14, ctx)
        isAllCaps = false
        setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
        minHeight = 0; minWidth = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(ctx, 8) }
        setOnClickListener {
            dlg.dismiss()
            onConfirm()
        }
    })
    root.addView(btnRow)
    dlg.setView(root)
    dlg.window?.setBackgroundDrawable(cardBg(ctx))
    dlg.show()
}

    private fun showPasteTokenDialog(ctx: Context, onSaved: () -> Unit) {
    val dlg = AlertDialog.Builder(ctx).create()
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg(ctx)
        setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 16))
    }
    root.addView(TextView(ctx).apply {
        text = "✏️  Paste FebBox Token"
        setTextColor(TEXT)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
    })
    root.addView(TextView(ctx).apply {
        text = "Paste the cookie from febbox.com. Either the raw ui value or the full cookie string works."
        setTextColor(SUBTEXT)
        textSize = 13f
        setPadding(0, dp(ctx, 12), 0, dp(ctx, 16))
    })
    val input = EditText(ctx).apply {
        setTextColor(TEXT)
        setHintTextColor(SUBTEXT)
        hint = "ui=... or raw value"
        textSize = 14f
        background = bg(INPUT, 8, ctx)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        maxLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    root.addView(input, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(ctx, 20), 0, 0)
    }
    btnRow.addView(Button(ctx).apply {
        text = "Cancel"
        textSize = 14f
        setTextColor(TEXT)
        background = bg(ROW, 14, ctx)
        isAllCaps = false
        setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
        minHeight = 0; minWidth = 0
        setOnClickListener { dlg.dismiss() }
    })
    btnRow.addView(Button(ctx).apply {
        text = "Save"
        textSize = 14f
        setTextColor(0xFF0A0D14.toInt())
        background = saveButtonBg(ctx)
        isAllCaps = false
        setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
        minHeight = 0; minWidth = 0
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(ctx, 8) }
        setOnClickListener {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) {
                Toast.makeText(ctx, "Token is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveFebBoxToken(raw)
            Toast.makeText(ctx, "✓ Token saved", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
            onSaved()
        }
    })
    root.addView(btnRow)
    dlg.setView(root)
    dlg.window?.setBackgroundDrawable(cardBg(ctx))
    dlg.show()
}

    // ── basic prefs ──
    fun getConcurrency(): Int = (getKey<Int>(K_CONCURRENCY) ?: 50).coerceIn(1, 50)
    fun isPrefilterEnabled(): Boolean = getKey<Boolean>(K_PREFILTER) ?: true
    fun isPrefetchEnabled(): Boolean = getKey<Boolean>(K_PREFETCH) ?: true
    fun getQualityPref(): String = getKey<String>(K_QUALITY) ?: "Auto"
    fun isVerboseLog(): Boolean = getKey<Boolean>(K_VERBOSE_LOG) ?: false

    // ── CF cookies ──
    fun getCfDomains(): List<String> =
        (getKey<String>(K_CF_DOMAINS) ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
    fun getCookieForDomain(domain: String): String? =
        getKey<String>(K_CF_COOKIE_PREFIX + domain)?.takeIf { it.isNotBlank() }
    fun saveCookieForDomain(domain: String, cookie: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, cookie)
        val cur = getCfDomains().toMutableSet().also { it.add(domain) }
        setKey(K_CF_DOMAINS, cur.joinToString(","))
    }
    fun clearCookieForDomain(domain: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, "")
        val cur = getCfDomains().toMutableSet().also { it.remove(domain) }
        setKey(K_CF_DOMAINS, cur.joinToString(","))
    }

    // ── MovieBox session ──
    fun getMbToken(): String? = getKey<String>(K_MB_TOKEN)?.takeIf { it.isNotBlank() }
    fun getMbTokenExp(): Long = getKey<Long>(K_MB_TOKEN_EXP) ?: 0L
    fun saveMbToken(token: String, expMs: Long) {
        setKey(K_MB_TOKEN, token)
        setKey(K_MB_TOKEN_EXP, expMs)
    }

    // ── FebBox token ──
    fun getFebBoxToken(): String = getKey<String>(K_FEBBOX_TOKEN) ?: ""
    fun saveFebBoxToken(t: String) { setKey(K_FEBBOX_TOKEN, t) }
    fun clearFebBoxToken() { setKey(K_FEBBOX_TOKEN, "") }

    // ── source toggles ──
    fun isSrcVm(): Boolean = getKey<Boolean>(K_SRC_VM) ?: true
    fun isSrcMd(): Boolean = getKey<Boolean>(K_SRC_MD) ?: true
    fun isSrcHdh(): Boolean = getKey<Boolean>(K_SRC_HDH) ?: true
    fun isSrcFebBox(): Boolean = getKey<Boolean>(K_SRC_FEBBOX) ?: true
    fun isSrcMovieBox(): Boolean = getKey<Boolean>(K_SRC_MOVIEBOX) ?: true
    fun isSrcAnikoto(): Boolean = getKey<Boolean>(K_SRC_ANIKOTO) ?: true
    fun isSrcShowBox(): Boolean = getKey<Boolean>(K_SRC_SHOWBOX) ?: true
    fun isSrcMlsbd(): Boolean = getKey<Boolean>(K_SRC_MLSBD) ?: true
    // ══════════════════════════════════════════════════════════
    // ── COLORS ──
    // ══════════════════════════════════════════════════════════
    private const val BG = 0xFF0A0D14.toInt()
    private const val SKY_TOP = 0xFF1E3A5F.toInt()
    private const val SKY_MID = 0xFF142238.toInt()
    private const val SKY_BOTTOM = 0xFF0A0D14.toInt()
    private const val CARD = 0xFF0F1520.toInt()
    private const val CARD_BORDER = 0xFF1E2A3D.toInt()
    private const val ROW = 0xFF141B28.toInt()
    private const val INPUT = 0xFF0B1018.toInt()
    private const val ACCENT = 0xFF7DD3FC.toInt()
    private const val ACCENT_BG = 0x1A7DD3FC
    private const val ACCENT_STRONG = 0xFF38BDF8.toInt()
    private const val SAVE_GRAD_TOP = 0xFF38BDF8.toInt()
    private const val SAVE_GRAD_BOTTOM = 0xFF7DD3FC.toInt()
    private const val TEXT = 0xFFE6EDF5.toInt()
    private const val SUBTEXT = 0xFF8296AD.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val DISABLED = 0xFF3A4555.toInt()
    private const val LOG_TEXT = 0xFFB8C4D4.toInt()

    // ══════════════════════════════════════════════════════════
    // ── SHAPE HELPERS ──
    // ══════════════════════════════════════════════════════════
    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun skyGradient(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SKY_TOP, SKY_MID, SKY_BOTTOM)
        ).apply { cornerRadius = 0f }

    private fun cardBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }

    private fun withRipple(shape: GradientDrawable, rippleColor: Int = 0x40FFFFFF): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape)

private fun accentPill(ctx: Context): RippleDrawable = withRipple(
    GradientDrawable().apply {
        setColor(ACCENT_BG)
        cornerRadius = dp(ctx, 20).toFloat()
        setStroke(dp(ctx, 1), 0x337DD3FC)
    }
)

private fun saveButtonBg(ctx: Context): RippleDrawable = withRipple(
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(SAVE_GRAD_TOP, SAVE_GRAD_BOTTOM)
    ).apply { cornerRadius = dp(ctx, 14).toFloat() },
    rippleColor = 0x50FFFFFF
)

private fun cancelButtonBg(ctx: Context): RippleDrawable = withRipple(
    GradientDrawable().apply {
        setColor(0xFF000000.toInt())
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }
)

private fun arrowButtonBg(ctx: Context): RippleDrawable = withRipple(
    GradientDrawable().apply {
        setColor(ROW)
        cornerRadius = dp(ctx, 8).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }
)

    // ══════════════════════════════════════════════════════════
    // ── SHOOTING STARS ──
    // ══════════════════════════════════════════════════════════
    private class ShootingStarsView(context: Context) : View(context) {
    private data class Star(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var length: Float, var alpha: Float,
        var thickness: Float, var age: Float, var lifespan: Float
    )
    // Genshin-style spark orbiting near the head
    private data class Spark(
        var angle: Float,
        var radius: Float,
        var speed: Float,
        var size: Float,
        var hue: Int   // 0 = white, 1 = pale purple
    )
    private val stars = mutableListOf<Star>()
    private val sparks = mutableListOf<Spark>()
    private val tailPath = Path()
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private var lastNs = 0L

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f
            else ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNs = now

        // ── Spawn: only one star onscreen at a time ──
        if (rnd.nextFloat() < 0.006f && stars.isEmpty()) {
            val angleDeg = 25f + rnd.nextFloat() * 30f
            val rad = Math.toRadians(angleDeg.toDouble())
            val speed = 260f + rnd.nextFloat() * 180f
            val startX = width * (0.5f + rnd.nextFloat() * 0.7f)
            val startY = -40f + rnd.nextFloat() * (height * 0.5f)
            val thickness = 3.5f + rnd.nextFloat() * 2.0f
            stars.add(Star(
                x = startX,
                y = startY,
                vx = (-Math.cos(rad) * speed).toFloat(),
                vy = (Math.sin(rad) * speed).toFloat(),
                length = 200f + rnd.nextFloat() * 180f,
                alpha = 1.0f,
                thickness = thickness,
                age = 0f,
                lifespan = 1.8f + rnd.nextFloat() * 0.8f
            ))
            // Genshin sparks: 4 small particles orbiting near the head
            sparks.clear()
            val sparkCount = 4
            for (i in 0 until sparkCount) {
                sparks.add(Spark(
                    angle = rnd.nextFloat() * 360f,
                    radius = thickness * (2.5f + rnd.nextFloat() * 2.5f),
                    speed = 180f + rnd.nextFloat() * 140f,
                    size = thickness * (0.5f + rnd.nextFloat() * 0.6f),
                    hue = if (rnd.nextFloat() < 0.5f) 0 else 1
                ))
            }
        }

        val iter = stars.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.age += dt

            if (s.age > s.lifespan || s.x < -320f || s.x > width + 320f || s.y > height + 120f) {
                iter.remove()
                sparks.clear()
                continue
            }

            val lifeFrac = (s.age / s.lifespan).coerceIn(0f, 1f)
            val fadeIn = (lifeFrac / 0.12f).coerceIn(0f, 1f)
            val fadeOut = ((1f - lifeFrac) / 0.45f).coerceIn(0f, 1f)
            val fade = fadeIn * fadeOut
            val a = (s.alpha * fade * 255f).coerceIn(0f, 255f).toInt()

            val speedMag = Math.hypot(s.vx.toDouble(), s.vy.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = s.vx / speedMag
            val uy = s.vy / speedMag
            val tipX = s.x - ux * s.length
            val tipY = s.y - uy * s.length
            val px = -uy
            val py = ux
            val headHalf = s.thickness * 2.4f

            // ── Tapered tail — purple → pink → white gradient, head brightest ──
            tailPath.reset()
            tailPath.moveTo(s.x + px * headHalf, s.y + py * headHalf)
            tailPath.lineTo(tipX, tipY)
            tailPath.lineTo(s.x - px * headHalf, s.y - py * headHalf)
            tailPath.close()

            // Three-stop gradient: white head → pale pink → purple tip → transparent
            tailPaint.shader = android.graphics.LinearGradient(
                s.x, s.y, tipX, tipY,
                intArrayOf(
                    Color.argb(a, 255, 235, 255),      // white-pink at head
                    Color.argb((a * 0.7f).toInt(), 220, 170, 255),  // mid purple-pink
                    Color.argb((a * 0.35f).toInt(), 170, 120, 240), // deep purple
                    Color.argb(0, 150, 100, 220)       // fade out
                ),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(tailPath, tailPaint)
            tailPaint.shader = null

            // ── Head glow — layered Genshin style ──
            // Layer 1: outer violet halo
            headPaint.shader = RadialGradient(
                s.x, s.y, s.thickness * 14f,
                Color.argb((a * 0.35f).toInt(), 190, 140, 255),
                Color.argb(0, 190, 140, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(s.x, s.y, s.thickness * 14f, headPaint)

            // Layer 2: mid pink glow
            headPaint.shader = RadialGradient(
                s.x, s.y, s.thickness * 7f,
                Color.argb((a * 0.65f).toInt(), 255, 200, 250),
                Color.argb(0, 255, 200, 250),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(s.x, s.y, s.thickness * 7f, headPaint)

            // Layer 3: bright white core
            headPaint.shader = null
            headPaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.thickness * 2.6f, headPaint)

            // ── Sparks orbiting around the head ──
            for (sp in sparks) {
                sp.angle += sp.speed * dt
                val rad = Math.toRadians(sp.angle.toDouble())
                val sx = s.x + (Math.cos(rad) * sp.radius).toFloat()
                val sy = s.y + (Math.sin(rad) * sp.radius).toFloat()
                val sparkColor = if (sp.hue == 0) {
                    Color.argb((a * 0.9f).toInt(), 255, 255, 255)
                } else {
                    Color.argb((a * 0.85f).toInt(), 220, 180, 255)
                }
                sparkPaint.color = sparkColor
                canvas.drawCircle(sx, sy, sp.size, sparkPaint)
            }
        }
        postInvalidateOnAnimation()
    }
    }

// ══════════════════════════════════════════════════════════
// ── NIGHT CLOUDS ──
// Translucent soft clouds drifting right→left.
// Runs behind shooting stars inside the header.
// ══════════════════════════════════════════════════════════
private class NightCloudsView(context: Context) : View(context) {
    private data class Cloud(
        var x: Float, var y: Float,
        var speed: Float,
        var width: Float,
        var height: Float,
        var alpha: Float,
        var seed: Int
    )
    private val clouds = mutableListOf<Cloud>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private var lastNs = 0L

    init { setWillNotDraw(false) }

    private fun spawnCloud(offscreen: Boolean) {
        val w = 135f + rnd.nextFloat() * 195f
        val h = w * (0.32f + rnd.nextFloat() * 0.18f)
        clouds.add(Cloud(
            x = if (offscreen) width + w else rnd.nextFloat() * width,
            y = height * (0.28f + rnd.nextFloat() * 0.55f),
            speed = 4f + rnd.nextFloat() * 8f,
            width = w,
            height = h,
            alpha = 0.55f + rnd.nextFloat() * 0.3f,
            seed = rnd.nextInt(1000)
        ))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (clouds.isEmpty() && w > 0) {
            spawnCloud(false)
            spawnCloud(true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f
            else ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNs = now

        if (clouds.isEmpty() && width > 0) {
            spawnCloud(false); spawnCloud(true)
        }

        val iter = clouds.iterator()
        var pendingSpawn = 0
        while (iter.hasNext()) {
            val c = iter.next()
            c.x -= c.speed * dt

            if (c.x + c.width < -60f) {
                iter.remove()
                pendingSpawn++
                continue
        }

            // Fade at both edges so clouds don't pop in/out
            val edgeFade = when {
                c.x < 0f -> ((c.x + c.width) / c.width).coerceIn(0f, 1f)
                c.x > width - c.width -> ((width - c.x) / c.width).coerceIn(0f, 1f)
                else -> 1f
            }

            // 6 lobes → soft cloud silhouette via radial gradients
            val lobes = 6
            val step = c.width / lobes
            val r = c.height * 0.55f
            for (i in 0 until lobes) {
                val n = ((c.seed * (i + 1) * 9301 + 49297) % 233280).toFloat() / 233280f
                val lr = r * (0.55f + n * 0.6f)
                val cx = c.x + i * step + step / 2f
                val cy = c.y + (n - 0.5f) * c.height * 0.35f
                paint.shader = RadialGradient(
                    cx, cy, lr * 1.6f,
                    Color.argb((c.alpha * edgeFade * 42f).toInt(), 195, 215, 245),
                    Color.argb(0, 195, 215, 245),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, lr * 1.6f, paint)
            }
                        paint.shader = null
                    }
                    for (i in 0 until pendingSpawn) spawnCloud(true)
                    postInvalidateOnAnimation()
                }
            }

    // ══════════════════════════════════════════════════════════
    // ── CARD BUILDER ──
    // ══════════════════════════════════════════════════════════
    private class Card(
        val root: LinearLayout,
        val body: LinearLayout,
        val statusBadge: TextView,
        val chevron: TextView
    )

    private fun buildCard(
        ctx: Context, emoji: String, title: String,
        subtitle: String? = null, badge: String? = null,
        badgeColor: Int = ACCENT, expanded: Boolean = false
    ): Card {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            isClickable = true
        }
        header.addView(TextView(ctx).apply {
            text = emoji; textSize = 18f; setPadding(0, 0, dp(ctx, 12), 0)
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = title; setTextColor(TEXT); textSize = 16f
        })
        if (!subtitle.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = subtitle; setTextColor(SUBTEXT); textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        header.addView(col)
        val status = TextView(ctx).apply {
            text = badge ?: ""; setTextColor(badgeColor); textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            visibility = if (badge.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        header.addView(status)
        val chev = TextView(ctx).apply {
            text = if (expanded) "▾" else "▸"
            setTextColor(SUBTEXT); textSize = 14f
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        header.addView(chev)
        root.addView(header)
        val bodyLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        root.addView(bodyLayout)
        header.setOnClickListener {
            val showing = bodyLayout.visibility == View.VISIBLE
            TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(180))
            bodyLayout.visibility = if (showing) View.GONE else View.VISIBLE
            chev.text = if (showing) "▸" else "▾"
        }
        return Card(root, bodyLayout, status, chev)
    }

    // ══════════════════════════════════════════════════════════
    // ── REUSABLE ROWS ──
    // ══════════════════════════════════════════════════════════
    private fun toggleRow(
        ctx: Context, label: String, desc: String?, initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        val sw = Switch(ctx); sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun stepperRow(
        ctx: Context, label: String, desc: String?, min: Int, max: Int,
        initial: Int, onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        var current = initial
        val valTxt = TextView(ctx).apply {
            text = "$current"; setTextColor(ACCENT); textSize = 20f
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        }
        fun mkBtn(sym: String, delta: Int) = Button(ctx).apply {
            text = sym; textSize = 18f; setTextColor(TEXT)
            background = bg(INPUT, 20, ctx); isAllCaps = false
            minWidth = dp(ctx, 40); minHeight = dp(ctx, 40)
            setOnClickListener {
                current = (current + delta).coerceIn(min, max)
                valTxt.text = "$current"
                onChange(current)
            }
        }
        row.addView(mkBtn("−", -1)); row.addView(valTxt); row.addView(mkBtn("+", 1))
        return row
    }

    private fun actionRow(
        ctx: Context, label: String, desc: String?,
        buttonText: String, buttonColor: Int = ACCENT,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = buttonText; textSize = 13f
            setTextColor(if (buttonColor == RED) RED else ACCENT_STRONG)
            background = if (buttonColor == RED) bg(0x22F87171, 18, ctx) else accentPill(ctx)
            setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            minHeight = 0; minWidth = 0; isAllCaps = false
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun domainRow(
        ctx: Context, domain: String, hasCookie: Boolean,
        onOpen: () -> Unit, onClear: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = domain; setTextColor(TEXT); textSize = 14f
        })
        col.addView(TextView(ctx).apply {
            text = if (hasCookie) "✓ Solved" else "Not solved"
            setTextColor(if (hasCookie) GREEN else SUBTEXT)
            textSize = 11f; setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = if (hasCookie) "Reopen" else "Open"
            textSize = 12f; setTextColor(ACCENT_STRONG)
            background = accentPill(ctx); isAllCaps = false
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            setOnClickListener { onOpen() }
        })
        if (hasCookie) {
            row.addView(Button(ctx).apply {
                text = "✕"; textSize = 12f; setTextColor(RED)
                background = bg(0x22F87171, 18, ctx); isAllCaps = false
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                minHeight = 0; minWidth = 0
                setOnClickListener { onClear() }
            })
        }
        return row
    }

    private fun labelBlock(ctx: Context, title: String, subtitle: String?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 10))
            addView(TextView(ctx).apply {
                text = title; setTextColor(TEXT); textSize = 14f
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = subtitle; setTextColor(SUBTEXT); textSize = 11f
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
        }

    private fun makeArrowBtn(
        ctx: Context, symbol: String, enabled: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = symbol
        textSize = 14f
        setTextColor(if (enabled) ACCENT_STRONG else DISABLED)
        background = arrowButtonBg(ctx)
        gravity = Gravity.CENTER
        val size = dp(ctx, 32)
        layoutParams = LinearLayout.LayoutParams(size, size)
            .apply { leftMargin = dp(ctx, 4) }
        isClickable = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun rowReorderItem(
        ctx: Context, spec: RowSpec, position: Int, total: Int,
        onToggle: (Boolean) -> Unit,
        onMoveUp: () -> Unit, onMoveDown: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        row.addView(TextView(ctx).apply {
            text = "${position + 1}"
            setTextColor(SUBTEXT); textSize = 12f
            gravity = Gravity.CENTER
            val s = dp(ctx, 24)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = bg(INPUT, 12, ctx)
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8); rightMargin = dp(ctx, 4) }
        }
        col.addView(TextView(ctx).apply {
            text = spec.name; setTextColor(TEXT); textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = spec.sourceLabel; setTextColor(SUBTEXT); textSize = 10f
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col)
        val sw = Switch(ctx)
        sw.isChecked = isRowEnabled(spec.key)
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onToggle(v) }
        row.addView(sw)
        row.addView(makeArrowBtn(ctx, "▲", position > 0) { onMoveUp() })
        row.addView(makeArrowBtn(ctx, "▼", position < total - 1) { onMoveDown() })
        return row
    }

    // ══════════════════════════════════════════════════════════
    // ── MAIN DIALOG ──
    // ══════════════════════════════════════════════════════════
    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        lateinit var dialog: AlertDialog

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }

        // ── header ──
        run {
            val headerFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 160)
                )
                background = skyGradient(ctx)
                clipChildren = true
            }
            headerFrame.addView(NightCloudsView(ctx).apply {
               layoutParams = FrameLayout.LayoutParams(
                   ViewGroup.LayoutParams.MATCH_PARENT,
                   ViewGroup.LayoutParams.MATCH_PARENT
               )
            })
            headerFrame.addView(ShootingStarsView(ctx).apply {
               layoutParams = FrameLayout.LayoutParams(
                   ViewGroup.LayoutParams.MATCH_PARENT,
                   ViewGroup.LayoutParams.MATCH_PARENT
               )
            })
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            content.addView(TextView(ctx).apply {
                text = "☁  BingeCloud"
                setTextColor(TEXT); textSize = 26f
            })
            content.addView(TextView(ctx).apply {
                text = "Configure sources, catalogs & cookies"
                setTextColor(ACCENT); textSize = 12f
                setPadding(0, dp(ctx, 6), 0, 0)
            })
            headerFrame.addView(content)
            root.addView(headerFrame)
        }

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 24))
        }
        scroll.addView(body)

        // ── PERFORMANCE ──
        run {
            val c = buildCard(ctx, "⚡", "Performance", "Control scraping speed")
            c.body.addView(stepperRow(
                ctx, "Concurrency", "Providers running in parallel",
                1, 50, getConcurrency()
            ) { setKey(K_CONCURRENCY, it) })
            c.body.addView(toggleRow(
                ctx, "Smart prefetch",
                "Pre-cache current + next episode in background",
                isPrefetchEnabled()
            ) { setKey(K_PREFETCH, it) })
            body.addView(c.root)
        }

        // ── CLOUDFLARE SHIELD ──
        run {
            val c = buildCard(
                ctx, "🛡️", "Cloudflare Shield",
                subtitle = "One-tap bypass for all protected sources"
            )

            val pillHolder = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 10))
            }

            fun renderPills() {
                pillHolder.removeAllViews()
                for ((sourceName, _) in CloudflareShield.GROUPS) {
                    val st = CloudflareShield.statusOf(sourceName)
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = bg(ROW, 12, ctx)
                        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(ctx, 6) }
                    }
                    row.addView(TextView(ctx).apply {
                        text = when (st.state) {
                            CloudflareShield.State.PROTECTED -> "🟢"
                            CloudflareShield.State.PARTIAL -> "🟡"
                            CloudflareShield.State.WORKING -> "🔄"
                            else -> "🔴"
                        }
                        textSize = 16f
                        setPadding(0, 0, dp(ctx, 10), 0)
                    })
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(ctx).apply {
                        text = sourceName
                        setTextColor(TEXT)
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    val sub = when (st.state) {
                        CloudflareShield.State.PROTECTED -> {
                            val hours = if (st.earliestExpiryMs > 0) {
                                ((st.earliestExpiryMs - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0)
                            } else -1L
                            if (hours >= 0) "All protected · expires in ${hours}h"
                            else "All protected"
                        }
                        CloudflareShield.State.PARTIAL -> "Some cookies expired"
                        CloudflareShield.State.WORKING -> "Bypassing…"
                        else -> "Not protected"
                    }
                    col.addView(TextView(ctx).apply {
                        text = sub
                        setTextColor(SUBTEXT)
                        textSize = 11f
                        setPadding(0, dp(ctx, 2), 0, 0)
                    })
                    row.addView(col)
                    row.addView(TextView(ctx).apply {
                        text = "${st.freshCount} / ${st.totalCount}"
                        setTextColor(ACCENT_STRONG)
                        textSize = 13f
                    })
                    pillHolder.addView(row)
                }
            }
            renderPills()
            c.body.addView(pillHolder)

            // primary — Bypass / Refresh
            c.body.addView(actionRow(
                ctx,
                "Bypass all protected sources",
                "Opens one window, solves each site in order",
                "Bypass"
            ) {
                val src = CloudflareShield.GROUPS.keys.firstOrNull() ?: return@actionRow
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                scope.launch {
                    val ok = CloudflareShield.bypassGroup(ctx, src) { cur, total, dm ->
                        Toast.makeText(ctx, "Bypassing $cur/$total: $dm", Toast.LENGTH_SHORT).show()
                    }
                    val total = CloudflareShield.GROUPS[src]?.size ?: 0
                    Toast.makeText(
                        ctx,
                        if (ok == total) "✓ All protected" else "Protected $ok / $total",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                }
            })

            // clear all
            c.body.addView(actionRow(
                ctx,
                "Clear all cookies",
                "Removes every saved Cloudflare cookie",
                "Clear", buttonColor = RED
            ) {
                AlertDialog.Builder(ctx)
                    .setTitle("Clear all CF cookies?")
                    .setMessage("You will need to bypass again next time.")
                    .setPositiveButton("Clear") { _, _ ->
                        for ((_, domains) in CloudflareShield.GROUPS) {
                            for (dm in domains) CloudflareShield.clearCookieAndExpiry(dm)
                        }
                        Toast.makeText(ctx, "All cookies cleared", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showSettingsDialog(ctx, onSaved)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })

            // saved domains list
            val saved = getCfDomains()
            if (saved.isNotEmpty()) {
                c.body.addView(labelBlock(ctx, "Saved domains",
                    "Tap ↻ to refresh, ✕ to clear one"))
                for (domain in saved) {
                    val has = getCookieForDomain(domain) != null
                    c.body.addView(domainRow(
                        ctx, domain, has,
                        onOpen = { openCfWebView(ctx, "https://$domain", domain) },
                        onClear = {
                            CloudflareShield.clearCookieAndExpiry(domain)
                            Toast.makeText(ctx, "Cleared $domain", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            showSettingsDialog(ctx, onSaved)
                        }
                    ))
                }
            }

            // add custom domain
            c.body.addView(actionRow(
                ctx, "Add custom domain", "Enter a domain to protect",
                "Add"
            ) {
                val input = EditText(ctx).apply {
                    setTextColor(TEXT)
                    setHintTextColor(SUBTEXT)
                    hint = "example.com"
                    textSize = 14f
                    background = bg(INPUT, 8, ctx)
                    setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg(ctx)
                    setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 16))
                }
                wrap.addView(TextView(ctx).apply {
                    text = "Add domain"
                    setTextColor(TEXT)
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                })
                wrap.addView(TextView(ctx).apply {
                    text = "Enter the domain without https:// — e.g. example.com"
                    setTextColor(SUBTEXT)
                    textSize = 13f
                    setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
                })
                wrap.addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                val inputDlg = AlertDialog.Builder(ctx).create()
                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(ctx, 20), 0, 0)
                }
                btnRow.addView(Button(ctx).apply {
                    text = "Cancel"
                    textSize = 14f
                    setTextColor(TEXT)
                    background = bg(ROW, 14, ctx)
                    isAllCaps = false
                    setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
                    minHeight = 0; minWidth = 0
                    setOnClickListener { inputDlg.dismiss() }
                })
                btnRow.addView(Button(ctx).apply {
                    text = "Save"
                    textSize = 14f
                    setTextColor(0xFF0A0D14.toInt())
                    background = saveButtonBg(ctx)
                    isAllCaps = false
                    setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
                    minHeight = 0; minWidth = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(ctx, 8) }
                    setOnClickListener {
                        val raw = input.text?.toString()?.trim().orEmpty()
                            .removePrefix("https://").removePrefix("http://")
                            .substringBefore("/").lowercase()
                        if (raw.isBlank() || !raw.contains(".")) {
                            Toast.makeText(ctx, "Invalid domain", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        inputDlg.dismiss()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
    try {
        val html = cloudflareGet("https://$raw", referer = "https://$raw")
        if (html != null) {
            val cookie = CookieManager.getInstance().getCookie("https://$raw")
            if (!cookie.isNullOrBlank()) {
                saveCookieForDomain(raw, cookie)
                Toast.makeText(ctx, "✓ Saved for $raw", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (_: Exception) {}
}
                    }
                })
                wrap.addView(btnRow)
                inputDlg.setView(wrap)
                inputDlg.window?.setBackgroundDrawable(cardBg(ctx))
                inputDlg.show()
            })

            body.addView(c.root)
        }

        // ── FEBBOX ACCOUNT ──
        run {
            val has = getFebBoxToken().isNotBlank()
            val c = buildCard(
                ctx, "🔑", "FebBox Account",
                subtitle = if (has) "Signed in" else "Not signed in",
                badge = if (has) "✓ Active" else "○ None",
                badgeColor = if (has) GREEN else SUBTEXT
            )
            c.body.addView(labelBlock(
                ctx,
                if (has) "You're signed in"
                else "Sign in to unlock ShowBox/FebBox sources",
                if (has) "Session saved — nothing else to do."
                else "Sign in via WebView, or paste a cookie from febbox.com."
            ))
            c.body.addView(actionRow(
                ctx, "Sign in / Refresh", "Opens febbox.com login",
                if (has) "Re-login" else "Sign in"
            ) {
                openFebBoxLogin(ctx) {
                    onSaved()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                }
            })
            c.body.addView(actionRow(
                ctx, "Paste token", "Manually paste cookie from febbox.com",
                "Paste"
            ) {
                showPasteTokenDialog(ctx) {
                    onSaved()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                }
            })
            if (has) {
                c.body.addView(actionRow(
                    ctx, "Copy token", "Copy saved session to clipboard",
                    "Copy"
                ) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("FebBox Token", getFebBoxToken()))
                    Toast.makeText(ctx, "Token copied", Toast.LENGTH_SHORT).show()
                })
                c.body.addView(actionRow(
                    ctx, "Sign out", "Removes saved session",
                    "Sign out", buttonColor = RED
                ) {
                    clearFebBoxToken()
                    Toast.makeText(ctx, "Signed out", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                })
            }
            body.addView(c.root)
        }

        // ── SOURCES ──
        run {
            val all = listOf(
                isSrcVm(), isSrcMd(), isSrcHdh(),
                isSrcMovieBox(), isSrcAnikoto(), isSrcShowBox()
               // ── MLSBD REVIVE ── add `, isSrcMlsbd()` back to include in the count
             )
             val on = all.count { it }
             val total = all.size
             val c = buildCard(
                 ctx, "📡", "Sources", "$on of $total enabled",
                 badge = "$on/$total"
             )
            c.body.addView(toggleRow(ctx, "VegaMovies", null, isSrcVm()) { setKey(K_SRC_VM, it) })
            c.body.addView(toggleRow(ctx, "MoviesDrive", null, isSrcMd()) { setKey(K_SRC_MD, it) })
            c.body.addView(toggleRow(ctx, "HDhub4u", null, isSrcHdh()) { setKey(K_SRC_HDH, it) })
            c.body.addView(toggleRow(ctx, "MovieBox", "Native API — no login", isSrcMovieBox()) { setKey(K_SRC_MOVIEBOX, it) })
            c.body.addView(toggleRow(ctx, "AniKoto", "Anime only — sub/dub", isSrcAnikoto()) { setKey(K_SRC_ANIKOTO, it) })
            c.body.addView(toggleRow(ctx, "ShowBox", "FebBox — movies & series", isSrcShowBox()) { setKey(K_SRC_SHOWBOX, it) })
           // ── MLSBD REVIVE ── uncomment below to re-enable
           // c.body.addView(toggleRow(ctx, "MLSBD", "Bangla movies & series", isSrcMlsbd()) { setKey(K_SRC_MLSBD, it) })
            body.addView(c.root)
        }

        // ── PREFERRED QUALITY ──
        run {
            val cur = getQualityPref()
            val c = buildCard(ctx, "🎞️", "Preferred Quality", "Current: $cur")
            val options = listOf("Auto", "480p", "720p", "1080p", "2K (1440p)", "4K (2160p)")
            val display = options.map { it.substringBefore(" (").trim() }
            val spinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(display.indexOf(cur).coerceAtLeast(0))
                background = bg(ROW, 10, ctx)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 4) }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        setKey(K_QUALITY, display[pos])
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            c.body.addView(spinner)
            body.addView(c.root)
        }

        // ── LINK RANKING ──
        run {
            val c = buildCard(ctx, "🎯", "Link Ranking", "Score and order by reliability")
            c.body.addView(toggleRow(
                ctx, "Smart link ranking",
                "Rank links by confidence (🟢🟡🔴)",
                isPrefilterEnabled()
            ) { setKey(K_PREFILTER, it) })
            body.addView(c.root)
        }

        // ── DEBUG LOGS ──
        run {
             val c = buildCard(
                ctx, "🐞", "Debug Logs",
               "Local · ${BCLog.count()} lines • tap ▸ to expand"
             )
               c.body.addView(toggleRow(
               ctx, "Verbose logging",
              "Log full URLs, HTML dumps, tokens — off for normal use",
              isVerboseLog()
              ) { enabled ->
              setKey(K_VERBOSE_LOG, enabled)
              BCLog.setVerbose(enabled)
            })
            val logView = TextView(ctx).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(LOG_TEXT)
                setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                setTextIsSelectable(false)
                text = BCLog.allSanitized()
            }
            val logScroll = ScrollView(ctx).apply {
                background = bg(INPUT, 8, ctx)
                isVerticalScrollBarEnabled = false
                isFillViewport = false
            }
            logScroll.addView(logView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

            val logScrollbar = LogScrollbar(ctx, logScroll)
            val logRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 320)
                ).apply {
                    leftMargin = dp(ctx, 8)
                    rightMargin = dp(ctx, 8)
                    bottomMargin = dp(ctx, 6)
                }
            }
            logRow.addView(logScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            logRow.addView(logScrollbar, LinearLayout.LayoutParams(dp(ctx, 10), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(ctx, 2)
            })

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 4))
            }
            fun smallBtn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(color)
                background = accentPill(ctx)
                isAllCaps = false
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                minHeight = 0; minWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(ctx, 3); rightMargin = dp(ctx, 3) }
                setOnClickListener { onClick() }
            }
            btnRow.addView(smallBtn("Refresh", ACCENT_STRONG) {
                logView.text = BCLog.allSanitized()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            })
            btnRow.addView(smallBtn("Save", ACCENT_STRONG) {
                try {
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val fname = "bingelog_$ts.txt"
                    val content = BCLog.allSanitized()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = ctx.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                        uri?.let {
                            ctx.contentResolver.openOutputStream(it)?.use { os ->
                                os.write(content.toByteArray())
                            }
                            Toast.makeText(ctx, "Saved to Downloads/$fname", Toast.LENGTH_LONG).show()
                        } ?: Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
                    } else {
                        val dir = android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        java.io.File(dir, fname).writeText(content)
                        Toast.makeText(ctx, "Saved to Downloads/$fname", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
            btnRow.addView(smallBtn("Copy", ACCENT_STRONG) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("BingeCloud Logs", BCLog.allSanitized()))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            })
            btnRow.addView(smallBtn("Clear", RED) {
                BCLog.clear()
                logView.text = "(cleared)"
            })
            c.body.addView(logRow)
            c.body.addView(btnRow)
            body.addView(c.root)
        }

        // ── HOMEPAGE ──
        run {
            val c = buildCard(
                ctx, "🏠", "Homepage", "Tap ▲▼ to reorder sections"
            )
            val listHolder = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun renderList() {
                listHolder.removeAllViews()
                val order = getRowOrder()
                val total = order.size
                val on = order.count { isRowEnabled(it) }
                c.statusBadge.text = "$on/$total"
                c.statusBadge.visibility = View.VISIBLE

                for ((idx, key) in order.withIndex()) {
                    val spec = getRowSpecByKey(key) ?: continue
                    listHolder.addView(rowReorderItem(
                        ctx, spec, idx, total,
                        onToggle = { enabled ->
                            setKey(spec.key, enabled)
                            renderList()
                        },
                        onMoveUp = {
                            val current = getRowOrder().toMutableList()
                            val i = current.indexOf(key)
                            if (i > 0) {
                                current[i] = current[i - 1]
                                current[i - 1] = key
                                setRowOrder(current)
                                renderList()
                            }
                        },
                        onMoveDown = {
                            val current = getRowOrder().toMutableList()
                            val i = current.indexOf(key)
                            if (i >= 0 && i < current.size - 1) {
                                current[i] = current[i + 1]
                                current[i + 1] = key
                                setRowOrder(current)
                                renderList()
                            }
                        }
                    ))
                }
            }

               renderList()
               c.body.addView(actionRow(
                   ctx, "Reset home", "Restore default rows, order and toggles",
                   "Reset", buttonColor = RED
               ) {
                    showResetConfirmDialog(ctx) {
                        resetHomeToDefaults()
                        renderList()
                        Toast.makeText(ctx, "Home reset to defaults", Toast.LENGTH_SHORT).show()
                    }
              })
              c.body.addView(labelBlock(ctx, "Section order",
                    "Position 1 shows first on the home screen."))
              c.body.addView(listHolder)
              body.addView(c.root)
        }

        // ── footer ──
        run {
            val footer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 16), dp(ctx, 20), dp(ctx, 16), dp(ctx, 8))
            }
            footer.addView(TextView(ctx).apply {
                text = "☁  FLUMMOX Repo"
                setTextColor(SUBTEXT); textSize = 13f
                gravity = Gravity.CENTER
            })
            footer.addView(TextView(ctx).apply {
                text = "BINGECLOUD  •  EXTENSION"
                setTextColor(0xFF3D4A5C.toInt()); textSize = 10f
                letterSpacing = 0.15f
                setPadding(0, dp(ctx, 4), 0, 0)
                gravity = Gravity.CENTER
            })
            body.addView(footer)
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── bottom bar ──
        run {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = bg(BG, 0, ctx)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 16))
            }
            bar.addView(Button(ctx).apply {
                text = "Cancel"; textSize = 14f; setTextColor(SUBTEXT)
                background = cancelButtonBg(ctx); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 50), 1f)
                    .apply { rightMargin = dp(ctx, 6) }
                setOnClickListener { dialog.dismiss() }
            })
            bar.addView(Button(ctx).apply {
                text = "Save & Close"; textSize = 14f
                setTextColor(0xFF0A0D14.toInt())
                background = saveButtonBg(ctx); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 50), 1.4f)
                    .apply { leftMargin = dp(ctx, 6) }
                setOnClickListener { dialog.dismiss(); onSaved() }
            })
            root.addView(bar)
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawable(bg(BG, 20, ctx))
        dialog.show()
    }

    // ── CF WEBVIEW ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openCfWebView(ctx: Context, startUrl: String, domain: String) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = bg(BG, 0, ctx)
        }
        val urlBar = TextView(ctx).apply {
            text = startUrl; setTextColor(SUBTEXT); textSize = 11f
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10)); maxLines = 1
        }
        layout.addView(urlBar)
        val wvWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlBar.text = url ?: startUrl
                }
            }
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(startUrl)
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🍪  Save Cookies"; textSize = 13f
            setTextColor(0xFF0A0D14.toInt())
            background = saveButtonBg(ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie(startUrl) ?: ""
                if (cookie.isBlank()) {
                    Toast.makeText(ctx, "No cookies — solve challenge first",
                        Toast.LENGTH_SHORT).show()
                } else {
                    saveCookieForDomain(domain, cookie)
                    Toast.makeText(ctx, "✓ Saved for $domain", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                }
            }
        })
        layout.addView(bar)
        dlg.setContentView(layout)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }

    // ── FEBBOX LOGIN WEBVIEW ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openFebBoxLogin(ctx: Context, onSaved: () -> Unit) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = bg(BG, 0, ctx)
        }
        val banner = TextView(ctx).apply {
            text = "Sign in to FebBox. Your session is stored locally."
            setTextColor(SUBTEXT); textSize = 12f
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        layout.addView(banner)
        val wvWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl("https://www.febbox.com/login")
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🔑  Save Token"; textSize = 13f
            setTextColor(0xFF0A0D14.toInt())
            background = saveButtonBg(ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
            val cookie = CookieManager.getInstance().getCookie("https://www.febbox.com") ?: ""
            val hasUi = cookie.split(";").any { it.trim().startsWith("ui=") }
    if (!hasUi) {
        Toast.makeText(ctx, "Not signed in yet — complete login first",
            Toast.LENGTH_SHORT).show()
    } else {
        // save full cookie string — FebBox file_download needs all of it, not just ui
        saveFebBoxToken(cookie)
        Toast.makeText(ctx, "✓ Signed in", Toast.LENGTH_SHORT).show()
        dlg.dismiss()
        onSaved()
    }
            }
        })
        layout.addView(bar)
        dlg.setContentView(layout)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }
}
