package com.malekplay.player.effects

/**
 * User-facing quality preset. Presets tune GPU work; they never change decoder
 * resolution or claim to reconstruct detail that is absent from the source.
 */
enum class VideoEnhancementProfile(
    val wireValue: String,
    val denoiseStrength: Float,
    val detailStrength: Float,
    /** Frame interpolation remains disabled for every preset; no synthetic frames are generated. */
    val previousFrameWeight: Float = 0f
) {
    ECONOMY("economy", 0.24f, 0.48f),
    BALANCED("balanced", 0.38f, 0.78f),
    ULTRA("ultra", 0.50f, 1.02f);

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
 * SEVERE=4, CRITICAL=5, EMERGENCY=6, SHUTDOWN=7).
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
        if (thermalStatus != null && thermalStatus >= 4 &&
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

        val displayLong = maxOf(displayWidth, displayHeight)
        val displayShort = minOf(displayWidth, displayHeight)
        val scale = minOf(1f, maxLongEdge.toFloat() / displayLong.toFloat())
        val targetLong = (displayLong * scale).toInt().coerceAtLeast(1)
        val targetShort = (displayShort * scale).toInt().coerceAtLeast(1)
        val sourceLong = maxOf(sourceWidth, sourceHeight)
        val sourceShort = minOf(sourceWidth, sourceHeight)
        if (sourceLong >= targetLong || sourceShort >= targetShort) return null
        return VideoUpscaleTarget(targetLong, targetShort)
    }
}
