package com.malekplay.translation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatedSubtitleCacheTest {

    @Test
    fun removesOnlyExpiredGeneratedSubtitleFiles() {
        val directory = createTempDir(prefix = "malekplay-cache-test-")
        try {
            val now = 1_800_000_000_000L
            val expired = File(directory, "malekplay-ar-expired.srt").apply {
                writeText("expired")
                setLastModified(now - 8L * 24 * 60 * 60 * 1_000)
            }
            val recent = File(directory, "malekplay-ar-recent.srt").apply {
                writeText("recent")
                setLastModified(now - 60_000)
            }
            val unrelated = File(directory, "user-subtitle.srt").apply {
                writeText("keep")
                setLastModified(now - 30L * 24 * 60 * 60 * 1_000)
            }
            val otherExtension = File(directory, "malekplay-ar-temp.vtt").apply {
                writeText("keep")
                setLastModified(now - 30L * 24 * 60 * 60 * 1_000)
            }

            TranslatedSubtitleCache.pruneExpired(directory, now)

            assertFalse(expired.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
            assertTrue(otherExtension.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
