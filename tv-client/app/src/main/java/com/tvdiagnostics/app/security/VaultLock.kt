package com.tvdiagnostics.app.security

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import com.tvdiagnostics.app.BuildConfig
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.decoy.DecoyActivity
import java.lang.ref.WeakReference

/** The signed-in TV user, from a successful code entry. [activityId] tags this session's activity log. */
data class Session(val userId: Int, val userName: String, val token: String, val activityId: String = "")

/** Why the vault locked, as shown in the admin activity log. */
enum class LockReason(val code: String) {
    MANUAL("manual"), BACK("back"), HOME("home"), IDLE("idle"), PANIC("panic"), EXPIRED("expired")
}

/**
 * Vault session and locking. The session lives only in memory: it is dropped whenever the
 * app leaves the screen, on panic, after 15 minutes without input (unless a video is playing),
 * and when the backend rejects the token.
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

    // --- Inactivity auto-lock ---

    const val IDLE_LOCK_MS = 15 * 60 * 1000L
    private const val IDLE_CHECK_MS = 30 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var lastInputAt = SystemClock.elapsedRealtime()
    private var isPlaying = false

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!isUnlocked) return
            if (!isPlaying && SystemClock.elapsedRealtime() - lastInputAt >= IDLE_LOCK_MS) {
                lock(LockReason.IDLE)
                return
            }
            handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    /** Any remote key press while unlocked restarts the 15-minute countdown. */
    fun touch() {
        lastInputAt = SystemClock.elapsedRealtime()
    }

    /** A playing video counts as activity; paused or finished, the countdown runs. */
    fun setPlaying(playing: Boolean) {
        if (playing != isPlaying) touch()
        isPlaying = playing
    }

    /** Counts key presses in a dialog's window (dialogs don't pass keys through their activity). */
    fun trackInput(window: Window?) {
        val base = window?.callback ?: return
        if (base is InputTrackingCallback) return
        window.callback = InputTrackingCallback(base)
    }

    private class InputTrackingCallback(private val base: Window.Callback) : Window.Callback by base {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            touch()
            return base.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touch()
            return base.dispatchTouchEvent(event)
        }
    }

    // --- Session ---

    fun unlock(newSession: Session) {
        session = newSession
        isPlaying = false
        touch()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, IDLE_CHECK_MS)
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

    fun lock(reason: LockReason) {
        session?.let { ApiClient.logActivity(it, "lock", detail = reason.code) }
        session = null
        isPlaying = false
        handler.removeCallbacks(idleCheck)
        vaultActivities.forEach { it.get()?.finish() }
        vaultActivities.clear()
    }

    fun lockOnLeave() {
        if (!isUnlocked && vaultActivities.isEmpty()) return
        lock(LockReason.HOME)
        promptOnReturn = true
    }

    /** Locks and shows the decoy screen with the code prompt on top. */
    fun lockAndPrompt(from: Activity, reason: LockReason) {
        lock(reason)
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
        lockAndPrompt(activity, LockReason.EXPIRED)
        return true
    }
}
