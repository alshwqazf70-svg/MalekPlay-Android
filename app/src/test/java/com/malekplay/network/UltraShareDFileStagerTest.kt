package com.malekplay.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDFileStagerTest {
    @Test
    fun closeDeletesOnlyItsStagedFileAndSessionDirectory() {
        val cache = createTempDir(prefix = "dfile-stage-close")
        try {
            val root = File(cache, "ultrashare-dfile-staging").apply { mkdirs() }
            val session = File(root, "session-test").apply { mkdirs() }
            val staged = File(session, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val digest = ByteArray(32) { 7 }
            val file = UltraShareDFileStager.StagedFile(
                file = staged,
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                sizeBytes = 3L,
                sha256 = digest,
                stagingDirectory = session
            )

            file.close()

            assertFalse(staged.exists())
            assertFalse(session.exists())
            assertTrue(root.isDirectory)
            assertTrue(digest.all { it == 0.toByte() })
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun cleanupRemovesOnlyExpiredChildrenAndKeepsRecentStagingSessions() {
        val cache = createTempDir(prefix = "dfile-stage-cleanup")
        try {
            val root = File(cache, "ultrashare-dfile-staging").apply { mkdirs() }
            val stale = File(root, "session-stale").apply { mkdirs() }
            File(stale, "old.bin").writeBytes(byteArrayOf(1))
            val recent = File(root, "session-recent").apply { mkdirs() }
            File(recent, "new.bin").writeBytes(byteArrayOf(2))
            assertTrue(stale.setLastModified(System.currentTimeMillis() - 60_000L))

            UltraShareDFileStager.cleanupStale(cache, olderThanMillis = 1_000L)

            assertFalse(stale.exists())
            assertTrue(recent.isDirectory)
            assertTrue(File(recent, "new.bin").isFile)
        } finally {
            cache.deleteRecursively()
        }
    }
}
