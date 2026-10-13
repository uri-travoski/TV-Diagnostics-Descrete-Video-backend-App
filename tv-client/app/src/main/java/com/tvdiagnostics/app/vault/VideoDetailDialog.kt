package com.tvdiagnostics.app.vault

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.security.VaultLock
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem
import com.tvdiagnostics.app.player.PlayerActivity
import com.tvdiagnostics.app.ui.UiMode
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VideoDetailDialog(
    context: Context,
    private val video: VideoItem,
    private val onProgressReset: () -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(UiMode.layout(R.layout.dialog_video_detail, R.layout.dialog_video_detail_mobile))

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        VaultLock.trackInput(window)
        if (UiMode.isMobile) setupBottomSheet()

        val tvTitle = findViewById<TextView>(R.id.tvDetailTitle)
        val tvRating = findViewById<TextView>(R.id.tvDetailRating)
        val tvDuration = findViewById<TextView>(R.id.tvDetailDuration)
        val tvResolution = findViewById<TextView>(R.id.tvDetailResolution)
        val tvProgress = findViewById<TextView>(R.id.tvDetailProgressInfo)

        val tvTagsHeader = findViewById<TextView>(R.id.tvTagsHeader)
        val tvTags = findViewById<TextView>(R.id.tvDetailTags)

        val tvNotesHeader = findViewById<TextView>(R.id.tvNotesHeader)
        val tvNotes = findViewById<TextView>(R.id.tvDetailNotes)

        val btnResume = findViewById<Button>(R.id.btnResume)
        val btnPlayStart = findViewById<Button>(R.id.btnPlayStart)
        val btnResetProgress = findViewById<Button>(R.id.btnResetVideoProgress)

        tvTitle.text = video.title
        tvRating.text = video.getCircleRatingSymbol()
        if (video.rating > 0) {
            tvRating.setTextColor(ContextCompat.getColor(context, R.color.circle_filled))
        } else {
            tvRating.setTextColor(ContextCompat.getColor(context, R.color.circle_empty))
        }

        tvDuration.text = "Duration: ${video.getFormattedDuration()}"
        tvResolution.text = "${video.getResolutionBadge()} (${video.videoCodec} / ${video.audioCodec})"

        if (video.watchedSeconds > 0) {
            tvProgress.visibility = View.VISIBLE
            tvProgress.text = "Watched: ${video.getFormattedWatchedTime()} (${video.getProgressPercentage()}%)"
            btnResume.visibility = View.VISIBLE
            btnResume.text = context.getString(R.string.btn_resume, video.getFormattedWatchedTime())
            btnResume.post { btnResume.requestFocus() }
        } else {
            tvProgress.visibility = View.GONE
            btnResume.visibility = View.GONE
            btnPlayStart.post { btnPlayStart.requestFocus() }
        }

        // Tags Section
        val tags = video.getTagList()
        if (tags.isNotEmpty()) {
            tvTagsHeader.visibility = View.VISIBLE
            tvTags.visibility = View.VISIBLE
            tvTags.text = tags.joinToString("  ") { "#$it" }
        } else {
            tvTagsHeader.visibility = View.GONE
            tvTags.visibility = View.GONE
        }

        // Notes Section: Only show if unlocked via 4-digit PIN in settings
        val isNotesUnlocked = TVDiagnosticsApp.instance.preferences.isNotesEnabled
        if (isNotesUnlocked) {
            tvNotesHeader.visibility = View.VISIBLE
            tvNotes.visibility = View.VISIBLE
            if (video.notes.isNotBlank()) {
                tvNotes.text = video.notes
            } else {
                tvNotes.text = context.getString(R.string.no_notes)
            }
        } else {
            tvNotesHeader.visibility = View.GONE
            tvNotes.visibility = View.GONE
        }

        listOf(btnResetProgress, btnPlayStart, btnResume).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1.0f)
                    .scaleY(if (hasFocus) 1.08f else 1.0f)
                    .setDuration(120)
                    .start()
            }
        }

        if (UiMode.isMobile) styleMobileButtons(btnPlayStart, btnResume, btnResetProgress)

        btnResume.setOnClickListener {
            launchPlayer(startPositionMs = (video.watchedSeconds * 1000).toLong())
        }

        btnPlayStart.setOnClickListener {
            launchPlayer(startPositionMs = 0L)
        }

        btnResetProgress.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val success = ApiClient.resetProgress(video.id)
                if (success) {
                    video.watchedSeconds = 0.0
                    video.completed = 0
                    tvProgress.visibility = View.GONE
                    btnResume.visibility = View.GONE
                    btnPlayStart.requestFocus()
                    if (UiMode.isMobile) styleMobileButtons(btnPlayStart, btnResume, btnResetProgress)
                    onProgressReset()
                    Toast.makeText(context, "Playback position reset", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Phone: slides up from the bottom, full width, with the video's thumbnail on top. */
    private fun setupBottomSheet() {
        val metrics = context.resources.displayMetrics
        // Full width on a phone held upright; on a sideways phone or tablet, a centred sheet
        val sheetWidth = minOf(metrics.widthPixels, UiMode.dp(context, 560))
        window?.let { w ->
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.attributes = w.attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        // Long notes scroll inside the sheet; the play buttons always stay on screen
        val body = findViewById<View>(R.id.svDetailBody)
        body.post {
            val max = (metrics.heightPixels * 0.5).toInt()
            if (body.height > max) body.layoutParams = body.layoutParams.apply { height = max }
        }
        val thumbUrl = ApiClient.getThumbnailUrl(video.id)
        Glide.with(context)
            .load(object : GlideUrl(thumbUrl) {
                // Same cache entry as the library tile (the session token is left out of the key)
                override fun getCacheKey(): String = thumbUrl.substringBefore("?t=")
            })
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(findViewById<ImageView>(R.id.ivDetailThumb).apply {
                // 16:9 like the video, but never more than about a quarter of the screen height
                val fullWidth = (sheetWidth - UiMode.dp(context, 36)) * 9 / 16
                layoutParams = layoutParams.apply { height = minOf(fullWidth, (metrics.heightPixels * 0.28).toInt()) }
            })
    }

    /** Phone: the main action (Resume, else Play) is the blue button; reset only when there is progress. */
    private fun styleMobileButtons(btnPlayStart: Button, btnResume: Button, btnReset: Button) {
        val hasProgress = btnResume.visibility == View.VISIBLE
        btnPlayStart.setBackgroundResource(if (hasProgress) R.drawable.btn_tv_focus else R.drawable.btn_primary)
        btnPlayStart.text = if (hasProgress) "▶  Play from Start" else "▶  Play"
        btnReset.visibility = if (hasProgress) View.VISIBLE else View.GONE
    }

    private fun launchPlayer(startPositionMs: Long) {
        dismiss()
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_ITEM, video)
            putExtra(PlayerActivity.EXTRA_START_POSITION_MS, startPositionMs)
        }
        context.startActivity(intent)
    }
}
