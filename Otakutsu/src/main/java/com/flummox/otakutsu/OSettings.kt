package com.flummox.otakutsu

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object OSettings {

    // ── charcoal palette ──
    private const val BG = 0xFF0A0A0A.toInt()
    private const val HEADER = 0xFF141414.toInt()
    private const val CARD = 0xFF1A1A1A.toInt()
    private const val CARD_BORDER = 0xFF2E2E2E.toInt()
    private const val ROW = 0xFF161616.toInt()
    private const val INPUT = 0xFF0F0F0F.toInt()
    private const val ACCENT_BG = 0x22FFFFFF
    private const val ACCENT_STRONG = 0xFFD4D4D4.toInt()
    private const val TEXT = 0xFFEDEDED.toInt()
    private const val SUBTEXT = 0xFF8A8A8A.toInt()
    private const val RED = 0xFFE57373.toInt()
    private const val LOG_TEXT = 0xFFBFBFBF.toInt()

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun cardBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }

    private fun accentPill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(ACCENT_BG)
        cornerRadius = dp(ctx, 20).toFloat()
        setStroke(dp(ctx, 1), 0x33FFFFFF)
    }

    fun show(ctx: Context) {
        val dialog = AlertDialog.Builder(ctx).create()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }

        // ── header ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(HEADER, 0, ctx)
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22))
        }
        header.addView(TextView(ctx).apply {
            text = "⛩  Otakutsu"
            setTextColor(TEXT)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(ctx).apply {
            text = "Extension logs"
            setTextColor(ACCENT_STRONG)
            textSize = 12f
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        root.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 24))
        }

        // ── card ──
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx)
            setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12))
        }
        card.addView(TextView(ctx).apply {
            text = "🐞  Debug Logs"
            setTextColor(TEXT)
            textSize = 16f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 6))
        })
        card.addView(TextView(ctx).apply {
            text = "Local · ${OLog.count()} lines"
            setTextColor(SUBTEXT)
            textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12))
        })

        val logView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(LOG_TEXT)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            setTextIsSelectable(false)
            text = OLog.allSanitized()
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

        val scrollbar = OLogScrollbar(ctx, logScroll)
        val logRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 380)
            )
        }
        logRow.addView(logScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        logRow.addView(scrollbar, LinearLayout.LayoutParams(dp(ctx, 10), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(ctx, 4)
        })
        card.addView(logRow)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 4))
        }
        fun smallBtn(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(color)
            background = accentPill(ctx)
            isAllCaps = false
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 3); rightMargin = dp(ctx, 3) }
            setOnClickListener { onClick() }
        }
        btnRow.addView(smallBtn("Refresh", ACCENT_STRONG) {
            logView.text = OLog.allSanitized()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        })
        btnRow.addView(smallBtn("Save", ACCENT_STRONG) {
            try {
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val fname = "otakutsu_log_$ts.txt"
                val content = OLog.allSanitized()
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
            cm.setPrimaryClip(ClipData.newPlainText("Otakutsu Logs", OLog.allSanitized()))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(smallBtn("Clear", RED) {
            OLog.clear()
            logView.text = "(cleared)"
        })
        card.addView(btnRow)
        body.addView(card)

        // ── footer ──
        body.addView(TextView(ctx).apply {
            text = "FLUMMOX Repo  •  Otakutsu"
            setTextColor(SUBTEXT)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 20), 0, 0)
        })

        val scroll = ScrollView(ctx)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── bottom bar ──
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(BG, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 16))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"
            textSize = 14f
            setTextColor(TEXT)
            background = accentPill(ctx)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 50), 1f)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(bar)

        dialog.setView(root)
        dialog.window?.setBackgroundDrawable(bg(BG, 20, ctx))
        dialog.show()
    }
}
