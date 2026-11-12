package com.malekplay.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressPolicyTest {

    @Test
    fun doesNotMarkUnknownOrZeroDurationAsCompleted() {
        assertFalse(isPlaybackCompleted(0L, 0L))
        assertFalse(isPlaybackCompleted(10_000L, 0L))
    }

    @Test
    fun usesShorterCompletionToleranceForShortClips() {
        assertFalse(isPlaybackCompleted(8_000L, 10_000L))
        assertTrue(isPlaybackCompleted(9_000L, 10_000L))
        assertTrue(isPlaybackCompleted(9_001L, 10_000L))
    }

    @Test
    fun usesTwoSecondToleranceForLongMedia() {
        assertFalse(isPlaybackCompleted(597_999L, 600_000L))
        assertTrue(isPlaybackCompleted(598_000L, 600_000L))
    }

    @Test
    fun clampsNegativePositionsToZero() {
        assertFalse(isPlaybackCompleted(-1L, 10_000L))
    }
    @Test
    fun playCountDoesNotOverflowAtMaximumValue() {
        assertEquals(Int.MAX_VALUE, nextPlaybackCount(Int.MAX_VALUE, markPlayed = true))
    }

    @Test
    fun playCountNeverBecomesNegativeForLegacyCorruptValues() {
        assertEquals(0, nextPlaybackCount(-1, markPlayed = false))
        assertEquals(1, nextPlaybackCount(-1, markPlayed = true))
    }

    @Test
    fun playCountIncrementsOnlyForARealPlaybackStart() {
        assertEquals(7, nextPlaybackCount(7, markPlayed = false))
        assertEquals(8, nextPlaybackCount(7, markPlayed = true))
    }

}
