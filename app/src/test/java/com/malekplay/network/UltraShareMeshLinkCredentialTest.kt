package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraShareMeshLinkCredentialTest {
    private val roomA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val roomB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val peerA = "11111111-1111-4111-8111-111111111111"
    private val peerB = "22222222-2222-4222-8222-222222222222"
    private val peerC = "33333333-3333-4333-8333-333333333333"
    private val key = ByteArray(32) { (it * 7 + 11).toByte() }

    @Test fun bothEndpointsDeriveSameCredentialRegardlessOfDirection() {
        val ab = UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerA, peerB)
        val ba = UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerB, peerA)
        assertEquals(ab, ba)
        assertTrue(UltraShareMeshLinkCredential.isCredential(ab))
        assertTrue(UltraShareCore.isValidPairingSecret(ab))
        assertTrue(UltraShareMeshLinkCredential.matches(ab, ba))
    }

    @Test fun credentialsAreSeparatedByRoomAndPeerPair() {
        val ab = UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerA, peerB)
        val ac = UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerA, peerC)
        val otherRoom = UltraShareMeshLinkCredential.derivePairingSecret(roomB, key, peerA, peerB)
        assertFalse(UltraShareMeshLinkCredential.matches(ab, ac))
        assertFalse(UltraShareMeshLinkCredential.matches(ab, otherRoom))
    }

    @Test fun credentialsAreSeparatedByRoomKey() {
        val otherKey = key.copyOf().also { it[0] = (it[0].toInt() xor 0x55).toByte() }
        val original = UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerA, peerB)
        val changed = UltraShareMeshLinkCredential.derivePairingSecret(roomA, otherKey, peerA, peerB)
        assertFalse(UltraShareMeshLinkCredential.matches(original, changed))
        otherKey.fill(0)
    }

    @Test fun invalidInputsAndSelfLinksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshLinkCredential.derivePairingSecret(roomA, ByteArray(31), peerA, peerB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshLinkCredential.derivePairingSecret(roomA, key, peerA, peerA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshLinkCredential.derivePairingSecret("bad", key, peerA, peerB)
        }
    }

    @Test fun comparisonRejectsMalformedValues() {
        assertFalse(UltraShareMeshLinkCredential.matches("bad", "bad"))
        assertFalse(UltraShareMeshLinkCredential.matches("0".repeat(64), "0".repeat(63)))
        assertFalse(UltraShareMeshLinkCredential.isCredential("g".repeat(64)))
    }
}
