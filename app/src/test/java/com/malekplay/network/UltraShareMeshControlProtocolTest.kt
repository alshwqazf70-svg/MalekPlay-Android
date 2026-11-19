package com.malekplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraShareMeshControlProtocolTest {
    private val room = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val peer = "11111111111111111111111111111111"
    private val key = ByteArray(32) { (it + 3).toByte() }

    @Test fun rejectsHexIdsThatDoNotRepresentExactlySixteenBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.keyOffer("aaaaaaaaaaaaaaaa", peer, 1, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.keyOffer(room, "1111111111111111", 1, key)
        }
    }

    @Test fun keyOfferRoundTripsOnlyAsFixedLengthSecretPayload() {
        val message = UltraShareMeshControlProtocol.keyOffer(room, peer, 1, key)
        val decoded = UltraShareMeshControlProtocol.decode(UltraShareMeshControlProtocol.encode(message))
        assertEquals(UltraShareMeshControlProtocol.Type.KEY_OFFER, decoded?.type)
        assertArrayEquals(key, decoded?.payload)
    }

    @Test fun rosterUpdateRoundTripsWithBoundedWirePayload() {
        val wire = "MPMESH|1|aabbcc"
        val message = UltraShareMeshControlProtocol.rosterUpdate(room, peer, 9, wire)
        val decoded = UltraShareMeshControlProtocol.decode(UltraShareMeshControlProtocol.encode(message))
        assertEquals(9L, decoded?.revision)
        assertEquals(wire, decoded?.payload?.toString(Charsets.UTF_8))
    }

    @Test fun acknowledgementsRequireSha256Digest() {
        val ack = UltraShareMeshControlProtocol.acknowledgement(
            UltraShareMeshControlProtocol.Type.ROSTER_ACK, room, peer, 4, ByteArray(32)
        )
        assertEquals(32, UltraShareMeshControlProtocol.decode(UltraShareMeshControlProtocol.encode(ack))?.payload?.size)
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.acknowledgement(
                UltraShareMeshControlProtocol.Type.KEY_ACK, room, peer, 4, ByteArray(8)
            )
        }
    }

    @Test fun malformedIdentityRevisionAndPayloadAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.keyOffer("bad", peer, 1, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.keyOffer(room, peer, 0, key)
        }
        assertNull(UltraShareMeshControlProtocol.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun tamperedTypeAndTrailingBytesAreRejected() {
        val encoded = UltraShareMeshControlProtocol.encode(
            UltraShareMeshControlProtocol.keyOffer(room, peer, 1, key)
        )
        val tampered = encoded.copyOf().also { it[5] = 99 }
        assertNull(UltraShareMeshControlProtocol.decode(tampered))
        assertNull(UltraShareMeshControlProtocol.decode(encoded + byteArrayOf(0)))
    }

    @Test fun rosterPayloadCannotExceedProtocolBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareMeshControlProtocol.rosterUpdate(room, peer, 1, "x".repeat(13 * 1024))
        }
    }
}
