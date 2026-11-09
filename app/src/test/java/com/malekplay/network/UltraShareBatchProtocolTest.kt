package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class UltraShareBatchProtocolTest {
    private fun entry(name: String, content: String): UltraShareBatchProtocol.FileEntry {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return UltraShareBatchProtocol.newEntry(name, "video/mp4", content.toByteArray().size.toLong(), digest)
    }

    @Test fun manifestRoundTripsMultipleFilesAndPreservesIndependentIdentity() {
        val first = entry("one.bin", "alpha")
        val second = entry("two.bin", "beta")
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(first, second))
        val decoded = UltraShareBatchProtocol.decode(UltraShareBatchProtocol.encode(manifest))
        assertEquals(manifest.batchId, decoded.batchId)
        assertEquals(listOf(first, second), decoded.entries)
        assertEquals(9L, decoded.totalBytes)
    }

    @Test fun perFileResultsAreValidatedIndependently() {
        val first = entry("one.bin", "alpha")
        val second = entry("two.bin", "beta")
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(first, second))
        val results = listOf(
            UltraShareBatchProtocol.FileResult(first.id, UltraShareBatchProtocol.FileStatus.COMPLETED, first.sizeBytes),
            UltraShareBatchProtocol.FileResult(second.id, UltraShareBatchProtocol.FileStatus.FAILED, 2L, "connection lost")
        )
        assertEquals(results, UltraShareBatchProtocol.validateResults(manifest, results))
    }

    @Test fun zeroByteFilesRemainValidManifestEntries() {
        val empty = UltraShareBatchProtocol.newEntry(
            name = "empty.bin",
            mimeType = "audio/mpeg",
            sizeBytes = 0L,
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
        )
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(empty))
        val decoded = UltraShareBatchProtocol.decode(UltraShareBatchProtocol.encode(manifest))
        assertEquals(0L, decoded.entries.single().sizeBytes)
    }

    @Test fun acceptsGeneralDocumentsArchivesAndApplicationPackages() {
        listOf(
            "video/mp4",
            "audio/mpeg",
            "image/jpeg",
            "application/pdf",
            "application/zip",
            "application/vnd.android.package-archive",
            "text/plain",
            "application/octet-stream"
        ).forEachIndexed { index, mime ->
            val bytes = "payload-$index".toByteArray()
            val item = UltraShareBatchProtocol.newEntry(
                name = "file-$index.bin",
                mimeType = mime,
                sizeBytes = bytes.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            )
            val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item))
            assertEquals(mime, UltraShareBatchProtocol.decode(UltraShareBatchProtocol.encode(manifest)).entries.single().mimeType)
        }
    }

    @Test fun rejectsMalformedOrWildcardMimeTypes() {
        listOf("", "application", "*/*", "video/*", "application/zip; bad").forEach { mime ->
            assertTrue(!UltraShareTransport.isAllowedTransferMimeType(mime))
        }
        assertTrue(UltraShareTransport.isAllowedTransferMimeType("application/vnd.android.package-archive"))
    }

    @Test fun rejectsDuplicateFileIds() {
        val item = entry("one.bin", "alpha")
        val duplicate = item.copy(name = "two.bin")
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchProtocol.validate(
                UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item, duplicate))
            )
        }
    }

    @Test fun rejectsTrailingBytesAndInvalidHeader() {
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(entry("one.bin", "x")))
        val encoded = UltraShareBatchProtocol.encode(manifest) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { UltraShareBatchProtocol.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) { UltraShareBatchProtocol.decode(byteArrayOf(0, 0, 0, 0)) }
    }

    @Test fun rejectsCompletedResultWithPartialByteCount() {
        val item = entry("one.bin", "alpha")
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item))
        val invalid = listOf(UltraShareBatchProtocol.FileResult(item.id, UltraShareBatchProtocol.FileStatus.COMPLETED, 1L))
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchProtocol.validateResults(manifest, invalid)
        }
    }

    @Test fun encodedManifestStaysWithinConfiguredBound() {
        val item = entry("one.bin", "alpha")
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item))
        assertTrue(UltraShareBatchProtocol.encode(manifest).size < UltraShareBatchProtocol.MAX_MANIFEST_BYTES)
    }

    @Test fun v2UsesSeparateDataPortAndHasBoundedPeerConcurrency() {
        assertEquals(UltraShareTransport.DEFAULT_PORT + 1, UltraShareBatchTransport.DEFAULT_PORT)
        assertEquals(4, UltraShareBatchTransport.DEFAULT_MAX_PEERS)
    }
}
