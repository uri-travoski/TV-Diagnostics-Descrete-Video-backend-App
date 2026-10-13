package com.tvdiagnostics.app.security

import android.app.Activity
import android.view.KeyEvent

object PanicManager {
    private var lastBackPressTime: Long = 0
    private const val DOUBLE_CLICK_INTERVAL_MS = 700L

    /**
     * Checks if a key event should trigger the Panic Shield.
     * Returns true if handled (panic triggered).
     */
    fun handleKeyEvent(activity: Activity, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                // Immediate Panic trigger on TV MENU key
                triggerPanic(activity)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < DOUBLE_CLICK_INTERVAL_MS) {
                    triggerPanic(activity)
                    return true
                }
                lastBackPressTime = now
            }
        }
        return false
    }

    fun triggerPanic(activity: Activity) {
        // Lock vault, clear the back stack and present the Decoy code prompt
        VaultLock.lockAndPrompt(activity)
    }
}
