package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDFileTimeoutPolicyTest {
    @Test
    fun emptyAndSmallBatchesUseMinimumTimeout() {
        assertEquals(60_000, UltraShareDFileTimeoutPolicy.completionTimeoutMillis(emptyList()))
        assertEquals(61_000, UltraShareDFileTimeoutPolicy.completionTimeoutMillis(listOf(1L)))
    }

    @Test
    fun oneGibibyteGetsMoreThanThirtyMinutesAtConservativeRate() {
        val timeout = UltraShareDFileTimeoutPolicy.completionTimeoutMillis(
            listOf(1024L * 1024L * 1024L)
        )
        assertEquals(4_156_000, timeout)
        assertTrue(timeout > 30 * 60 * 1_000)
    }

    @Test
    fun hugeBatchesAreCappedBelowSixHourDataSyncBudget() {
        assertEquals(
            5 * 60 * 60 * 1_000 + 30 * 60 * 1_000,
            UltraShareDFileTimeoutPolicy.completionTimeoutMillis(listOf(Long.MAX_VALUE))
        )
    }

    @Test
    fun rejectsNegativeSizesAndOverflowingBatchTotals() {
        assertTrue(runCatching {
            UltraShareDFileTimeoutPolicy.completionTimeoutMillis(listOf(-1L))
        }.isFailure)
        assertTrue(runCatching {
            UltraShareDFileTimeoutPolicy.completionTimeoutMillis(listOf(Long.MAX_VALUE, 1L))
        }.isFailure)
    }
}
