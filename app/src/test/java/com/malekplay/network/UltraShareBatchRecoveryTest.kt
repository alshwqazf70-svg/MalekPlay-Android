package com.malekplay.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareBatchRecoveryTest {

    @Test
    fun failedBatchRetriesEveryFileIndependentlyAndPreservesQueueOrder() = runBlocking {
        var batchCalls = 0
        val attemptedSingles = mutableListOf<String>()
        val batchFailures = mutableListOf<String>()

        val result = UltraShareBatchRecovery.execute(
            items = listOf("first.mp4", "unreadable.mp4", "last.mp4"),
            sendBatch = { _ ->
                batchCalls++
                // Simulate a connection dropping after the receiver may already
                // have durably published an earlier item in the batch.
                throw IOException("connection reset")
            },
            sendSingle = { item ->
                attemptedSingles += item
                if (item == "unreadable.mp4") throw SecurityException("URI permission revoked")
                "SENT:$item"
            },
            onItemFailure = { item, failure ->
                "FAILED:$item:${failure.message}"
            },
            onBatchFailure = { batchFailures += it.message.orEmpty() }
        )

        assertEquals(1, batchCalls)
        assertEquals(
            listOf("first.mp4", "unreadable.mp4", "last.mp4"),
            attemptedSingles
        )
        assertEquals(
            listOf(
                "SENT:first.mp4",
                "FAILED:unreadable.mp4:URI permission revoked",
                "SENT:last.mp4"
            ),
            result
        )
        assertEquals(listOf("connection reset"), batchFailures)
    }

    @Test
    fun successfulBatchDoesNotOpenPerFileFallbackSessions() = runBlocking {
        var singleCalls = 0
        val result = UltraShareBatchRecovery.execute(
            items = listOf(1, 2, 3),
            sendBatch = { items -> items.map { "OK:$it" } },
            sendSingle = {
                singleCalls++
                "SINGLE:$it"
            },
            onItemFailure = { item, _ -> "FAILED:$item" }
        )

        assertEquals(listOf("OK:1", "OK:2", "OK:3"), result)
        assertEquals(0, singleCalls)
    }

    @Test
    fun cancellationDuringBatchIsNeverConvertedIntoPerFileRetries() = runBlocking {
        var singleCalls = 0
        val failure = runCatching {
            UltraShareBatchRecovery.execute(
                items = listOf("a", "b"),
                sendBatch = { throw CancellationException("user stopped transfer") },
                sendSingle = {
                    singleCalls++
                    it
                },
                onItemFailure = { item, _ -> item }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, singleCalls)
    }

    @Test
    fun failedSingleFileBatchDoesNotRepeatTheSameSession() = runBlocking {
        var singleCalls = 0
        val result = UltraShareBatchRecovery.execute(
            items = listOf("only.mp4"),
            sendBatch = { throw IOException("all bounded retries exhausted") },
            sendSingle = {
                singleCalls++
                "unexpected"
            },
            onItemFailure = { item, failure -> "FAILED:$item:${failure.message}" }
        )

        assertEquals(listOf("FAILED:only.mp4:all bounded retries exhausted"), result)
        assertEquals(0, singleCalls)
    }

    @Test
    fun largePartialResumeTruncatesOnlyUncommittedTailAtDurableCheckpoint() {
        val directory = java.nio.file.Files.createTempDirectory("ultrashare-large-resume").toFile()
        try {
            val expectedSize = 24L * 1024L * 1024L
            val durableOffset = 16L * 1024L * 1024L
            val buffer = ByteArray(1024 * 1024)
            val fullDigest = java.security.MessageDigest.getInstance("SHA-256")
            val prefixDigest = java.security.MessageDigest.getInstance("SHA-256")
            val partial = java.io.File(directory, "large.part")
            val checkpoint = java.io.File(directory, "large.resume")
            val checkpointTemp = java.io.File(directory, "large.resume.tmp")
            java.io.FileOutputStream(partial).use { output ->
                var written = 0L
                while (written < expectedSize) {
                    val count = minOf(buffer.size.toLong(), expectedSize - written).toInt()
                    for (index in 0 until count) buffer[index] = ((written + index) * 17).toByte()
                    fullDigest.update(buffer, 0, count)
                    if (written < durableOffset) {
                        val prefixCount = minOf(count.toLong(), durableOffset - written).toInt()
                        prefixDigest.update(buffer, 0, prefixCount)
                        output.write(buffer, 0, prefixCount)
                    }
                    written += count
                }
                output.write(ByteArray(1024 * 1024) { 0x5a })
                output.fd.sync()
            }
            val digestHex = fullDigest.digest().joinToString("") { "%02x".format(it) }
            writeUltraShareResumeCheckpointAtomically(
                checkpointTemp, checkpoint, expectedSize, digestHex, durableOffset
            )

            assertEquals(
                durableOffset,
                resumableFileOffset(partial, checkpoint, expectedSize, digestHex)
            )
            assertEquals(durableOffset, partial.length())
            val actualPrefixDigest = java.security.MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { input ->
                val verifyBuffer = ByteArray(1024 * 1024)
                var remaining = durableOffset
                while (remaining > 0L) {
                    val count = input.read(verifyBuffer, 0, minOf(verifyBuffer.size.toLong(), remaining).toInt())
                    check(count > 0)
                    actualPrefixDigest.update(verifyBuffer, 0, count)
                    remaining -= count
                }
            }
            assertEquals(
                prefixDigest.digest().joinToString("") { "%02x".format(it) },
                actualPrefixDigest.digest().joinToString("") { "%02x".format(it) }
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun perFileRecoveryContinuesAfterOneItemFails() = runBlocking {
        val visited = mutableListOf<Int>()
        val result = UltraShareBatchRecovery.execute(
            items = listOf(1, 2, 3, 4),
            sendBatch = { throw IOException("batch failed") },
            sendSingle = { item ->
                visited += item
                if (item == 2 || item == 4) throw IOException("file failed")
                item * 10
            },
            onItemFailure = { item, _ -> -item }
        )

        assertEquals(listOf(1, 2, 3, 4), visited)
        assertEquals(listOf(10, -2, 30, -4), result)
    }
}
