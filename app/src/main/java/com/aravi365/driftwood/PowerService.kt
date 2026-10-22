package com.aravi365.driftwood

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Apps can't turn a TV off; only system apps can. An accessibility service can do what holding
 * the remote's power button does, so this one exists only for the Turn off button. It reads no
 * screen content and handles no events.
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

        /** Standby, like holding power on the remote. Falls back to the system power menu. */
        fun turnOff(): Boolean {
            val s = running ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) || s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
    }
}
