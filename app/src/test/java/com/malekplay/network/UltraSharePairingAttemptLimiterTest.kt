package com.malekplay.network

import org.junit.Assert.assertEquals
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
        limiter.recordFailure("peer", 40_000L)
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

    @Test
    fun peerFailureTableRemainsBoundedUnderChangingSourceAddresses() {
        val limiter = UltraSharePairingAttemptLimiter(maxTrackedPeers = 3, globalFailureThreshold = 1_000)
        repeat(20) { index ->
            limiter.recordFailure("192.168.1.$index", 10_000L + index)
        }

        assertEquals(3, limiter.trackedPeerCount())
        // The newest address remains tracked and its per-peer threshold is preserved.
        repeat(4) { index ->
            limiter.recordFailure("192.168.1.19", 20_000L + index)
        }
        assertFalse(limiter.isAllowed("192.168.1.19", 20_010L))
    }

    @Test
    fun expiredPeerEntriesArePrunedWhenNewAttemptsArrive() {
        val limiter = UltraSharePairingAttemptLimiter(maxTrackedPeers = 8)
        repeat(5) { index ->
            limiter.recordFailure("peer-$index", 1_000L + index)
        }
        assertEquals(5, limiter.trackedPeerCount())

        limiter.recordFailure("new-peer", 70_000L)

        assertEquals(1, limiter.trackedPeerCount())
        assertTrue(limiter.isAllowed("peer-0", 70_001L))
    }
}
