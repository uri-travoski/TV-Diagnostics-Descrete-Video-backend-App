package com.tvdiagnostics.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tv_diagnostics_prefs", Context.MODE_PRIVATE)

    init {
        // Codes now live on the backend per user; drop the old locally stored single PIN
        prefs.edit().remove("pin_code").remove("is_vault_unlocked").apply()
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var isNotesEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_NOTES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_NOTES_ENABLED, value).apply()

    var isAutoLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTO_LOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_AUTO_LOCK_ENABLED, value).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IS_NOTES_ENABLED = "is_notes_enabled"
        private const val KEY_IS_AUTO_LOCK_ENABLED = "is_auto_lock_enabled"
        const val DEFAULT_SERVER_URL = "http://tv-diagnostics:8090"
    }
}
