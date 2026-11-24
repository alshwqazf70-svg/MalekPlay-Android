package com.malekplay.network

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareWifiDirectPolicyTest {
    @Test
    fun losingAnEstablishedOrAdvertisedP2pGroupInvalidatesItsInvitation() {
        assertTrue(shouldInvalidateWifiDirectInvitation(false, true, false))
        assertTrue(shouldInvalidateWifiDirectInvitation(false, false, true))
        assertTrue(shouldInvalidateWifiDirectInvitation(false, true, true))
        assertFalse(shouldInvalidateWifiDirectInvitation(false, false, false))
        assertFalse(shouldInvalidateWifiDirectInvitation(true, true, true))
    }

    @Test
    fun staleOwnerGroupInfoCannotReplaceConnectionInfoFormationConfirmation() {
        assertTrue(isFormedWifiDirectOwnerGroup(groupFormed = true, connectionInfoSaysOwner = true))
        assertFalse(isFormedWifiDirectOwnerGroup(groupFormed = false, connectionInfoSaysOwner = true))
        assertFalse(isFormedWifiDirectOwnerGroup(groupFormed = true, connectionInfoSaysOwner = false))
        assertFalse(isFormedWifiDirectOwnerGroup(groupFormed = false, connectionInfoSaysOwner = false))
    }

    @Test
    fun delayedGroupCreationRetryCannotSupersedeANewerGroupAttempt() {
        assertTrue(isCurrentWifiDirectGroupRetry(false, 9, 9))
        assertFalse(isCurrentWifiDirectGroupRetry(false, 10, 9))
        assertFalse(isCurrentWifiDirectGroupRetry(true, 9, 9))
    }

    @Test
    fun delayedGroupCredentialsCannotOverwriteANewerP2pGroup() {
        assertTrue(isCurrentWifiDirectGroupInfoRequest(false, 7, 7))
        assertFalse(isCurrentWifiDirectGroupInfoRequest(false, 8, 7))
        assertFalse(isCurrentWifiDirectGroupInfoRequest(true, 7, 7))
    }

    @Test
    fun stableAdvertisementIsReusedOnlyWhenNoMutationOrCleanupIsPending() {
        assertTrue(canReuseWifiDirectAdvertisement(true, true, false, false))
        assertFalse(canReuseWifiDirectAdvertisement(true, true, true, false))
        assertFalse(canReuseWifiDirectAdvertisement(true, true, false, true))
        assertFalse(canReuseWifiDirectAdvertisement(false, true, false, false))
        assertFalse(canReuseWifiDirectAdvertisement(true, false, false, false))
    }

    @Test
    fun delayedAdvertisementCleanupCannotCompleteANewerInvitation() {
        assertTrue(isCurrentWifiDirectAdvertisementOperation(false, 4, 4))
        assertFalse(isCurrentWifiDirectAdvertisementOperation(false, 5, 4))
        assertFalse(isCurrentWifiDirectAdvertisementOperation(true, 4, 4))
    }

    @Test
    fun delayedDnsSdCallbacksCannotReviveACancelledInvitationRequest() {
        val originalRequest = Any()
        val replacementRequest = Any()

        assertTrue(
            isCurrentWifiDirectInvitationRequest(
                sessionClosed = false,
                currentRequest = originalRequest,
                callbackRequest = originalRequest
            )
        )
        assertFalse(
            isCurrentWifiDirectInvitationRequest(
                sessionClosed = false,
                currentRequest = replacementRequest,
                callbackRequest = originalRequest
            )
        )
        assertFalse(
            isCurrentWifiDirectInvitationRequest(
                sessionClosed = false,
                currentRequest = null,
                callbackRequest = originalRequest
            )
        )
        assertFalse(
            isCurrentWifiDirectInvitationRequest(
                sessionClosed = true,
                currentRequest = originalRequest,
                callbackRequest = originalRequest
            )
        )
    }

    @Test
    fun delayedPeerDiscoveryStopCannotChangeANewerDiscoveryState() {
        assertTrue(isCurrentWifiDirectDiscoveryStop(false, 4, 4))
        assertFalse(isCurrentWifiDirectDiscoveryStop(false, 5, 4))
        assertFalse(isCurrentWifiDirectDiscoveryStop(true, 4, 4))
    }

    @Test
    fun delayedDiscoveryRetryCallbacksCannotMutateANewerAttemptForTheSameRequest() {
        val request = Any()
        assertTrue(isCurrentWifiDirectInvitationDiscovery(false, request, request, 2, 2))
        assertFalse(isCurrentWifiDirectInvitationDiscovery(false, request, request, 3, 2))
        assertFalse(isCurrentWifiDirectInvitationDiscovery(false, Any(), request, 2, 2))
        assertFalse(isCurrentWifiDirectInvitationDiscovery(true, request, request, 2, 2))
    }

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
    fun staleClientGroupConfirmationCallbacksCannotMutateANewerAttempt() {
        assertTrue(isCurrentWifiDirectPeerConfirmation(sessionClosed = false, currentToken = 7, callbackToken = 7))
        assertFalse(isCurrentWifiDirectPeerConfirmation(sessionClosed = false, currentToken = 8, callbackToken = 7))
        assertFalse(isCurrentWifiDirectPeerConfirmation(sessionClosed = true, currentToken = 7, callbackToken = 7))
    }

    @Test
    fun clientGroupConfirmationUsesTheSameBoundedLegacyCompatibilityWindow() {
        assertTrue(shouldRetryWifiDirectPeerConfirmation(0))
        assertTrue(shouldRetryWifiDirectPeerConfirmation(69))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(70))
    }

    @Test
    fun peerRefreshTimeoutAndCallbackCannotStartTwoConnectAttempts() {
        val finished = AtomicBoolean(false)
        assertTrue(claimWifiDirectPeerRefreshCompletion(finished))
        assertFalse(claimWifiDirectPeerRefreshCompletion(finished))
        assertFalse(claimWifiDirectPeerRefreshCompletion(finished))
    }




    @Test
    fun android16GroupCreationForcesLegacyWpa2Compatibility() {
        assertFalse(shouldForceLegacyWifiDirectSecurity(23))
        assertFalse(shouldForceLegacyWifiDirectSecurity(35))
        assertTrue(shouldForceLegacyWifiDirectSecurity(36))
    }

    @Test
    fun qrSecurityUsesWpa3OnlyForAndroid16SaeGroups() {
        assertEquals("wpa2", wifiDirectQrSecurity(23, isWpa3Sae = true))
        assertEquals("wpa2", wifiDirectQrSecurity(35, isWpa3Sae = true))
        assertEquals("wpa3", wifiDirectQrSecurity(36, isWpa3Sae = true))
        assertEquals("wpa2", wifiDirectQrSecurity(36, isWpa3Sae = false))
    }

    @Test
    fun idleOwnerGroupNeverStartsThePeerDisconnectDeadline() {
        assertFalse(shouldStartWifiDirectOwnerPeerDeadline(clientCount = 0, wasConnected = false))
        assertTrue(shouldStartWifiDirectOwnerPeerDeadline(clientCount = 1, wasConnected = false))
        assertTrue(shouldStartWifiDirectOwnerPeerDeadline(clientCount = 0, wasConnected = true))
        assertTrue(shouldStartWifiDirectOwnerPeerDeadline(clientCount = 3, wasConnected = true))
    }

    @Test
    fun wifiDirectOwnerNegotiationIsSymmetricForBothPairingDirections() {
        assertEquals(15, wifiDirectNeutralGroupOwnerIntent(preferThisDeviceAsGroupOwner = true))
        assertEquals(0, wifiDirectNeutralGroupOwnerIntent(preferThisDeviceAsGroupOwner = false))
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
        assertTrue(isRetryableUltraSharePairingFailure(
            IllegalStateException("هوية الجهاز لا تطابق الجهاز المكتشف؛ سنجرّب مسار IP آخر.")
        ))
        assertTrue(isRetryableUltraSharePairingFailure(java.net.SocketTimeoutException("route timed out")))
        assertTrue(isRetryableUltraSharePairingFailure(java.io.EOFException("stale endpoint")))
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