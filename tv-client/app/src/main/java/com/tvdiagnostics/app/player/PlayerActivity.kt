package com.tvdiagnostics.app.player

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem
import com.tvdiagnostics.app.security.PanicManager
import com.tvdiagnostics.app.security.Session
import com.tvdiagnostics.app.security.VaultLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen player with as little as possible over the picture:
 * - CENTER / OK: play or pause, and briefly show the small bottom overlay
 *   (play/pause icon, elapsed / total time, thin progress line). Stays up while paused.
 * - LEFT / RIGHT: seek 10 s back / forward without showing anything.
 * - UP: transparent speed menu in the bottom-right corner (UP/DOWN to pick, OK to apply).
 * - DOWN: peek at the overlay without pausing.
 * - BACK: hide the speed menu / overlay, else return to the library. Double BACK or MENU = panic.
 * Play, pause, skips, speed changes and closing are sent to the admin activity log.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var bottomOverlay: LinearLayout
    private lateinit var ivPlayState: ImageView
    private lateinit var tvTime: TextView
    private lateinit var tvSpeedBadge: TextView
    private lateinit var progressFill: View
    private lateinit var speedMenu: LinearLayout

    private var exoPlayer: ExoPlayer? = null
    private var videoItem: VideoItem? = null
    private var startPositionMs: Long = 0L

    /** Captured at start: the vault session is cleared before the final progress sync and exit log on leave. */
    private var playerSession: Session? = null
    private val sessionToken: String? get() = playerSession?.token

    // Activity log: wall-clock time actually spent playing, and the start of a run of skips
    private var watchedMs = 0L
    private var playingSince = 0L
    private var seekChainFrom = -1L
    private var exitLogged = false
    private val logSeekRunnable = Runnable { logSeekChain() }

    private var currentSpeedIndex = SPEEDS.indexOf(1.0f)
    private var speedMenuIndex = currentSpeedIndex

    // Rapid LEFT/RIGHT presses accumulate from the last target instead of the lagging position
    private var lastSeekTarget = 0L
    private var lastSeekAt = 0L

    private var progressSyncJob: Job? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        if (exoPlayer?.playWhenReady == true && speedMenu.visibility != View.VISIBLE) {
            hideOverlay()
        }
    }
    private val overlayTicker = object : Runnable {
        override fun run() {
            updateOverlay()
            if (bottomOverlay.visibility == View.VISIBLE) uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VaultLock.redirectIfLocked(this)) return
        VaultLock.registerVaultActivity(this)
        playerSession = VaultLock.session

        // Security: Prevent OS recents snapshot. Keep the screen fully bright while playing or paused.
        VaultLock.secureWindow(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)

        videoItem = intent.getSerializableExtra(EXTRA_VIDEO_ITEM) as? VideoItem
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        if (videoItem == null) {
            finish()
            return
        }

        playerView = findViewById(R.id.playerView)
        bottomOverlay = findViewById(R.id.bottomOverlay)
        ivPlayState = findViewById(R.id.ivPlayState)
        tvTime = findViewById(R.id.tvPlayerTime)
        tvSpeedBadge = findViewById(R.id.tvSpeedBadge)
        progressFill = findViewById(R.id.progressFill)
        progressFill.pivotX = 0f
        speedMenu = findViewById(R.id.speedMenu)

        buildSpeedMenu()
        initPlayer()
    }

    private fun initPlayer() {
        val video = videoItem ?: return
        val streamUrl = ApiClient.getStreamUrl(video.id)

        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        if (startPositionMs > 0) {
            exoPlayer?.setMediaItem(mediaItem, startPositionMs)
        } else {
            exoPlayer?.setMediaItem(mediaItem)
        }

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        logEvent("play", startPositionMs)

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    syncProgress(completed = true)
                    logEvent("ended", exoPlayer?.currentPosition ?: 0L)
                    showOverlay()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (bottomOverlay.visibility == View.VISIBLE) updateOverlay()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Playing counts as activity for the 15-minute auto-lock; paused, the countdown runs
                VaultLock.setPlaying(isPlaying)
                val now = SystemClock.elapsedRealtime()
                if (isPlaying) {
                    playingSince = now
                } else if (playingSince > 0) {
                    watchedMs += now - playingSince
                    playingSince = 0
                }
            }
        })

        startProgressHeartbeat()
    }

    // --- Overlay ---

    private fun showOverlay() {
        bottomOverlay.visibility = View.VISIBLE
        updateOverlay()
        uiHandler.removeCallbacks(overlayTicker)
        uiHandler.postDelayed(overlayTicker, 500)
        uiHandler.removeCallbacks(hideOverlayRunnable)
        // While paused the overlay stays so the pause state is visible; while playing it goes away
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun hideOverlay() {
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.removeCallbacks(overlayTicker)
        bottomOverlay.visibility = View.GONE
    }

    private fun updateOverlay() {
        val player = exoPlayer ?: return
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        ivPlayState.setImageResource(if (playing) R.drawable.ic_play else R.drawable.ic_pause)

        val duration = player.duration.takeIf { it > 0 } ?: ((videoItem?.duration ?: 0.0) * 1000).toLong()
        val position = player.currentPosition.coerceAtLeast(0L)
        tvTime.text = "${formatTime(position)} / ${formatTime(duration)}"
        progressFill.scaleX = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

        tvSpeedBadge.text = "▲ ${formatSpeed(SPEEDS[currentSpeedIndex])}"
    }

    // --- Speed menu ---

    private fun buildSpeedMenu() {
        speedMenu.removeAllViews()
        val density = resources.displayMetrics.density
        // Fastest at the top so UP goes faster, DOWN slower
        for (i in SPEEDS.indices.reversed()) {
            val item = TextView(this).apply {
                text = formatSpeed(SPEEDS[i])
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = (64 * density).toInt()
                setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
                tag = i
            }
            speedMenu.addView(item)
        }
        renderSpeedMenu()
    }

    private fun renderSpeedMenu() {
        for (v in 0 until speedMenu.childCount) {
            val item = speedMenu.getChildAt(v) as TextView
            val index = item.tag as Int
            val highlighted = index == speedMenuIndex
            item.setBackgroundResource(if (highlighted) R.drawable.bg_speed_item_selected else 0)
            item.setTextColor(if (highlighted) 0xE6FFFFFF.toInt() else 0x99FFFFFF.toInt())
            item.setTypeface(null, if (index == currentSpeedIndex) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun openSpeedMenu() {
        speedMenuIndex = currentSpeedIndex
        renderSpeedMenu()
        speedMenu.visibility = View.VISIBLE
        showOverlay()
    }

    private fun closeSpeedMenu() {
        speedMenu.visibility = View.GONE
        showOverlay()
    }

    private fun handleSpeedMenuKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> speedMenuIndex = (speedMenuIndex + 1).coerceAtMost(SPEEDS.size - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> speedMenuIndex = (speedMenuIndex - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (speedMenuIndex != currentSpeedIndex) {
                    currentSpeedIndex = speedMenuIndex
                    exoPlayer?.playbackParameters = PlaybackParameters(SPEEDS[currentSpeedIndex])
                    logEvent("speed", exoPlayer?.currentPosition ?: 0L, formatSpeed(SPEEDS[currentSpeedIndex]))
                }
                closeSpeedMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                closeSpeedMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            else -> return false
        }
        renderSpeedMenu()
        // Keep the menu up while the user is choosing
        uiHandler.removeCallbacks(hideOverlayRunnable)
        uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
        return true
    }

    // --- Keys ---

    override fun onUserInteraction() {
        super.onUserInteraction()
        VaultLock.touch()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Panic Button Handler (Double-tap BACK or MENU key)
        if (keyCode == KeyEvent.KEYCODE_MENU) logExit()
        if (PanicManager.handleKeyEvent(this, keyCode, event)) {
            releasePlayer()
            return true
        }

        val player = exoPlayer ?: return super.onKeyDown(keyCode, event)

        if (speedMenu.visibility == View.VISIBLE && handleSpeedMenuKey(keyCode)) {
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) togglePlayPause(player)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!player.playWhenReady) togglePlayPause(player)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (player.playWhenReady) togglePlayPause(player)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                seekBy(player, -SEEK_STEP_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                seekBy(player, SEEK_STEP_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) openSpeedMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (bottomOverlay.visibility == View.VISIBLE && player.playWhenReady) {
                    hideOverlay()
                    return true
                }
                finish()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun togglePlayPause(player: ExoPlayer) {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
            logEvent("play", 0L, "again")
        } else if (player.playWhenReady) {
            player.pause()
            syncProgress()
            logEvent("pause", player.currentPosition)
        } else {
            player.play()
            logEvent("resume", player.currentPosition)
        }
        showOverlay()
    }

    /** Silent seek: nothing is shown (an overlay that is already up just updates). */
    private fun seekBy(player: ExoPlayer, deltaMs: Long) {
        val now = SystemClock.uptimeMillis()
        val chained = now - lastSeekAt < SEEK_CHAIN_WINDOW_MS
        val base = if (chained) lastSeekTarget else player.currentPosition
        if (!chained || seekChainFrom < 0) seekChainFrom = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (base + deltaMs).coerceIn(0L, (duration - 1000L).coerceAtLeast(0L))
        player.seekTo(target)
        lastSeekTarget = target
        lastSeekAt = now
        if (bottomOverlay.visibility == View.VISIBLE) updateOverlay()
        // A run of LEFT/RIGHT presses is logged once, as where it started and where it ended
        uiHandler.removeCallbacks(logSeekRunnable)
        uiHandler.postDelayed(logSeekRunnable, SEEK_CHAIN_WINDOW_MS)
    }

    // --- Activity log ---

    private fun logEvent(event: String, positionMs: Long, detail: String = "", watchedSec: Double? = null) {
        val session = playerSession ?: return
        val video = videoItem ?: return
        ApiClient.logActivity(session, event, video.id, positionMs / 1000.0, watchedSec, detail)
    }

    private fun logSeekChain() {
        val from = seekChainFrom
        seekChainFrom = -1L
        if (from < 0) return
        val to = lastSeekTarget
        val dir = if (to >= from) "forward" else "back"
        logEvent("seek", to, "${formatTime(from)} → ${formatTime(to)} ($dir ${formatTime(kotlin.math.abs(to - from))})")
    }

    /** "Closed video" with where it stopped and how long it actually played. Logged once. */
    private fun logExit() {
        if (exitLogged) return
        val player = exoPlayer ?: return
        exitLogged = true
        if (seekChainFrom >= 0) {
            uiHandler.removeCallbacks(logSeekRunnable)
            logSeekChain()
        }
        val playedMs = watchedMs + if (playingSince > 0) SystemClock.elapsedRealtime() - playingSince else 0L
        logEvent("exit", player.currentPosition, watchedSec = playedMs / 1000.0)
    }

    // --- Progress sync & auto-lock ---

    private fun startProgressHeartbeat() {
        progressSyncJob?.cancel()
        progressSyncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(10000) // Sync every 10 seconds
                val (playing, currentSec, videoId) = withContext(Dispatchers.Main) {
                    val p = exoPlayer
                    val v = videoItem
                    if (p != null && v != null) {
                        Triple(p.isPlaying, p.currentPosition / 1000.0, v.id)
                    } else {
                        Triple(false, 0.0, -1)
                    }
                }
                if (playing && videoId != -1) {
                    ApiClient.updateProgressWithToken(sessionToken, videoId, currentSec, null)
                }
            }
        }
    }

    private fun syncProgress(completed: Boolean = false) {
        val player = exoPlayer ?: return
        val video = videoItem ?: return
        val currentSec = player.currentPosition / 1000.0
        val token = sessionToken

        CoroutineScope(Dispatchers.IO).launch {
            ApiClient.updateProgressWithToken(token, video.id, currentSec, if (completed) true else null)
        }
    }

    private fun releasePlayer() {
        val player = exoPlayer ?: return
        logExit()
        progressSyncJob?.cancel()
        syncProgress()
        player.stop()
        player.release()
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        syncProgress()
    }

    override fun onStop() {
        // Before super: leaving the app locks the vault in onStop, and the log should read exit → lock
        logExit()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        VaultLock.setPlaying(false)
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs) else String.format("%02d:%02d", mins, secs)
    }

    private fun formatSpeed(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
        return "$text×"
    }

    companion object {
        const val EXTRA_VIDEO_ITEM = "extra_video_item"
        const val EXTRA_START_POSITION_MS = "extra_start_position_ms"
        private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_CHAIN_WINDOW_MS = 1500L
        private const val OVERLAY_TIMEOUT_MS = 2500L
    }
}
