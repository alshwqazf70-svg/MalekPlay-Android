package com.malekplay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaThumbnailFrameTimeTest {

    @Test
    fun unknownDurationUsesTheFirstFrameAsFallback() {
        assertEquals(0L, representativeThumbnailTimeUs(0L))
        assertEquals(0L, representativeThumbnailTimeUs(-1L))
    }

    @Test
    fun knownDurationUsesAFrameNearTheBeginningButAfterTheOpening() {
        assertEquals(10_000_000L, representativeThumbnailTimeUs(100_000L))
        assertEquals(500_000L, representativeThumbnailTimeUs(5_000L))
    }

    @Test
    fun extremeDurationDoesNotOverflowTimestamp() {
        assertEquals(Long.MAX_VALUE, representativeThumbnailTimeUs(Long.MAX_VALUE))
    }

    @Test
    fun veryShortVideosStillUseAValidTimestamp() {
        assertEquals(100_000L, representativeThumbnailTimeUs(1_000L))
        assertEquals(1_000L, representativeThumbnailTimeUs(10L))
    }
}
