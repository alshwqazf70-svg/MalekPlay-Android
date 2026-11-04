package com.malekplay.player

/** Central guards for values crossing the player/UI/settings boundary. */
internal object PlaybackValuePolicy {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f
    fun normalizeSpeed(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_SPEED, MAX_SPEED) else 1f
    fun addSeekDelta(positionMs: Long, deltaMs: Long): Long = when {
        deltaMs > 0L && positionMs > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && positionMs < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
        else -> positionMs + deltaMs
    }.coerceAtLeast(0L)
}
