package com.malekplay.player.effects

/**
 * User-facing quality preset. Presets tune GPU work; they never change decoder
 * resolution or claim to reconstruct detail that is absent from the source.
 */
enum class VideoEnhancementProfile(
    val wireValue: String,
    val denoiseStrength: Float,
    val detailStrength: Float,
    val contrastBoost: Float,
    val clarityStrength: Float,
    /** Long-edge GPU texture ceiling used only when Lanczos upscaling is requested. */
    val upscaleMaxLongEdge: Int,
    /** Maximum intermediate texture area, limiting GPU memory and fragment workload. */
    val upscaleMaxPixels: Int,
    /** Frame interpolation remains disabled for every preset; no synthetic frames are generated. */
    val previousFrameWeight: Float = 0f
) {
    // Presets have deliberately separated spatial-detail strengths while keeping
    // denoise and texture budgets conservative on mobile GPUs.
    ECONOMY("economy", 0.22f, 0.62f, 0.09f, 0.10f, 1920, 1_500_000),
    BALANCED("balanced", 0.34f, 0.90f, 0.14f, 0.19f, 2560, 2_500_000),
    ULTRA("ultra", 0.44f, 1.12f, 0.19f, 0.27f, 2960, 3_500_000);

    companion object {
        fun fromWireValue(value: String?): VideoEnhancementProfile =
            entries.firstOrNull { it.wireValue == value } ?: BALANCED
    }
}

data class VideoEnhancementTuning(
    val requested: VideoEnhancementProfile,
    val effective: VideoEnhancementProfile,
    val downgradeReason: String? = null
)

/**
 * Pure capability policy so legacy-device and thermal decisions are unit-testable.
 * thermalStatus uses Android PowerManager values (NONE=0, LIGHT=1, MODERATE=2,
 * SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6).
 */
object VideoEnhancementPolicy {
    /**
     * A pre-track enhancement request is safe to acknowledge only after the GPU
     * effect exists and the same player has an operational effect registry.
     */
    fun canAcceptPendingEnhancementRequest(
        pipelineCreated: Boolean,
        pipelineAttached: Boolean,
        registryOperational: Boolean
    ): Boolean = pipelineCreated && pipelineAttached && registryOperational

    fun resolve(
        requested: VideoEnhancementProfile,
        sdkInt: Int,
        isLowRamDevice: Boolean,
        memoryClassMb: Int,
        thermalStatus: Int? = null,
        performanceCap: VideoEnhancementProfile? = null
    ): VideoEnhancementTuning {
        var effective = requested
        var reason: String? = null

        val economyOnly = sdkInt < 26 || isLowRamDevice || memoryClassMb < 256
        if (economyOnly && effective != VideoEnhancementProfile.ECONOMY) {
            effective = VideoEnhancementProfile.ECONOMY
            reason = when {
                sdkInt < 26 -> "legacy_android"
                isLowRamDevice -> "low_ram_device"
                else -> "limited_memory"
            }
        }

        // Moderate heat caps Ultra at Balanced; severe heat caps all profiles at Economy.
        if (thermalStatus != null && thermalStatus >= 3 &&
            effective != VideoEnhancementProfile.ECONOMY
        ) {
            effective = VideoEnhancementProfile.ECONOMY
            reason = "thermal_severe"
        } else if (thermalStatus != null && thermalStatus >= 2 &&
            effective == VideoEnhancementProfile.ULTRA
        ) {
            effective = VideoEnhancementProfile.BALANCED
            reason = "thermal_moderate"
        }

        // A burst of dropped frames imposes a temporary ceiling for this playback item.
        if (performanceCap != null && performanceCap.ordinal < effective.ordinal) {
            effective = performanceCap
            reason = "dropped_frames"
        }
        return VideoEnhancementTuning(requested, effective, reason)
    }
}

/** Display-sized upscaling target with a strict GPU texture budget. */
data class VideoUpscaleTarget(val longEdge: Int, val shortEdge: Int)

object VideoUpscaleTargetPolicy {
    fun resolve(
        sourceWidth: Int,
        sourceHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        maxLongEdge: Int,
        maxPixels: Int = 4_194_304
    ): VideoUpscaleTarget? {
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0 || maxLongEdge <= 0 || maxPixels <= 0
        ) return null

        // Treat the display as a bounding box, not as the video's aspect ratio.
        // LanczosResample applies one uniform scale factor, so both target dimensions
        // must preserve the source aspect ratio or 4:3/ultrawide footage will distort.
        val displayLong = maxOf(displayWidth, displayHeight).toDouble()
        val displayShort = minOf(displayWidth, displayHeight).toDouble()
        val sourceLong = maxOf(sourceWidth, sourceHeight).toDouble()
        val sourceShort = minOf(sourceWidth, sourceHeight).toDouble()
        val scale = minOf(
            displayLong / sourceLong,
            displayShort / sourceShort,
            maxLongEdge.toDouble() / sourceLong,
            kotlin.math.sqrt(maxPixels.toDouble() / (sourceWidth.toDouble() * sourceHeight.toDouble()))
        )
        if (!scale.isFinite() || scale <= 1.0) return null

        val targetLong = (sourceLong * scale).toInt().coerceAtLeast(1)
        val targetShort = (sourceShort * scale).toInt().coerceAtLeast(1)
        if (targetLong <= sourceLong || targetShort <= sourceShort) return null
        return VideoUpscaleTarget(targetLong, targetShort)
    }
}
