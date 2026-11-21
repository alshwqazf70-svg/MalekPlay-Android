package com.malekplay.player.effects

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
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
        assertEquals(true, VideoEnhancementProfile.ECONOMY.contrastBoost > 0f)
        assertEquals(true, VideoEnhancementProfile.BALANCED.clarityStrength > VideoEnhancementProfile.ECONOMY.clarityStrength)
        assertEquals(true, VideoEnhancementProfile.ULTRA.contrastBoost > VideoEnhancementProfile.BALANCED.contrastBoost)
    }

    @Test fun economyBalancedAndUltraHaveDistinctIncreasingDetailProfiles() {
        val economy = VideoEnhancementProfile.ECONOMY
        val balanced = VideoEnhancementProfile.BALANCED
        val ultra = VideoEnhancementProfile.ULTRA

        assertTrue(economy.detailStrength < balanced.detailStrength)
        assertTrue(balanced.detailStrength < ultra.detailStrength)
        assertTrue(economy.clarityStrength < balanced.clarityStrength)
        assertTrue(balanced.clarityStrength < ultra.clarityStrength)
        assertTrue(economy.contrastBoost < balanced.contrastBoost)
        assertTrue(balanced.contrastBoost < ultra.contrastBoost)
        assertTrue(economy.denoiseStrength < balanced.denoiseStrength)
        assertTrue(balanced.denoiseStrength < ultra.denoiseStrength)
    }

    @Test fun veryLowResolution128pVideoUpscalesWithoutExceedingBalancedPixelBudget() {
        val target = VideoUpscaleTargetPolicy.resolve(
            sourceWidth = 224,
            sourceHeight = 128,
            displayWidth = 2960,
            displayHeight = 1440,
            maxLongEdge = VideoEnhancementProfile.BALANCED.upscaleMaxLongEdge,
            maxPixels = VideoEnhancementProfile.BALANCED.upscaleMaxPixels
        )
        val actual = requireNotNull(target)
        assertTrue(actual.longEdge > 224)
        assertTrue(actual.shortEdge > 128)
        assertTrue(actual.longEdge.toLong() * actual.shortEdge <= 2_500_000L)
        assertEquals(7f / 4f, actual.longEdge.toFloat() / actual.shortEdge, 0.01f)
    }

    @Test fun lowResolutionVideoUpscalesToCappedFullHdTarget() {
        val target = VideoUpscaleTargetPolicy.resolve(640, 360, 2960, 1440, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(1080, target?.shortEdge)
    }

    @Test fun fullHdSourceIsNotUpscaledBeyondItsExistingResolution() {
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(1920, 1080, 2960, 1440, 1920))
    }

    @Test fun fourByThreeSourceUsesDisplayBudgetWithoutChangingSourceAspectRatio() {
        val target = VideoUpscaleTargetPolicy.resolve(640, 480, 2400, 1080, 1920)
        assertEquals(1440, target?.longEdge)
        assertEquals(1080, target?.shortEdge)
    }

    @Test fun portraitSourceAndPortraitDisplayKeepFlexibleOrientationTarget() {
        val target = VideoUpscaleTargetPolicy.resolve(360, 640, 1080, 2400, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(1080, target?.shortEdge)
    }

    @Test fun ultrawideLowResolutionSourceRespectsTextureBudget() {
        val target = VideoUpscaleTargetPolicy.resolve(1280, 540, 2960, 1440, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(810, target?.shortEdge)
    }

    @Test fun sixteenByNineSourceFillsDisplayBoxWithoutChangingAspectRatio() {
        val target = VideoUpscaleTargetPolicy.resolve(640, 360, 2960, 1440, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(1080, target?.shortEdge)
    }

    @Test fun sourceAspectRatioIsPreservedWhenDisplayIsMuchWiderThanFourByThreeVideo() {
        val target = VideoUpscaleTargetPolicy.resolve(640, 480, 2960, 1440, 1920)
        assertEquals(1920, target?.longEdge)
        assertEquals(1440, target?.shortEdge)
        assertEquals(4f / 3f, target!!.longEdge.toFloat() / target.shortEdge, 0.001f)
    }

    @Test fun highResolutionSourceIsNeverUpscaledOrForcedIntoSmallerTextureTarget() {
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(3840, 2160, 2960, 1440, 1920))
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(7680, 4320, 2960, 1440, 1920))
    }

    @Test fun invalidVideoOrDisplaySizeDisablesUpscalingSafely() {
        assertEquals(null, VideoUpscaleTargetPolicy.resolve(0, 360, 2960, 1440, 1920))
    }

    @Test fun gpuPipelineUsesPassthroughUntilAStageIsEnabledAndAcceptsLiveConfigUpdates() {
        val passthrough = VideoEnhancementPipelineConfig()
        assertFalse(passthrough.hasActiveStage)

        val active = VideoEnhancementPipelineConfig(
            denoiseEnabled = true,
            detailEnabled = true,
            aiBoostEnabled = true,
            denoiseStrength = 0.38f,
            detailStrength = 0.78f,
            contrastBoost = 0.12f,
            clarityStrength = 0.16f
        )
        assertTrue(active.hasActiveStage)

        val effect = VideoEnhancementPipelineEffect()
        effect.update(active)
        assertEquals(active, effect.currentConfig())
    }

    @Test fun disabledPipelineIgnoresStaleProfileStrengthsAndRemainsPassthrough() {
        val disabled = VideoEnhancementPipelineConfig(
            denoiseEnabled = false,
            detailEnabled = false,
            aiBoostEnabled = false,
            denoiseStrength = 0.50f,
            detailStrength = 1.02f,
            contrastBoost = 0.16f,
            clarityStrength = 0.22f
        )
        assertFalse(disabled.hasActiveStage)

        val effect = VideoEnhancementPipelineEffect(disabled)
        assertFalse(effect.currentConfig().hasActiveStage)
        effect.update(disabled.copy(aiBoostEnabled = true))
        assertTrue(effect.currentConfig().hasActiveStage)
    }

    @Test fun pipelineDiagnosticsCountOnePhysicalDrawAndItsActiveStages() {
        VideoEnhancementMetrics.reset()
        VideoEnhancementMetrics.recordPipelineProcessed(
            denoise = true,
            detail = true,
            presentationTimeUs = 42_000L
        )
        val metrics = VideoEnhancementMetrics.snapshot()
        assertEquals(1L, metrics.processedDraws)
        assertEquals(1L, metrics.denoiseDraws)
        assertEquals(1L, metrics.detailDraws)
        assertEquals(42_000L, metrics.lastPresentationTimeUs)
        VideoEnhancementMetrics.reset()
    }

    @Test fun shaderAndBridgeFailuresExposeTheExactDiagnosticStageAndCause() {
        VideoEnhancementMetrics.reset()
        VideoEnhancementMetrics.recordFailure(
            "shader_compile",
            IllegalStateException("GLSL compile failed at line 42")
        )

        val metrics = VideoEnhancementMetrics.snapshot()
        assertEquals("shader_compile", metrics.lastEffectName)
        assertTrue(metrics.lastFailure.orEmpty().contains("GLSL compile failed at line 42"))
        VideoEnhancementMetrics.reset()
    }

    @Test fun lowResolution720pAnd1080pSourcesKeepSpatialStagesEnabledAndCanUpscale() {
        val config = VideoEnhancementPipelineConfig(
            denoiseEnabled = true,
            detailEnabled = true,
            aiBoostEnabled = false,
            denoiseStrength = VideoEnhancementProfile.BALANCED.denoiseStrength,
            detailStrength = VideoEnhancementProfile.BALANCED.detailStrength,
            contrastBoost = VideoEnhancementProfile.BALANCED.contrastBoost,
            clarityStrength = VideoEnhancementProfile.BALANCED.clarityStrength
        )
        assertTrue(config.hasActiveStage)

        val target720p = VideoUpscaleTargetPolicy.resolve(1280, 720, 2960, 1440, 2560)
        val target1080p = VideoUpscaleTargetPolicy.resolve(1920, 1080, 2960, 1440, 2560)
        assertEquals(VideoUpscaleTarget(2108, 1185), target720p)
        assertEquals(VideoUpscaleTarget(2105, 1184), target1080p)
    }

    @Test fun profileUpscaleBudgetsMatchSupportedDisplayClasses() {
        assertEquals(1920, VideoEnhancementProfile.ECONOMY.upscaleMaxLongEdge)
        assertEquals(2560, VideoEnhancementProfile.BALANCED.upscaleMaxLongEdge)
        assertEquals(2960, VideoEnhancementProfile.ULTRA.upscaleMaxLongEdge)
        assertEquals(1_500_000, VideoEnhancementProfile.ECONOMY.upscaleMaxPixels)
        assertEquals(2_500_000, VideoEnhancementProfile.BALANCED.upscaleMaxPixels)
        assertEquals(3_500_000, VideoEnhancementProfile.ULTRA.upscaleMaxPixels)
    }

    @Test fun balancedAndUltraCanUseNote9ClassDisplayTargetsForLowResolutionVideo() {
        val balanced = VideoUpscaleTargetPolicy.resolve(640, 360, 2960, 1440,
            VideoEnhancementProfile.BALANCED.upscaleMaxLongEdge)
        val ultra = VideoUpscaleTargetPolicy.resolve(640, 360, 2960, 1440,
            VideoEnhancementProfile.ULTRA.upscaleMaxLongEdge)
        assertEquals(VideoUpscaleTarget(2105, 1184), balanced)
        assertEquals(VideoUpscaleTarget(2494, 1403), ultra)
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
