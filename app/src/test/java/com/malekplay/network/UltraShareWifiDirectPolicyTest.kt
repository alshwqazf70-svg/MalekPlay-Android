package com.malekplay.network

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareWifiDirectPolicyTest {
    @Test
    fun peerRefreshTimeoutAndCallbackCannotStartTwoConnectAttempts() {
        val finished = AtomicBoolean(false)
        assertTrue(claimWifiDirectPeerRefreshCompletion(finished))
        assertFalse(claimWifiDirectPeerRefreshCompletion(finished))
        assertFalse(claimWifiDirectPeerRefreshCompletion(finished))
    }
}
