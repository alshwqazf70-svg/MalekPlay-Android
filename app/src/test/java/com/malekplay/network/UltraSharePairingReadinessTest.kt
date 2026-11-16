package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePairingReadinessTest {
    @Test fun qrPayloadRemainsByteForByteStableAfterFirstPublication() {
        val first = "malekplay://share?invitation=stable&host=192.168.49.1"
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(null, first))
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(first, "malekplay://share?invitation=rotated"))
        assertEquals(first, UltraSharePairingReadiness.stableQrPayload(first, null))
        assertNull(UltraSharePairingReadiness.stableQrPayload(null, null))
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

    @Test fun wifiDirectQrWaitsForActiveGroupInvitationAndBothListeners() {
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
        assertFalse(
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
        assertFalse(
            UltraSharePairingReadiness.canShowWifiDirectQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                activePeerCount = 1,
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
        fun canShow(fallback: Boolean, hosts: List<String> = listOf("192.168.1.20")) =
            UltraSharePairingReadiness.canShowLanQr(
                receiving = true,
                controlChannelReady = true,
                dataChannelReady = true,
                lanFallbackReady = fallback,
                activePeerCount = 0,
                pairingSecret = "123456789",
                localAddresses = hosts
            )
        assertTrue(canShow(true))
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

    @Test fun qrWaitsForBothControlAndDataListeners() {
        assertTrue(ready())
        assertFalse(ready(control = false))
        assertFalse(ready(data = false))
        assertFalse(ready(control = false, data = false))
    }

    @Test fun qrWaitsForEveryHotspotInvitationRequirement() {
        assertFalse(ready(receiving = false))
        assertFalse(ready(hotspot = false))
        assertFalse(ready(peers = 1))
        assertFalse(ready(secret = "123"))
        assertFalse(ready(ssid = null))
        assertFalse(ready(password = ""))
        assertFalse(ready(hosts = emptyList()))
        assertFalse(ready(hosts = listOf("8.8.8.8")))
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

    @Test fun dataTransferRequiresBothAuthenticatedControlAndReachableDataEndpoint() {
        assertTrue(UltraSharePairingReadiness.canSendData(true, true))
        assertFalse(UltraSharePairingReadiness.canSendData(false, true))
        assertFalse(UltraSharePairingReadiness.canSendData(true, false))
        assertFalse(UltraSharePairingReadiness.canSendData(false, false))
    }
}
