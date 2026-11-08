package com.malekplay.ui.player.video

/**
 * Small, allocation-free policy for pinch zoom. The player view itself performs
 * the scaling; this helper only bounds user input and never processes frames.
 */
internal object PlayerZoomPolicy {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 3f

    fun scaleBy(currentScale: Float, scaleFactor: Float): Float {
        if (!currentScale.isFinite() || !scaleFactor.isFinite() || scaleFactor <= 0f) {
            return currentScale.takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: MIN_SCALE
        }
        return (currentScale.coerceIn(MIN_SCALE, MAX_SCALE) * scaleFactor)
            .coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
