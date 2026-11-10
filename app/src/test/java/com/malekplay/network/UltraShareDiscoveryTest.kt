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
    fun negotiationFallsBackWhenThePreferredPathCannotReachTheDataListener() {
        val candidates = listOf(
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WIFI_DIRECT,
                "192.168.49.1",
                UltraShareBatchTransport.DEFAULT_PORT,
                reachable = false,
                priority = 30
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WLAN,
                "192.168.1.20",
                UltraShareBatchTransport.DEFAULT_PORT,
                reachable = true,
                priority = 20
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.HOTSPOT,
                "192.168.43.1",
                UltraShareBatchTransport.DEFAULT_PORT,
                reachable = true,
                priority = 10
            )
        )

        assertEquals(
            UltraShareNegotiation.Transport.WLAN,
            UltraShareNegotiation.selectCandidate(candidates)?.transport
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
