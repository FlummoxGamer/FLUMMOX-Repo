package com.flummox.bingeanime

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
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog

object BingeAnimeSettings {

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

    private fun shape(color: Int, radiusDp: Int, ctx: Context,
                      strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable =
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

        val title = TextView(ctx).apply {
            text = "BINGEANIME"
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
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 28))
        }
        root.addView(subtitle)

        // ── status pill ──
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 18, ctx, 1, BORDER)
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 18) }
        }
        pill.addView(TextView(ctx).apply {
            text = "EXTENSION"
            setTextColor(TEXT)
            textSize = 12f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pill.addView(View(ctx).apply {
            background = shape(ACTIVE, 5, ctx)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply {
                rightMargin = dp(ctx, 10)
            }
        })
        pill.addView(TextView(ctx).apply {
            text = "ACTIVE"
            setTextColor(ACTIVE)
            textSize = 12f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(pill)

        // ── square tile factory ──
        fun squareTile(label: String, tappable: Boolean, preview: Boolean = false,
                       onClick: (() -> Unit)? = null): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(SURFACE, 18, ctx, 1, if (tappable) BORDER_HI else BORDER)
                if (onClick != null) {
                    isClickable = true
                    setOnClickListener { onClick() }
                }
                setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))

                if (preview) {
                    BLog.recent(3).forEach { raw ->
                        val cut = raw.indexOf("] ")
                        val line = if (cut in 0 until raw.length - 2) raw.substring(cut + 2).take(24)
                                   else raw.take(24)
                        addView(TextView(ctx).apply {
                            text = line
                            setTextColor(0x99EDEDED.toInt())
                            textSize = 9f
                            typeface = Typeface.MONOSPACE
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                    }
                }

                addView(View(ctx), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ))
                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(TEXT)
                    textSize = 11f
                    letterSpacing = 0.18f
                    setTypeface(typeface, Typeface.BOLD)
                })
            }
        }

        val rowHeight = dp(ctx, 130)

        // Row 1: LOGS | (empty)
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 14) }
        }
        row1.addView(
            squareTile("LOGS", tappable = true, preview = true) { showLogs(ctx) },
            LinearLayout.LayoutParams(0, rowHeight, 1f).apply { rightMargin = dp(ctx, 14) }
        )
        row1.addView(squareTile("", tappable = false), LinearLayout.LayoutParams(0, rowHeight, 1f))
        root.addView(row1)

        // Row 2: (empty) | (empty)
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(squareTile("", tappable = false),
            LinearLayout.LayoutParams(0, rowHeight, 1f).apply { rightMargin = dp(ctx, 14) })
        row2.addView(squareTile("", tappable = false), LinearLayout.LayoutParams(0, rowHeight, 1f))
        root.addView(row2)

        root.addView(View(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── close ──
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
            listOf(title, subtitle, pill, row1, row2, close).forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = 28f
                v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(60L * i)
                    .setDuration(380)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        dlg.show()
    }

    // ── logs ──
    private fun showLogs(ctx: Context) {
        val dlg = Dialog(ctx).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, 0, ctx)
        }

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
            text = "${BLog.count()} LINES"
            setTextColor(SUBTEXT)
            textSize = 11f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(countText)
        root.addView(header)

        val logView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(LOG_TEXT)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            setTextIsSelectable(true)
            text = BLog.allSanitized()
        }
        val logScroll = ScrollView(ctx).apply {
            background = shape(SURFACE_2, 14, ctx, 1, BORDER)
            isVerticalScrollBarEnabled = true
        }
        logScroll.addView(logView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val logWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                leftMargin = dp(ctx, 16)
                rightMargin = dp(ctx, 16)
            }
        }
        logWrap.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(logWrap)

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
                leftMargin = dp(ctx, 4); rightMargin = dp(ctx, 4)
            }
            setOnClickListener { onClick() }
        }

        btnRow.addView(btn("REFRESH", TEXT) {
            logView.text = BLog.allSanitized()
            countText.text = "${BLog.count()} LINES"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        })
        btnRow.addView(btn("SAVE", TEXT) {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
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
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    uri?.let {
                        ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
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
        btnRow.addView(btn("COPY", TEXT) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BingeAnime Logs", BLog.allSanitized()))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(btn("CLEAR", RED) {
            BLog.clear()
            logView.text = "(cleared)"
            countText.text = "0 LINES"
        })
        root.addView(btnRow)

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
            listOf(header, logWrap, btnRow, close).forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = 24f
                v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(50L * i)
                    .setDuration(340)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        dlg.show()
    }
}
