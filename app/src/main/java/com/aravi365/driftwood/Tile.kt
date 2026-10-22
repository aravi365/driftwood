package com.aravi365.driftwood

import android.animation.Animator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * tvOS-style app tile: lifts and grows on focus, a light sheen sweeps across,
 * then it gently sways in 3D while it stays focused. The name appears only when focused.
 */
@SuppressLint("ViewConstructor")
class Tile(
    context: Context,
    val label: String,
    banner: Drawable?,
    icon: Drawable?,
    private val tileW: Int,
    private val tileH: Int,
    fill: Int? = null,
) : LinearLayout(context) {

    val card = FrameLayout(context)
    /** Card plus the floating glass frame; this is what grows and sways. */
    private val holder = FrameLayout(context)
    private val ring = View(context)
    var row = 0
    var onFocus: ((Tile) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val shine = View(context)
    private val caption = TextView(context)
    private val shineW = (tileW * 0.45f).toInt()
    private val sway = ArrayList<Animator>()
    private val glass = GlassDrawable(14 * dp, dp)
    private val glassView = View(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 26) {
            defaultFocusHighlightEnabled = false
            revealOnFocusHint = false
        }

        card.outlineProvider = roundedOutline(14 * dp)
        card.clipToOutline = true
        card.cameraDistance = 9000 * dp
        if (Build.VERSION.SDK_INT >= 28) {
            // Glow instead of a dark drop shadow.
            card.outlineSpotShadowColor = 0xFFFFFFFF.toInt()
            card.outlineAmbientShadowColor = 0xFFFFFFFF.toInt()
        }

        val image = ImageView(context)
        if (banner != null) {
            card.setBackgroundColor(0xFF1C1C1E.toInt())
            image.setImageDrawable(banner)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val base = fill ?: icon?.render(48, 48, 0)?.averageColor() ?: 0xFF3A3A3C.toInt()
            card.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(base.withValue(0.62f, 0.9f), base.withValue(0.30f)),
            )
            image.setImageDrawable(icon)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (tileH * 0.22f).toInt()
            image.setPadding(pad, pad, pad, pad)
        }
        card.addView(image, FrameLayout.LayoutParams(-1, -1))

        shine.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF),
        )
        shine.alpha = 0f
        card.addView(shine, FrameLayout.LayoutParams(shineW, -1))
        glassView.background = glass
        glassView.alpha = IDLE_GLASS
        card.addView(glassView, FrameLayout.LayoutParams(-1, -1))
        // Floating glass frame a few dp outside the card, visible on white and dark banners alike.
        val gapPx = (RING_GAP * dp).toInt()
        ring.background = GradientDrawable().apply {
            cornerRadius = (14 + RING_GAP) * dp
            setStroke((3 * dp).toInt(), 0xFFFFFFFF.toInt())
        }
        ring.alpha = 0f
        holder.clipChildren = false
        holder.clipToPadding = false
        holder.cameraDistance = 9000 * dp
        holder.addView(ring, FrameLayout.LayoutParams(-1, -1))
        holder.addView(card, FrameLayout.LayoutParams(tileW, tileH).apply { setMargins(gapPx, gapPx, gapPx, gapPx) })
        addView(holder, LayoutParams(tileW + 2 * gapPx, tileH + 2 * gapPx))

        caption.text = label
        caption.setTextColor(0xFFFFFFFF.toInt())
        caption.textSize = 13f
        caption.typeface = android.graphics.Typeface.create(resources.getFont(R.font.google_sans_flex), 500, false)
        caption.gravity = Gravity.CENTER
        caption.setPadding(gapPx, 0, gapPx, 0)
        caption.isSingleLine = true
        caption.ellipsize = TextUtils.TruncateAt.END
        caption.alpha = 0f
        addView(caption, LayoutParams(tileW + 2 * gapPx, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        val ease = DecelerateInterpolator(2.2f)
        val scale = if (gain) 1.12f else 1f
        holder.animate().scaleX(scale).scaleY(scale).setDuration(220).setInterpolator(ease).start()
        card.animate().translationZ(if (gain) 20 * dp else 0f).setDuration(220).setInterpolator(ease).start()
        ring.animate().alpha(if (gain) 1f else 0f).setDuration(260).setInterpolator(ease).start()
        glassView.animate().alpha(if (gain) 1f else IDLE_GLASS).setDuration(260).setInterpolator(ease).start()
        caption.animate().alpha(if (gain) 1f else 0f)
            .translationY(if (gain) tileH * 0.07f else 0f)
            .setDuration(220).setInterpolator(ease).start()

        if (gain) {
            shine.animate().cancel()
            shine.translationX = -shineW.toFloat()
            shine.alpha = 1f
            shine.animate().translationX(tileW.toFloat()).setStartDelay(90).setDuration(700)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { shine.alpha = 0f }.start()
            startSway()
            onFocus?.invoke(this)
        } else {
            stopSway()
        }
    }

    private fun startSway() {
        stopSway()
        sway += swing(View.ROTATION_Y, 2.4f, 3100).apply {
            addUpdateListener { glass.tilt = holder.rotationY / 2.4f }
        }
        sway += swing(View.ROTATION_X, 1.6f, 4300)
    }

    private fun swing(prop: android.util.Property<View, Float>, deg: Float, ms: Long) =
        ObjectAnimator.ofFloat(holder, prop, 0f, deg, 0f, -deg, 0f).apply {
            duration = ms
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 350
            start()
        }

    fun pauseMotion() = sway.forEach { it.pause() }

    fun resumeMotion() = sway.forEach { it.resume() }

    private fun stopSway() {
        sway.forEach { it.cancel() }
        sway.clear()
        holder.animate().rotationX(0f).rotationY(0f).setDuration(200).start()
        glass.tilt = 0f
    }

    companion object {
        const val IDLE_GLASS = 0.3f
        const val RING_GAP = 4f
    }
}
