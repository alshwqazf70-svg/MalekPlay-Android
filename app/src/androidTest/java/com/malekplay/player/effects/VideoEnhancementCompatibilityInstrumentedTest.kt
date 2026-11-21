package com.malekplay.player.effects

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-runtime compatibility checks for the enhancement capability policy.
 *
 * These tests exercise the actual Android SDK and device memory characteristics.
 * They do not claim to validate rendered GPU pixels; that still requires physical
 * device playback with representative SDR/HDR clips.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class VideoEnhancementCompatibilityInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun currentAndroidRuntimeResolvesAValidSafeEnhancementProfile() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tuning = VideoEnhancementPolicy.resolve(
            requested = VideoEnhancementProfile.BALANCED,
            sdkInt = Build.VERSION.SDK_INT,
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            thermalStatus = null
        )

        assertTrue("Unsupported runtime API: ${Build.VERSION.SDK_INT}", Build.VERSION.SDK_INT >= 23)
        assertNotNull(tuning.effective)
        if (Build.VERSION.SDK_INT < 26 || activityManager.isLowRamDevice ||
            activityManager.memoryClass < 256
        ) {
            assertEquals(VideoEnhancementProfile.ECONOMY, tuning.effective)
        } else {
            assertEquals(VideoEnhancementProfile.BALANCED, tuning.effective)
        }
    }

    @Test
    fun runtimeCanCalculateAspectSafeTargetsWithinTextureBudget() {
        val portrait = VideoUpscaleTargetPolicy.resolve(
            sourceWidth = 360,
            sourceHeight = 640,
            displayWidth = 1080,
            displayHeight = 2400,
            maxLongEdge = 1920
        )
        val landscape = VideoUpscaleTargetPolicy.resolve(
            sourceWidth = 640,
            sourceHeight = 480,
            displayWidth = 2400,
            displayHeight = 1080,
            maxLongEdge = 1920
        )

        assertEquals(VideoUpscaleTarget(1920, 1080), portrait)
        assertEquals(VideoUpscaleTarget(1440, 1080), landscape)
        assertTrue(requireNotNull(portrait).longEdge <= 1920)
        assertTrue(requireNotNull(landscape).longEdge <= 1920)
    }

    @Test
    fun profileTextureBudgetsStayWithinTheRequestedDeviceClasses() {
        assertEquals(1920, VideoEnhancementProfile.ECONOMY.upscaleMaxLongEdge)
        assertEquals(2560, VideoEnhancementProfile.BALANCED.upscaleMaxLongEdge)
        assertEquals(2960, VideoEnhancementProfile.ULTRA.upscaleMaxLongEdge)
        assertEquals(1_500_000, VideoEnhancementProfile.ECONOMY.upscaleMaxPixels)
        assertEquals(2_500_000, VideoEnhancementProfile.BALANCED.upscaleMaxPixels)
        assertEquals(3_500_000, VideoEnhancementProfile.ULTRA.upscaleMaxPixels)

        listOf(
            VideoEnhancementProfile.ECONOMY,
            VideoEnhancementProfile.BALANCED,
            VideoEnhancementProfile.ULTRA
        ).forEach { profile ->
            val target = VideoUpscaleTargetPolicy.resolve(
                sourceWidth = 640,
                sourceHeight = 360,
                displayWidth = 1440,
                displayHeight = 2960,
                maxLongEdge = profile.upscaleMaxLongEdge,
                maxPixels = profile.upscaleMaxPixels
            )
            assertNotNull(target)
            val actual = requireNotNull(target)
            assertTrue(actual.longEdge <= profile.upscaleMaxLongEdge)
            assertTrue(
                actual.longEdge.toLong() * actual.shortEdge <= profile.upscaleMaxPixels.toLong()
            )
        }
    }

    @Test
    fun gpuShaderConfigurationSwitchesBetweenPassthroughAndInternalAiStages() {
        val effect = VideoEnhancementPipelineEffect()
        assertFalse(effect.currentConfig().hasActiveStage)

        val aiConfig = VideoEnhancementPipelineConfig(
            denoiseEnabled = true,
            detailEnabled = true,
            aiBoostEnabled = true,
            denoiseStrength = 0.50f,
            detailStrength = 1.02f,
            contrastBoost = 0.16f,
            clarityStrength = 0.22f
        )
        effect.update(aiConfig)
        assertEquals(aiConfig, effect.currentConfig())
        assertTrue(effect.currentConfig().hasActiveStage)

        effect.update(VideoEnhancementPipelineConfig())
        assertFalse(effect.currentConfig().hasActiveStage)
    }

    @Test
    fun hdAnd720pSourcesRetainDenoiseDetailAndAdaptiveClarityStages() {
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
        val target = VideoUpscaleTargetPolicy.resolve(
            sourceWidth = 1280,
            sourceHeight = 720,
            displayWidth = 2960,
            displayHeight = 1440,
            maxLongEdge = VideoEnhancementProfile.BALANCED.upscaleMaxLongEdge
        )
        assertEquals(VideoUpscaleTarget(2560, 1440), target)
    }

    @Test
    fun disabledShaderConfigRemainsPassthroughEvenWithRetainedPresetValues() {
        val effect = VideoEnhancementPipelineEffect(
            VideoEnhancementPipelineConfig(
                denoiseEnabled = false,
                detailEnabled = false,
                aiBoostEnabled = false,
                denoiseStrength = 0.50f,
                detailStrength = 1.02f,
                contrastBoost = 0.16f,
                clarityStrength = 0.22f
            )
        )
        assertFalse(effect.currentConfig().hasActiveStage)

        effect.update(effect.currentConfig().copy(denoiseEnabled = true))
        assertTrue(effect.currentConfig().hasActiveStage)
    }

    @Test
    fun highResolutionSourcesAreNotResampledToAnUpscaleTarget() {
        assertEquals(
            null,
            VideoUpscaleTargetPolicy.resolve(3840, 2160, 2960, 1440, 1920)
        )
        assertEquals(
            null,
            VideoUpscaleTargetPolicy.resolve(7680, 4320, 2960, 1440, 1920)
        )
    }
}
