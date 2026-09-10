package com.tvdiagnostics.app.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem
import com.tvdiagnostics.app.decoy.DecoyActivity
import com.tvdiagnostics.app.security.PanicManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topOverlay: LinearLayout
    private lateinit var tvTitle: TextView

    private var exoPlayer: ExoPlayer? = null
    private var videoItem: VideoItem? = null
    private var startPositionMs: Long = 0L

    private var progressSyncJob: Job? = null
    private val overlayHideHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable {
        topOverlay.visibility = View.GONE
    }

    private val autoLockHandler = Handler(Looper.getMainLooper())
    private val pausedAutoLockRunnable = Runnable {
        triggerAutoLock()
    }
    private val endedAutoLockRunnable = Runnable {
        triggerAutoLock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: Prevent OS recents snapshot
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_player)

        videoItem = intent.getSerializableExtra(EXTRA_VIDEO_ITEM) as? VideoItem
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        if (videoItem == null) {
            finish()
            return
        }

        playerView = findViewById(R.id.playerView)
        topOverlay = findViewById(R.id.topOverlay)
        tvTitle = findViewById(R.id.tvPlayerTitle)

        tvTitle.text = videoItem?.title ?: ""

        initPlayer()
        showOverlayTemporarily()
    }

    private fun initPlayer() {
        val video = videoItem ?: return
        val streamUrl = ApiClient.getStreamUrl(video.id)

        exoPlayer = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()

        playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        exoPlayer?.setMediaItem(mediaItem)

        if (startPositionMs > 0) {
            exoPlayer?.seekTo(startPositionMs)
        }

        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    syncProgress(completed = true)
                }
                scheduleOrCancelAutoLockTimers()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                scheduleOrCancelAutoLockTimers()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scheduleOrCancelAutoLockTimers()
            }
        })

        startProgressHeartbeat()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            scheduleOrCancelAutoLockTimers()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startProgressHeartbeat() {
        progressSyncJob?.cancel()
        progressSyncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(10000) // Sync every 10 seconds
                if (exoPlayer?.isPlaying == true) {
                    val currentSec = (exoPlayer?.currentPosition ?: 0L) / 1000.0
                    val video = videoItem ?: continue
                    ApiClient.updateProgress(video.id, currentSec)
                }
            }
        }
    }

    private fun syncProgress(completed: Boolean = false) {
        val player = exoPlayer ?: return
        val video = videoItem ?: return
        val currentSec = player.currentPosition / 1000.0

        CoroutineScope(Dispatchers.IO).launch {
            ApiClient.updateProgress(video.id, currentSec, completed)
        }
    }

    private fun showOverlayTemporarily() {
        topOverlay.visibility = View.VISIBLE
        overlayHideHandler.removeCallbacks(hideOverlayRunnable)
        overlayHideHandler.postDelayed(hideOverlayRunnable, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Panic Button Handler (Double-tap BACK or MENU key)
        if (PanicManager.handleKeyEvent(this, keyCode, event)) {
            releasePlayer()
            return true
        }

        val player = exoPlayer ?: return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
                showOverlayTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0L)
                player.seekTo(newPos)
                showOverlayTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val newPos = (player.currentPosition + 10000).coerceAtMost(player.duration)
                player.seekTo(newPos)
                showOverlayTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                showOverlayTemporarily()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOverlayTemporarily()
                playerView.showController()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun scheduleOrCancelAutoLockTimers() {
        autoLockHandler.removeCallbacks(pausedAutoLockRunnable)
        autoLockHandler.removeCallbacks(endedAutoLockRunnable)

        if (!TVDiagnosticsApp.instance.preferences.isAutoLockEnabled) {
            return
        }

        val player = exoPlayer ?: return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                autoLockHandler.postDelayed(endedAutoLockRunnable, ENDED_AUTO_LOCK_TIMEOUT_MS)
            }
            !player.playWhenReady -> {
                autoLockHandler.postDelayed(pausedAutoLockRunnable, PAUSE_AUTO_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun triggerAutoLock() {
        if (isFinishing || isDestroyed) return

        autoLockHandler.removeCallbacks(pausedAutoLockRunnable)
        autoLockHandler.removeCallbacks(endedAutoLockRunnable)

        TVDiagnosticsApp.instance.preferences.lockVault()
        releasePlayer()

        val intent = Intent(this, DecoyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(DecoyActivity.EXTRA_AUTO_PROMPT_PIN, true)
        }
        startActivity(intent)
        finish()
    }

    private fun releasePlayer() {
        progressSyncJob?.cancel()
        syncProgress()
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        autoLockHandler.removeCallbacks(pausedAutoLockRunnable)
        autoLockHandler.removeCallbacks(endedAutoLockRunnable)
        exoPlayer?.pause()
        syncProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoLockHandler.removeCallbacks(pausedAutoLockRunnable)
        autoLockHandler.removeCallbacks(endedAutoLockRunnable)
        releasePlayer()
        overlayHideHandler.removeCallbacks(hideOverlayRunnable)
    }

    companion object {
        const val EXTRA_VIDEO_ITEM = "extra_video_item"
        const val EXTRA_START_POSITION_MS = "extra_start_position_ms"
        private const val PAUSE_AUTO_LOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
        private const val ENDED_AUTO_LOCK_TIMEOUT_MS = 5 * 60 * 1000L   // 5 minutes
    }
}
