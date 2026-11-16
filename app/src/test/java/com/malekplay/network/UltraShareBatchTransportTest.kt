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

    @Test
    fun resumeUsesOnlyDurablePartialBytesWithinManifestSize() {
        val directory = Files.createTempDirectory("ultrashare-resume-offset").toFile()
        try {
            val partial = java.io.File(directory, "payload.part")
            partial.writeBytes(ByteArray(4096) { it.toByte() })

            assertTrue(partial.isFile)
            org.junit.Assert.assertEquals(
                4096L,
                UltraShareBatchTransport.resumableFileOffset(partial, 8192L)
            )
            org.junit.Assert.assertEquals(
                0L,
                UltraShareBatchTransport.resumableFileOffset(partial, 2048L)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedTransferResumesFromDurablePrefixWithoutDuplicatingBytes() {
        val directory = Files.createTempDirectory("ultrashare-interrupted-resume").toFile()
        try {
            val payload = ByteArray(32_777) { (it * 31 % 251).toByte() }
            val partial = java.io.File(directory, "transfer.part")
            val interruptedAt = 12_345
            java.io.FileOutputStream(partial).use { output ->
                output.write(payload, 0, interruptedAt)
                output.fd.sync()
            }

            // Simulate a lost socket: only the durable prefix exists on disk.
            org.junit.Assert.assertEquals(
                interruptedAt.toLong(),
                UltraShareBatchTransport.resumableFileOffset(partial, payload.size.toLong())
            )

            java.io.RandomAccessFile(partial, "rw").use { output ->
                output.seek(interruptedAt.toLong())
                output.write(payload, interruptedAt, payload.size - interruptedAt)
                output.fd.sync()
            }

            org.junit.Assert.assertArrayEquals(payload, partial.readBytes())
            org.junit.Assert.assertEquals(payload.size.toLong(), partial.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedOrInvalidPartialIsNeverUsedAsResumeOffset() {
        val directory = Files.createTempDirectory("ultrashare-invalid-resume").toFile()
        try {
            val partial = java.io.File(directory, "corrupt.part")
            partial.writeBytes(ByteArray(4097) { 7 })
            org.junit.Assert.assertEquals(
                0L,
                UltraShareBatchTransport.resumableFileOffset(partial, 4096L)
            )
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                UltraShareBatchTransport.resumableFileOffset(partial, -1L)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resumeDigestIncludesBytesSkippedBeforeTheTransferOffset() = runBlocking {
        val payload = ByteArray(8193) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
        val actual = java.security.MessageDigest.getInstance("SHA-256")

        UltraShareBatchTransport.skipAndDigest(
            java.io.ByteArrayInputStream(payload),
            4096L,
            actual
        )
        actual.update(payload, 4096, payload.size - 4096)

        org.junit.Assert.assertArrayEquals(expected, actual.digest())
    }

    @Test
    fun durableReceiptIsCommittedBeforePublicationAndCanBeReadBack() {
        val directory = Files.createTempDirectory("ultrashare-receipt").toFile()
        try {
            val temporary = java.io.File(directory, "receipt.tmp")
            val marker = java.io.File(directory, "receipt.done")
            val content = "1234:" + "ab".repeat(32) + ":movie.mp4"

            writeUltraShareReceiptAtomically(temporary, marker, content)

            org.junit.Assert.assertTrue(marker.isFile)
            org.junit.Assert.assertEquals(content, marker.readText(Charsets.UTF_8))
            org.junit.Assert.assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun receiptCommitRejectsTemporaryFileOutsideMarkerDirectory() {
        val first = Files.createTempDirectory("ultrashare-receipt-temp").toFile()
        val second = Files.createTempDirectory("ultrashare-receipt-marker").toFile()
        try {
            val failure = runCatching {
                writeUltraShareReceiptAtomically(
                    java.io.File(first, "receipt.tmp"),
                    java.io.File(second, "receipt.done"),
                    "receipt"
                )
            }.exceptionOrNull()
            org.junit.Assert.assertTrue(failure is IllegalArgumentException)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun resumeStartsAtZeroWhenPartialFileIsMissing() {
        val directory = Files.createTempDirectory("ultrashare-resume-missing").toFile()
        try {
            val partial = java.io.File(directory, "missing.part")
            assertTrue(!partial.exists())
            org.junit.Assert.assertEquals(
                0L,
                UltraShareBatchTransport.resumableFileOffset(partial, 8192L)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
