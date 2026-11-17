package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            assertTrue(!session.exists())
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
    fun concurrentPublicationsNeverReplaceExistingFilesOrEachOther() {
        val root = createTempDir(prefix = "dfile-publish-race-")
        val executor = Executors.newFixedThreadPool(6)
        try {
            val destination = File(root, "receive").apply { mkdir() }
            val original = File(destination, "clip.mp4").apply { writeText("original") }
            val ready = CountDownLatch(6)
            val start = CountDownLatch(1)
            val futures = (0 until 6).map { index ->
                val session = File(destination, "session-$index").apply { mkdir() }
                val staged = File(session, "clip.mp4").apply { writeText("payload-$index") }
                executor.submit<File?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    UltraShareDFilePublisher.publish(staged, destination, "clip.mp4")
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val published = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(published.all { it != null })
            assertEquals(6, published.mapNotNull { it?.name }.distinct().size)
            assertEquals("original", original.readText())
            assertEquals(
                (0 until 6).map { "payload-$it" }.toSet(),
                published.filterNotNull().map { it.readText() }.toSet()
            )
        } finally {
            executor.shutdownNow()
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
