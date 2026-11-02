package com.malekplay.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test fun negativePositionIsClamped() {
        assertEquals(0L, PlaybackProgressPolicy.evaluate(-5L, 60_000L).positionMs)
    }

    @Test fun shortMediaIsNotCompletedAtTheBeginning() {
        assertEquals(false, PlaybackProgressPolicy.evaluate(0L, 1_000L).completed)
    }

    @Test fun shortMediaCompletesNearItsEnd() {
        assertEquals(true, PlaybackProgressPolicy.evaluate(950L, 1_000L).completed)
    }

    @Test fun longMediaUsesTwoSecondCompletionWindow() {
        assertEquals(false, PlaybackProgressPolicy.evaluate(57_999L, 60_000L).completed)
        assertEquals(true, PlaybackProgressPolicy.evaluate(58_000L, 60_000L).completed)
    }

    @Test fun unknownDurationNeverMarksCompleted() {
        assertEquals(false, PlaybackProgressPolicy.evaluate(10_000L, 0L).completed)
    }
}
