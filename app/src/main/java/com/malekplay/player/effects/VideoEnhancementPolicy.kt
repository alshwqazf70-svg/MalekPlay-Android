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
        thermalStatus: Int? = null
    ): VideoEnhancementTuning {
        val economyOnly = sdkInt < 26 || isLowRamDevice || memoryClassMb < 256
        if (economyOnly && requested != VideoEnhancementProfile.ECONOMY) {
            val reason = when {
                sdkInt < 26 -> "legacy_android"
                isLowRamDevice -> "low_ram_device"
                else -> "limited_memory"
            }
            return VideoEnhancementTuning(requested, VideoEnhancementProfile.ECONOMY, reason)
        }

        // Moderate heat caps GPU work at Balanced; severe or worse uses Economy.
        if (thermalStatus != null && thermalStatus >= 4 &&
            requested != VideoEnhancementProfile.ECONOMY
        ) {
            return VideoEnhancementTuning(requested, VideoEnhancementProfile.ECONOMY, "thermal_severe")
        }
        if (thermalStatus != null && thermalStatus >= 2 &&
            requested == VideoEnhancementProfile.ULTRA
        ) {
            return VideoEnhancementTuning(requested, VideoEnhancementProfile.BALANCED, "thermal_moderate")
        }
        return VideoEnhancementTuning(requested, requested)
    }
}
