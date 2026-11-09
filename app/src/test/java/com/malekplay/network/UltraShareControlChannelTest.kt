package com.malekplay.network

import java.net.ServerSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareControlChannelTest {
    @Test
    fun authenticatedRequestWaitsForAndReceivesApproval() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "123456789012"
        val server = async {
            UltraShareControlChannel.serveOnce(secret, port) { request ->
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
        server.await()
    }

    @Test
    fun rejectedRequestReturnsEncryptedRejection() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "987654321098"
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
        server.await()
    }
}
