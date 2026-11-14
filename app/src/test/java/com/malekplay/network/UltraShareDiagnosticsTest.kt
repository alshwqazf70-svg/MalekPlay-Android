package com.malekplay.network

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareDiagnosticsTest {
    @Test
    fun retentionKeepsOnlyThreeNewestSessions() {
        val newestFirst = (1..5).map { Uri.parse("content://diagnostics/$it") }
        assertEquals(newestFirst.take(3), UltraShareDiagnostics.filesToDelete(newestFirst).let { newestFirst - it.toSet() })
        assertEquals(newestFirst.drop(3), UltraShareDiagnostics.filesToDelete(newestFirst))
    }

    @Test
    fun retentionDoesNotDeleteWhenThreeOrFewerSessionsExist() {
        val sessions = (1..3).map { Uri.parse("content://diagnostics/$it") }
        assertEquals(emptyList<Uri>(), UltraShareDiagnostics.filesToDelete(sessions))
    }
}
