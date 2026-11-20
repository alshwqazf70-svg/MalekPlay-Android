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
