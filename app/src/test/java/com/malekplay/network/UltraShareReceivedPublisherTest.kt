package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UltraShareReceivedPublisherTest {
    @Test
    fun savesToAppFolderBeforeFallingBackToDownloadsRoot() {
        assertEquals(
            listOf("Download/MalekPlayer/Received", "Download"),
            UltraShareReceivedPublisher.publicRelativePaths()
        )
    }

    @Test
    fun legacyDownloadsFallbackTargetsTheActualDownloadsRoot() {
        assertEquals("", UltraShareReceivedPublisher.legacyDownloadsSubdirectory("Download"))
    }

    @Test
    fun legacyAppFolderRemainsInsideDownloads() {
        assertEquals(
            "MalekPlayer/Received",
            UltraShareReceivedPublisher.legacyDownloadsSubdirectory("Download/MalekPlayer/Received")
        )
    }
}
