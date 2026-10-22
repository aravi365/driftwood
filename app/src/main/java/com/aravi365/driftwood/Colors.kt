package com.aravi365.driftwood

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

/** Independent copy, so one drawable is never attached to two views. */
fun Drawable.copy(): Drawable = constantState?.newDrawable()?.mutate() ?: this

fun Drawable.render(w: Int, h: Int, background: Int = 0xFF1C1C1E.toInt()): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(background)
    val d = copy()
    d.setBounds(0, 0, w, h)
    d.draw(canvas)
    return bmp
}

/** Average of the opaque pixels, so transparent icon corners don't wash it out. */
fun Bitmap.averageColor(): Int {
    val s = Bitmap.createScaledBitmap(this, 16, 16, true)
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (y in 0 until 16) for (x in 0 until 16) {
        val p = s.getPixel(x, y)
        if (Color.alpha(p) < 128) continue
        r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
    }
    if (n == 0L) return Color.DKGRAY
    return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

fun Int.withValue(maxValue: Float, satScale: Float = 1f): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(this, hsv)
    hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceAtMost(maxValue)
    return Color.HSVToColor(hsv)
}
