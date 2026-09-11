package com.tvdiagnostics.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tv_diagnostics_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var pinCode: String
        get() {
            val stored = prefs.getString(KEY_PIN_CODE, DEFAULT_PIN_CODE) ?: DEFAULT_PIN_CODE
            if (stored == "123456" || stored == "1234") {
                prefs.edit().putString(KEY_PIN_CODE, DEFAULT_PIN_CODE).apply()
                return DEFAULT_PIN_CODE
            }
            return stored
        }
        set(value) = prefs.edit().putString(KEY_PIN_CODE, value).apply()

    var isVaultUnlocked: Boolean
        get() = prefs.getBoolean(KEY_IS_UNLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_UNLOCKED, value).apply()

    var isNotesEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_NOTES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_NOTES_ENABLED, value).apply()

    var isAutoLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTO_LOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_AUTO_LOCK_ENABLED, value).apply()

    fun lockVault() {
        isVaultUnlocked = false
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PIN_CODE = "pin_code"
        private const val KEY_IS_UNLOCKED = "is_vault_unlocked"
        private const val KEY_IS_NOTES_ENABLED = "is_notes_enabled"
        private const val KEY_IS_AUTO_LOCK_ENABLED = "is_auto_lock_enabled"
        const val DEFAULT_PIN_CODE = "482061"
        const val DEFAULT_SERVER_URL = "http://tv-diagnostics:8090"
    }
}
