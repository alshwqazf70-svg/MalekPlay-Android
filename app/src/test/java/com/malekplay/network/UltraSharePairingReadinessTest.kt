package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePairingReadinessTest {
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
