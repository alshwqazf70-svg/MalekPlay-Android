package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareDiagnosticsTest {
    @Test
    fun retentionKeepsThreeNewestSessions() {
        val newestFirst = listOf("session-5", "session-4", "session-3", "session-2", "session-1")
        assertEquals(listOf("session-2", "session-1"), UltraShareDiagnostics.filesToDelete(newestFirst))
        assertEquals(newestFirst.take(3), newestFirst - UltraShareDiagnostics.filesToDelete(newestFirst).toSet())
    }

    @Test
    fun retentionDoesNotDeleteThreeOrFewerSessions() {
        assertEquals(emptyList<String>(), UltraShareDiagnostics.filesToDelete(listOf("one", "two", "three")))
        assertEquals(emptyList<String>(), UltraShareDiagnostics.filesToDelete(emptyList()))
    }
}
