package com.tvdiagnostics.app.data

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class VideoItem(
    val id: Int,
    val filepath: String,
    val filename: String,
    val title: String,
    val filesize: Long = 0,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    @SerializedName("video_codec") val videoCodec: String = "",
    @SerializedName("audio_codec") val audioCodec: String = "",
    @SerializedName("audio_channels") val audioChannels: Int = 2,
    @SerializedName("thumbnail_path") val thumbnailPath: String = "",
    val rating: Int = 0, // 0: ○, 1: ●, 2: ●●
    val notes: String = "",
    val tags: String = "",
    @SerializedName("watched_seconds") var watchedSeconds: Double = 0.0,
    var completed: Int = 0,
    @SerializedName("last_watched_at") val lastWatchedAt: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    /** Library folder + subfolders, e.g. "both/holiday" */
    val folder: String = "",
    /** Times this user started the video */
    @SerializedName("play_count") val playCount: Int = 0
) : Serializable {

    fun getCircleRatingSymbol(): String {
        return when (rating) {
            1 -> "●"
            2 -> "●●"
            else -> "○"
        }
    }

    fun getTagList(): List<String> {
        if (tags.isBlank()) return emptyList()
        return tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getFormattedDuration(): String {
        return formatTime(duration)
    }

    fun getFormattedWatchedTime(): String {
        return formatTime(watchedSeconds)
    }

    /** Storyboard frames available for the grid preview (one every 7 seconds). */
    fun getPreviewFrameCount(): Int {
        if (duration <= 0.0) return 0
        return ((duration - 0.5) / 7.0).toInt().coerceAtLeast(0)
    }

    fun getProgressPercentage(): Int {
        if (duration <= 0.0) return 0
        return ((watchedSeconds / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun getResolutionBadge(): String {
        return when {
            height >= 2160 -> "4K UHD"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height > 0 -> "${height}p"
            else -> "SD"
        }
    }

    private fun formatTime(seconds: Double): String {
        if (seconds <= 0.0) return "00:00"
        val totalSec = seconds.toLong()
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }
}

data class TagItem(
    val tag: String = "",
    val count: Int = 0
) : Serializable

data class PinVerifyRequest(
    val pin: String,
    val device: String? = null
)

data class PinVerifyResponse(
    val valid: Boolean,
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("user_name") val userName: String? = null,
    val token: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    /** Wrong code: tries left before this TV is locked out */
    @SerializedName("attempts_left") val attemptsLeft: Int? = null,
    /** Too many wrong codes: code entry refused for this many seconds */
    @SerializedName("locked_seconds") val lockedSeconds: Int? = null
)

data class ChangePinRequest(
    @SerializedName("new_pin") val newPin: String
)

data class ChangePinResponse(
    val success: Boolean = false,
    val token: String? = null
)

data class ErrorResponse(
    val detail: String? = null
)

data class ProgressUpdateRequest(
    @SerializedName("watched_seconds") val watchedSeconds: Double,
    val completed: Boolean? = null
)

data class ResetProgressResponse(
    val success: Boolean,
    @SerializedName("video_id") val videoId: Int? = null,
    @SerializedName("watched_seconds") val watchedSeconds: Double = 0.0
)

data class ServerStatus(
    val status: String,
    val version: String? = null
)

data class ActivityEvent(
    @SerializedName("session_id") val sessionId: String,
    val event: String,
    @SerializedName("video_id") val videoId: Int? = null,
    val position: Double? = null,
    @SerializedName("watched_seconds") val watchedSeconds: Double? = null,
    val detail: String = ""
)
