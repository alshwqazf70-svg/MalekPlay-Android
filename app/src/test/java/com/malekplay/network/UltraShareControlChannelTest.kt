package com.malekplay.network

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareControlChannelTest {
    @Test
    fun authenticatedRequestWaitsForAndReceivesApproval() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "123456789"
        val stablePeerId = "0123456789abcdef0123456789abcdef"
        val sessionClosed = CompletableDeferred<Unit>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver Phone",
                localPeerId = "fedcba9876543210fedcba9876543210",
                onSessionClosed = { sessionClosed.complete(Unit) },
                onDFileServerReady = { _, dfileKey ->
                    assertEquals(16, dfileKey.size)
                    39_001
                }
            ) { request ->
                assertEquals("Sender Phone", request.peerName)
                assertTrue(request.peerAddress == "127.0.0.1")
                assertEquals(stablePeerId, request.peerId)
                UltraShareControlChannel.Decision.ACCEPTED
            }
        }
        delay(150)
        val decision = UltraShareControlChannel.request(
            host = "127.0.0.1",
            pairingSecret = secret,
            peerName = "Sender Phone",
            port = port,
            peerId = stablePeerId
        )
        assertEquals(UltraShareControlChannel.Decision.ACCEPTED, decision)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isServerSessionActive("127.0.0.1"))
        assertEquals("Receiver Phone", UltraShareControlChannel.sessionPeerName("127.0.0.1"))
        assertEquals("fedcba9876543210fedcba9876543210", UltraShareControlChannel.sessionPeerId("127.0.0.1"))
        assertEquals(stablePeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertEquals(39_001, UltraShareControlChannel.clientDFilePort("127.0.0.1"))
        assertTrue(UltraShareControlChannel.clientDFileKey("127.0.0.1")?.size == 16)
        assertTrue(UltraShareControlChannel.serverDFileKey("127.0.0.1")?.size == 16)

        // A second malformed peer must be isolated in its own worker and must not
        // tear down the already approved heartbeat session.
        Socket().use { malformed ->
            malformed.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
        }
        delay(150)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        UltraShareControlChannel.closeSession("127.0.0.1")
        withTimeout(2_000L) { sessionClosed.await() }
        assertEquals(null, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertEquals(null, UltraShareControlChannel.serverDFileKey("127.0.0.1"))
        server.cancelAndJoin()
    }

    @Test
    fun reconnectingPeerReplacesItsOldSessionWithoutStaleDisconnect() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "246813579"
        val firstPeerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val secondPeerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver"
            ) { UltraShareControlChannel.Decision.ACCEPTED }
        }
        delay(150)

        assertEquals(
            UltraShareControlChannel.Decision.ACCEPTED,
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = secret,
                peerName = "Sender one",
                port = port,
                peerId = firstPeerId
            )
        )
        assertEquals(firstPeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertEquals(null, UltraShareControlChannel.sessionPeerId("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        assertEquals(
            UltraShareControlChannel.Decision.ACCEPTED,
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = secret,
                peerName = "Sender two",
                port = port,
                peerId = secondPeerId
            )
        )
        assertEquals(secondPeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isServerSessionActive("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        UltraShareControlChannel.closeSession("127.0.0.1")
        assertFalse(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        UltraShareControlChannel.closeServerSession("127.0.0.1")
        server.cancelAndJoin()
    }

    @Test
    fun rejectedRequestReturnsEncryptedRejection() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "987654321"
        val server = async {
            UltraShareControlChannel.serveOnce(secret, port) {
                UltraShareControlChannel.Decision.REJECTED
            }
        }
        delay(150)
        val decision = UltraShareControlChannel.request(
            host = "127.0.0.1",
            pairingSecret = secret,
            peerName = "Second Phone",
            port = port
        )
        assertEquals(UltraShareControlChannel.Decision.REJECTED, decision)
        server.cancelAndJoin()
    }
}
