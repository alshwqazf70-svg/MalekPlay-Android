package com.malekplay.player.effects

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
