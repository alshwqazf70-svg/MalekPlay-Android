package com.malekplay.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDFileStagerTest {
    @Test
    fun stagedFileValidationRejectsSizeMismatchAndPathsOutsideOwnedSession() {
        val cache = createTempDir(prefix = "dfile-stage-path")
        try {
            val root = File(cache, "ultrashare-dfile-staging").apply { mkdirs() }
            val session = File(root, "session-safe").apply { mkdirs() }
            val staged = File(session, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

            assertTrue(
                UltraShareDFileStager.isSafeStagedFile(
                    staged, session, root, cache, copiedBytes = 4L, expectedBytes = 4L
                )
            )
            assertFalse(
                UltraShareDFileStager.isSafeStagedFile(
                    staged, session, root, cache, copiedBytes = 3L, expectedBytes = 4L
                )
            )
            assertFalse(
                UltraShareDFileStager.isSafeStagedFile(
                    staged, session, root, cache, copiedBytes = 4L, expectedBytes = 5L
                )
            )

            val outside = File(cache, "outside.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val link = File(session, "escaped.mp4")
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            }.onSuccess {
                assertFalse(
                    UltraShareDFileStager.isSafeStagedFile(
                        link, session, root, cache, copiedBytes = 4L, expectedBytes = 4L
                    )
                )
            }
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun stagedDigestDetectsMutationAfterPreparation() {
        val cache = createTempDir(prefix = "dfile-stage-digest")
        try {
            val root = File(cache, "ultrashare-dfile-staging").apply { mkdirs() }
            val session = File(root, "session-digest").apply { mkdirs() }
            val stagedFile = File(session, "clip.mp4").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(stagedFile.readBytes())
            val staged = UltraShareDFileStager.StagedFile(
                file = stagedFile,
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                sizeBytes = 4L,
                sha256 = digest,
                stagingDirectory = session
            )

            assertTrue(UltraShareDFileStager.verifyStagedDigest(staged))
            stagedFile.writeBytes(byteArrayOf(4, 5, 6, 8))
            assertFalse(UltraShareDFileStager.verifyStagedDigest(staged))
            staged.close()
        } finally {
            cache.deleteRecursively()
        }
    }

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
            file.close() // Closing a completed/cancelled transfer twice is harmless.

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
