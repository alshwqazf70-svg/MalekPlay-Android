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
