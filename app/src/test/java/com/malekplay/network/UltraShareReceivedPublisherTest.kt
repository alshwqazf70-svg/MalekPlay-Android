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
    fun publishesOnlyInsideTheMalekPlayerDownloadsFolder() {
        assertEquals(
            listOf("Download/MalekPlayer"),
            UltraShareReceivedPublisher.publicRelativePaths()
        )
    }

    @Test
    fun legacyAppFolderIsCreatedDirectlyInsideDownloads() {
        assertEquals(
            "MalekPlayer",
            UltraShareReceivedPublisher.legacyDownloadsSubdirectory("Download/MalekPlayer")
        )
    }

    @Test
    fun publicationNeverDeletesAnExistingDestinationFile() {
        val directory = Files.createTempDirectory("malekplayer-received-no-overwrite").toFile()
        try {
            val source = File(directory, "incoming.part").apply { writeText("new received content") }
            val destination = File(directory, "movie.mp4").apply { writeText("keep existing content") }

            val failure = runCatching {
                UltraShareReceivedPublisher.copyVerified(
                    source,
                    destination,
                    source.length()
                )
            }.exceptionOrNull()

            org.junit.Assert.assertTrue(failure != null)
            assertEquals("keep existing content", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
