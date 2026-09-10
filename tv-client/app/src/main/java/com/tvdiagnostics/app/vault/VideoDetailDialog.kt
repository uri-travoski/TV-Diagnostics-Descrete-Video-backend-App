package com.tvdiagnostics.app.vault

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem
import com.tvdiagnostics.app.player.PlayerActivity
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
        setContentView(R.layout.dialog_video_detail)

        window?.setBackgroundDrawableResource(android.R.color.transparent)

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
            btnResume.requestFocus()
        } else {
            tvProgress.visibility = View.GONE
            btnResume.visibility = View.GONE
            btnPlayStart.requestFocus()
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
                    onProgressReset()
                    Toast.makeText(context, "Playback position reset", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
