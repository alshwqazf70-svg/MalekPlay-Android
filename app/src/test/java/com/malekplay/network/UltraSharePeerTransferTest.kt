package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UltraSharePeerTransferTest {
    @Test
    fun fractionIsZeroWhenTotalIsUnknown() {
        val transfer = UltraSharePeerTransfer(
            host = "192.168.1.20",
            fileName = "movie.mp4",
            transferredBytes = 120L,
            totalBytes = 0L,
            bytesPerSecond = 40L,
            status = "يستقبل الآن"
        )

        assertEquals(0f, transfer.fraction, 0.0001f)
    }

    @Test
    fun fractionIsClampedToProgressRange() {
        val transfer = UltraSharePeerTransfer(
            host = "192.168.1.21",
            fileName = "episode.mkv",
            transferredBytes = 150L,
            totalBytes = 100L,
            bytesPerSecond = 25L,
            status = "يستقبل الآن"
        )

        assertEquals(1f, transfer.fraction, 0.0001f)
    }

    @Test
    fun fractionReflectsIndependentPeerProgress() {
        val firstPeer = UltraSharePeerTransfer(
            host = "192.168.1.22",
            fileName = "one.mp4",
            transferredBytes = 25L,
            totalBytes = 100L,
            bytesPerSecond = 10L,
            status = "يستقبل الآن"
        )
        val secondPeer = UltraSharePeerTransfer(
            host = "192.168.1.23",
            fileName = "two.mp4",
            transferredBytes = 75L,
            totalBytes = 100L,
            bytesPerSecond = 20L,
            status = "يستقبل الآن"
        )

        assertEquals(0.25f, firstPeer.fraction, 0.0001f)
        assertEquals(0.75f, secondPeer.fraction, 0.0001f)
    }
    @Test
    fun finishingOnePeerKeepsAnotherPeersProgressVisible() {
        val completedPeer = UltraSharePeerTransfer(
            host = "192.168.1.30",
            fileName = "done.mp4",
            transferredBytes = 100L,
            totalBytes = 100L,
            bytesPerSecond = 0L,
            status = "اكتمل • 1 ملفات",
            updatedAtMillis = 20L
        )
        val activePeer = UltraSharePeerTransfer(
            host = "192.168.1.31",
            fileName = "still-receiving.mkv",
            transferredBytes = 40L,
            totalBytes = 100L,
            bytesPerSecond = 12L,
            status = "يستقبل الآن",
            updatedAtMillis = 10L
        )

        val progress = latestActivePeerReceiveProgress(listOf(completedPeer, activePeer))

        assertEquals("still-receiving.mkv", progress?.fileName)
        assertEquals(40L, progress?.transferredBytes)
        assertEquals(100L, progress?.totalBytes)
        assertEquals(true, progress?.receiving)
    }

    @Test
    fun noSharedProgressIsReportedWhenAllPeersAreFinished() {
        val completedPeer = UltraSharePeerTransfer(
            host = "192.168.1.32",
            fileName = "done.mp4",
            transferredBytes = 100L,
            totalBytes = 100L,
            bytesPerSecond = 0L,
            status = "اكتمل • 1 ملفات"
        )

        assertEquals(null, latestActivePeerReceiveProgress(listOf(completedPeer)))
    }

}
