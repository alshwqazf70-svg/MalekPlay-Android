package com.malekplay.network

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareBatchTransportTest {
    @Test
    fun dataEndpointProbeConfirmsTheBoundListenerWithoutStartingATransfer() = runBlocking {
        val directory = Files.createTempDirectory("ultrashare-v2-probe").toFile()
        val localPrivateAddress = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
            ?: run {
                directory.deleteRecursively()
                return@runBlocking
            }
        val ready = CompletableDeferred<Int>()
        val server = launch {
            UltraShareBatchTransport.serveMany(
                port = 0,
                pairingSecret = "123456789",
                destinationDirectory = directory,
                onListening = { if (!ready.isCompleted) ready.complete(it) }
            )
        }
        try {
            val actualPort = withTimeout(5_000L) { ready.await() }
            assertTrue(
                UltraShareDiscovery.probeDataEndpoint(
                    localPrivateAddress,
                    actualPort,
                    emptyList()
                )
            )
        } finally {
            server.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ephemeralListenerPublishesItsAssignedPort() = runBlocking {
        val directory = Files.createTempDirectory("ultrashare-v2-listener").toFile()
        val ready = CompletableDeferred<Int>()
        val server = launch {
            UltraShareBatchTransport.serveMany(
                port = 0,
                pairingSecret = "123456789",
                destinationDirectory = directory,
                onListening = { actualPort ->
                    if (!ready.isCompleted) ready.complete(actualPort)
                }
            )
        }
        try {
            val actualPort = withTimeout(5_000L) { ready.await() }
            assertTrue(actualPort in 1..65535)
        } finally {
            server.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
