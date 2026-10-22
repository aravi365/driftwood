package com.aravi365.driftwood

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView

/** A ScrollView that leaves all scrolling to the activity, so rows glide into a fixed position. */
class FocusScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean) = false
    override fun executeKeyEvent(event: KeyEvent) = false

    /** Fade only under the sticky bar at the top, never the rows peeking in at the bottom. */
    override fun getBottomFadingEdgeStrength() = 0f
}
