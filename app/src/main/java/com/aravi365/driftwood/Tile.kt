package com.aravi365.driftwood

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
 * tvOS-style app tile: lifts and grows inside a thin glowing glass ring, and a light sheen
 * sweeps across. The name appears only when focused.
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
    /** Card plus the floating glass ring; this is what grows on focus. */
    private val holder = FrameLayout(context)
    private val ring = View(context)
    var row = 0
    var onFocus: ((Tile) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val shine = View(context)
    private val caption = TextView(context)
    private val shineW = (tileW * 0.45f).toInt()
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
        // Thin glass edge right on the card's border, visible on white and dark banners alike.
        val gapPx = (RING_GAP * dp).toInt()
        ring.background = GlowRingDrawable((14 + RING_GAP) * dp, dp)
        ring.translationZ = 11 * dp  // stays above the lifted card
        ring.alpha = 0f
        holder.clipChildren = false
        holder.clipToPadding = false
        holder.cameraDistance = 9000 * dp
        holder.addView(card, FrameLayout.LayoutParams(tileW, tileH).apply { setMargins(gapPx, gapPx, gapPx, gapPx) })
        holder.addView(ring, FrameLayout.LayoutParams(-1, -1))  // on top, hugging the card's edge
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
        val scale = if (gain) 1.08f else 1f
        holder.animate().scaleX(scale).scaleY(scale).setDuration(220).setInterpolator(ease).start()
        card.animate().translationZ(if (gain) 10 * dp else 0f).setDuration(220).setInterpolator(ease).start()
        ring.animate().alpha(if (gain) 1f else 0f).setDuration(260).setInterpolator(ease).start()
        glassView.animate().alpha(if (gain) 0.7f else IDLE_GLASS).setDuration(260).setInterpolator(ease).start()
        caption.animate().alpha(if (gain) 1f else 0f)
            .translationY(if (gain) tileH * 0.05f else 0f)
            .setDuration(220).setInterpolator(ease).start()

        if (gain) {
            shine.animate().cancel()
            shine.translationX = -shineW.toFloat()
            shine.alpha = 1f
            shine.animate().translationX(tileW.toFloat()).setStartDelay(90).setDuration(700)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { shine.alpha = 0f }.start()
            onFocus?.invoke(this)
        }
    }

    companion object {
        const val IDLE_GLASS = 0.3f
        const val RING_GAP = 1f
    }
}
