package com.malekplay.ui.library

/** Minimal immutable input used to count unseen arrivals without UI or database dependencies. */
internal data class NewMediaRecord(
    val folderPath: String,
    val mediaId: String,
    val addedAtMs: Long
)

/**
 * Counts only items added after the first-library baseline and not yet played.
 * A single pass keeps recomposition cost linear even for large media libraries.
 */
internal fun unseenNewMediaCountsByFolder(
    records: Sequence<NewMediaRecord>,
    baselineMs: Long,
    seenIds: Set<String>
): Map<String, Int> {
    if (baselineMs <= 0L) return emptyMap()
    val counts = HashMap<String, Int>()
    records.forEach { item ->
        if (item.addedAtMs >= baselineMs && item.mediaId !in seenIds) {
            counts[item.folderPath] = (counts[item.folderPath] ?: 0) + 1
        }
    }
    return counts
}
