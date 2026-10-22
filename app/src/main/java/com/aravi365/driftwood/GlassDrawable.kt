package com.aravi365.driftwood

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Liquid-glass overlay for a rounded card: a specular gloss across the top, a bright rim
 * that fades from top-left to bottom-right, and a faint inner edge. [tilt] (-1..1) slides the
 * gloss sideways so the light seems to move as the card sways.
 */
class GlassDrawable(private val radius: Float, private val dp: Float) : Drawable() {

    var tilt = 0f
        set(v) { field = v; invalidateSelf() }

    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * dp }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = 0x26FFFFFF
    }
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val shift = tilt * w * 0.35f

        gloss.shader = LinearGradient(
            b.left + shift, b.top.toFloat(), b.left + w * 0.55f + shift, b.top + h * 0.7f,
            intArrayOf(0x52FFFFFF, 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        r.set(b)
        canvas.drawRoundRect(r, radius, radius, gloss)

        rim.shader = LinearGradient(
            b.left + shift, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(0xF2FFFFFF.toInt(), 0x59FFFFFF, 0x8CFFFFFF.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        val half = rim.strokeWidth / 2
        r.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        canvas.drawRoundRect(r, radius - half, radius - half, rim)

        val inset = rim.strokeWidth + inner.strokeWidth
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        canvas.drawRoundRect(r, radius - inset, radius - inset, inner)
    }

    override fun setAlpha(alpha: Int) {
        gloss.alpha = alpha; rim.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
