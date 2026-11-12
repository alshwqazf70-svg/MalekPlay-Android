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
            transferredBytes = 0L,
            totalBytes = 0L,
            message = "error:-1"
        )
        val progress = NstackxNativeBridge.DFileEvent(
            sessionId = 41,
            eventType = NstackxNativeBridge.DFILE_ON_SESSION_IN_PROGRESS,
            transferredBytes = 128L,
            totalBytes = 256L,
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
