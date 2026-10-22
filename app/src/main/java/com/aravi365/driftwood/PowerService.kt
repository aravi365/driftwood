package com.aravi365.driftwood

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Apps can't turn a TV off; only system apps can. An accessibility service may open the system
 * power menu, so this one exists only for the Turn off button. It reads no screen content and
 * handles no events.
 */
class PowerService : AccessibilityService() {

    override fun onServiceConnected() {
        running = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile private var running: PowerService? = null

        fun enabled(ctx: Context) = running != null

        /**
         * Opens the TV's own power menu, the same one the stock home screen's power button shows:
         * it asks to confirm, then shows "Shutting down". The system does the actual power off.
         */
        fun showPowerMenu(): Boolean = running?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) ?: false
    }
}
