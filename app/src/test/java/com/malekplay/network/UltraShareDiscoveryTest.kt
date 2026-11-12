package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDiscoveryTest {
    @Test
    fun stableIdentityMustBeCanonicalUuidAndCaseInsensitive() {
        assertTrue(UltraShareDiscovery.isValidStablePeerId("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(UltraShareDiscovery.isValidStablePeerId("123E4567-E89B-12D3-A456-426614174000"))
        assertFalse(UltraShareDiscovery.isValidStablePeerId(""))
        assertFalse(UltraShareDiscovery.isValidStablePeerId("123e4567e89b12d3a456426614174000"))
        assertFalse(UltraShareDiscovery.isValidStablePeerId("123e4567-e89b-12d3-a456-42661417400z"))
    }

    @Test
    fun discoveryProbeHasDedicatedNonTransferMarker() {
        val marker = UltraShareBatchTransport.discoveryProbeProof()
        assertEquals(32, marker.size)
        assertTrue(UltraShareBatchTransport.isDiscoveryProbeProof(marker))
        assertFalse(UltraShareBatchTransport.isDiscoveryProbeProof(ByteArray(32)))
        marker[0] = 0
        assertTrue(UltraShareBatchTransport.isDiscoveryProbeProof(
            UltraShareBatchTransport.discoveryProbeProof()
        ))
    }

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
    fun fallbackRoutesAreOrderedByTransportAndDiscardUnusableDuplicates() {
        val candidates = listOf(
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.HOTSPOT, "192.168.43.1",
                UltraShareBatchTransport.DEFAULT_PORT, reachable = true, priority = 99
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WLAN, "192.168.1.20",
                UltraShareBatchTransport.DEFAULT_PORT, reachable = true, priority = 1
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WIFI_DIRECT, "192.168.49.1",
                UltraShareBatchTransport.DEFAULT_PORT, reachable = true, priority = 0
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WIFI_DIRECT, "192.168.49.2",
                UltraShareBatchTransport.DEFAULT_PORT, reachable = false, priority = 100
            )
        )
        val ordered = UltraShareNegotiation.orderedCandidates(candidates)
        assertEquals(
            listOf(
                UltraShareNegotiation.Transport.WIFI_DIRECT,
                UltraShareNegotiation.Transport.WLAN,
                UltraShareNegotiation.Transport.HOTSPOT
            ),
            ordered.map { it.transport }
        )
    }

    @Test
    fun stalePeerSightingsExpireAfterTheConfiguredWindow() {
        assertTrue(UltraShareDiscovery.isSightingFresh(10_000L, 21_999L, 12_000L))
        assertTrue(UltraShareDiscovery.isSightingFresh(10_000L, 22_000L, 12_000L))
        assertFalse(UltraShareDiscovery.isSightingFresh(10_000L, 22_001L, 12_000L))
        assertFalse(UltraShareDiscovery.isSightingFresh(10_001L, 10_000L, 12_000L))
    }

    @Test
    fun nativeNstackxNameEnrichesOnlyTheMatchingStableLanIdentity() {
        val lanPeer = UltraShareDiscovery.Peer(
            host = "192.168.1.25",
            port = UltraShareBatchTransport.DEFAULT_PORT,
            name = "Old broadcast name",
            peerId = "123e4567-e89b-12d3-a456-426614174000"
        )

        val enriched = UltraShareDiscovery.mergeNativePeerName(
            lanPeer,
            "123E4567-E89B-12D3-A456-426614174000",
            "MalekPlayer Receiver"
        )

        assertEquals("MalekPlayer Receiver", enriched.name)
        assertEquals(lanPeer.host, enriched.host)
        assertEquals(lanPeer.port, enriched.port)
        assertEquals(lanPeer.peerId, enriched.peerId)
    }

    @Test
    fun nativeNstackxNameCannotRenameAnUnmatchedOrUnroutablePeer() {
        val lanPeer = UltraShareDiscovery.Peer(
            host = "192.168.1.25",
            port = UltraShareBatchTransport.DEFAULT_PORT,
            name = "Verified LAN name",
            peerId = "peer-a"
        )

        assertEquals(
            lanPeer,
            UltraShareDiscovery.mergeNativePeerName(lanPeer, "peer-b", "Spoofed name")
        )
        assertEquals(
            lanPeer,
            UltraShareDiscovery.mergeNativePeerName(lanPeer, "peer-a", "")
        )

        val nameOnlyNativePeer = UltraShareDiscovery.Peer(
            host = "",
            port = 0,
            name = "NSTACKX only",
            peerId = "peer-a"
        )
        assertEquals(
            nameOnlyNativePeer,
            UltraShareDiscovery.mergeNativePeerName(nameOnlyNativePeer, "peer-b", "Other")
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
