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
    /** Frame interpolation remains disabled for every preset; no synthetic frames are generated. */
    val previousFrameWeight: Float = 0f
) {
    ECONOMY("economy", 0.24f, 0.48f, 0.07f, 0.08f, 1920),
    BALANCED("balanced", 0.38f, 0.78f, 0.12f, 0.16f, 2560),
    ULTRA("ultra", 0.50f, 1.02f, 0.16f, 0.22f, 2960);

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
        maxLongEdge: Int
    ): VideoUpscaleTarget? {
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0 || maxLongEdge <= 0
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
            maxLongEdge.toDouble() / sourceLong
        )
        if (!scale.isFinite() || scale <= 1.0) return null

        val targetLong = (sourceLong * scale).toInt().coerceAtLeast(1)
        val targetShort = (sourceShort * scale).toInt().coerceAtLeast(1)
        if (targetLong <= sourceLong || targetShort <= sourceShort) return null
        return VideoUpscaleTarget(targetLong, targetShort)
    }
}
