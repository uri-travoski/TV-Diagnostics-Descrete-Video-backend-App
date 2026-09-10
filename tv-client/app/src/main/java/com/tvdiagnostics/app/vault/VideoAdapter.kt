package com.tvdiagnostics.app.vault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem

class VideoAdapter(
    private var items: List<VideoItem>,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    fun updateData(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position], onVideoClick)
    }

    override fun getItemCount(): Int = items.size

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvVideoTitle)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDurationBadge)
        private val tvResBadge: TextView = itemView.findViewById(R.id.tvResBadge)
        private val tvCircleRating: TextView = itemView.findViewById(R.id.tvCircleRating)
        private val pbProgress: ProgressBar = itemView.findViewById(R.id.pbWatchProgress)
        private val tvTagsSnippet: TextView = itemView.findViewById(R.id.tvTagsSnippet)
        private val tvNotesSnippet: TextView = itemView.findViewById(R.id.tvNotesSnippet)

        fun bind(item: VideoItem, onVideoClick: (VideoItem) -> Unit) {
            tvTitle.text = item.title
            tvDuration.text = item.getFormattedDuration()
            tvResBadge.text = item.getResolutionBadge()

            // Discreet Circle Rating: ○ (unrated), ● (level 1), ●● (level 2)
            tvCircleRating.text = item.getCircleRatingSymbol()
            val ratingColor = if (item.rating > 0) {
                ContextCompat.getColor(itemView.context, R.color.circle_filled)
            } else {
                ContextCompat.getColor(itemView.context, R.color.circle_empty)
            }
            tvCircleRating.setTextColor(ratingColor)

            // Progress bar
            val progress = item.getProgressPercentage()
            pbProgress.progress = progress
            pbProgress.visibility = if (progress > 0) View.VISIBLE else View.GONE

            // Tags snippet
            val tags = item.getTagList()
            if (tags.isNotEmpty()) {
                tvTagsSnippet.text = tags.joinToString(" ") { "#$it" }
                tvTagsSnippet.visibility = View.VISIBLE
            } else {
                tvTagsSnippet.visibility = View.GONE
            }

            // Notes snippet (ONLY shown when unlocked in settings via 4-digit PIN)
            val isNotesUnlocked = TVDiagnosticsApp.instance.preferences.isNotesEnabled
            if (isNotesUnlocked && item.notes.isNotBlank()) {
                tvNotesSnippet.text = item.notes
                tvNotesSnippet.visibility = View.VISIBLE
            } else {
                tvNotesSnippet.visibility = View.GONE
            }

            // Load generated 60-second thumbnail from backend
            Glide.with(itemView.context)
                .load(ApiClient.getThumbnailUrl(item.id))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .placeholder(R.color.card_dark)
                .into(ivThumbnail)

            itemView.setOnClickListener {
                onVideoClick(item)
            }
        }
    }
}
