package com.tvdiagnostics.app

import android.app.Application
import com.tvdiagnostics.app.data.PreferencesManager

class TVDiagnosticsApp : Application() {
    lateinit var preferences: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = PreferencesManager(this)
    }

    companion object {
        lateinit var instance: TVDiagnosticsApp
            private set
    }
}
