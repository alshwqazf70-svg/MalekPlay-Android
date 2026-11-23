package com.malekplay.network

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UltraShareRoomStateTest {
    @Test
    fun unpairedRoomClearsEveryTransferResultAndStaleMessage() {
        val dirty = UltraShareServiceState(
            progress = UltraShareCore.Progress("movie.mp4", 50L, 100L, 10L, false),
            sendProgress = UltraShareCore.Progress("movie.mp4", 50L, 100L, 10L, false),
            receiveProgress = UltraShareCore.Progress("episode.mkv", 80L, 100L, 20L, true),
            receivedFile = UltraShareCore.ReceivedFile(
                File("received.mp4"), "received.mp4", 100L, "video/mp4", Uri.parse("file:///received.mp4")
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
    }
}
