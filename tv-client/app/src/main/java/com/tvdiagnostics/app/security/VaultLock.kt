package com.tvdiagnostics.app.security

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import com.tvdiagnostics.app.BuildConfig
import com.tvdiagnostics.app.decoy.DecoyActivity
import java.lang.ref.WeakReference

/** The signed-in TV user, from a successful code entry. */
data class Session(val userId: Int, val userName: String, val token: String)

/**
 * Vault session and locking. The session lives only in memory: it is dropped whenever the
 * app leaves the screen, on panic, on auto-lock and when the backend rejects the token.
 */
object VaultLock {
    @Volatile
    var session: Session? = null
        private set

    /** Set when the app was locked while in the background: the next return shows the code prompt. */
    var promptOnReturn = false

    /** Library and player screens, closed when the vault locks. */
    private val vaultActivities = mutableListOf<WeakReference<Activity>>()

    val isUnlocked: Boolean get() = session != null

    fun unlock(newSession: Session) {
        session = newSession
    }

    fun updateToken(token: String) {
        session = session?.copy(token = token)
    }

    /** Blocks screenshots and the recent-apps thumbnail (release builds; debug keeps them for testing). */
    fun secureWindow(activity: Activity) {
        if (!BuildConfig.DEBUG) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun registerVaultActivity(activity: Activity) {
        vaultActivities.removeAll { it.get() == null || it.get() == activity }
        vaultActivities.add(WeakReference(activity))
    }

    fun lock() {
        session = null
        vaultActivities.forEach { it.get()?.finish() }
        vaultActivities.clear()
    }

    fun lockOnLeave() {
        if (!isUnlocked && vaultActivities.isEmpty()) return
        lock()
        promptOnReturn = true
    }

    /** Locks and shows the decoy screen with the code prompt on top. */
    fun lockAndPrompt(from: Activity) {
        lock()
        val intent = Intent(from, DecoyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(DecoyActivity.EXTRA_AUTO_PROMPT_PIN, true)
        }
        from.startActivity(intent)
        from.finish()
    }

    /** For vault screens' onCreate/onResume: true (and redirected to the prompt) if locked. */
    fun redirectIfLocked(activity: Activity): Boolean {
        if (isUnlocked) return false
        lockAndPrompt(activity)
        return true
    }
}
