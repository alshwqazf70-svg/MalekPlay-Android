package com.malekplay.network

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UltraShareQueueStoreTest {

    @Test
    fun queueClaimsAreExclusiveAndSkippedItemsCannotBeResumed() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val activeUri = Uri.parse("content://malekplay.test/document/active")
        val skippedUri = Uri.parse("content://malekplay.test/document/skipped")

        UltraShareQueueStore.clear(context)
        try {
            UltraShareQueueStore.replace(context, listOf(activeUri, skippedUri))

            assertTrue(
                UltraShareQueueStore.claimForSending(
                    context,
                    activeUri,
                    "active.bin"
                )
            )
            assertFalse(
                UltraShareQueueStore.claimForSending(
                    context,
                    activeUri,
                    "active.bin"
                )
            )

            // A skip request must never overwrite an item already claimed by the service.
            UltraShareQueueStore.skip(context, activeUri)
            assertEquals(
                UltraShareQueueStore.STATUS_SENDING,
                UltraShareQueueStore.observe(context).first()
                    .first { it.uri == activeUri.toString() }.status
            )

            UltraShareQueueStore.skip(context, skippedUri)
            assertFalse(
                UltraShareQueueStore.claimForSending(
                    context,
                    skippedUri,
                    "skipped.bin"
                )
            )
            assertTrue(UltraShareQueueStore.pending(context).isEmpty())

            UltraShareQueueStore.update(
                context,
                activeUri,
                displayName = "active.bin",
                status = UltraShareQueueStore.STATUS_FAILED,
                error = "temporary network error"
            )
            assertTrue(
                UltraShareQueueStore.claimForSending(
                    context,
                    activeUri,
                    "active.bin"
                )
            )

            UltraShareQueueStore.recoverInterrupted(context)
            assertEquals(
                UltraShareQueueStore.STATUS_PENDING,
                UltraShareQueueStore.observe(context).first()
                    .first { it.uri == activeUri.toString() }.status
            )
            assertEquals(1, UltraShareQueueStore.pending(context).size)
        } finally {
            UltraShareQueueStore.clear(context)
        }
    }

    @Test
    fun replaceDeduplicatesUrisAndResetsEveryEntryToPending() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val first = Uri.parse("content://malekplay.test/document/first")
        val second = Uri.parse("content://malekplay.test/document/second")
        UltraShareQueueStore.clear(context)
        try {
            UltraShareQueueStore.replace(context, listOf(first, second, first))
            val initial = UltraShareQueueStore.observe(context).first()
            assertEquals(2, initial.size)
            assertEquals(listOf(first.toString(), second.toString()), initial.map { it.uri })
            UltraShareQueueStore.update(context, first, displayName = "first.mp4", status = UltraShareQueueStore.STATUS_COMPLETED)
            UltraShareQueueStore.replace(context, listOf(first, second))
            val replaced = UltraShareQueueStore.observe(context).first()
            assertEquals(2, replaced.size)
            assertTrue(replaced.all { it.status == UltraShareQueueStore.STATUS_PENDING })
            assertTrue(replaced.all { it.error == null })
        } finally {
            UltraShareQueueStore.clear(context)
        }
    }

    @Test
    fun pendingReturnsFailedAndPendingButExcludesTerminalEntries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val pendingUri = Uri.parse("content://malekplay.test/document/pending")
        val failedUri = Uri.parse("content://malekplay.test/document/failed")
        val completedUri = Uri.parse("content://malekplay.test/document/completed")
        val skippedUri = Uri.parse("content://malekplay.test/document/skipped")
        UltraShareQueueStore.clear(context)
        try {
            UltraShareQueueStore.replace(context, listOf(pendingUri, failedUri, completedUri, skippedUri))
            UltraShareQueueStore.update(context, failedUri, status = UltraShareQueueStore.STATUS_FAILED, error = "network unavailable")
            UltraShareQueueStore.update(context, completedUri, status = UltraShareQueueStore.STATUS_COMPLETED)
            UltraShareQueueStore.update(context, skippedUri, status = UltraShareQueueStore.STATUS_SKIPPED)
            val retryable = UltraShareQueueStore.pending(context).map { it.uri }.toSet()
            assertEquals(setOf(pendingUri.toString(), failedUri.toString()), retryable)
        } finally {
            UltraShareQueueStore.clear(context)
        }
    }

    @Test
    fun enqueuePreservesOlderHistoryAndResetsOnlyRequeuedUri() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val oldUri = Uri.parse("content://malekplay.test/document/old")
        val reusedUri = Uri.parse("content://malekplay.test/document/reused")
        val newUri = Uri.parse("content://malekplay.test/document/new")
        UltraShareQueueStore.clear(context)
        try {
            UltraShareQueueStore.replace(context, listOf(oldUri, reusedUri))
            UltraShareQueueStore.update(
                context, oldUri, displayName = "old.mp4",
                status = UltraShareQueueStore.STATUS_COMPLETED
            )
            UltraShareQueueStore.update(
                context, reusedUri, displayName = "reused.mp4",
                status = UltraShareQueueStore.STATUS_FAILED, error = "temporary failure"
            )

            UltraShareQueueStore.enqueue(context, listOf(newUri, reusedUri, newUri))
            val entries = UltraShareQueueStore.observe(context).first()
            assertEquals(3, entries.size)
            assertEquals(
                UltraShareQueueStore.STATUS_COMPLETED,
                entries.first { it.uri == oldUri.toString() }.status
            )
            val requeued = entries.first { it.uri == reusedUri.toString() }
            assertEquals(UltraShareQueueStore.STATUS_PENDING, requeued.status)
            assertEquals(null, requeued.error)
            assertEquals(
                UltraShareQueueStore.STATUS_PENDING,
                entries.first { it.uri == newUri.toString() }.status
            )
        } finally {
            UltraShareQueueStore.clear(context)
        }
    }
}
