package com.aravi365.driftwood

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A row of glass chips that slides down from the top: the things you'd otherwise dig through Settings for. */
@SuppressLint("ViewConstructor")
class QuickPanel(context: Context) : LinearLayout(context) {

    class Item(val label: String, val icon: Int, val run: () -> Unit)

    private val dp = resources.displayMetrics.density

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        // Room for the focused chip to grow without being cut off.
        val pad = (8 * dp).toInt()
        setPadding(0, pad, 0, pad)
    }

    fun setItems(items: List<Item>) {
        removeAllViews()
        items.forEachIndexed { i, item ->
            addView(chip(item), LayoutParams((104 * dp).toInt(), (84 * dp).toInt()).apply {
                if (i > 0) marginStart = (14 * dp).toInt()
            })
        }
    }

    fun focusFirst() = getChildAt(0)?.requestFocus()

    private fun chip(item: Item): View {
        val radius = 18 * dp
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = radius; setColor(0xF2FFFFFF.toInt())
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = radius; setColor(0x33FFFFFF); setStroke((1 * dp).toInt(), 0x40FFFFFF)
            })
        }
        val icon = ImageView(context).apply { setImageResource(item.icon) }
        val label = TextView(context).apply {
            text = item.label
            textSize = 13f
            gravity = Gravity.CENTER
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = bg
            isFocusable = true
            defaultFocusHighlightEnabled = false
            addView(icon, LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
            fun paint(focused: Boolean) {
                val c = if (focused) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
                icon.setColorFilter(c)
                label.setTextColor(c)
            }
            paint(false)
            setOnFocusChangeListener { v, f ->
                paint(f)
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(160).start()
            }
            setOnClickListener { item.run() }
        }
    }
}
