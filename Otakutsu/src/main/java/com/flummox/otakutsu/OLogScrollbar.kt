package com.flummox.otakutsu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.max

class OLogScrollbar(
    context: Context,
    private val scrollView: ScrollView,
    thumbColor: Int = 0x8C38BDF8.toInt(),
    private val horizontalInsetDp: Int = 2,
    private val verticalInsetDp: Int = 6
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaintColor = thumbColor
    private var thumbTop = 0f
    private var thumbBottom = 0f
    private var dragging = false
    private var dragOffset = 0f
    private var horizontalInsetPx = 0f
    private var verticalInsetPx = 0f

    init {
        setWillNotDraw(false)
        val density = context.resources.displayMetrics.density
        horizontalInsetPx = horizontalInsetDp * density
        verticalInsetPx = verticalInsetDp * density
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateThumb(); invalidate() }
        post { updateThumb() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumb()
    }

    private fun updateThumb() {
        val child = scrollView.getChildAt(0) ?: run {
            thumbTop = verticalInsetPx
            thumbBottom = height - verticalInsetPx
            return
        }
        val contentH = child.height
        val viewH = scrollView.height
        val usableH = height - 2 * verticalInsetPx
        if (contentH <= viewH || usableH <= 0f) {
            thumbTop = verticalInsetPx
            thumbBottom = height - verticalInsetPx
            return
        }
        val ratio = viewH.toFloat() / contentH
        val thumbH = max(usableH * ratio, 60f)
        val scrollRange = contentH - viewH
        val scrollRatio = if (scrollRange > 0) scrollView.scrollY.toFloat() / scrollRange else 0f
        thumbTop = verticalInsetPx + scrollRatio * (usableH - thumbH)
        thumbBottom = thumbTop + thumbH
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbBottom <= thumbTop) updateThumb()
        paint.color = thumbPaintColor
        val left = horizontalInsetPx
        val right = width - horizontalInsetPx
        val radius = (right - left) / 2f
        canvas.drawRoundRect(left, thumbTop, right, thumbBottom, radius, radius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val slop = 24f * resources.displayMetrics.density
                if (event.y >= thumbTop - slop && event.y <= thumbBottom + slop) {
                    dragging = true
                    dragOffset = event.y - thumbTop
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val thumbH = thumbBottom - thumbTop
                val minY = verticalInsetPx
                val maxY = height - verticalInsetPx - thumbH
                thumbTop = (event.y - dragOffset).coerceIn(minY, maxY)
                thumbBottom = thumbTop + thumbH
                applyScroll()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyScroll() {
        val child = scrollView.getChildAt(0) ?: return
        val contentH = child.height
        val viewH = scrollView.height
        val scrollRange = contentH - viewH
        if (scrollRange <= 0) return
        val usableH = height - 2 * verticalInsetPx
        val thumbH = thumbBottom - thumbTop
        val usableTrack = usableH - thumbH
        if (usableTrack <= 0f) return
        val ratio = (thumbTop - verticalInsetPx) / usableTrack
        scrollView.scrollTo(0, (ratio * scrollRange).toInt())
    }
}
