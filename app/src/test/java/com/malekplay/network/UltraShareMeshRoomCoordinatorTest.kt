package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class UltraShareMeshRoomCoordinatorTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"

    private fun peer(id: String, host: String) = UltraShareMeshTopology.Peer(
        peerId = id,
        displayName = "Peer $id",
        candidates = listOf(UltraShareNegotiation.Candidate(
            transport = UltraShareNegotiation.Transport.WLAN,
            host = host,
            port = UltraShareBatchTransport.DEFAULT_PORT,
            connected = true,
            reachable = true,
            priority = 10
        ))
    )

    @Test fun authorityCreatesFreshRoomAndAddsAuthenticatedPeers() {
        val authority = UltraShareMeshRoomCoordinator(a, SecureRandom(byteArrayOf(1,2,3,4)))
        try {
            val initial = authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            assertTrue(initial.isAuthority)
            assertEquals(1L, initial.revision)
            authority.upsertMember(b, peer(b, "192.168.1.11"))
            val roster = authority.createRosterUpdate()
            assertEquals(2L, roster.revision)
            assertEquals(UltraShareMeshControlProtocol.Type.ROSTER_UPDATE, roster.type)
        } finally { authority.close() }
    }

    @Test fun keyOfferMustMatchAuthenticatedMpc2Identity() {
        val authority = UltraShareMeshRoomCoordinator(a)
        val joiner = UltraShareMeshRoomCoordinator(b)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            val offer = authority.createKeyOffer()
            assertThrows(IllegalArgumentException::class.java) {
                joiner.acceptKeyOffer(c, offer)
            }
            val ack = joiner.acceptKeyOffer(a, offer)
            assertEquals(UltraShareMeshControlProtocol.Type.KEY_ACK, ack.type)
            assertFalse(joiner.state().isAuthority)
        } finally { authority.close(); joiner.close() }
    }

    @Test fun joinerAcceptsOnlyAuthenticatedNewerRosterContainingItself() {
        val authority = UltraShareMeshRoomCoordinator(a)
        val joiner = UltraShareMeshRoomCoordinator(b)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            val keyAck = joiner.acceptKeyOffer(a, authority.createKeyOffer())
            assertTrue(authority.verifyAcknowledgement(b, keyAck,
                UltraShareMeshControlProtocol.Type.KEY_ACK, authority.createKeyOffer().payload))
            authority.upsertMember(b, peer(b, "192.168.1.11"))
            val update = authority.createRosterUpdate()
            val ack = joiner.acceptRosterUpdate(a, update)
            assertEquals(2L, joiner.state().revision)
            assertEquals(2, joiner.state().members.size)
            val rosterPayload = UltraShareMeshControlProtocol.decode(update.payload)!!.payload
            assertTrue(authority.verifyAcknowledgement(b, ack,
                UltraShareMeshControlProtocol.Type.ROSTER_ACK, rosterPayload))
        } finally { authority.close(); joiner.close() }
    }

    @Test fun staleRosterRevisionIsRejected() {
        val authority = UltraShareMeshRoomCoordinator(a)
        val joiner = UltraShareMeshRoomCoordinator(b)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            joiner.acceptKeyOffer(a, authority.createKeyOffer())
            authority.upsertMember(b, peer(b, "192.168.1.11"))
            val update = authority.createRosterUpdate()
            joiner.acceptRosterUpdate(a, update)
            assertThrows(IllegalArgumentException::class.java) {
                joiner.acceptRosterUpdate(a, update)
            }
        } finally { authority.close(); joiner.close() }
    }

    @Test fun authorityRemovesDisconnectedMembersAndCanInviteReplacement() {
        val authority = UltraShareMeshRoomCoordinator(a)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            authority.upsertMember(b, peer(b, "192.168.1.11"))
            authority.upsertMember(c, peer(c, "192.168.1.12"))

            val updated = authority.removeMember(b)
            assertEquals(UltraShareMeshControlProtocol.Type.ROSTER_UPDATE, updated?.type)
            assertEquals(4L, updated?.revision)
            val roster = UltraShareMeshRoster.decode(
                requireNotNull(updated).payload.toString(Charsets.UTF_8),
                authority.createKeyOffer().payload
            )
            assertEquals(setOf(a.replace("-", ""), c.replace("-", "")),
                roster?.members?.map { it.peerId }?.toSet())

            assertNull(authority.removeMember(c))
            assertEquals(1, authority.state().members.size)
            assertEquals(5L, authority.state().revision)

            authority.upsertMember(b, peer(b, "192.168.1.11"))
            assertEquals(6L, authority.state().revision)
            assertEquals(2, authority.state().members.size)
        } finally { authority.close() }
    }

    @Test fun authorityRejectsUnboundOrSelfPeerRegistration() {
        val authority = UltraShareMeshRoomCoordinator(a)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            assertThrows(IllegalArgumentException::class.java) {
                authority.upsertMember(c, peer(b, "192.168.1.11"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                authority.upsertMember(a, peer(a, "192.168.1.10"))
            }
        } finally { authority.close() }
    }

    @Test fun directLinkCredentialRequiresBothAuthenticatedRoomMembers() {
        val authority = UltraShareMeshRoomCoordinator(a)
        val joiner = UltraShareMeshRoomCoordinator(b)
        try {
            authority.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
            assertThrows(IllegalArgumentException::class.java) {
                authority.deriveDirectLinkCredential(a, c)
            }
            joiner.acceptKeyOffer(a, authority.createKeyOffer())
            authority.upsertMember(b, peer(b, "192.168.1.11"))
            val update = authority.createRosterUpdate()
            joiner.acceptRosterUpdate(a, update)

            val fromAuthority = authority.deriveDirectLinkCredential(a, b)
            val fromJoiner = joiner.deriveDirectLinkCredential(b, a)
            assertEquals(fromAuthority, fromJoiner)
            assertTrue(UltraShareMeshLinkCredential.isCredential(fromAuthority))
            assertThrows(IllegalArgumentException::class.java) {
                joiner.deriveDirectLinkCredential(b, c)
            }
        } finally {
            authority.close()
            joiner.close()
        }
    }

    @Test fun closeDestroysRoomState() {
        val coordinator = UltraShareMeshRoomCoordinator(a)
        coordinator.createAuthorityRoom(peer(a, "192.168.1.10"), 1_800_000_000_000L)
        coordinator.close()
        assertThrows(IllegalStateException::class.java) { coordinator.state() }
    }
}
