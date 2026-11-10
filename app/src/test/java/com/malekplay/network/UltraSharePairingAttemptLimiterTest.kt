package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePairingAttemptLimiterTest {
    @Test
    fun locksPeerAfterFiveFailuresAndAllowsAgainAfterCooldown() {
        val limiter = UltraSharePairingAttemptLimiter()
        repeat(4) { index ->
            limiter.recordFailure("192.168.1.10", 1_000L + index)
            assertTrue(limiter.isAllowed("192.168.1.10", 1_000L + index))
        }
        limiter.recordFailure("192.168.1.10", 2_000L)
        assertFalse(limiter.isAllowed("192.168.1.10", 2_001L))
        assertTrue(limiter.isAllowed("192.168.1.10", 32_000L))
    }

    @Test
    fun cooldownEscalatesAfterRepeatedFailureCycles() {
        val limiter = UltraSharePairingAttemptLimiter()
        repeat(5) { limiter.recordFailure("peer", 1_000L + it) }
        assertFalse(limiter.isAllowed("peer", 2_000L))
        repeat(5) { limiter.recordFailure("peer", 40_000L + it) }
        assertFalse(limiter.isAllowed("peer", 70_000L))
        assertTrue(limiter.isAllowed("peer", 101_000L))
    }

    @Test
    fun successfulAuthenticationClearsOnlyThatPeerFailures() {
        val limiter = UltraSharePairingAttemptLimiter()
        repeat(5) { limiter.recordFailure("peer-a", 1_000L + it) }
        limiter.recordFailure("peer-b", 1_010L)
        limiter.recordSuccess("peer-a")
        assertTrue(limiter.isAllowed("peer-a", 2_000L))
        assertTrue(limiter.isAllowed("peer-b", 2_000L))
    }

    @Test
    fun globalBudgetBlocksDistributedFailuresTemporarily() {
        val limiter = UltraSharePairingAttemptLimiter()
        repeat(24) { index ->
            limiter.recordFailure("192.168.1.$index", 5_000L + index)
        }
        assertFalse(limiter.isAllowed("192.168.1.200", 6_000L))
        assertTrue(limiter.isAllowed("192.168.1.200", 66_000L))
    }
}
