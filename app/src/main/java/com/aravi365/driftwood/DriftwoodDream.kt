package com.aravi365.driftwood

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.random.Random

/**
 * Aerial-style screensaver: the Driftwood wallpapers drift and cross-fade, with a thin clock,
 * date and weather that move every 30 s so nothing burns in. Pitch black when offline.
 */
class DriftwoodDream : DreamService() {

    private lateinit var block: LinearLayout
    private lateinit var caption: TextView
    private val shots = arrayOfNulls<ImageView>(2)
    private var front = 0
    private var current: Photo? = null

    private val drift = object : Runnable {
        override fun run() {
            block.animate().alpha(0f).setDuration(1200).withEndAction {
                val root = block.parent as View
                block.translationX = Random.nextFloat() * (root.width - block.width).coerceAtLeast(1)
                block.translationY = Random.nextFloat() * (root.height - block.height).coerceAtLeast(1)
                block.animate().alpha(1f).setDuration(1200).start()
            }.start()
            block.postDelayed(this, 30_000)
        }
    }

    /** Same never-repeat queue as the home screen; decoding happens off the main thread. */
    private val rotate = object : Runnable {
        override fun run() {
            val maxW = resources.displayMetrics.widthPixels
            Thread {
                val p = Backgrounds.next(this@DriftwoodDream)
                val bmp = p?.let { Backgrounds.decode(it, maxW) }
                if (p != null && bmp != null) block.post { show(p, bmp) }
                if (Backgrounds.needsMore(this@DriftwoodDream)) Backgrounds.refresh(this@DriftwoodDream)
            }.start()
            block.postDelayed(this, 60_000)
        }
    }

    private fun show(p: Photo, bmp: android.graphics.Bitmap) {
        val next = shots[1 - front]!!
        next.setImageBitmap(bmp)
        ObjectAnimator.ofPropertyValuesHolder(
            next,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.04f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.04f, 1.18f),
        ).apply { duration = 64_000; interpolator = LinearInterpolator(); start() }
        next.animate().alpha(1f).setDuration(2500).start()
        shots[front]!!.animate().alpha(0f).setDuration(2500).start()
        front = 1 - front
        caption.text = p.caption
        // Take the credit's colour from the corner it sits on, 60% transparent.
        caption.setTextColor(bmp.regionColor(0f, 0.85f, 0.5f, 1f).blendedText(0x66))
        current?.file?.delete()
        current = p
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        for (i in 0..1) {
            shots[i] = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
            root.addView(shots[i], FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x8C000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 96f
            setTextColor(0xD9FFFFFF.toInt())
            typeface = Typeface.create(resources.getFont(R.font.google_sans_flex), 200, false)
            includeFontPadding = false
            setShadowLayer(18f, 0f, 2f, 0x59000000)
            // Big thin digits carry their own left spacing; pull them back so the clock lines up
            // with the date and weather under it.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(t: android.text.Editable?) = alignLeft(this@apply)
            })
            post { alignLeft(this) }
        }
        val sans = resources.getFont(R.font.google_sans_flex)
        val date = TextClock(this).apply {
            typeface = sans
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 20f
            setTextColor(0xB3FFFFFF.toInt())
            includeFontPadding = false
            setPadding(0, 10, 0, 0)
            setShadowLayer(12f, 0f, 1f, 0x4D000000)
        }
        val weather = TextView(this).apply {
            typeface = sans
            textSize = 18f
            setTextColor(0x99FFFFFF.toInt())
            includeFontPadding = false
            setPadding(0, 8, 0, 0)
            setShadowLayer(12f, 0f, 1f, 0x4D000000)
        }
        caption = TextView(this).apply {
            typeface = sans
            textSize = 12f
            setTextColor(0x66FFFFFF)  // 60% transparent
            includeFontPadding = false
            setShadowLayer(10f, 0f, 1f, 0x4D000000)
        }
        block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(clock); addView(date); addView(weather)
            alpha = 0f
        }
        root.addView(block, FrameLayout.LayoutParams(-2, -2))
        // The credit stays put near the bottom while the clock wanders.
        val dp = resources.displayMetrics.density
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins((48 * dp).toInt(), 0, (48 * dp).toInt(), (28 * dp).toInt())
        })
        setContentView(root)

        val prefs = Prefs(this)
        // Evenings get a darker, warmer screensaver.
        if (Weather.isEvening(prefs)) shots.forEach { it?.colorFilter = Weather.eveningFilter(1.6f) }
        weather.text = prefs.weather.orEmpty()
        Weather.refresh(prefs) { weather.text = it.orEmpty() }
    }

    /** Shift a text view left by its first glyph's side bearing, so its ink starts at the edge. */
    private fun alignLeft(v: TextView) {
        val text = v.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val bounds = android.graphics.Rect()
        v.paint.getTextBounds(text, 0, 1, bounds)
        v.translationX = -bounds.left.toFloat()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        block.post(drift)
        block.post(rotate)
    }

    override fun onDreamingStopped() {
        block.removeCallbacks(drift)
        block.removeCallbacks(rotate)
        super.onDreamingStopped()
    }
}
