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
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
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
}
