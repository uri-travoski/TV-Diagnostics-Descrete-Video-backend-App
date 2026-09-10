package com.tvdiagnostics.app.security

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.decoy.ScreenTestActivity

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
        // 1. Lock vault
        TVDiagnosticsApp.instance.preferences.lockVault()

        // 2. Clear entire task backstack and instantly present Screen Test
        val intent = Intent(activity, ScreenTestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
