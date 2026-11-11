package com.malekplay.network

import org.junit.Assert.*
import org.junit.Test

class UltraShareNegotiationTest {
    @Test fun offerRoundTripsWithoutPairingSecret() {
        val offer = UltraShareNegotiation.Offer(
            peerId = "device-123",
            displayName = "هاتف مالك",
            candidates = listOf(
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WLAN, "192.168.1.8", 38743,
                    priority = 10
                ),
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WIFI_DIRECT, "192.168.49.1", 38743,
                    priority = 1
                )
            )
        )
        val wire = UltraShareNegotiation.encode(offer)
        assertFalse(wire.contains("pairing"))
        assertEquals(offer, UltraShareNegotiation.decode(wire))
    }

    @Test fun rejectsMalformedAndUnsupportedOffers() {
        assertNull(UltraShareNegotiation.decode("MPULTRA_OFFER|9|x|y|0"))
        assertNull(UltraShareNegotiation.decode("MPULTRA_OFFER|2|x|y|999"))
        assertNull(UltraShareNegotiation.decode("not-an-offer"))
    }

    @Test fun dataPortFollowsTheSelectedPeerCandidateAndKeepsLegacyFallback() {
        val candidates = listOf(
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WLAN, "192.168.1.20", 52341,
                reachable = true
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.HOTSPOT, "192.168.43.1", 52342,
                reachable = true
            )
        )
        assertEquals(52341, UltraShareNegotiation.dataPortForHost("192.168.1.20", candidates, 38742))
        assertEquals(38742, UltraShareNegotiation.dataPortForHost("192.168.1.99", candidates, 38742))
    }

    @Test fun selectsOnlyReachableCandidatesInPreferredOrder() {
        val candidates = listOf(
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WLAN, "192.168.1.4", 38743
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.WIFI_DIRECT, "192.168.49.1", 38743,
                reachable = false
            ),
            UltraShareNegotiation.Candidate(
                UltraShareNegotiation.Transport.HOTSPOT, "192.168.43.1", 38743
            )
        )
        assertEquals(
            UltraShareNegotiation.Transport.WLAN,
            UltraShareNegotiation.selectCandidate(candidates)?.transport
        )
    }

    @Test fun selectionUsesPriorityWithinTheSameTransportAndRejectsNonLocalTargets() {
        val low = UltraShareNegotiation.Candidate(
            UltraShareNegotiation.Transport.WLAN, "192.168.1.10", 38743, priority = 1
        )
        val high = low.copy(host = "192.168.1.11", priority = 20)
        val public = low.copy(host = "8.8.8.8", priority = 100)
        assertEquals(high, UltraShareNegotiation.selectCandidate(listOf(low, public, high)))
    }

    @Test fun refreshedRoutesMatchStablePeerIdNotOldIpOrDisplayName() {
        val expected = UltraShareDiscovery.Peer(
            host = "192.168.49.1",
            port = UltraShareBatchTransport.DEFAULT_PORT,
            name = "Same display name",
            peerId = "stable-device-42",
            candidates = listOf(
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WIFI_DIRECT,
                    "192.168.49.1",
                    52344,
                    reachable = true
                )
            )
        )
        val impostor = expected.copy(
            host = "192.168.1.77",
            peerId = "different-device",
            candidates = listOf(expected.candidates.single().copy(host = "192.168.1.77"))
        )

        val routes = UltraShareNegotiation.candidatesForPeer(
            "stable-device-42",
            listOf(impostor, expected)
        )

        assertEquals(1, routes.size)
        assertEquals("192.168.49.1", routes.single().host)
        assertEquals(
            52344,
            UltraShareNegotiation.dataPortForHost(
                routes.single().host,
                routes,
                UltraShareBatchTransport.DEFAULT_PORT
            )
        )
        assertTrue(UltraShareNegotiation.candidatesForPeer(null, listOf(expected)).isEmpty())
        assertTrue(UltraShareNegotiation.candidatesForPeer("unknown", listOf(expected)).isEmpty())
    }

    @Test fun invitationCanOnlyBeDecidedOnceAndExpires() {
        val pending = UltraShareNegotiation.Invitation("i1", "peer1", 1_000)
        assertEquals(
            UltraShareNegotiation.InvitationStatus.ACCEPTED,
            pending.decide(true).status
        )
        assertEquals(
            UltraShareNegotiation.InvitationStatus.EXPIRED,
            pending.expire(31_000).status
        )
        assertThrows(IllegalArgumentException::class.java) {
            pending.decide(false).decide(true)
        }
    }

    @Test fun batchManifestValidatesIndependentFileIdentities() {
        val manifest = UltraShareBatchManifest(
            "batch-1",
            listOf(
                UltraShareBatchManifest.FileEntry("f1", "video.mp4", 12, "a".repeat(64)),
                UltraShareBatchManifest.FileEntry("f2", "song.mp3", 8, "b".repeat(64))
            )
        )
        assertEquals(2, manifest.files.size)
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchManifest(
                "batch-2",
                listOf(
                    UltraShareBatchManifest.FileEntry("same", "a", 1, "a".repeat(64)),
                    UltraShareBatchManifest.FileEntry("same", "b", 1, "b".repeat(64))
                )
            )
        }
    }
}
