package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UltraShareDFilePublisherTest {
    @Test
    fun existingDestinationIsPreservedAndIncomingFileGetsUniqueName() {
        val root = createTempDir(prefix = "dfile-publish-")
        try {
            val destination = File(root, "receive").apply { mkdir() }
            val session = File(destination, "session-one").apply { mkdir() }
            val staged = File(session, "movie.mp4").apply {
                writeBytes("new verified content".toByteArray())
            }
            val existing = File(destination, "movie.mp4").apply {
                writeBytes("keep existing content".toByteArray())
            }

            val published = UltraShareDFilePublisher.publish(staged, destination, "movie.mp4")

            assertNotNull(published)
            assertEquals("movie (1).mp4", published!!.name)
            assertEquals("new verified content", published.readText())
            assertEquals("keep existing content", existing.readText())
            assertTrue(!staged.exists())
            assertTrue(destination.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedPublicationReservesNextAvailableSuffixWithoutOverwriting() {
        val root = createTempDir(prefix = "dfile-publish-")
        try {
            val destination = File(root, "receive").apply { mkdir() }
            val firstSession = File(destination, "session-one").apply { mkdir() }
            val secondSession = File(destination, "session-two").apply { mkdir() }
            File(destination, "clip.mkv").writeText("original")
            File(destination, "clip (1).mkv").writeText("first collision")

            val first = File(firstSession, "clip.mkv").apply { writeText("second payload") }
            val second = File(secondSession, "clip.mkv").apply { writeText("third payload") }

            val firstPublished = UltraShareDFilePublisher.publish(first, destination, "clip.mkv")
            val secondPublished = UltraShareDFilePublisher.publish(second, destination, "clip.mkv")

            assertEquals("clip (2).mkv", firstPublished?.name)
            assertEquals("clip (3).mkv", secondPublished?.name)
            assertEquals("original", File(destination, "clip.mkv").readText())
            assertEquals("first collision", File(destination, "clip (1).mkv").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesStagedFileOutsideItsOwnedSessionDirectory() {
        val root = createTempDir(prefix = "dfile-publish-")
        try {
            val destination = File(root, "receive").apply { mkdir() }
            val staged = File(root, "outside.mp4").apply { writeText("payload") }

            assertNull(UltraShareDFilePublisher.publish(staged, destination, "outside.mp4"))
            assertEquals("payload", staged.readText())
            assertTrue(destination.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
