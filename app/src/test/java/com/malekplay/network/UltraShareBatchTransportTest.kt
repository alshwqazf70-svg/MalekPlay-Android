package com.malekplay.network

import java.nio.file.Files
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareBatchTransportTest {
    @Test
    fun readAtMostMakesProgressWhenBulkReadReturnsZero() {
        val delegate = java.io.ByteArrayInputStream(byteArrayOf(4, 8, 15, 16, 23, 42))
        val unusual = object : java.io.InputStream() {
            private var returnZero = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }
        val buffer = ByteArray(8)
        val count = UltraShareBatchTransport.readAtMost(unusual, buffer, buffer.size)
        assertEquals(6, count)
        assertEquals(listOf<Byte>(4, 8, 15, 16, 23, 42), buffer.take(count))
        assertEquals(0, UltraShareBatchTransport.readAtMost(unusual, buffer, buffer.size))
    }

    @Test
    fun zeroByteFilesRemainValidAcrossBatchManifestEncoding() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0))
        val entry = UltraShareBatchProtocol.newEntry(
            name = "empty.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 0L,
            sha256 = digest
        )
        val manifest = UltraShareBatchProtocol.Manifest(
            batchId = java.util.UUID.randomUUID().toString(),
            entries = listOf(entry)
        )

        val decoded = UltraShareBatchProtocol.decode(UltraShareBatchProtocol.encode(manifest))
        assertEquals(0L, decoded.entries.single().sizeBytes)
        assertEquals(digest.toList(), decoded.entries.single().sha256.toList())
    }
    @Test
    fun cancellingTransferJobClosesBlockedSocketReadImmediately() = runBlocking {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val readStarted = CompletableDeferred<Unit>()
        val transfer = launch(Dispatchers.IO) {
            val cancellationHandle = UltraShareBatchTransport.closeSocketOnJobCancellation(
                currentCoroutineContext()[Job] ?: error("Missing transfer Job"),
                client
            )
            try {
                readStarted.complete(Unit)
                try {
                    client.getInputStream().read()
                } catch (failure: SocketException) {
                    // Closing the socket is the cancellation signal that releases read().
                    if (currentCoroutineContext().isActive) throw failure
                }
            } finally {
                cancellationHandle.dispose()
                client.close()
            }
        }

        try {
            withTimeout(2_000L) { readStarted.await() }
            withTimeout(2_000L) { transfer.cancelAndJoin() }
            val remoteEndOfStream = withTimeout(2_000L) {
                withContext(Dispatchers.IO) { accepted.getInputStream().read() }
            }
            assertEquals(-1, remoteEndOfStream)
        } finally {
            client.close()
            transfer.cancelAndJoin()
            accepted.close()
            server.close()
        }
    }

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
    fun resumeUsesOnlyCheckpointedBytesAndTruncatesUncommittedTail() {
        val directory = Files.createTempDirectory("ultrashare-resume-offset").toFile()
        try {
            val payload = ByteArray(8192) { it.toByte() }
            val digestHex = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload).joinToString("") { "%02x".format(it) }
            val partial = java.io.File(directory, "payload.part")
            val checkpoint = java.io.File(directory, "payload.resume")
            val checkpointTemp = java.io.File(directory, "payload.resume.tmp")
            partial.writeBytes(payload.copyOfRange(0, 4096))

            // No checkpoint means the existing bytes are not authorized for resume.
            assertEquals(0L, resumableFileOffset(
                partial, checkpoint, payload.size.toLong(), digestHex
            ))
            assertEquals(0L, partial.length())

            java.io.FileOutputStream(partial).use { output ->
                output.write(payload, 0, 3072)
                output.fd.sync()
            }
            writeUltraShareResumeCheckpointAtomically(
                checkpointTemp, checkpoint, payload.size.toLong(), digestHex, 3072L
            )
            java.io.FileOutputStream(partial, true).use { output ->
                output.write(payload, 3072, 1024)
                output.fd.sync()
            }

            assertEquals(3072L, resumableFileOffset(
                partial, checkpoint, payload.size.toLong(), digestHex
            ))
            assertEquals(3072L, partial.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedTransferResumesFromCheckpointWithoutDuplicatingBytes() {
        val directory = Files.createTempDirectory("ultrashare-interrupted-resume").toFile()
        try {
            val payload = ByteArray(32_777) { (it * 31 % 251).toByte() }
            val digestHex = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload).joinToString("") { "%02x".format(it) }
            val partial = java.io.File(directory, "transfer.part")
            val checkpoint = java.io.File(directory, "transfer.resume")
            val checkpointTemp = java.io.File(directory, "transfer.resume.tmp")
            val interruptedAt = 12_345
            java.io.FileOutputStream(partial).use { output ->
                output.write(payload, 0, interruptedAt)
                output.fd.sync()
            }
            writeUltraShareResumeCheckpointAtomically(
                checkpointTemp, checkpoint, payload.size.toLong(), digestHex, interruptedAt.toLong()
            )

            val resumeOffset = resumableFileOffset(
                partial, checkpoint, payload.size.toLong(), digestHex
            )
            assertEquals(interruptedAt.toLong(), resumeOffset)

            java.io.RandomAccessFile(partial, "rw").use { output ->
                output.seek(resumeOffset)
                output.write(payload, resumeOffset.toInt(), payload.size - resumeOffset.toInt())
                output.fd.sync()
            }

            org.junit.Assert.assertArrayEquals(payload, partial.readBytes())
            org.junit.Assert.assertEquals(payload.size.toLong(), partial.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeInterruptedTransferResumesFromDurableOffsetWithExactSha256() {
        val directory = Files.createTempDirectory("ultrashare-large-resume").toFile()
        try {
            // Exercise a multi-megabyte file and a resume point that is not aligned
            // to the sender's normal frame size.
            val payload = ByteArray(16 * 1024 * 1024 + 137) { index ->
                ((index * 31 + index / 251) % 256).toByte()
            }
            val partial = java.io.File(directory, "large-transfer.part")
            val checkpoint = java.io.File(directory, "large-transfer.resume")
            val checkpointTemp = java.io.File(directory, "large-transfer.resume.tmp")
            val interruptedAt = 7 * 1024 * 1024 + 333
            val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload)
            val digestHex = expectedDigest.joinToString("") { "%02x".format(it) }

            java.io.FileOutputStream(partial).use { output ->
                output.write(payload, 0, interruptedAt)
                output.fd.sync()
            }
            writeUltraShareResumeCheckpointAtomically(
                checkpointTemp, checkpoint, payload.size.toLong(), digestHex, interruptedAt.toLong()
            )

            val resumeOffset = resumableFileOffset(
                partial, checkpoint, payload.size.toLong(), digestHex
            )
            org.junit.Assert.assertEquals(interruptedAt.toLong(), resumeOffset)

            java.io.RandomAccessFile(partial, "rw").use { output ->
                output.seek(resumeOffset)
                output.write(payload, resumeOffset.toInt(), payload.size - resumeOffset.toInt())
                output.fd.sync()
            }

            val actualDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(partial.readBytes())

            org.junit.Assert.assertEquals(payload.size.toLong(), partial.length())
            org.junit.Assert.assertArrayEquals(expectedDigest, actualDigest)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedUncommittedTailIsTruncatedToLastCheckpoint() {
        val directory = Files.createTempDirectory("ultrashare-invalid-resume").toFile()
        try {
            val partial = java.io.File(directory, "corrupt.part")
            val checkpoint = java.io.File(directory, "corrupt.resume")
            val digestHex = "ab".repeat(32)
            partial.writeBytes(ByteArray(4097) { 7 })
            checkpoint.writeText("4096:$digestHex:4096", Charsets.UTF_8)
            assertEquals(
                4096L,
                resumableFileOffset(partial, checkpoint, 4096L, digestHex)
            )
            assertEquals(4096L, partial.length())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                resumableFileOffset(partial, checkpoint, -1L, digestHex)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun checkpointForDifferentContentIsRejectedAndPartialIsDiscarded() {
        val directory = Files.createTempDirectory("ultrashare-checkpoint-identity").toFile()
        try {
            val partial = java.io.File(directory, "payload.part")
            val checkpoint = java.io.File(directory, "payload.resume")
            partial.writeBytes(ByteArray(2048) { 4 })
            checkpoint.writeText("4096:abababababababababababababababababababababababababababababababab:2048", Charsets.UTF_8)

            assertEquals(
                0L,
                resumableFileOffset(
                    partial, checkpoint, 4096L, "cd".repeat(32)
                )
            )
            assertEquals(0L, partial.length())
            assertTrue(!checkpoint.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun checkpointAheadOfPartialFileIsRejected() {
        val directory = Files.createTempDirectory("ultrashare-checkpoint-ahead").toFile()
        try {
            val partial = java.io.File(directory, "payload.part")
            val checkpoint = java.io.File(directory, "payload.resume")
            val digest = "ef".repeat(32)
            partial.writeBytes(ByteArray(100) { 1 })
            checkpoint.writeText("1000:$digest:200", Charsets.UTF_8)

            assertEquals(
                0L,
                resumableFileOffset(partial, checkpoint, 1000L, digest)
            )
            assertEquals(0L, partial.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedCheckpointReplacementPreservesPreviousDurableOffset() {
        val directory = Files.createTempDirectory("ultrashare-checkpoint-preserve").toFile()
        try {
            val temp = java.io.File(directory, "checkpoint.tmp")
            val target = java.io.File(directory, "checkpoint")
            temp.mkdir() // Force the temporary-file write to fail before replacement.
            target.writeText("previous durable checkpoint", Charsets.UTF_8)
            val failure = runCatching {
                writeUltraShareResumeCheckpointAtomically(
                    temp, target, 1024L, "12".repeat(32), 512L
                )
            }.exceptionOrNull()
            assertTrue(failure != null)
            assertEquals("previous durable checkpoint", target.readText(Charsets.UTF_8))
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
            marker.writeText("previous durable receipt", Charsets.UTF_8)

            writeUltraShareReceiptAtomically(temporary, marker, content)

            org.junit.Assert.assertTrue(marker.isFile)
            org.junit.Assert.assertEquals(content, marker.readText(Charsets.UTF_8))
            org.junit.Assert.assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedReceiptWritePreservesThePreviousDurableMarker() {
        val directory = Files.createTempDirectory("ultrashare-receipt-preserve").toFile()
        try {
            val temporary = java.io.File(directory, "receipt.tmp")
            val marker = java.io.File(directory, "receipt.done")
            marker.writeText("previous durable receipt", Charsets.UTF_8)
            assertTrue(temporary.mkdir())

            val failure = runCatching {
                writeUltraShareReceiptAtomically(temporary, marker, "replacement receipt")
            }.exceptionOrNull()

            org.junit.Assert.assertTrue(failure != null)
            org.junit.Assert.assertEquals(
                "previous durable receipt",
                marker.readText(Charsets.UTF_8)
            )
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
            val checkpoint = java.io.File(directory, "missing.resume")
            assertTrue(!partial.exists())
            assertEquals(
                0L,
                resumableFileOffset(
                    partial, checkpoint, 8192L, "ab".repeat(32)
                )
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verificationTimeoutScalesWithBatchSizeAndStaysBounded() {
        assertEquals(
            120_000,
            UltraShareBatchTransport.verificationTimeoutMillis(0L)
        )
        assertEquals(
            512_000,
            UltraShareBatchTransport.verificationTimeoutMillis(1024L * 1024L * 1024L)
        )
        assertEquals(
            5 * 60 * 60 * 1_000 + 30 * 60 * 1_000,
            UltraShareBatchTransport.verificationTimeoutMillis(Long.MAX_VALUE / 2L)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun verificationTimeoutRejectsNegativeBatchSize() {
        UltraShareBatchTransport.verificationTimeoutMillis(-1L)
    }
}
