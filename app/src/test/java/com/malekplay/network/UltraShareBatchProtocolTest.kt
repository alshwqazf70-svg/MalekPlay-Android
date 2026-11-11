package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun acceptsGenericFilesAndArchivesButRejectsApkAndPdf() {
        listOf(
            "video/mp4", "audio/mpeg", "image/jpeg", "video/x-matroska",
            "application/zip", "text/plain", "application/octet-stream",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ).forEach { mime ->
            assertTrue("Expected supported MIME type: $mime", UltraShareTransport.isSupportedShareMimeType(mime))
            val item = UltraShareBatchProtocol.newEntry("shared-file.bin", mime, 1L,
                MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)))
            assertEquals(mime, UltraShareBatchProtocol.validate(
                UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item))
            ).entries.single().mimeType)
        }
        listOf("application/pdf", "application/vnd.android.package-archive").forEach { mime ->
            assertFalse(UltraShareTransport.isSupportedShareMimeType(mime))
            val item = UltraShareBatchProtocol.newEntry("blocked-file.bin", mime, 1L,
                MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) {
                UltraShareBatchProtocol.validate(
                    UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(item))
                )
            }
        }
        listOf("package.apk" to "application/octet-stream", "document.pdf" to "application/octet-stream")
            .forEach { (name, mime) ->
                assertFalse(UltraShareTransport.isAllowedShareFile(name, mime))
            }
    }

    @Test fun rejectsMalformedMimeTypes() {
        listOf("", "application", "/pdf", "application/", "application/pdf;broken").forEach { mime ->
            assertTrue("Expected malformed MIME type to be rejected: $mime",
                !UltraShareTransport.isSupportedShareMimeType(mime))
        }
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

    @Test fun androidManifestMatchesDesktopV2GoldenWireVector() {
        val manifest = UltraShareBatchProtocol.Manifest(
            "00112233-4455-6677-8899-aabbccddeeff",
            listOf(
                UltraShareBatchProtocol.FileEntry(
                    "ffeeddcc-bbaa-9988-7766-554433221100",
                    "مقطع.bin",
                    "application/octet-stream",
                    6L,
                    "dd56de4137951d9c92681b03416ec15f886b4482a27e3a517d32f085244cbe5d"
                        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                )
            )
        )
        val expected = (
            "4d505532000200112233445566778899aabbccddeeff0001" +
            "ffeeddccbbaa99887766554433221100000cd985d982d8b7d8b92e62696e" +
            "00186170706c69636174696f6e2f6f637465742d73747265616d" +
            "0000000000000006" +
            "dd56de4137951d9c92681b03416ec15f886b4482a27e3a517d32f085244cbe5d"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertTrue(expected.contentEquals(UltraShareBatchProtocol.encode(manifest)))
        assertEquals(manifest, UltraShareBatchProtocol.decode(expected))
    }

    @Test fun manifestRejectsMalformedUtf8InsteadOfSilentlyReplacingBytes() {
        val manifest = UltraShareBatchProtocol.Manifest(
            UUID.randomUUID().toString(), listOf(entry("one.bin", "x"))
        )
        val malformed = UltraShareBatchProtocol.encode(manifest)
        // Fixed header (24 bytes), entry UUID (16), name length (2), then UTF-8 name.
        malformed[42] = 0xC3.toByte()
        malformed[43] = 0x28
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchProtocol.decode(malformed)
        }
    }

    @Test fun v2UsesSeparateDataPortAndHasBoundedPeerConcurrency() {
        assertEquals(UltraShareTransport.DEFAULT_PORT + 1, UltraShareBatchTransport.DEFAULT_PORT)
        assertEquals(4, UltraShareBatchTransport.DEFAULT_MAX_PEERS)
    }
    @Test fun pairingAcknowledgementRequiresBothValidSecretAndApprovedSession() {
        assertTrue(UltraShareBatchTransport.isPeerSessionAuthorized(authenticated = true, sessionActive = true))
        assertTrue(!UltraShareBatchTransport.isPeerSessionAuthorized(authenticated = false, sessionActive = true))
        assertTrue(!UltraShareBatchTransport.isPeerSessionAuthorized(authenticated = true, sessionActive = false))
        assertTrue(!UltraShareBatchTransport.isPeerSessionAuthorized(authenticated = false, sessionActive = false))
    }


    @Test fun v2ReaderMakesProgressWhenBulkReadTemporarilyReturnsZero() {
        val source = java.io.ByteArrayInputStream(byteArrayOf(10, 20, 30))
        val unusual = object : java.io.InputStream() {
            private var returnZero = true
            override fun read(): Int = source.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return source.read(buffer, offset, length)
            }
        }
        val buffer = ByteArray(3)
        assertEquals(3, UltraShareBatchTransport.readAtMost(unusual, buffer, buffer.size))
        assertEquals(listOf<Byte>(10, 20, 30), buffer.toList())
        assertEquals(0, UltraShareBatchTransport.readAtMost(unusual, buffer, buffer.size))
    }
}
