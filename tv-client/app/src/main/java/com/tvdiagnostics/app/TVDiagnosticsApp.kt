package com.tvdiagnostics.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.tvdiagnostics.app.data.PreferencesManager
import com.tvdiagnostics.app.security.VaultLock

class TVDiagnosticsApp : Application() {
    lateinit var preferences: PreferencesManager
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = PreferencesManager(this)

        // Leaving the app (HOME, switching apps, screen off) locks the vault: the player and
        // library are closed, and coming back always lands on the code prompt, never the video.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0 && !activity.isChangingConfigurations) {
                    startedActivities = 0
                    VaultLock.lockOnLeave()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        lateinit var instance: TVDiagnosticsApp
            private set
    }
}
