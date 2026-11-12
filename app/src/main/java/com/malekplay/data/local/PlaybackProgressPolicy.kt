package com.malekplay.data.local

/**
 * Avoids treating short clips as completed at position zero merely because
 * the normal end tolerance (two seconds) is longer than the media itself.
 */
internal fun isPlaybackCompleted(positionMs: Long, durationMs: Long): Boolean {
    val duration = durationMs.coerceAtLeast(0L)
    if (duration == 0L) return false
    val endToleranceMs = minOf(2_000L, duration / 10L)
    val completionThreshold = duration - endToleranceMs
    return positionMs.coerceAtLeast(0L) >= completionThreshold
}

/**
 * Play counts are monotonic and saturate instead of overflowing to a negative
 * value after extremely long-lived libraries or imported legacy records.
 */
internal fun nextPlaybackCount(currentCount: Int, markPlayed: Boolean): Int {
    val safeCount = currentCount.coerceAtLeast(0)
    if (!markPlayed || safeCount == Int.MAX_VALUE) return safeCount
    return safeCount + 1
}
