package com.malekplay.player

/** Central guards for values crossing the player/UI/settings boundary. */
internal object PlaybackValuePolicy {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f

    fun normalizeSpeed(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_SPEED, MAX_SPEED) else 1f

    fun addSeekDelta(positionMs: Long, deltaMs: Long): Long {
        val safePosition = positionMs.coerceAtLeast(0L)
        return try {
            Math.addExact(safePosition, deltaMs).coerceAtLeast(0L)
        } catch (_: ArithmeticException) {
            if (deltaMs > 0L) Long.MAX_VALUE else 0L
        }
    }
}
