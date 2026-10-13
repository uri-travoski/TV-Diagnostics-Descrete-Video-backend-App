package com.tvdiagnostics.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tv_diagnostics_prefs", Context.MODE_PRIVATE)

    init {
        // Codes now live on the backend per user; drop the old locally stored single PIN
        // The inactivity auto-lock is always on now (15 minutes); its old on/off switch is gone
        prefs.edit().remove("pin_code").remove("is_vault_unlocked").remove("is_auto_lock_enabled").apply()
        // Installs from before the TV / mobile choice were all on TV boxes: keep them on TV, don't ask
        if (!prefs.contains(KEY_UI_MODE) && prefs.contains(KEY_SERVER_URL)) {
            prefs.edit().putString(KEY_UI_MODE, "tv").apply()
        }
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var isNotesEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_NOTES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_NOTES_ENABLED, value).apply()

    /** Library sort order (SortOption name) */
    var librarySort: String?
        get() = prefs.getString(KEY_LIBRARY_SORT, null)
        set(value) = prefs.edit().putString(KEY_LIBRARY_SORT, value).apply()

    /** "tv" or "mobile" (see UiMode); null until chosen on first launch */
    var uiMode: String?
        get() = prefs.getString(KEY_UI_MODE, null)
        set(value) = prefs.edit().putString(KEY_UI_MODE, value).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IS_NOTES_ENABLED = "is_notes_enabled"
        private const val KEY_LIBRARY_SORT = "library_sort"
        private const val KEY_UI_MODE = "ui_mode"
        const val DEFAULT_SERVER_URL = "http://tv-diagnostics:8090"
    }
}
