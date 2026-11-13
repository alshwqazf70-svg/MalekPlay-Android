package com.malekplay.network

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Exercises the real TCP challenge/response listener without installing an APK.
 *
 * CI runners expose a private IPv4 interface; using that address keeps the
 * production private-LAN validation and peer-address checks enabled. No
 * loopback exception or production-only test switch is introduced.
 */
class UltraSharePairingIntegrationTest {

    @Test
    fun rejectsWrongPinThenPairsCorrectClientAndConsumesSession() = runBlocking {
        val host = findPrivateIpv4Address()
        val correctPin = "004219837"
        val wrongPin = "004219838"
        var pairedPeer: String? = null

        val receiver = async {
            UltraSharePairing.serveWhileActive(correctPin) { peer ->
                pairedPeer = peer
            }
        }

        try {
            // Give the receiver coroutine time to bind the production pairing port.
            delay(400)

            val rejected = runCatching {
                UltraSharePairing.connect(host, wrongPin)
            }
            assertTrue("A wrong PIN must be rejected", rejected.isFailure)
            assertTrue(
                "Wrong PIN rejection must identify an authentication failure",
                rejected.exceptionOrNull()?.message.orEmpty().contains("رفض الجهاز رمز الاقتران")
            )
            assertFalse("The receiver must remain active after a failed attempt", receiver.isCompleted)

            val pairedHost = withTimeout(20_000) {
                UltraSharePairing.connect(host, correctPin)
            }
            assertEquals(host, pairedHost)

            withTimeout(10_000) { receiver.await() }
            assertEquals(host, pairedPeer)
            assertTrue("A successful pairing consumes the one-time receiver session", receiver.isCompleted)
        } finally {
            receiver.cancelAndJoin()
        }
    }

    @Test
    fun rejectsPublicAndLoopbackAddressesBeforeConnecting() {
        assertFalse(UltraShareTransport.isValidPrivateLanHost("127.0.0.1"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("8.8.8.8"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("example.com"))
        assertTrue(UltraShareTransport.isValidPrivateLanHost("192.168.1.25"))
    }

    private fun findPrivateIpv4Address(): String {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        return addresses.firstOrNull()?.hostAddress
            ?: throw AssertionError("CI runner has no private IPv4 interface for the TCP pairing test")
    }
}
