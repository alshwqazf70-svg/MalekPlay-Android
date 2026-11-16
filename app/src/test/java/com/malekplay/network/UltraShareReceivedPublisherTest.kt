package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import java.nio.file.Files
import org.junit.Test

class UltraShareReceivedPublisherTest {
    @Test
    fun repeatedReceivedFileNamesNeverSelectAnExistingDestination() {
        val directory = Files.createTempDirectory("malekplayer-received-test").toFile()
        try {
            val existing = File(directory, "video.mp4").apply { writeText("previous file") }
            val next = UltraShareReceivedPublisher.uniqueFile(directory, "video.mp4")
            assertEquals("video (1).mp4", next.name)
            assertFalse(next.exists())
            assertEquals("previous file", existing.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

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
