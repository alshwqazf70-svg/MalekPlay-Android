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
}
