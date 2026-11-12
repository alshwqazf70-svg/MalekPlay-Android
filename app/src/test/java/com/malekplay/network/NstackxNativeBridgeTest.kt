package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NstackxNativeBridgeTest {
    @Test
    fun fatalDFileEventIsMarkedAsUnusableSession() {
        val fatal = NstackxNativeBridge.DFileEvent(
            sessionId = 41,
            eventType = NstackxNativeBridge.DFILE_ON_FATAL_ERROR,
            transferId = 0,
            transferredBytes = 0L,
            totalBytes = 0L,
            fileNames = emptyList(),
            message = "error:-1"
        )
        val progress = NstackxNativeBridge.DFileEvent(
            sessionId = 41,
            eventType = NstackxNativeBridge.DFILE_ON_SESSION_IN_PROGRESS,
            transferId = 7,
            transferredBytes = 128L,
            totalBytes = 256L,
            fileNames = listOf("/cache/video.mp4"),
            message = "0"
        )

        assertTrue(fatal.isFatal)
        assertFalse(progress.isFatal)
    }

    @Test
    fun upstreamDFileEventNumbersRemainWireCompatible() {
        assertTrue(NstackxNativeBridge.DFILE_ON_CONNECT_SUCCESS == 1)
        assertTrue(NstackxNativeBridge.DFILE_ON_FATAL_ERROR == 8)
        assertTrue(NstackxNativeBridge.DFILE_ON_SESSION_IN_PROGRESS == 9)
        assertTrue(NstackxNativeBridge.DFILE_ON_TRANS_IN_PROGRESS == 10)
    }
}


class NstackxDFileEventMetadataTest {
    @Test
    fun dfileEventCarriesTransferIdentityAndSafeFileNames() {
        val event = NstackxNativeBridge.DFileEvent(
            sessionId = 9,
            eventType = NstackxNativeBridge.DFILE_ON_TRANS_IN_PROGRESS,
            transferId = 23,
            transferredBytes = 50L,
            totalBytes = 100L,
            fileNames = listOf("video.mp4", "subtitle.srt"),
            message = "0"
        )
        assertTrue(event.transferId == 23)
        assertTrue(event.fileNames == listOf("video.mp4", "subtitle.srt"))
    }
}
