package com.malekplay.player.effects

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts successful GPU shader draws, not just effect-list submission.
 * A count advances only after the draw and GL error check both complete.
 */
object VideoEnhancementMetrics {
    private val processedDraws = AtomicLong(0L)

    @Volatile private var lastEffectName: String = "none"
    @Volatile private var lastPresentationTimeUs: Long = -1L
    @Volatile private var lastFailure: String? = null

    fun recordProcessed(effectName: String, presentationTimeUs: Long) {
        lastEffectName = effectName
        lastPresentationTimeUs = presentationTimeUs
        lastFailure = null
        processedDraws.incrementAndGet()
    }

    fun recordFailure(effectName: String, error: Throwable) {
        lastEffectName = effectName
        lastFailure = error.javaClass.simpleName
    }

    fun snapshot(): Snapshot = Snapshot(
        processedDraws = processedDraws.get(),
        lastEffectName = lastEffectName,
        lastPresentationTimeUs = lastPresentationTimeUs,
        lastFailure = lastFailure
    )

    fun reset() {
        processedDraws.set(0L)
        lastEffectName = "none"
        lastPresentationTimeUs = -1L
        lastFailure = null
    }

    data class Snapshot(
        val processedDraws: Long,
        val lastEffectName: String,
        val lastPresentationTimeUs: Long,
        val lastFailure: String?
    )
}
