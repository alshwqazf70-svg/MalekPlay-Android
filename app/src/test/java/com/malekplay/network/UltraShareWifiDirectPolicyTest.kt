package com.malekplay.network

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareWifiDirectPolicyTest {
    @Test
    fun acceptsFormedClientGroupForAnActiveQrPairingWithoutComparingRotatingMacAddresses() {
        assertTrue(
            isReadyWifiDirectClientGroup(
                groupPresent = true,
                groupIsOwner = false,
                pairingAttemptActive = true
            )
        )
    }

    @Test
    fun rejectsMissingOwnerGroupAndUnsolicitedClientGroups() {
        assertFalse(isReadyWifiDirectClientGroup(false, false, true))
        assertFalse(isReadyWifiDirectClientGroup(true, true, true))
        assertFalse(isReadyWifiDirectClientGroup(true, false, false))
    }

    @Test
    fun ownerAcceptsAnyConnectedClientDuringTheActiveQrSessionWithoutMacComparison() {
        assertTrue(isReadyWifiDirectOwnerGroup(true, 1, true))
        assertTrue(isReadyWifiDirectOwnerGroup(true, 2, true))
        assertFalse(isReadyWifiDirectOwnerGroup(true, 0, true))
        assertFalse(isReadyWifiDirectOwnerGroup(false, 1, true))
        assertFalse(isReadyWifiDirectOwnerGroup(true, 1, false))
    }

    @Test
    fun ownerWaitsLongEnoughForSlowAndroidNineAndTenDnsSdPairing() {
        assertTrue(shouldRetryWifiDirectPeerConfirmation(0))
        assertTrue(shouldRetryWifiDirectPeerConfirmation(69))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(70))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(-1))
    }

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
        assertFalse(isRetryableUltraSharePairingFailure(java.net.SocketTimeoutException("approval timeout")))
        assertTrue(isRetryableUltraSharePairingFailure(
            IllegalArgumentException("إصدار قناة التحكم غير متوافق.")
        ))
        assertFalse(isRetryableUltraSharePairingFailure(IllegalArgumentException("invalid QR")))
        assertFalse(isRetryableUltraSharePairingFailure(SecurityException("permission denied")))
    }
    @Test
    fun retriesTransientWifiDirectFrameworkErrorsAcrossAndroidVersions() {
        assertTrue(shouldRetryWifiDirectConnectFailure(0, 0)) // ERROR (reason 0)
        assertTrue(shouldRetryWifiDirectConnectFailure(2, 1)) // BUSY
        assertTrue(shouldRetryWifiDirectConnectFailure(0, 2))
        assertFalse(shouldRetryWifiDirectConnectFailure(0, 3))
        assertFalse(shouldRetryWifiDirectConnectFailure(1, 0)) // unsupported hardware
        assertFalse(shouldRetryWifiDirectConnectFailure(3, 0)) // no service request
        assertFalse(shouldRetryWifiDirectConnectFailure(2, -1))
    }

}