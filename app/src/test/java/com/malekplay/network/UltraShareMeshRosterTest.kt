package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UltraShareMeshRosterTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun member(id: String, host: String) = UltraShareMeshRoster.Member(
        peerId = id,
        displayName = "Device $id",
        candidates = listOf(
            UltraShareNegotiation.Candidate(
                transport = UltraShareNegotiation.Transport.WLAN,
                host = host,
                port = UltraShareBatchTransport.DEFAULT_PORT,
                connected = true,
                reachable = true,
                priority = 10
            )
        )
    )

    private fun snapshot() = UltraShareMeshRoster.Snapshot(
        roomId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        issuerPeerId = "11111111-1111-4111-8111-111111111111",
        revision = 7,
        createdAtMillis = 1_800_000_000_000L,
        members = listOf(
            member("11111111-1111-4111-8111-111111111111", "192.168.1.10"),
            member("22222222-2222-4222-8222-222222222222", "192.168.1.11"),
            member("33333333-3333-4333-8333-333333333333", "192.168.1.12")
        )
    )

    @Test fun authenticatedRosterRoundTripsAndNormalizesIds() {
        val decoded = UltraShareMeshRoster.decode(UltraShareMeshRoster.encode(snapshot(), key), key)
        assertNotNull(decoded)
        assertEquals(7L, decoded!!.revision)
        assertEquals(3, decoded.members.size)
        assertEquals("aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa", decoded.roomId)
        assertEquals("11111111111141118111111111111111", decoded.issuerPeerId)
    }

    @Test fun rosterIsDeterministicRegardlessOfMemberInputOrder() {
        val original = snapshot()
        assertEquals(
            UltraShareMeshRoster.encode(original, key),
            UltraShareMeshRoster.encode(original.copy(members = original.members.reversed()), key)
        )
    }

    @Test fun tamperedRosterIsRejected() {
        val encoded = UltraShareMeshRoster.encode(snapshot(), key)
        val changed = encoded.dropLast(1) + if (encoded.last() == '0') "1" else "0"
        assertNull(UltraShareMeshRoster.decode(changed, key))
    }

    @Test fun wrongRoomKeyIsRejected() {
        val encoded = UltraShareMeshRoster.encode(snapshot(), key)
        assertNull(UltraShareMeshRoster.decode(encoded, ByteArray(32) { 99 }))
    }

    @Test fun invalidRoomKeysAreRejected() {
        assertFalse(runCatching { UltraShareMeshRoster.encode(snapshot(), ByteArray(16)) }.isSuccess)
    }

    @Test fun duplicatePeerIdentityIsRejected() {
        val original = snapshot()
        assertFalse(runCatching {
            UltraShareMeshRoster.encode(original.copy(members = original.members + original.members.first()), key)
        }.isSuccess)
    }

    @Test fun issuerMustBePartOfTheRoom() {
        val invalid = snapshot().copy(issuerPeerId = "99999999-9999-4999-8999-999999999999")
        assertFalse(runCatching { UltraShareMeshRoster.encode(invalid, key) }.isSuccess)
    }

    @Test fun invalidPublicRouteIsRejected() {
        val original = snapshot()
        val invalid = original.copy(members = original.members.mapIndexed { index, member ->
            if (index == 1) member.copy(candidates = listOf(
                UltraShareNegotiation.Candidate(
                    UltraShareNegotiation.Transport.WLAN, "8.8.8.8",
                    UltraShareBatchTransport.DEFAULT_PORT, reachable = true
                )
            )) else member
        })
        assertFalse(runCatching { UltraShareMeshRoster.encode(invalid, key) }.isSuccess)
    }

    @Test fun revisionMustBePositiveAndRosterMustContainAtLeastTwoPeers() {
        assertFalse(runCatching { UltraShareMeshRoster.encode(snapshot().copy(revision = 0), key) }.isSuccess)
        assertFalse(runCatching {
            UltraShareMeshRoster.encode(snapshot().copy(members = listOf(snapshot().members.first())), key)
        }.isSuccess)
    }
}
