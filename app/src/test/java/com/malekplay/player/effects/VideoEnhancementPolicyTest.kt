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
