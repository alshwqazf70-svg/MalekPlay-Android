package com.malekplay.player
import org.junit.Assert.assertEquals
import org.junit.Test
class PlaybackValuePolicyTest {
    @Test fun clampsFiniteSpeeds() {
        assertEquals(0.25f, PlaybackValuePolicy.normalizeSpeed(0f))
        assertEquals(4f, PlaybackValuePolicy.normalizeSpeed(8f))
        assertEquals(1.5f, PlaybackValuePolicy.normalizeSpeed(1.5f))
    }
    @Test fun replacesNonFiniteSpeeds() {
        assertEquals(1f, PlaybackValuePolicy.normalizeSpeed(Float.NaN))
        assertEquals(1f, PlaybackValuePolicy.normalizeSpeed(Float.POSITIVE_INFINITY))
        assertEquals(1f, PlaybackValuePolicy.normalizeSpeed(Float.NEGATIVE_INFINITY))
    }
    @Test fun seekAdditionDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, PlaybackValuePolicy.addSeekDelta(Long.MAX_VALUE - 2, 10))
        assertEquals(0L, PlaybackValuePolicy.addSeekDelta(5, -10))
        assertEquals(15L, PlaybackValuePolicy.addSeekDelta(10, 5))
    }
}
