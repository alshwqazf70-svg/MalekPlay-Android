package com.malekplay.data.repository

internal data class PlaybackProgress(
    val positionMs: Long,
    val completed: Boolean
)

/**
 * Treat short media proportionally: a fixed two-second completion window would mark
 * a one-second clip completed even at position zero.
 */
internal object PlaybackProgressPolicy {
    fun evaluate(positionMs: Long, durationMs: Long): PlaybackProgress {
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        val completionWindow = minOf(2_000L, duration / 10L)
        val completed = duration > 0L && position >= duration - completionWindow
        return PlaybackProgress(position, completed)
    }
}
