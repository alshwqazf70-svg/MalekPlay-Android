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
    fun jsonEncoderEscapesControlCharactersAndPreservesPrimitiveTypes() {
        val encoded = UltraShareDiagnostics.jsonObject(
            linkedMapOf(
                "message" to "first line\nsecond line",
                "count" to 3,
                "enabled" to true
            )
        )
        assertEquals(
            """{"message":"first line\nsecond line","count":3,"enabled":true}""",
            encoded
        )
    }

    @Test
    fun retentionDoesNotDeleteThreeOrFewerSessions() {
        assertEquals(emptyList<String>(), UltraShareDiagnostics.filesToDelete(listOf("one", "two", "three")))
        assertEquals(emptyList<String>(), UltraShareDiagnostics.filesToDelete(emptyList<String>()))
    }

    @Test
    fun peerDiagnosticKeysAreStableDistinctAndDoNotExposeHost() {
        val first = UltraShareDiagnostics.diagnosticPeerSessionKey("192.168.1.10")
        val samePeer = UltraShareDiagnostics.diagnosticPeerSessionKey("192.168.1.10")
        val second = UltraShareDiagnostics.diagnosticPeerSessionKey("192.168.1.11")

        assertEquals(first, samePeer)
        org.junit.Assert.assertNotEquals(first, second)
        org.junit.Assert.assertFalse(first.contains("192.168.1.10"))
    }

    @Test
    fun peerDiagnosticKeysNormalizeHostCaseAndWhitespace() {
        assertEquals(
            UltraShareDiagnostics.diagnosticPeerSessionKey("Peer.Example"),
            UltraShareDiagnostics.diagnosticPeerSessionKey(" peer.example ")
        )
    }
}
