package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareDiscoveryTest {
    @Test
    fun stablePeerIdWinsOverChangingNetworkAddress() {
        val first = UltraShareDiscovery.Peer(
            host = "192.168.1.10",
            port = UltraShareBatchTransport.DEFAULT_PORT,
            name = "MalekPlay",
            peerId = "stable-device-id"
        )
        val second = first.copy(host = "192.168.49.1")

        assertEquals(
            UltraShareDiscovery.identityKey(first),
            UltraShareDiscovery.identityKey(second)
        )
    }

    @Test
    fun legacyPeersFallBackToTheirLanAddress() {
        val peer = UltraShareDiscovery.Peer(
            host = "192.168.1.15",
            port = UltraShareTransport.DEFAULT_PORT,
            name = "Legacy device"
        )
        assertEquals("host:192.168.1.15", UltraShareDiscovery.identityKey(peer))
    }
}
