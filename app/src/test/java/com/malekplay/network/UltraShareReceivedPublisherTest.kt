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
}
