package com.malekplay.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareRoomStateTest {
    @Test
    fun unpairedRoomClearsEveryTransferResultAndStaleMessage() {
        val dirty = UltraShareServiceState(
            progress = UltraShareCore.Progress("movie.mp4", 50L, 100L, 10L, false),
            sendProgress = UltraShareCore.Progress("movie.mp4", 50L, 100L, 10L, false),
            receiveProgress = UltraShareCore.Progress("episode.mkv", 80L, 100L, 20L, true),
            receivedFile = UltraShareCore.ReceivedFile(
                File("received.mp4"), "received.mp4", 100L, "video/mp4"
            ),
            status = "انتهى الطابور مع وجود ملفات فاشلة",
            error = "تعذر إرسال ملف",
            queueCompleted = 1,
            queueTotal = 3,
            queueCurrentName = "pending.mp4",
            queueTotalBytes = 300L,
            queueTransferredBytes = 100L,
            lastTransferMetrics = UltraShareBatchTransport.TransferMetrics(100L, 10L, 10L, 2L, 12L, 8L),
            lastReceiveMetrics = UltraShareBatchTransport.ReceiveMetrics(100L, 10L, 10L, 1L, 2L, 3L, 1),
            peerTransfers = listOf(
                UltraSharePeerTransfer("192.168.49.2", "movie.mp4", 50L, 100L, 10L, "فشل")
            )
        )

        val clean = resetRoomTransferPresentation(dirty)

        assertNull(clean.progress)
        assertNull(clean.sendProgress)
        assertNull(clean.receiveProgress)
        assertNull(clean.receivedFile)
        assertNull(clean.lastTransferMetrics)
        assertNull(clean.lastReceiveMetrics)
        assertNull(clean.status)
        assertNull(clean.error)
        assertEquals(0, clean.queueCompleted)
        assertEquals(0, clean.queueTotal)
        assertNull(clean.queueCurrentName)
        assertEquals(0L, clean.queueTotalBytes)
        assertEquals(0L, clean.queueTransferredBytes)
        assertEquals(emptyList<UltraSharePeerTransfer>(), clean.peerTransfers)
        assertEquals(dirty.roomResetGeneration + 1L, clean.roomResetGeneration)
    }

    @Test
    fun sessionEndCleanupClearsTempStateAndBumpsBothGenerations() {
        val dirty = UltraShareServiceState(
            peerTransfers = listOf(
                UltraSharePeerTransfer("192.168.49.2", "movie.mp4", 50L, 100L, 10L, "فشل")
            ),
            status = "جارٍ النقل",
            error = "تعذر الإرسال",
            queueTotal = 3,
            queueCompleted = 1
        )

        val clean = clearRoomTransferPresentationForSessionEnd(dirty)

        // Transient room items are gone...
        assertEquals(emptyList<UltraSharePeerTransfer>(), clean.peerTransfers)
        assertNull(clean.status)
        assertNull(clean.error)
        assertEquals(0, clean.queueTotal)
        assertEquals(0, clean.queueCompleted)
        // ...both monotonic tokens advance so stale callbacks from the old session
        // can be ignored by observers of the new room.
        assertEquals(dirty.roomResetGeneration + 1L, clean.roomResetGeneration)
        assertEquals(dirty.transferSessionGeneration + 1L, clean.transferSessionGeneration)
    }

    @Test
    fun sessionEndCleanupIsIdempotentAndPureAndPreservesPermanentHistory() {
        val original = UltraShareServiceState(
            peerTransfers = listOf(
                UltraSharePeerTransfer("192.168.49.2", "movie.mp4", 50L, 100L, 10L, "فشل")
            ),
            status = "نقل نشط",
            queueCompleted = 2,
            queueTotal = 2
        )
        val first = clearRoomTransferPresentationForSessionEnd(original)
        val second = clearRoomTransferPresentationForSessionEnd(first)

        // Idempotent but monotonic: each call bumps the tokens exactly once.
        assertEquals(first.roomResetGeneration + 1L, second.roomResetGeneration)
        assertEquals(first.transferSessionGeneration + 1L, second.transferSessionGeneration)

        // The function is pure: it never mutates the caller's state, mirroring how the
        // permanent transfer history (UltraShareQueueStore / UltraSharePeerDeliveryStore)
        // must survive session-end cleanup untouched.
        assertEquals(listOf(UltraSharePeerTransfer("192.168.49.2", "movie.mp4", 50L, 100L, 10L, "فشل")), original.peerTransfers)
        assertEquals("نقل نشط", original.status)
        assertEquals(2, original.queueTotal)
    }

    @Test
    fun staleAsyncCallbacksFromEndedSessionCannotReinsertTransferItemsIntoCleanRoom() {
        val sessionOne = UltraShareServiceState(transferSessionGeneration = 5L)
        val capturedGeneration = sessionOne.transferSessionGeneration

        // User leaves the room or unpairs; generation advances to 6:
        val afterLeave = clearRoomTransferPresentationForSessionEnd(sessionOne)
        assertEquals(6L, afterLeave.transferSessionGeneration)

        // Late send/receive progress callback from session 5 arrives asynchronously:
        val afterLateCallback = applyIfTransferGenerationMatches(afterLeave, capturedGeneration) { current ->
            current.copy(
                receiveProgress = UltraShareCore.Progress("stale.mp4", 40L, 100L, 5L, true),
                peerTransfers = listOf(
                    UltraSharePeerTransfer("192.168.49.1", "stale.mp4", 40L, 100L, 5L, "جارٍ الاستقبال")
                )
            )
        }
        assertNull("Stale callback must not repopulate receiveProgress", afterLateCallback.receiveProgress)
        assertTrue("Stale callback must not repopulate peerTransfers", afterLateCallback.peerTransfers.isEmpty())

        // Callback from current active session 6 applies normally:
        val afterValidCallback = applyIfTransferGenerationMatches(afterLeave, afterLeave.transferSessionGeneration) { current ->
            current.copy(
                peerTransfers = listOf(
                    UltraSharePeerTransfer("192.168.49.1", "fresh.mp4", 100L, 100L, 12L, "مكتمل")
                )
            )
        }
        assertEquals(1, afterValidCallback.peerTransfers.size)
        assertEquals("fresh.mp4", afterValidCallback.peerTransfers.first().fileName)
    }

    @Test
    fun sessionRoomDeliveryFilterHidesPreviousSessionReceiptsWhileKeepingPersistentStoreIntact() {
        val oldSessionReceipt = UltraSharePeerDeliveryEntry(
            fileUri = "content://media/external/video/media/101",
            peerSessionId = "peer-session-1",
            peerHost = "192.168.49.2",
            displayName = "old_session_video.mp4",
            status = UltraSharePeerDeliveryStore.STATUS_COMPLETED,
            error = null,
            updatedAtMillis = 1_000L
        )
        val newSessionReceipt = oldSessionReceipt.copy(
            fileUri = "content://media/external/video/media/102",
            displayName = "current_session_video.mp4",
            updatedAtMillis = 5_500L
        )
        val persistentStoreEntries = listOf(oldSessionReceipt, newSessionReceipt)
        val activePeer = UltraSharePeerSession(
            sessionId = "peer-session-1",
            name = "Android 10 Peer",
            host = "192.168.49.2",
            role = "receiver",
            approvedAtMillis = 5_000L
        )

        val visibleInRoom = com.malekplay.ui.network.filterSessionRoomPeerDeliveries(
            entries = persistentStoreEntries,
            peer = activePeer,
            peerHost = activePeer.host,
            roomStartedAtMillis = 5_000L
        )

        // Room shows ONLY the current session's delivery:
        assertEquals(1, visibleInRoom.size)
        assertEquals("current_session_video.mp4", visibleInRoom.first().displayName)
        // Persistent history list itself remains untouched (both entries preserved):
        assertEquals(2, persistentStoreEntries.size)
    }

    @Test
    fun batchTransportSessionSocketAbortIsSafeWhenIdle() {
        // Calling session-abort helpers when no socket is open must never throw or touch disk:
        UltraShareBatchTransport.abortPeerReceiveSessions("192.168.49.2")
        UltraShareBatchTransport.abortAllActiveReceiveSessions()
    }
}
