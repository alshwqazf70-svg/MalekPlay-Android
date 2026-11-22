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
    fun staleActivePeerSpeedIsZeroedWithoutChangingProgressOrCompletedPeers() {
        val staleReceive = UltraSharePeerTransfer(
            host = "192.168.1.40",
            fileName = "stalled.mp4",
            transferredBytes = 40L,
            totalBytes = 100L,
            bytesPerSecond = 900L,
            status = "يستقبل الآن",
            updatedAtMillis = 1_000L
        )
        val freshSend = UltraSharePeerTransfer(
            host = "192.168.1.41",
            fileName = "active.zip",
            transferredBytes = 20L,
            totalBytes = 100L,
            bytesPerSecond = 500L,
            status = "يرسل الآن • V2",
            updatedAtMillis = 2_400L
        )
        val completed = UltraSharePeerTransfer(
            host = "192.168.1.42",
            fileName = "done.txt",
            transferredBytes = 100L,
            totalBytes = 100L,
            bytesPerSecond = 0L,
            status = "اكتمل • 1 ملفات",
            updatedAtMillis = 100L
        )

        val refreshed = zeroStalePeerTransferSpeeds(
            listOf(staleReceive, freshSend, completed),
            nowMillis = 2_600L
        )

        assertEquals(0L, refreshed[0].bytesPerSecond)
        assertEquals(40L, refreshed[0].transferredBytes)
        assertEquals(500L, refreshed[1].bytesPerSecond)
        assertEquals(0L, refreshed[2].bytesPerSecond)
    }

    @Test
    fun aggregateSpeedSumsOnlyActivePeersAndSaturatesOnOverflow() {
        val activeReceiveA = UltraSharePeerTransfer(
            host = "192.168.1.50", fileName = "a.mp4",
            transferredBytes = 10L, totalBytes = 100L, bytesPerSecond = 700L,
            status = "يستقبل الآن"
        )
        val activeReceiveB = UltraSharePeerTransfer(
            host = "192.168.1.51", fileName = "b.mp4",
            transferredBytes = 20L, totalBytes = 100L, bytesPerSecond = 900L,
            status = "يستقبل الآن"
        )
        val completed = UltraSharePeerTransfer(
            host = "192.168.1.52", fileName = "done.mp4",
            transferredBytes = 100L, totalBytes = 100L, bytesPerSecond = 5_000L,
            status = "اكتمل • 1 ملفات"
        )
        assertEquals(
            1_600L,
            aggregateActivePeerBytesPerSecond(
                listOf(activeReceiveA, activeReceiveB, completed),
                receiving = true
            )
        )
        val huge = activeReceiveA.copy(bytesPerSecond = Long.MAX_VALUE)
        assertEquals(
            Long.MAX_VALUE,
            aggregateActivePeerBytesPerSecond(listOf(huge, activeReceiveB), receiving = true)
        )
    }

    @Test
    fun latestSendProgressTracksTheMostRecentlyUpdatedActivePeer() {
        val first = UltraSharePeerTransfer(
            host = "192.168.1.43", fileName = "first.mp4",
            transferredBytes = 10L, totalBytes = 100L, bytesPerSecond = 100L,
            status = "يرسل الآن", updatedAtMillis = 10L
        )
        val second = UltraSharePeerTransfer(
            host = "192.168.1.44", fileName = "second.mp4",
            transferredBytes = 70L, totalBytes = 100L, bytesPerSecond = 700L,
            status = "يرسل الآن • V2", updatedAtMillis = 20L
        )

        val progress = latestActivePeerSendProgress(listOf(first, second))

        assertEquals("second.mp4", progress?.fileName)
        assertEquals(800L, progress?.bytesPerSecond)
        assertEquals(false, progress?.receiving)
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
