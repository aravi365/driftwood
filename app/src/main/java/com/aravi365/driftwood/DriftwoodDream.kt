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
    private var index = -1
    private var photos: List<Photo> = emptyList()

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

    private val rotate = object : Runnable {
        override fun run() {
            if (photos.isEmpty()) return
            index = (index + 1) % photos.size
            val p = photos[index]
            val bmp = Backgrounds.decode(p, resources.displayMetrics.widthPixels)
            if (bmp != null) {
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
            }
            block.postDelayed(this, 60_000)
        }
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
            setTextColor(0xF2FFFFFF.toInt())
            typeface = Typeface.create(resources.getFont(R.font.google_sans_flex), 200, false)
            setShadowLayer(16f, 0f, 2f, 0x80000000.toInt())
        }
        val sans = resources.getFont(R.font.google_sans_flex)
        val date = TextClock(this).apply {
            typeface = sans
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 20f
            setTextColor(0xCCFFFFFF.toInt())
        }
        val weather = TextView(this).apply {
            typeface = sans
            textSize = 18f
            setTextColor(0xB3FFFFFF.toInt())
        }
        caption = TextView(this).apply {
            typeface = sans
            textSize = 12f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(0, 18, 0, 0)
        }
        block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(clock); addView(date); addView(weather); addView(caption)
            alpha = 0f
        }
        root.addView(block, FrameLayout.LayoutParams(-2, -2))
        setContentView(root)

        val prefs = Prefs(this)
        weather.text = prefs.weather.orEmpty()
        Weather.refresh(prefs) { weather.text = it.orEmpty() }
        photos = Backgrounds.cached(this)
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
