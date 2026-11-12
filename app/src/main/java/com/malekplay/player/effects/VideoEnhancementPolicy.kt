package com.malekplay.player.effects

/**
 * User-facing quality preset. Presets tune GPU work; they never change decoder
 * resolution or claim to reconstruct detail that is absent from the source.
 */
enum class VideoEnhancementProfile(
    val wireValue: String,
    val denoiseStrength: Float,
    val detailStrength: Float,
    val previousFrameWeight: Float
) {
    ECONOMY("economy", 0.16f, 0.34f, 0f),
    BALANCED("balanced", 0.28f, 0.62f, 0.035f),
    ULTRA("ultra", 0.40f, 0.88f, 0.05f);

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
