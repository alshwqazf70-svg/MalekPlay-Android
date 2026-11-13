package com.malekplay.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDenoisePolicyTest {
    @Test
    fun allowsHdAndFullHdAtOrBelowSixtyFramesOnGles2() {
        assertTrue(VideoDenoisePolicy.isEligible(1280, 720, 30f, 0x20000))
        assertTrue(VideoDenoisePolicy.isEligible(1920, 1080, 60f, 0x30000))
        assertTrue(VideoDenoisePolicy.isEligible(1080, 1920, -1f, 0x20000))
    }

    @Test
    fun rejectsUnknownDimensionsAndUnsupportedGraphics() {
        assertFalse(VideoDenoisePolicy.isEligible(0, 720, 30f, 0x30000))
        assertFalse(VideoDenoisePolicy.isEligible(1280, 720, 30f, 0x10000))
    }

    @Test
    fun avoidsHighResolutionAndHighFrameRateGpuLoad() {
        assertFalse(VideoDenoisePolicy.isEligible(3840, 2160, 30f, 0x30000))
        assertFalse(VideoDenoisePolicy.isEligible(1920, 1080, 120f, 0x30000))
    }
}
