package com.dictee.boucle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/** Jauge en direct de |hp| avec un trait rouge au niveau du seuil. */
class TapGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxScale = 8f
    private var level = 0f
    private var shown = 0f
    private var threshold = 2.84f
    private var lastFrame = 0L

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.gauge_track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.gauge_fill) }
    private val hit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.gauge_hit) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gauge_threshold)
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val rect = RectF()

    fun setLevel(value: Float, thr: Float) {
        level = value
        threshold = thr
        if (value > shown) shown = value
        invalidate()
    }

    fun setThreshold(thr: Float) {
        threshold = thr
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16 else now - lastFrame
        lastFrame = now
        // Décroissance douce de l'affichage pour voir les pics.
        shown = max(level, shown - dt * 0.012f)

        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val fraction = min(1f, shown / maxScale)
        if (fraction > 0f) {
            rect.set(0f, 0f, max(h, w * fraction), h)
            canvas.drawRoundRect(rect, r, r, if (shown > threshold) hit else fill)
        }
        val x = w * min(1f, threshold / maxScale)
        canvas.drawLine(x, 0f, x, h, line)
        if (shown > 0.01f) postInvalidateOnAnimation()
    }
}
