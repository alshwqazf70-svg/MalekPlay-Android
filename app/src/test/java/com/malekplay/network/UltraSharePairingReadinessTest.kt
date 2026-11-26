package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePairingReadinessTest {

    @Test fun wifiDirectRecoveryWaitsUntilLanProbeAndHotspotFallbackAreFinished() {
        fun allowed(
            lanProbe: Boolean = false,
            hotspot: Boolean = false,
            fallbackStarted: Boolean = false,
            receiving: Boolean = true,
            invitationBlank: Boolean = true,
            p2pAttempted: Boolean = false,
            peers: Boolean = false,
            lanRoute: Boolean = false
        ) = UltraSharePairingReadiness.shouldStartWifiDirectFallback(
            receiving = receiving,
            lanProbeInProgress = lanProbe,
            hotspotActive = hotspot,
            hotspotFallbackStarted = fallbackStarted,
            invitationIdBlank = invitationBlank,
            wifiDirectAlreadyAttempted = p2pAttempted,
            hasActivePeers = peers,
            hasActiveLanRoute = lanRoute
        )

        assertTrue(allowed())
        assertFalse(allowed(lanProbe = true))
        assertFalse(allowed(hotspot = true))
        assertFalse(allowed(fallbackStarted = true))
        assertFalse(allowed(receiving = false))
        assertFalse(allowed(invitationBlank = false))
        assertFalse(allowed(p2pAttempted = true))
        assertFalse(allowed(peers = true))
        assertFalse(allowed(lanRoute = true))
    }

    @Test fun authenticatedPeerIdentityMustMatchTheDiscoveredDeviceButAllowsLegacyIdentityAbsence() {
        assertTrue(
            matchesExpectedUltraSharePeerIdentity(
                "123e4567-e89b-12d3-a456-426614174000",
                "123e4567e89b12d3a456426614174000"
            )
        )
        assertFalse(
            matchesExpectedUltraSharePeerIdentity(
                "123e4567-e89b-12d3-a456-426614174000",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        // Missing expected identity is accepted only for legacy discovery entries.
        assertTrue(matchesExpectedUltraSharePeerIdentity(null, "any-peer"))
        assertTrue(matchesExpectedUltraSharePeerIdentity(null, null))
        // A known discovered peer must prove that identity during authentication.
        assertFalse(matchesExpectedUltraSharePeerIdentity("known-peer", null))
        assertFalse(matchesExpectedUltraSharePeerIdentity("known-peer", ""))
        assertFalse(matchesExpectedUltraSharePeerIdentity(" ", "authenticated-peer"))
    }

    @Test fun approvedSendTargetKeepsSessionIdSeparateFromRemotePeerIdentity() {
        val target = UltraShareApprovedSendTarget(
            sessionId = "temporary-session-123",
            host = "192.168.1.20",
            secret = "123456789",
            peerId = "stable-remote-peer"
        )
        assertEquals("temporary-session-123", target.sessionId)
        assertEquals("stable-remote-peer", target.peerId)
        assertTrue(matchesExpectedUltraSharePeerIdentity(target.peerId, "stable-remote-peer"))
        assertFalse(matchesExpectedUltraSharePeerIdentity(target.peerId, "different-peer"))
    }

    @Test fun receiverInvitationAdvertisementStaysAvailableForAdditionalPeers() {
        assertTrue(UltraSharePairingReadiness.shouldRetainReceiverInvitationAdvertisement(receiving = true))
        assertFalse(UltraSharePairingReadiness.shouldRetainReceiverInvitationAdvertisement(receiving = false))
    }

    @Test fun p2pQrIsNotPublishableUntilItsExactDnsSdAdvertisementSucceeds() {
        assertTrue(
            UltraSharePairingReadiness.canPublishWifiDirectInvitationQr(
                invitationId = "invite-1",
                advertisedInvitationId = "invite-1"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canPublishWifiDirectInvitationQr(
                invitationId = "invite-1",
                advertisedInvitationId = null
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canPublishWifiDirectInvitationQr(
                invitationId = "invite-2",
                advertisedInvitationId = "invite-1"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canPublishWifiDirectInvitationQr(
                invitationId = null,
                advertisedInvitationId = "invite-1"
            )
        )
    }

    @Test fun qrWifiFallbackRequiresTheExactInvitationSecretAndAUsableRoute() {
        fun allowed(
            invitationId: String? = "invite-1",
            secret: String? = "123456789",
            ssid: String? = "DIRECT-xy-MalekPlayer",
            password: String? = "abcdefgh",
            hosts: List<String> = listOf("192.168.49.1")
        ) = UltraSharePairingReadiness.canUseQrWifiCredentialFallback(
            expectedInvitationId = "invite-1",
            expectedPairingSecret = "123456789",
            qrInvitationId = invitationId,
            qrPairingSecret = secret,
            ssid = ssid,
            password = password,
            hosts = hosts
        )

        assertTrue(allowed())
        assertFalse(allowed(invitationId = "invite-2"))
        assertFalse(allowed(secret = "987654321"))
        assertFalse(allowed(ssid = null))
        assertFalse(allowed(password = "short"))
        assertFalse(allowed(hosts = emptyList()))
        assertFalse(allowed(hosts = listOf("8.8.8.8")))
    }

    @Test fun wifiDirectInvitationDiscoveryHasAnExplicitBoundedDeadline() {
        assertTrue(
            UltraSharePairingReadiness.WIFI_DIRECT_INVITATION_DISCOVERY_TIMEOUT_MS in 15_000L..30_000L
        )
    }

    @Test fun legacyUnifiedQrWithPeerIdentityStillUsesWifiDirectBeforeHotspotCredentials() {
        assertTrue(UltraSharePairingReadiness.shouldUseWifiDirectInvitation(null, "legacy-peer-id"))
        assertTrue(UltraSharePairingReadiness.shouldUseWifiDirectInvitation("p2p", "room-peer-id"))
        assertFalse(UltraSharePairingReadiness.shouldUseWifiDirectInvitation("lan", "peer-id"))
        assertFalse(UltraSharePairingReadiness.shouldUseWifiDirectInvitation("hotspot", "peer-id"))
        assertFalse(UltraSharePairingReadiness.shouldUseWifiDirectInvitation("p2p", null))
        assertFalse(UltraSharePairingReadiness.shouldUseWifiDirectInvitation(null, ""))
    }

    @Test fun staleInvitationTimeoutCannotCancelANewerAttemptWithTheSameSecret() {
        data class Attempt(val secret: String)
        val first = Attempt("123456789")
        val second = Attempt("123456789")
        // Data-class equality says these values match, but they are distinct attempts.
        assertEquals(first, second)
        assertTrue(UltraSharePairingReadiness.isCurrentInvitationAttempt(first, first))
        assertFalse(UltraSharePairingReadiness.isCurrentInvitationAttempt(second, first))
        assertFalse(UltraSharePairingReadiness.isCurrentInvitationAttempt(null, first))
    }

    @Test fun invitationDiscoveryTimeoutNeverRacesAnActiveWifiDirectConnection() {
        assertTrue(
            UltraSharePairingReadiness.shouldUseLanFallbackAfterInvitationDiscoveryTimeout(
                hasPendingInvitation = true,
                wifiDirectConnectionStarted = false
            )
        )
        assertFalse(
            UltraSharePairingReadiness.shouldUseLanFallbackAfterInvitationDiscoveryTimeout(
                hasPendingInvitation = true,
                wifiDirectConnectionStarted = true
            )
        )
        assertFalse(
            UltraSharePairingReadiness.shouldUseLanFallbackAfterInvitationDiscoveryTimeout(
                hasPendingInvitation = false,
                wifiDirectConnectionStarted = false
            )
        )
    }

    @Test fun invitationHostHintsRemainStableOnlyForTheSameSessionId() {
        val stored = listOf("192.168.1.10", "192.168.49.1")
        val refreshed = listOf("192.168.1.11")
        assertEquals(
            refreshed,
            UltraSharePairingReadiness.stableInvitationHosts(
                "invite-1", "invite-1", stored, refreshed
            )
        )
        assertEquals(
            refreshed,
            UltraSharePairingReadiness.stableInvitationHosts(
                "invite-1", "invite-2", stored, refreshed
            )
        )
        assertEquals(
            stored,
            UltraSharePairingReadiness.stableInvitationHosts(
                "invite-1", "invite-1", stored, emptyList()
            )
        )
        assertEquals(
            emptyList<String>(),
            UltraSharePairingReadiness.stableInvitationHosts(
                "invite-1", "invite-1", emptyList(), emptyList()
            )
        )
    }

    @Test fun qrPayloadRemainsByteForByteStableAfterFirstPublication() {
        val first = "malekplay://share?invitation=stable&host=192.168.49.1"
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(null, first))
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(first, "malekplay://share?invitation=rotated"))
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(first, null))
        assertNull(UltraSharePairingReadiness.stableQrPayload(null, null))
    }

    @Test fun lateUnifiedV5QrUpgradesOnlyTheSameFallbackInvitationAndSecret() {
        assertTrue(
            UltraSharePairingReadiness.shouldUpgradeStableQrToUnifiedV5(
                existingVersion = "3",
                candidateVersion = "5",
                existingInvitationId = "invite-1",
                candidateInvitationId = "invite-1",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789"
            )
        )
        assertTrue(
            UltraSharePairingReadiness.shouldUpgradeStableQrToUnifiedV5(
                existingVersion = "4",
                candidateVersion = "5",
                existingInvitationId = "invite-1",
                candidateInvitationId = "invite-1",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789"
            )
        )
    }

    @Test fun unifiedV5QrNeverRotatesOrReplacesAnotherInvitation() {
        val stableV5 = UltraSharePairingReadiness.shouldUpgradeStableQrToUnifiedV5(
            existingVersion = "5",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-1",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "123456789"
        )
        val wrongInvitation = UltraSharePairingReadiness.shouldUpgradeStableQrToUnifiedV5(
            existingVersion = "3",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-2",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "123456789"
        )
        val wrongSecret = UltraSharePairingReadiness.shouldUpgradeStableQrToUnifiedV5(
            existingVersion = "3",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-1",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "987654321"
        )
        assertFalse(stableV5)
        assertFalse(wrongInvitation)
        assertFalse(wrongSecret)
    }

    @Test fun unifiedV5QrAddsLateLanHostsWithoutChangingItsSessionCredentials() {
        assertTrue(
            UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
                existingVersion = "5",
                candidateVersion = "5",
                existingInvitationId = "invite-1",
                candidateInvitationId = "invite-1",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789",
                existingSsid = "DIRECT-MalekPlay",
                candidateSsid = "DIRECT-MalekPlay",
                existingWifiPassword = "share1234",
                candidateWifiPassword = "share1234",
                existingWifiSecurity = "wpa2",
                candidateWifiSecurity = "wpa2",
                existingHosts = listOf("192.168.49.1"),
                candidateHosts = listOf("192.168.49.1", "192.168.1.20")
            )
        )
    }

    @Test fun roomV6QrRefreshesChangedRoutesWithoutRotatingSessionOrWifiCredentials() {
        fun canRefresh(
            secret: String = "123456789",
            invitation: String? = "invite-1",
            name: String = "MalekPlayer",
            ssid: String? = "DIRECT-MalekPlay",
            password: String? = "share1234",
            hosts: List<String> = listOf("192.168.49.1", "192.168.1.21")
        ) = UltraSharePairingReadiness.shouldRefreshRoomV6Qr(
            existingVersion = "6",
            candidateVersion = "6",
            existingInvitationId = "invite-1",
            candidateInvitationId = invitation,
            existingPairingSecret = "123456789",
            candidatePairingSecret = secret,
            existingDeviceName = "MalekPlayer",
            candidateDeviceName = name,
            existingSsid = "DIRECT-MalekPlay",
            candidateSsid = ssid,
            existingWifiPassword = "share1234",
            candidateWifiPassword = password,
            candidateHosts = hosts
        )
        assertTrue(canRefresh())
        assertTrue(canRefresh(hosts = listOf("192.168.49.1")))
        assertFalse(canRefresh(secret = "987654321"))
        assertFalse(canRefresh(invitation = "invite-2"))
        assertFalse(canRefresh(name = "Other device"))
        assertFalse(canRefresh(ssid = "Other network"))
        assertFalse(canRefresh(password = "different123"))
        assertFalse(canRefresh(hosts = emptyList()))
        assertFalse(canRefresh(hosts = listOf("8.8.8.8")))
    }

    @Test fun roomV6QrMayGainLateWifiCredentialsWithoutChangingSessionIdentity() {
        assertTrue(
            UltraSharePairingReadiness.shouldRefreshRoomV6Qr(
                existingVersion = "6",
                candidateVersion = "6",
                existingInvitationId = "invite-1",
                candidateInvitationId = "invite-1",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789",
                existingDeviceName = "MalekPlayer",
                candidateDeviceName = "MalekPlayer",
                existingSsid = null,
                candidateSsid = "DIRECT-MalekPlay",
                existingWifiPassword = null,
                candidateWifiPassword = "share1234",
                candidateHosts = listOf("192.168.49.1")
            )
        )
    }

    @Test fun lanOnlyUnifiedV5QrCanGainSoftApCredentialsWhenGroupInfoArrivesLate() {
        assertTrue(
            UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
                existingVersion = "5",
                candidateVersion = "5",
                existingInvitationId = "invite-lan",
                candidateInvitationId = "invite-lan",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789",
                existingSsid = null,
                candidateSsid = "DIRECT-MalekPlay",
                existingWifiPassword = null,
                candidateWifiPassword = "share1234",
                existingWifiSecurity = "wpa2",
                candidateWifiSecurity = "wpa2",
                existingHosts = listOf("192.168.49.1", "192.168.1.20"),
                candidateHosts = listOf("192.168.49.1", "192.168.1.20")
            )
        )
    }

    @Test fun lanOnlyUnifiedV5QrCanGainLateLanHostsWithoutInventingWifiCredentials() {
        assertTrue(
            UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
                existingVersion = "5",
                candidateVersion = "5",
                existingInvitationId = "invite-lan",
                candidateInvitationId = "invite-lan",
                existingPairingSecret = "123456789",
                candidatePairingSecret = "123456789",
                existingSsid = null,
                candidateSsid = null,
                existingWifiPassword = null,
                candidateWifiPassword = null,
                existingWifiSecurity = "wpa2",
                candidateWifiSecurity = "wpa2",
                existingHosts = listOf("192.168.1.20"),
                candidateHosts = listOf("192.168.1.20", "10.0.0.4")
            )
        )
    }

    @Test fun unifiedV5QrRefreshesRoutesOnlyForTheSameSessionAndCredentials() {
        val unchanged = UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
            existingVersion = "5",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-1",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "123456789",
            existingSsid = "DIRECT-MalekPlay",
            candidateSsid = "DIRECT-MalekPlay",
            existingWifiPassword = "share1234",
            candidateWifiPassword = "share1234",
            existingWifiSecurity = "wpa2",
            candidateWifiSecurity = "wpa2",
            existingHosts = listOf("192.168.49.1", "192.168.1.20"),
            candidateHosts = listOf("192.168.49.1", "192.168.1.20")
        )
        val dropsOldRoute = UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
            existingVersion = "5",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-1",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "123456789",
            existingSsid = "DIRECT-MalekPlay",
            candidateSsid = "DIRECT-MalekPlay",
            existingWifiPassword = "share1234",
            candidateWifiPassword = "share1234",
            existingWifiSecurity = "wpa2",
            candidateWifiSecurity = "wpa2",
            existingHosts = listOf("192.168.49.1", "192.168.1.20"),
            candidateHosts = listOf("192.168.49.1", "192.168.1.21", "10.0.0.2")
        )
        val wrongWifiPassword = UltraSharePairingReadiness.shouldEnrichUnifiedV5Qr(
            existingVersion = "5",
            candidateVersion = "5",
            existingInvitationId = "invite-1",
            candidateInvitationId = "invite-1",
            existingPairingSecret = "123456789",
            candidatePairingSecret = "123456789",
            existingSsid = "DIRECT-MalekPlay",
            candidateSsid = "DIRECT-MalekPlay",
            existingWifiPassword = "share1234",
            candidateWifiPassword = "different123",
            existingWifiSecurity = "wpa2",
            candidateWifiSecurity = "wpa2",
            existingHosts = listOf("192.168.49.1"),
            candidateHosts = listOf("192.168.49.1", "192.168.1.20")
        )
        assertFalse(unchanged)
        assertTrue(dropsOldRoute)
        assertFalse(wrongWifiPassword)
    }

    private fun ready(
        receiving: Boolean = true,
        control: Boolean = true,
        data: Boolean = true,
        hotspot: Boolean = true,
        peers: Int = 0,
        secret: String = "123456789",
        ssid: String? = "MalekPlay",
        password: String? = "share1234",
        hosts: List<String> = listOf("192.168.43.1")
    ) = UltraSharePairingReadiness.canShowHotspotQr(
        receiving, control, data, hotspot, peers, secret, ssid, password, hosts
    )

    @Test fun wifiDirectQrWaitsForControlButNotTheOptionalDataListener() {
        assertTrue(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 0,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = false,
                dataChannelReady = true,
                activePeerCount = 0,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertTrue(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = false,
                activePeerCount = 0,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = false,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 0,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertTrue(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 1,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertTrue(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = false,
                activePeerCount = 8,
                pairingSecret = "123456789",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 0,
                pairingSecret = "bad",
                invitationId = "ephemeral-peer-123"
            )
        )
        assertFalse(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 0,
                pairingSecret = "123456789",
                invitationId = null
            )
        )
    }

    @Test fun lanFallbackQrRequiresExplicitFallbackAndSameLanAddress() {
        fun canShow(
            fallback: Boolean,
            hosts: List<String> = listOf("192.168.1.20"),
            data: Boolean = true,
            peers: Int = 0
        ) = UltraSharePairingReadiness.canShowLanQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = data,
                lanFallbackReady = fallback,
                activePeerCount = peers,
                pairingSecret = "123456789",
                localAddresses = hosts
            )
        assertTrue(canShow(true))
        assertTrue(canShow(true, data = false))
        assertFalse(canShow(false))
        assertFalse(canShow(true, emptyList()))
        assertFalse(canShow(true, listOf("8.8.8.8")))
        assertFalse(
            UltraSharePairingReadiness.canShowLanQr(
                receiving = false,
                controlChannelReady = true,
                dataChannelReady = true,
                lanFallbackReady = true,
                activePeerCount = 0,
                pairingSecret = "123456789",
                localAddresses = listOf("192.168.1.20")
            )
        )
    }

    @Test fun qrWaitsForTheControlListenerButNotTheOptionalDataListener() {
        assertTrue(ready())
        assertFalse(ready(control = false))
        assertTrue(ready(data = false))
        assertFalse(ready(control = false, data = false))
    }

    @Test fun qrWaitsForEveryHotspotInvitationRequirement() {
        assertFalse(ready(receiving = false))
        assertFalse(ready(hotspot = false))
        assertTrue(ready(peers = 1))
        assertFalse(ready(secret = "123"))
        assertFalse(ready(ssid = null))
        assertFalse(ready(password = ""))
        assertFalse(ready(hosts = emptyList()))
        assertFalse(ready(hosts = listOf("8.8.8.8")))
    }

    @Test fun initialUnformedCallbacksDoNotClearTheCachedP2pAddressButRealDisconnectsDo() {
        assertFalse(shouldClearWifiDirectLocalAddress(groupFormed = false, wasConnected = false))
        assertFalse(shouldClearWifiDirectLocalAddress(groupFormed = true, wasConnected = true))
        assertTrue(shouldClearWifiDirectLocalAddress(groupFormed = false, wasConnected = true))
    }

    @Test fun ownerPeerPollingKeepsEmptyRoomsOpenAndChecksVisiblePeersMoreOften() {
        assertEquals(2_000L, wifiDirectOwnerPeerConfirmationDelayMs(hasObservedClient = false))
        assertEquals(500L, wifiDirectOwnerPeerConfirmationDelayMs(hasObservedClient = true))
    }

    @Test fun ownerGroupIsNotPairedUntilAndroidReportsAClient() {
        assertFalse(isConfirmedWifiDirectOwnerPeer(
            groupFormed = false, isGroupOwner = true, clientCount = 1
        ))
        assertFalse(isConfirmedWifiDirectOwnerPeer(
            groupFormed = true, isGroupOwner = false, clientCount = 1
        ))
        assertFalse(isConfirmedWifiDirectOwnerPeer(
            groupFormed = true, isGroupOwner = true, clientCount = 0
        ))
        assertTrue(isConfirmedWifiDirectOwnerPeer(
            groupFormed = true, isGroupOwner = true, clientCount = 1
        ))
        assertTrue(isConfirmedWifiDirectOwnerPeer(
            groupFormed = true, isGroupOwner = true, clientCount = 2
        ))
    }

    @Test fun ownerPeerConfirmationRetriesAreBounded() {
        assertTrue(shouldRetryWifiDirectPeerConfirmation(0))
        assertTrue(shouldRetryWifiDirectPeerConfirmation(69))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(70))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(-1))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(0, maxAttempts = 0))
        assertTrue(shouldRetryWifiDirectPeerConfirmation(2, maxAttempts = 3))
        assertFalse(shouldRetryWifiDirectPeerConfirmation(3, maxAttempts = 3))
    }

    @Test fun connectedCallbackIsEmittedOnlyForANewConnectionTransition() {
        assertTrue(shouldEmitWifiDirectConnected(wasConnected = false))
        assertFalse(shouldEmitWifiDirectConnected(wasConnected = true))
    }

    @Test fun listenerReadinessRequiresTheExpectedBoundPort() {
        assertTrue(UltraSharePairingReadiness.isDataListenerReady(38742, 38742))
        assertFalse(UltraSharePairingReadiness.isDataListenerReady(0, 38742))
        assertFalse(UltraSharePairingReadiness.isDataListenerReady(38743, 38742))
        assertFalse(UltraSharePairingReadiness.isDataListenerReady(38742, 0))
    }

    @Test fun primaryWifiDirectStartsEvenWhenLanRouteExists() {
        assertTrue(
            UltraSharePairingReadiness.shouldStartPrimaryWifiDirect(
                receiving = true,
                hotspotActive = false,
                hotspotFallbackStarted = false,
                invitationIdBlank = true,
                wifiDirectAlreadyAttempted = false,
                hasActivePeers = false
            )
        )
        assertFalse(
            UltraSharePairingReadiness.shouldStartPrimaryWifiDirect(
                receiving = true,
                hotspotActive = false,
                hotspotFallbackStarted = true,
                invitationIdBlank = true,
                wifiDirectAlreadyAttempted = false,
                hasActivePeers = false
            )
        )
        assertFalse(
            UltraSharePairingReadiness.shouldStartPrimaryWifiDirect(
                receiving = true,
                hotspotActive = false,
                hotspotFallbackStarted = false,
                invitationIdBlank = false,
                wifiDirectAlreadyAttempted = false,
                hasActivePeers = false
            )
        )
    }

    @Test fun qrJoinPlanPrefersWifiDirectFirstThenFallsBackToLanBeforeHotspot() {
        assertEquals(
            listOf(
                UltraSharePairingReadiness.QrJoinTransport.WIFI_DIRECT,
                UltraSharePairingReadiness.QrJoinTransport.LAN,
                UltraSharePairingReadiness.QrJoinTransport.HOTSPOT
            ),
            UltraSharePairingReadiness.resolveQrJoinRoutePlan(
                mode = "p2p",
                invitationId = "invite-1",
                hasWifiCredentials = true,
                hasLanHosts = true
            )
        )
        assertEquals(
            listOf(
                UltraSharePairingReadiness.QrJoinTransport.WIFI_DIRECT,
                UltraSharePairingReadiness.QrJoinTransport.LAN
            ),
            UltraSharePairingReadiness.resolveQrJoinRoutePlan(
                mode = null,
                invitationId = "legacy-invite",
                hasWifiCredentials = false,
                hasLanHosts = true
            )
        )
        assertEquals(
            listOf(
                UltraSharePairingReadiness.QrJoinTransport.LAN,
                UltraSharePairingReadiness.QrJoinTransport.WIFI_DIRECT,
                UltraSharePairingReadiness.QrJoinTransport.HOTSPOT
            ),
            UltraSharePairingReadiness.resolveQrJoinRoutePlan(
                mode = "lan",
                invitationId = "invite-1",
                hasWifiCredentials = true,
                hasLanHosts = true
            )
        )
        assertEquals(
            listOf(
                UltraSharePairingReadiness.QrJoinTransport.HOTSPOT,
                UltraSharePairingReadiness.QrJoinTransport.LAN
            ),
            UltraSharePairingReadiness.resolveQrJoinRoutePlan(
                mode = "hotspot",
                invitationId = null,
                hasWifiCredentials = true,
                hasLanHosts = true
            )
        )
        assertEquals(
            listOf(
                UltraSharePairingReadiness.QrJoinTransport.WIFI_DIRECT,
                UltraSharePairingReadiness.QrJoinTransport.HOTSPOT,
                UltraSharePairingReadiness.QrJoinTransport.LAN
            ),
            UltraSharePairingReadiness.resolveQrJoinRoutePlan(
                mode = "p2p",
                invitationId = "invite-1",
                hasWifiCredentials = true,
                hasLanHosts = true,
                preferHotspotOverLan = true
            )
        )
    }

}
