package com.malekplay.network

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UltraShareReceivedHistoryStoreTest {

    @Test
    fun recordPersistsVerifiedFileMetadataAndCanRemoveEntry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "ultrashare-history-test.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        UltraShareReceivedHistoryStore.clear(context)
        try {
            UltraShareReceivedHistoryStore.record(
                context = context,
                file = file,
                fileName = "sample.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 4L,
                receivedAtEpochMs = 1_800_000_000_000L
            )
            val entry = UltraShareReceivedHistoryStore.observe(context).first().single()
            assertEquals("sample.bin", entry.fileName)
            assertEquals(4L, entry.sizeBytes)
            assertEquals("application/octet-stream", entry.mimeType)
            assertTrue(entry.isAvailable)
            assertEquals(1_800_000_000_000L, entry.receivedAtEpochMs)

            UltraShareReceivedHistoryStore.remove(context, entry.id)
            assertTrue(UltraShareReceivedHistoryStore.observe(context).first().isEmpty())
        } finally {
            UltraShareReceivedHistoryStore.clear(context)
            file.delete()
        }
    }

    @Test
    fun recordRejectsMissingOrIncompleteFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val missing = File(context.cacheDir, "ultrashare-does-not-exist.bin")
        UltraShareReceivedHistoryStore.clear(context)
        try {
            val failure = runCatching {
                UltraShareReceivedHistoryStore.record(
                    context, missing, "missing.bin", "application/octet-stream", 5L
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(UltraShareReceivedHistoryStore.observe(context).first().isEmpty())
        } finally {
            UltraShareReceivedHistoryStore.clear(context)
        }
    }

    @Test
    fun historyIsBoundedToMostRecentHundredEntries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val files = (0 until UltraShareReceivedHistoryStore.MAX_ENTRIES + 1).map { index ->
            File(context.cacheDir, "ultrashare-history-$index.bin").apply {
                writeBytes(byteArrayOf(index.toByte()))
            }
        }
        UltraShareReceivedHistoryStore.clear(context)
        try {
            files.forEachIndexed { index, file ->
                UltraShareReceivedHistoryStore.record(
                    context = context,
                    file = file,
                    fileName = file.name,
                    mimeType = "application/octet-stream",
                    sizeBytes = 1L,
                    receivedAtEpochMs = 1_800_000_000_000L + index
                )
            }
            val entries = UltraShareReceivedHistoryStore.observe(context).first()
            assertEquals(UltraShareReceivedHistoryStore.MAX_ENTRIES, entries.size)
            assertFalse(entries.any { it.fileName == files.first().name })
            assertEquals(files.last().name, entries.first().fileName)
        } finally {
            UltraShareReceivedHistoryStore.clear(context)
            files.forEach(File::delete)
        }
    }
}
