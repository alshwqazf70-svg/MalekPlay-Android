package com.malekplay.player.audio

import kotlin.math.roundToInt

/**
 * Resamples preset gains to the actual number of bands exposed by the device.
 *
 * Android devices commonly expose different equalizer band counts. Linear
 * interpolation preserves the preset's shape and endpoints instead of
 * repeating/skipping values when the hardware has more or fewer bands.
 */
internal object EqualizerBandMapper {

    fun map(source: IntArray, requestedBandCount: Int): List<Int> {
        val count = requestedBandCount.coerceIn(0, AudioEffectsController.MAX_BANDS)
        if (count == 0) return emptyList()
        if (source.isEmpty()) return List(count) { 0 }
        if (source.size == 1 || count == 1) {
            val value = if (count == 1 && source.size > 1) {
                source[source.lastIndex / 2]
            } else {
                source[0]
            }
            return List(count) { value }
        }

        val sourceLast = source.lastIndex
        val targetLast = count - 1
        return List(count) { index ->
            val scaledPosition = index.toLong() * sourceLast
            val lowerIndex = (scaledPosition / targetLast).toInt()
            val remainder = (scaledPosition % targetLast).toInt()
            if (remainder == 0 || lowerIndex >= sourceLast) {
                source[lowerIndex.coerceAtMost(sourceLast)]
            } else {
                val lower = source[lowerIndex]
                val upper = source[lowerIndex + 1]
                (lower + (upper - lower) * (remainder.toFloat() / targetLast))
                    .roundToInt()
            }
        }
    }
}
