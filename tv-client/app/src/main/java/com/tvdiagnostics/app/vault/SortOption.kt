package com.tvdiagnostics.app.vault

import com.tvdiagnostics.app.data.VideoItem

/** Library sort orders (the choice is remembered on the TV). */
enum class SortOption(val displayName: String) {
    NEWEST("Newest"),
    NAME("Name"),
    DURATION("Longest"),
    LEAST_WATCHED("Least watched");

    val comparator: Comparator<VideoItem>
        get() = when (this) {
            NEWEST -> newestFirst
            NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { v: VideoItem -> v.title.ifBlank { v.filename } }
            DURATION -> compareByDescending<VideoItem> { it.duration }.then(newestFirst)
            // Fewest plays by this user first, then the least of it seen, then newest
            LEAST_WATCHED -> compareBy<VideoItem> { it.playCount }
                .thenBy { if (it.completed != 0) 1.0 else if (it.duration > 0) it.watchedSeconds / it.duration else 0.0 }
                .then(newestFirst)
        }

    companion object {
        private val newestFirst = compareByDescending<VideoItem> { it.createdAt }.thenByDescending { it.id }

        fun fromName(name: String?): SortOption = values().firstOrNull { it.name == name } ?: NEWEST
    }
}
