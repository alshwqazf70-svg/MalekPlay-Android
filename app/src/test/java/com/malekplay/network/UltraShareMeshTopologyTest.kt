package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareMeshTopologyTest {
    private fun peer(id: String, host: String = "192.168.1.${id.last() - 'A' + 10}") =
        UltraShareMeshTopology.Peer(
            peerId = id,
            displayName = "Device $id",
            candidates = listOf(
                UltraShareNegotiation.Candidate(
                    transport = UltraShareNegotiation.Transport.WLAN,
                    host = host,
                    port = UltraShareBatchTransport.DEFAULT_PORT,
                    reachable = true,
                    priority = 20
                )
            )
        )

    @Test
    fun threeDevicesRequireAllThreeDirectLinksNotOnlyHubSpokes() {
        val plan = UltraShareMeshTopology.plan(
            localPeer = peer("A"),
            discoveredPeers = listOf(peer("B"), peer("C"))
        )

        assertEquals(3, plan.requiredEdges.size)
        assertEquals(
            setOf(
                UltraShareMeshTopology.Edge.of("A", "B"),
                UltraShareMeshTopology.Edge.of("A", "C"),
                UltraShareMeshTopology.Edge.of("B", "C")
            ),
            plan.requiredEdges
        )
        assertEquals(2, plan.outgoingLinks.size)
        assertTrue(plan.outgoingLinks.all { it.edge.initiatorPeerId == "A" })
        assertFalse(plan.isComplete)
    }

    @Test
    fun deterministicInitiatorPreventsBothSidesDialingTheSamePair() {
        val localA = UltraShareMeshTopology.plan(peer("A"), listOf(peer("B")))
        val localB = UltraShareMeshTopology.plan(peer("B"), listOf(peer("A")))

        assertEquals(1, localA.outgoingLinks.size)
        assertTrue(localB.outgoingLinks.isEmpty())
        assertEquals(
            localA.requiredEdges,
            localB.requiredEdges
        )
    }

    @Test
    fun alreadyActiveEdgesAreNotScheduledAgain() {
        val ab = UltraShareMeshTopology.Edge.of("A", "B")
        val plan = UltraShareMeshTopology.plan(
            localPeer = peer("A"),
            discoveredPeers = listOf(peer("B"), peer("C")),
            activeEdges = setOf(ab)
        )

        assertTrue(ab in plan.activeEdges)
        assertFalse(ab in plan.missingEdges)
        assertEquals(1, plan.outgoingLinks.size)
        assertEquals("C", plan.outgoingLinks.single().remotePeer.peerId)
    }

    @Test
    fun unreachablePreferredRouteFallsBackToNextVerifiedRoute() {
        val remote = peer("B").copy(
            candidates = listOf(
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WIFI_DIRECT,
                    "192.168.49.2",
                    UltraShareBatchTransport.DEFAULT_PORT,
                    reachable = false,
                    priority = 30
                ),
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WLAN,
                    "192.168.1.12",
                    UltraShareBatchTransport.DEFAULT_PORT,
                    reachable = true,
                    priority = 20
                )
            )
        )
        val plan = UltraShareMeshTopology.plan(peer("A"), listOf(remote))
        assertEquals("192.168.1.12", plan.outgoingLinks.single().endpoint.host)
    }

    @Test
    fun unreachableMemberDoesNotAbortOtherReachableDirectLinks() {
        val reachable = peer("B")
        val unreachable = peer("C").copy(
            candidates = listOf(
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WLAN,
                    "192.168.1.13",
                    UltraShareBatchTransport.DEFAULT_PORT,
                    reachable = false
                )
            )
        )
        val plan = UltraShareMeshTopology.plan(peer("A"), listOf(reachable, unreachable))

        assertEquals(2, plan.requiredEdges.size)
        assertEquals(2, plan.missingEdges.size)
        assertEquals(listOf("B"), plan.outgoingLinks.map { it.remotePeer.peerId })
    }

    @Test
    fun rejectsDuplicateIdentityWithConflictingPeerDetails() {
        val first = peer("B")
        val conflicting = first.copy(displayName = "Different device")
        assertFalse(
            runCatching {
                UltraShareMeshTopology.plan(peer("A"), listOf(first, conflicting))
            }.isSuccess
        )
    }

    @Test
    fun rejectsSelfLinksAndOversizedRooms() {
        assertFalse(
            runCatching { UltraShareMeshTopology.Edge.of("A", "a") }.isSuccess
        )
        val tooMany = (('B'..'Q').map { peer(it.toString()) })
        assertFalse(
            runCatching {
                UltraShareMeshTopology.plan(peer("A"), tooMany, maxParticipants = 16)
            }.isSuccess
        )
    }

    @Test
    fun completeMeshForFourDevicesContainsSixUndirectedEdges() {
        val plan = UltraShareMeshTopology.plan(
            localPeer = peer("A"),
            discoveredPeers = listOf(peer("B"), peer("C"), peer("D"))
        )
        assertEquals(6, plan.requiredEdges.size)
        assertEquals(3, plan.outgoingLinks.size)
        assertEquals(6, plan.missingEdges.size)
    }
}
