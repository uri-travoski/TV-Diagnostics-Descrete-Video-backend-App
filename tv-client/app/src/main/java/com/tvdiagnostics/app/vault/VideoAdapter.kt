package com.tvdiagnostics.app.vault

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
import com.bumptech.glide.load.model.GlideUrl
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.VideoItem

class VideoAdapter(
    private var items: List<VideoItem>,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    init {
        // Stable ids keep D-pad focus on the same card when the list refreshes
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    fun updateData(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VideoViewHolder(view)
    }

    /** Set to give the first card D-pad focus as soon as it is laid out. */
    var focusFirstOnBind = false

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position], onVideoClick)
        if (position == 0 && focusFirstOnBind) {
            focusFirstOnBind = false
            holder.itemView.post { holder.itemView.findViewById<View>(R.id.cardFrame).requestFocus() }
        }
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        holder.stopPreview()
    }

    override fun getItemCount(): Int = items.size

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardFrame: View = itemView.findViewById(R.id.cardFrame)
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val ivPreview: ImageView = itemView.findViewById(R.id.ivPreview)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvVideoTitle)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDurationBadge)
        private val tvResBadge: TextView = itemView.findViewById(R.id.tvResBadge)
        private val tvCircleRating: TextView = itemView.findViewById(R.id.tvCircleRating)
        private val pbProgress: ProgressBar = itemView.findViewById(R.id.pbWatchProgress)
        private val tvTagsSnippet: TextView = itemView.findViewById(R.id.tvTagsSnippet)
        private val tvNotesSnippet: TextView = itemView.findViewById(R.id.tvNotesSnippet)

        private var item: VideoItem? = null

        // Storyboard preview: while a card has focus, flip through frames taken every 7 s of the video
        private val previewHandler = Handler(Looper.getMainLooper())
        private var previewIndex = 0
        private val previewStep = object : Runnable {
            override fun run() {
                if (!screenAlive()) return
                val video = item ?: return
                val count = video.getPreviewFrameCount()
                if (count == 0) return
                if (previewIndex >= count) previewIndex = 0
                loadFrame(video, previewIndex, ivPreview)
                preloadFrame(video, (previewIndex + 1) % count)
                previewIndex++
                previewHandler.postDelayed(this, PREVIEW_FRAME_MS)
            }
        }

        init {
            // The library can close (lock, HOME, BACK) while a card is previewing: stop with it
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) = stopPreview()
            })
        }

        /** Glide refuses loads for a destroyed activity (and throws): check before every load. */
        private fun screenAlive(): Boolean {
            val activity = itemView.context as? Activity ?: return true
            return !activity.isDestroyed && !activity.isFinishing
        }

        fun bind(item: VideoItem, onVideoClick: (VideoItem) -> Unit) {
            stopPreview()
            this.item = item

            tvTitle.text = item.title
            tvDuration.text = item.getFormattedDuration()
            tvResBadge.text = item.getResolutionBadge()

            // Discreet Circle Rating: ●● / ● shown, unrated (○) hidden to keep the tile clean
            tvCircleRating.text = item.getCircleRatingSymbol()
            tvCircleRating.visibility = if (item.rating > 0) View.VISIBLE else View.GONE
            tvCircleRating.setTextColor(ContextCompat.getColor(itemView.context, R.color.circle_filled))

            val progress = item.getProgressPercentage()
            pbProgress.progress = progress
            pbProgress.visibility = if (progress > 0) View.VISIBLE else View.INVISIBLE

            val tags = item.getTagList()
            if (tags.isNotEmpty()) {
                tvTagsSnippet.text = tags.joinToString("  ") { "#$it" }
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

            Glide.with(itemView.context)
                .load(cacheableUrl(ApiClient.getThumbnailUrl(item.id)))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .placeholder(R.color.card_dark)
                .into(ivThumbnail)

            cardFrame.setOnClickListener {
                stopPreview()
                onVideoClick(item)
            }

            cardFrame.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(if (hasFocus) 12f else 0f)
                    .setDuration(150)
                    .start()
                if (hasFocus) startPreview() else stopPreview()
            }
        }

        private fun startPreview() {
            if (!screenAlive()) return
            val video = item ?: return
            if (video.getPreviewFrameCount() == 0) return
            previewHandler.removeCallbacks(previewStep)
            previewIndex = 0
            preloadFrame(video, 0)
            // Short pause first so scrolling across the grid doesn't fire off frame requests
            previewHandler.postDelayed(previewStep, PREVIEW_START_DELAY_MS)
        }

        fun stopPreview() {
            previewHandler.removeCallbacks(previewStep)
            if (screenAlive()) Glide.with(itemView.context).clear(ivPreview)
            ivPreview.setImageDrawable(null)
        }

        private fun loadFrame(video: VideoItem, index: Int, target: ImageView) {
            Glide.with(itemView.context)
                .load(cacheableUrl(ApiClient.getPreviewFrameUrl(video.id, index)))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(FRAME_WIDTH, FRAME_HEIGHT)
                .centerCrop()
                .dontAnimate()
                .into(target)
        }

        private fun preloadFrame(video: VideoItem, index: Int) {
            Glide.with(itemView.context)
                .load(cacheableUrl(ApiClient.getPreviewFrameUrl(video.id, index)))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .preload(FRAME_WIDTH, FRAME_HEIGHT)
        }

        /** Image URL whose Glide cache key leaves out the session token, so caches survive relocking. */
        private fun cacheableUrl(url: String) = object : GlideUrl(url) {
            override fun getCacheKey(): String = url.substringBefore("?t=")
        }

        companion object {
            private const val PREVIEW_START_DELAY_MS = 600L
            private const val PREVIEW_FRAME_MS = 900L
            private const val FRAME_WIDTH = 480
            private const val FRAME_HEIGHT = 270
        }
    }
}
