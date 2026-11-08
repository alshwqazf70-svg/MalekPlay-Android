package com.malekplay.player

import com.malekplay.ui.player.video.PlayerZoomPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerZoomPolicyTest {
    @Test
    fun scalesWithinSupportedRange() {
        assertEquals(1.5f, PlayerZoomPolicy.scaleBy(1f, 1.5f), 0.0001f)
        assertEquals(3f, PlayerZoomPolicy.scaleBy(2f, 2f), 0.0001f)
        assertEquals(1f, PlayerZoomPolicy.scaleBy(1.5f, 0.1f), 0.0001f)
    }

    @Test
    fun rejectsInvalidScaleFactorsWithoutBreakingCurrentZoom() {
        assertEquals(2f, PlayerZoomPolicy.scaleBy(2f, Float.NaN), 0.0001f)
        assertEquals(2f, PlayerZoomPolicy.scaleBy(2f, Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(2f, PlayerZoomPolicy.scaleBy(2f, -1f), 0.0001f)
    }

    @Test
    fun normalizesInvalidCurrentScale() {
        assertEquals(1f, PlayerZoomPolicy.scaleBy(Float.NaN, 2f), 0.0001f)
        assertEquals(3f, PlayerZoomPolicy.scaleBy(8f, 1f), 0.0001f)
    }
}
