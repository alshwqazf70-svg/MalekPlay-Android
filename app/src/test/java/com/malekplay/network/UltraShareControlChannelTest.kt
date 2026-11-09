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
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareControlChannelTest {
    @Test
    fun authenticatedRequestWaitsForAndReceivesApproval() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "123456789"
        val sessionClosed = CompletableDeferred<Unit>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver Phone",
                onSessionClosed = { sessionClosed.complete(Unit) }
            ) { request ->
                assertEquals("Sender Phone", request.peerName)
                assertTrue(request.peerAddress == "127.0.0.1")
                UltraShareControlChannel.Decision.ACCEPTED
            }
        }
        delay(150)
        val decision = UltraShareControlChannel.request(
            host = "127.0.0.1",
            pairingSecret = secret,
            peerName = "Sender Phone",
            port = port
        )
        assertEquals(UltraShareControlChannel.Decision.ACCEPTED, decision)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isServerSessionActive("127.0.0.1"))
        assertEquals("Receiver Phone", UltraShareControlChannel.sessionPeerName("127.0.0.1"))

        // A second malformed peer must be isolated in its own worker and must not
        // tear down the already approved heartbeat session.
        Socket().use { malformed ->
            malformed.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
        }
        delay(150)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        UltraShareControlChannel.closeSession("127.0.0.1")
        withTimeout(2_000L) { sessionClosed.await() }
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
