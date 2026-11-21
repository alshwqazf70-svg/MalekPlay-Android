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
    private val programCreations = AtomicLong(0L)
    private val configureCalls = AtomicLong(0L)
    private val drawFrameCalls = AtomicLong(0L)
    private val drawFrameSuccesses = AtomicLong(0L)
    @Volatile private var lastConfiguredWidth = 0
    @Volatile private var lastConfiguredHeight = 0
    @Volatile private var lastPipelineStage = "not_started"

    fun recordProgramCreated() {
        programCreations.incrementAndGet()
        lastPipelineStage = "program_created"
    }

    fun recordConfigured(width: Int, height: Int) {
        lastConfiguredWidth = width
        lastConfiguredHeight = height
        configureCalls.incrementAndGet()
        lastPipelineStage = "configured"
    }

    fun recordDrawFrameEntered() {
        drawFrameCalls.incrementAndGet()
        lastPipelineStage = "draw_frame_entered"
    }

    fun recordDrawFrameSucceeded() {
        drawFrameSuccesses.incrementAndGet()
        lastPipelineStage = "draw_frame_succeeded"
    }

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

    /** Counts one physical shader draw while attributing its active logical stages. */
    fun recordPipelineProcessed(
        denoise: Boolean,
        detail: Boolean,
        presentationTimeUs: Long
    ) {
        lastEffectName = when {
            denoise && detail -> "enhancement_pipeline_denoise_detail"
            denoise -> "enhancement_pipeline_denoise"
            detail -> "enhancement_pipeline_detail"
            else -> "enhancement_pipeline"
        }
        lastPresentationTimeUs = presentationTimeUs
        processedDraws.incrementAndGet()
        if (denoise) incrementEffectCounter("spatial_denoise")
        if (detail) incrementEffectCounter("detail_sharpen")
    }

    private fun incrementEffectCounter(effectName: String) {
        val counter = drawsByEffect[effectName] ?: run {
            val candidate = AtomicLong(0L)
            drawsByEffect.putIfAbsent(effectName, candidate) ?: candidate
        }
        counter.incrementAndGet()
    }

    fun recordFailure(effectName: String, error: Throwable) {
        lastEffectName = effectName
        lastPipelineStage = "failed:$effectName"
        // Media3 often wraps the EGL/GL exception in VideoFrameProcessingException.
        // Keep the causal chain so the diagnostics show the actionable low-level cause.
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 4 && chain.none { it === current }) {
            chain += current
            current = current.cause
        }
        lastFailure = chain.joinToString(" <- ") { cause ->
            val message = cause.message.orEmpty()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .take(96)
            if (message.isBlank()) cause.javaClass.simpleName
            else "${cause.javaClass.simpleName}: $message"
        }.take(240)
    }

    fun snapshot(): Snapshot = Snapshot(
        processedDraws = processedDraws.get(),
        denoiseDraws = drawsByEffect["spatial_denoise"]?.get() ?: 0L,
        detailDraws = drawsByEffect["detail_sharpen"]?.get() ?: 0L,
        lastEffectName = lastEffectName,
        lastPresentationTimeUs = lastPresentationTimeUs,
        lastFailure = lastFailure,
        programCreations = programCreations.get(),
        configureCalls = configureCalls.get(),
        drawFrameCalls = drawFrameCalls.get(),
        drawFrameSuccesses = drawFrameSuccesses.get(),
        lastConfiguredWidth = lastConfiguredWidth,
        lastConfiguredHeight = lastConfiguredHeight,
        lastPipelineStage = lastPipelineStage
    )

    fun reset() {
        processedDraws.set(0L)
        drawsByEffect.clear()
        // Program objects can outlive a media-item transition; keep this process-lifetime count.
        lastEffectName = "none"
        lastPresentationTimeUs = -1L
        lastFailure = null
        configureCalls.set(0L)
        drawFrameCalls.set(0L)
        drawFrameSuccesses.set(0L)
        lastConfiguredWidth = 0
        lastConfiguredHeight = 0
        lastPipelineStage = "not_started"
    }

    data class Snapshot(
        val processedDraws: Long,
        val denoiseDraws: Long,
        val detailDraws: Long,
        val lastEffectName: String,
        val lastPresentationTimeUs: Long,
        val lastFailure: String?,
        val programCreations: Long,
        val configureCalls: Long,
        val drawFrameCalls: Long,
        val drawFrameSuccesses: Long,
        val lastConfiguredWidth: Int,
        val lastConfiguredHeight: Int,
        val lastPipelineStage: String
    )
}
