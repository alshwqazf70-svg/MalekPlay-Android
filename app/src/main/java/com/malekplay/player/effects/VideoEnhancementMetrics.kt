package com.malekplay.player.effects

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts successful custom GPU shader draws. Media3 does not expose an internal
 * Lanczos draw counter, so Lanczos is reported as pipeline submission separately.
 */
object VideoEnhancementMetrics {
    private val processedDraws = AtomicLong(0L)
    private val drawsByEffect = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var lastEffectName = "none"
    @Volatile private var lastPresentationTimeUs = -1L
    @Volatile private var lastFailure: String? = null

    fun recordProcessed(effectName: String, presentationTimeUs: Long) {
        lastEffectName = effectName
        lastPresentationTimeUs = presentationTimeUs
        // Preserve the most recent failure until an explicit reset. A later
        // successful frame proves recovery, but must not erase evidence of an
        // intermittent shader failure from the diagnostics snapshot.
        processedDraws.incrementAndGet()
        val counter = drawsByEffect[effectName] ?: run {
            val candidate = AtomicLong(0L)
            drawsByEffect.putIfAbsent(effectName, candidate) ?: candidate
        }
        counter.incrementAndGet()
    }

    fun recordFailure(effectName: String, error: Throwable) {
        lastEffectName = effectName
        lastFailure = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
    }

    fun snapshot(): Snapshot = Snapshot(
        processedDraws = processedDraws.get(),
        denoiseDraws = drawsByEffect["spatial_denoise"]?.get() ?: 0L,
        detailDraws = drawsByEffect["detail_sharpen"]?.get() ?: 0L,
        lastEffectName = lastEffectName,
        lastPresentationTimeUs = lastPresentationTimeUs,
        lastFailure = lastFailure
    )

    fun reset() {
        processedDraws.set(0L)
        drawsByEffect.clear()
        lastEffectName = "none"
        lastPresentationTimeUs = -1L
        lastFailure = null
    }

    data class Snapshot(
        val processedDraws: Long,
        val denoiseDraws: Long,
        val detailDraws: Long,
        val lastEffectName: String,
        val lastPresentationTimeUs: Long,
        val lastFailure: String?
    )
}
