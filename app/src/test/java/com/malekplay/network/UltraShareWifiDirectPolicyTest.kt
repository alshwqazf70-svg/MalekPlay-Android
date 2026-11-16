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

    @Test
    fun retriesOnlyTransientSocketFailuresDuringPairingStartup() {
        assertTrue(isRetryableUltraSharePairingFailure(java.net.ConnectException("refused")))
        assertTrue(
            isRetryableUltraSharePairingFailure(
                java.io.IOException("route failed", java.net.NoRouteToHostException("unreachable"))
            )
        )
        assertTrue(isRetryableUltraSharePairingFailure(java.net.SocketTimeoutException("startup timeout")))
        assertFalse(isRetryableUltraSharePairingFailure(IllegalArgumentException("invalid QR")))
        assertFalse(isRetryableUltraSharePairingFailure(SecurityException("permission denied")))
    }
}
