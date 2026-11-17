package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UltraShareDFileManifestTest {
    private val key = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun authenticatedManifestRoundTripsMultipleFiles() {
        val entries = listOf(
            entry("movie.mp4", 1024L, ByteArray(32) { 0x11 }),
            entry("subtitle.srt", 0L, ByteArray(32) { 0x22 })
        )

        val decoded = UltraShareDFileManifest.decode(
            UltraShareDFileManifest.encode(entries, key),
            key
        )

        assertNotNull(decoded)
        assertEquals(2, decoded!!.entries.size)
        assertEquals("movie.mp4", decoded.entries[0].fileName)
        assertEquals(1024L, decoded.entries[0].sizeBytes)
        assertTrue(decoded.entries[0].sha256.contentEquals(entries[0].sha256))
        assertEquals(0L, decoded.entries[1].sizeBytes)
        assertEquals(1024L, decoded.totalBytes)
    }

    @Test
    fun rejectsManifestAuthenticatedWithAnotherSessionKey() {
        val encoded = UltraShareDFileManifest.encode(
            listOf(entry("video.mp4", 5L, ByteArray(32))),
            key
        )

        assertNull(UltraShareDFileManifest.decode(encoded, ByteArray(16) { 9 }))
    }

    @Test
    fun rejectsModifiedManifestBytes() {
        val encoded = UltraShareDFileManifest.encode(
            listOf(entry("video.mp4", 5L, ByteArray(32))),
            key
        )
        val replacement = if (encoded.first() == 'A') 'B' else 'A'
        val tampered = replacement + encoded.drop(1)

        assertNull(UltraShareDFileManifest.decode(tampered, key))
    }

    @Test
    fun rejectsDuplicateNamesAndInvalidDigestLength() {
        val duplicate = listOf(
            entry("same.mp4", 1L, ByteArray(32)),
            entry("same.mp4", 1L, ByteArray(32))
        )
        val invalidDigest = listOf(entry("video.mp4", 1L, ByteArray(31)))

        assertFalse(runCatching { UltraShareDFileManifest.encode(duplicate, key) }.isSuccess)
        assertFalse(runCatching { UltraShareDFileManifest.encode(invalidDigest, key) }.isSuccess)
    }

    @Test
    fun rejectsPathTraversalAndOversizedFiles() {
        assertFalse(
            runCatching {
                UltraShareDFileManifest.encode(
                    listOf(entry("../escape.mp4", 1L, ByteArray(32))),
                    key
                )
            }.isSuccess
        )
        assertFalse(
            runCatching {
                UltraShareDFileManifest.encode(
                    listOf(entry("huge.mp4", UltraShareTransport.MAX_FILE_BYTES + 1, ByteArray(32))),
                    key
                )
            }.isSuccess
        )
    }

    @Test
    fun rejectsWindowsPathSeparatorsInManifestNames() {
        assertFalse(
            runCatching {
                UltraShareDFileManifest.encode(
                    listOf(entry("folder\\\\escape.mp4", 1L, ByteArray(32))),
                    key
                )
            }.isSuccess
        )
    }

    @Test
    fun verifiesReceivedFileByExactSizeAndSha256() {
        val file = File.createTempFile("dfile-manifest", ".bin")
        try {
            val content = "MalekPlay DFile integrity".toByteArray()
            file.writeBytes(content)
            val expected = entry(
                file.name,
                content.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(content)
            )
            val wrongDigest = expected.copy(sha256 = ByteArray(32))

            assertTrue(UltraShareDFileManifest.verifyFile(file, expected))
            assertFalse(UltraShareDFileManifest.verifyFile(file, wrongDigest))
            assertFalse(
                UltraShareDFileManifest.verifyFile(
                    file,
                    expected.copy(sizeBytes = content.size.toLong() + 1)
                )
            )
        } finally {
            file.delete()
        }
    }

    private fun entry(name: String, size: Long, digest: ByteArray) =
        UltraShareDFileManifest.Entry(name, size, digest)
}
