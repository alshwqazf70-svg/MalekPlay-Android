package com.malekplay.player.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEnhancementPolicyTest {
    @Test fun legacyAndroidIsCappedAtEconomy() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 23, false, 512
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
        assertEquals("legacy_android", result.downgradeReason)
    }

    @Test fun lowRamDeviceIsCappedAtEconomy() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.BALANCED, 30, true, 512
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
    }

    @Test fun moderateThermalStateCapsUltraAtBalanced() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 35, false, 512, 2
        )
        assertEquals(VideoEnhancementProfile.BALANCED, result.effective)
        assertEquals("thermal_moderate", result.downgradeReason)
    }

    @Test fun androidSevereThermalStatusThreeCapsBalancedAtEconomy() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.BALANCED, 35, false, 512, thermalStatus = 3
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
        assertEquals("thermal_severe", result.downgradeReason)
    }

    @Test fun androidCriticalAndEmergencyThermalStatusesRemainEconomy() {
        listOf(4, 5, 6).forEach { status ->
            val result = VideoEnhancementPolicy.resolve(
                VideoEnhancementProfile.ULTRA, 35, false, 512, thermalStatus = status
            )
            assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
            assertEquals("thermal_severe", result.downgradeReason)
        }
    }

    @Test fun severeThermalStateUsesEconomy() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 35, false, 512, 4
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
    }

    @Test fun capableCoolDeviceKeepsRequestedProfile() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 35, false, 512, 0
        )
        assertEquals(VideoEnhancementProfile.ULTRA, result.effective)
        assertEquals(null, result.downgradeReason)
    }

    @Test fun android10CapableDeviceKeepsBalancedEnhancementProfile() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.BALANCED,
            sdkInt = 29,
            isLowRamDevice = false,
            memoryClassMb = 512,
            thermalStatus = 0
        )
        assertEquals(VideoEnhancementProfile.BALANCED, result.effective)
        assertEquals(null, result.downgradeReason)
    }

    @Test fun enhancementProfilesUseVisibleFilterStrengthWithoutEnablingFakeInterpolation() {
        assertEquals(0f, VideoEnhancementProfile.ECONOMY.previousFrameWeight)
        assertEquals(0f, VideoEnhancementProfile.BALANCED.previousFrameWeight)
        assertEquals(0f, VideoEnhancementProfile.ULTRA.previousFrameWeight)
        assertEquals(true, VideoEnhancementProfile.BALANCED.denoiseStrength > 0.30f)
        assertEquals(true, VideoEnhancementProfile.BALANCED.detailStrength > 0.70f)
    }

    @Test fun lowResolutionVideoUpscalesToCappedFullHdTarget() {
        val target = VideoUpscaleTargetPolicy.resolve(640, 360, 2960, 1440, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(933, target?.shortEdge)
    }

    @Test fun fullHdSourceIsNotUpscaledBeyondItsExistingResolution() {
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(1920, 1080, 2960, 1440, 1920))
    }

    @Test fun invalidVideoOrDisplaySizeDisablesUpscalingSafely() {
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(0, 360, 2960, 1440, 1920))
    }

    @Test fun invalidWireValueFallsBackToBalanced() {
        assertEquals(VideoEnhancementProfile.BALANCED, VideoEnhancementProfile.fromWireValue("invalid"))
    }
    @Test fun droppedFramesCapUltraAtBalanced() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 35, false, 512,
            thermalStatus = 0,
            performanceCap = VideoEnhancementProfile.BALANCED
        )
        assertEquals(VideoEnhancementProfile.BALANCED, result.effective)
        assertEquals("dropped_frames", result.downgradeReason)
    }

    @Test fun severeThermalLimitIsNotOverriddenByPerformanceCap() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ULTRA, 35, false, 512,
            thermalStatus = 4,
            performanceCap = VideoEnhancementProfile.BALANCED
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
        assertEquals("thermal_severe", result.downgradeReason)
    }

    @Test fun droppedFrameCapNeverRaisesAnEconomyProfile() {
        val result = VideoEnhancementPolicy.resolve(
            VideoEnhancementProfile.ECONOMY, 35, false, 512,
            thermalStatus = 0,
            performanceCap = VideoEnhancementProfile.ULTRA
        )
        assertEquals(VideoEnhancementProfile.ECONOMY, result.effective)
    }

}
