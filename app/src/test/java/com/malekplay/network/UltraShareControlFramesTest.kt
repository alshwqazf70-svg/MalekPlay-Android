package com.malekplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraShareControlFramesTest {
    private val master = ByteArray(32) { (it + 1).toByte() }
    private val transcript = "challenge-and-request-id-0001".toByteArray()

    private fun pair(): Pair<UltraShareControlFrames.Session, UltraShareControlFrames.Session> =
        UltraShareControlFrames.createSession(master, transcript, true) to
            UltraShareControlFrames.createSession(master, transcript, false)

    @Test fun clientAndServerExchangeInBothDirections() {
        val (client, server) = pair()
        client.use { server.use {
            val first = "roster announcement".toByteArray()
            assertArrayEquals(first, server.open(client.seal(first)))
            val reply = "roster acknowledged".toByteArray()
            assertArrayEquals(reply, client.open(server.seal(reply)))
        } }
    }

    @Test fun eachDirectionUsesIndependentKeysAndSequenceSpace() {
        val (client, server) = pair()
        client.use { server.use {
            val c2s = client.seal(byteArrayOf(1, 2, 3))
            val s2c = server.seal(byteArrayOf(1, 2, 3))
            assertNotEquals(c2s.toList(), s2c.toList())
            assertArrayEquals(byteArrayOf(1, 2, 3), server.open(c2s))
            assertArrayEquals(byteArrayOf(1, 2, 3), client.open(s2c))
        } }
    }

    @Test fun alteredCiphertextIsRejected() {
        val (client, server) = pair()
        client.use { server.use {
            val frame = client.seal("auth me".toByteArray())
            frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
            assertThrows(SecurityException::class.java) { server.open(frame) }
        } }
    }

    @Test fun replayIsRejected() {
        val (client, server) = pair()
        client.use { server.use {
            val frame = client.seal(byteArrayOf(7))
            assertArrayEquals(byteArrayOf(7), server.open(frame))
            assertThrows(IllegalArgumentException::class.java) { server.open(frame) }
        } }
    }

    @Test fun outOfOrderFrameIsRejectedWithoutAdvancingReceiver() {
        val (client, server) = pair()
        client.use { server.use {
            client.seal(byteArrayOf(1))
            val second = client.seal(byteArrayOf(2))
            assertThrows(IllegalArgumentException::class.java) { server.open(second) }
        } }
    }

    @Test fun wrongHandshakeSecretFailsAuthentication() {
        val client = UltraShareControlFrames.createSession(master, transcript, true)
        val server = UltraShareControlFrames.createSession(ByteArray(32) { 99 }, transcript, false)
        client.use { server.use {
            assertThrows(SecurityException::class.java) { server.open(client.seal(byteArrayOf(4))) }
        } }
    }

    @Test fun differentTranscriptFailsAuthentication() {
        val client = UltraShareControlFrames.createSession(master, transcript, true)
        val server = UltraShareControlFrames.createSession(master, "different-transcript-value".toByteArray(), false)
        client.use { server.use {
            assertThrows(SecurityException::class.java) { server.open(client.seal(byteArrayOf(4))) }
        } }
    }

    @Test fun payloadBoundsAreEnforced() {
        val (client, server) = pair()
        client.use { server.use {
            assertThrows(IllegalArgumentException::class.java) {
                client.seal(ByteArray(UltraShareControlFrames.MAX_PLAINTEXT_BYTES + 1))
            }
            assertThrows(IllegalArgumentException::class.java) { server.open(ByteArray(8)) }
        } }
    }

    @Test fun closedSessionCannotBeUsed() {
        val (client, server) = pair()
        client.close()
        server.use {
            assertThrows(IllegalStateException::class.java) { client.seal(byteArrayOf(1)) }
        }
    }
}
