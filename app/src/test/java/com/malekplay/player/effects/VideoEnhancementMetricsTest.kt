package com.malekplay.player.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEnhancementMetricsTest {
    @Test fun onlySuccessfulShaderDrawsIncreaseProcessedCount() {
        VideoEnhancementMetrics.reset()
        VideoEnhancementMetrics.recordFailure("denoise", IllegalStateException("shader failed"))
        assertEquals(0L, VideoEnhancementMetrics.snapshot().processedDraws)

        VideoEnhancementMetrics.recordProcessed("denoise", 1_000L)
        val snapshot = VideoEnhancementMetrics.snapshot()
        assertEquals(1L, snapshot.processedDraws)
        assertEquals("denoise", snapshot.lastEffectName)
        assertEquals(1_000L, snapshot.lastPresentationTimeUs)
        assertNull(snapshot.lastFailure)
        VideoEnhancementMetrics.reset()
    }
}
