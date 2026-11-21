package com.malekplay.player.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEnhancementMetricsTest {
    @Test fun onlySuccessfulShaderDrawsIncreaseProcessedCountAndFailureSurvivesRecovery() {
        VideoEnhancementMetrics.reset()
        VideoEnhancementMetrics.recordFailure("denoise", IllegalStateException("shader failed"))
        assertEquals(0L, VideoEnhancementMetrics.snapshot().processedDraws)

        VideoEnhancementMetrics.recordProcessed("denoise", 1_000L)
        val snapshot = VideoEnhancementMetrics.snapshot()
        assertEquals(1L, snapshot.processedDraws)
        assertEquals("denoise", snapshot.lastEffectName)
        assertEquals(1_000L, snapshot.lastPresentationTimeUs)
        assertEquals("IllegalStateException: shader failed", snapshot.lastFailure)
        VideoEnhancementMetrics.reset()
        val reset = VideoEnhancementMetrics.snapshot()
        assertEquals(null, reset.lastFailure)
        assertEquals(0L, reset.programCreations)
        assertEquals(0L, reset.configureCalls)
        assertEquals(0L, reset.drawFrameCalls)
        assertEquals(0L, reset.drawFrameSuccesses)
        assertEquals("not_started", reset.lastPipelineStage)
    }

    @Test fun pipelineStagesAreCountedIndependently() {
        VideoEnhancementMetrics.reset()
        VideoEnhancementMetrics.recordProgramCreated()
        VideoEnhancementMetrics.recordConfigured(1920, 1080)
        VideoEnhancementMetrics.recordDrawFrameEntered()
        VideoEnhancementMetrics.recordDrawFrameSucceeded()
        val snapshot = VideoEnhancementMetrics.snapshot()
        assertEquals(1L, snapshot.programCreations)
        assertEquals(1L, snapshot.configureCalls)
        assertEquals(1L, snapshot.drawFrameCalls)
        assertEquals(1L, snapshot.drawFrameSuccesses)
        assertEquals(1920, snapshot.lastConfiguredWidth)
        assertEquals(1080, snapshot.lastConfiguredHeight)
        assertEquals("draw_frame_succeeded", snapshot.lastPipelineStage)
        assertEquals(0L, snapshot.processedDraws)
    }
}
