package com.flummox.otakutsu

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, ctx: Context, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun show(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 20))
        }

        // ── title ──
        val title = TextView(ctx).apply {
            text = "OTAKUTSU"
            setTextColor(TEXT)
            textSize = 36f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "FLUMMOX REPO · EXTENSION"
            setTextColor(SUBTEXT)
            textSize = 10f
            letterSpacing = 0.22f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 28))
        }
        root.addView(subtitle)

        // ── status pill ──
        val statusPill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 18, ctx, 1, BORDER)
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 18) }
        }
        statusPill.addView(TextView(ctx).apply {
            text = "EXTENSION"
            setTextColor(TEXT)
            textSize = 12f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val activeDot = View(ctx).apply {
            background = shape(ACTIVE, 5, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply {
                rightMargin = dp(ctx, 10)
            }
        }
        statusPill.addView(activeDot)
        statusPill.addView(TextView(ctx).apply {
            text = "ACTIVE"
            setTextColor(ACTIVE)
            textSize = 12f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(statusPill)

        // ── hero box (empty) ──
        val hero = View(ctx).apply {
            background = shape(SURFACE, 18, ctx, 1, BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 170)
            ).apply { bottomMargin = dp(ctx, 18) }
        }
        root.addView(hero)

        // ── tile factory ──
        fun makeTile(label: String? = null, tappable: Boolean = false, onClick: (() -> Unit)? = null): FrameLayout {
            return FrameLayout(ctx).apply {
                background = shape(SURFACE, 18, ctx, 1, if (tappable) BORDER_HI else BORDER)
                if (onClick != null) {
                    isClickable = true
                    setOnClickListener { onClick() }
                }
                if (label != null) {
                    addView(TextView(ctx).apply {
                        text = label
                        setTextColor(TEXT)
                        textSize = 11f
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
                }
            }
        }

        // ── 2x2 grid ──
        val gridRow1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 14) }
        }
        val logsTile = makeTile("LOGS", tappable = true) { showLogWindow(ctx) }
        val emptyTile1 = makeTile()
        gridRow1.addView(logsTile, LinearLayout.LayoutParams(0, dp(ctx, 118), 1f).apply { rightMargin = dp(ctx, 14) })
        gridRow1.addView(emptyTile1, LinearLayout.LayoutParams(0, dp(ctx, 118), 1f))
        root.addView(gridRow1)

        val gridRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val emptyTile2 = makeTile()
        val emptyTile3 = makeTile()
        gridRow2.addView(emptyTile2, LinearLayout.LayoutParams(0, dp(ctx, 118), 1f).apply { rightMargin = dp(ctx, 14) })
        gridRow2.addView(emptyTile3, LinearLayout.LayoutParams(0, dp(ctx, 118), 1f))
        root.addView(gridRow2)

        // spacer
        root.addView(View(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── close ──
        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(BG)
            background = shape(TEXT, 14, ctx)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply { topMargin = dp(ctx, 24) }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(closeBtn)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            // pulsing ACTIVE dot
            ObjectAnimator.ofFloat(activeDot, "alpha", 0.25f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            // staggered entrance
            val entrance = listOf(title, subtitle, statusPill, hero, gridRow1, gridRow2, closeBtn)
            entrance.forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = 28f
                v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(70L * i)
                    .setDuration(420)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        dlg.show()
    }

    // ── log window ──
    private fun showLogWindow(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
        }

        // header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 40), dp(ctx, 20), dp(ctx, 22))
        }
        header.addView(TextView(ctx).apply {
            text = "LOGS"
            setTextColor(TEXT)
            textSize = 22f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countText = TextView(ctx).apply {
            text = "${OLog.count()} LINES"
            setTextColor(SUBTEXT)
            textSize = 11f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(countText)
        root.addView(header)

        // body
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
            ).apply {
                leftMargin = dp(ctx, 16)
                rightMargin = dp(ctx, 16)
            }
        }
        logRow.addView(logScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        logRow.addView(scrollbar, LinearLayout.LayoutParams(dp(ctx, 8), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(ctx, 6)
        })
        root.addView(logRow)

        // buttons
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 8))
        }
        fun btn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(color)
            background = shape(SURFACE, 12, ctx, 1, BORDER)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10))
            minHeight = 0; minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 44), 1f).apply {
                leftMargin = dp(ctx, 4)
                rightMargin = dp(ctx, 4)
            }
            setOnClickListener { onClick() }
        }
        btnRow.addView(btn("REFRESH", TEXT) {
            logView.text = OLog.allSanitized()
            countText.text = "${OLog.count()} LINES"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        })
        btnRow.addView(btn("SAVE", TEXT) {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val fname = "otakutsu_log_$ts.txt"
                val content = OLog.allSanitized()
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
            cm.setPrimaryClip(ClipData.newPlainText("Otakutsu Logs", OLog.allSanitized()))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(btn("CLEAR", RED) {
            OLog.clear()
            logView.text = "(cleared)"
            countText.text = "0 LINES"
        })
        root.addView(btnRow)

        // close
        val close = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(BG)
            background = shape(TEXT, 14, ctx)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)
            ).apply {
                leftMargin = dp(ctx, 20)
                rightMargin = dp(ctx, 20)
                topMargin = dp(ctx, 6)
                bottomMargin = dp(ctx, 24)
            }
            setOnClickListener { dlg.dismiss() }
        }
        root.addView(close)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(shape(BG, 0, ctx))

        dlg.setOnShowListener {
            // entrance animations on log window too
            listOf(header, logRow, btnRow, close).forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = 24f
                v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(60L * i)
                    .setDuration(360)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        dlg.show()
    }
}
