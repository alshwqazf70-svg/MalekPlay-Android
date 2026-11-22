package com.malekplay.ui.network

import com.malekplay.network.UltraSharePeerTransfer
import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareSessionRoomMetricsTest {

    @Test
    fun aggregateCountsOnlyLiveChannelsAndSumsTheirSpeedAndRemainingBytes() {
        val transfers = listOf(
            UltraSharePeerTransfer(
                host = "192.168.49.2",
                fileName = "video.mp4",
                transferredBytes = 400L,
                totalBytes = 1_000L,
                bytesPerSecond = 2_000L,
                status = "يرسل الآن • V2"
            ),
            UltraSharePeerTransfer(
                host = "192.168.49.3",
                fileName = "music.flac",
                transferredBytes = 250L,
                totalBytes = 500L,
                bytesPerSecond = 3_000L,
                status = "يستقبل الآن"
            ),
            UltraSharePeerTransfer(
                host = "192.168.49.4",
                fileName = "done.zip",
                transferredBytes = 900L,
                totalBytes = 900L,
                bytesPerSecond = 8_000L,
                status = "اكتملت الجلسة"
            ),
            UltraSharePeerTransfer(
                host = "192.168.49.5",
                fileName = "failed.bin",
                transferredBytes = 100L,
                totalBytes = 1_000L,
                bytesPerSecond = 9_000L,
                status = "فشل النقل"
            )
        )

        val result = aggregateUltraSharePeerMetrics(transfers)

        assertEquals(2, result.activePeerCount)
        assertEquals(5_000L, result.bytesPerSecond)
        assertEquals(850L, result.remainingBytes)
    }

    @Test
    fun aggregateIgnoresChannelsWithoutKnownProgressOrCurrentSpeed() {
        val transfers = listOf(
            UltraSharePeerTransfer(
                host = "192.168.49.2",
                fileName = "preparing",
                transferredBytes = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L,
                status = "جارٍ الاستقبال"
            ),
            UltraSharePeerTransfer(
                host = "192.168.49.3",
                fileName = "paused",
                transferredBytes = 100L,
                totalBytes = 1_000L,
                bytesPerSecond = 0L,
                status = "يرسل الآن"
            )
        )

        val result = aggregateUltraSharePeerMetrics(transfers)

        assertEquals(0, result.activePeerCount)
        assertEquals(0L, result.bytesPerSecond)
        assertEquals(0L, result.remainingBytes)
    }

    @Test
    fun aggregateSaturatesInsteadOfOverflowingLongCounters() {
        val transfers = listOf(
            UltraSharePeerTransfer(
                host = "192.168.49.2",
                fileName = "a.bin",
                transferredBytes = 0L,
                totalBytes = Long.MAX_VALUE,
                bytesPerSecond = Long.MAX_VALUE,
                status = "يرسل الآن"
            ),
            UltraSharePeerTransfer(
                host = "192.168.49.3",
                fileName = "b.bin",
                transferredBytes = 0L,
                totalBytes = Long.MAX_VALUE,
                bytesPerSecond = Long.MAX_VALUE,
                status = "يستقبل الآن"
            )
        )

        val result = aggregateUltraSharePeerMetrics(transfers)

        assertEquals(2, result.activePeerCount)
        assertEquals(Long.MAX_VALUE, result.bytesPerSecond)
        assertEquals(Long.MAX_VALUE, result.remainingBytes)
    }
}
