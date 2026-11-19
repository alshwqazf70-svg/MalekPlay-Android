package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareCoreTest {

    @Test
    fun sharePolicyAllowsOrdinaryFilesAndBlocksOnlyApkAndPdf() {
        listOf("video/mp4", "video/x-matroska", "audio/mpeg", "image/jpeg", "image/webp")
            .forEach { assertTrue(UltraShareCore.isSupportedShareMimeType(it)) }
        listOf(
            "video", "audio/", "", "not a mime type", "application/zip", "text/plain",
            "application/octet-stream", "application/pdf",
            "application/vnd.android.package-archive", "image/svg+xml"
        ).forEach { assertFalse(UltraShareCore.isSupportedShareMimeType(it)) }

        assertTrue(UltraShareCore.isAllowedShareFile("movie.mp4", "video/mp4"))
        assertTrue(UltraShareCore.isAllowedShareFile("track.mp3", "audio/mpeg"))
        assertTrue(UltraShareCore.isAllowedShareFile("photo.jpg", "image/jpeg"))
        assertTrue(UltraShareCore.isAllowedShareFile("provider-file.mp4", "application/octet-stream"))
        assertTrue(UltraShareCore.isAllowedShareFile("archive.zip", "application/zip"))
        assertTrue(UltraShareCore.isAllowedShareFile("document.txt", "text/plain"))
        assertTrue(UltraShareCore.isAllowedShareFile("unknown.bin", "application/octet-stream"))
        assertTrue(UltraShareCore.isAllowedShareFile("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertTrue(UltraShareCore.isAllowedShareFile("image.svg", "image/svg+xml"))
        assertFalse(UltraShareCore.isAllowedShareFile("renamed.apk", "video/mp4"))
        assertFalse(UltraShareCore.isAllowedShareFile("document.pdf", "application/octet-stream"))
        assertFalse(UltraShareCore.isAllowedShareFile("document.pdf ", "application/octet-stream"))
        assertFalse(UltraShareCore.isAllowedShareFile("nested/document.pdf", "text/plain"))

        assertFalse(UltraShareCore.isAllowedShareFile("", "application/octet-stream"))
    }

    @Test
    fun strictUtf8DecoderRejectsMalformedAndUnmappableByteSequences() {
        assertEquals("ملف.mp4", UltraShareCore.decodeUtf8Strict("ملف.mp4".toByteArray(Charsets.UTF_8)))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UltraShareCore.decodeUtf8Strict(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun pairingSecretIsCryptographicallyRandomFormat() {
        val first = UltraShareCore.createPairingSecret()
        val second = UltraShareCore.createPairingSecret()

        assertEquals(9, first.length)
        assertTrue(first.all { it in '0'..'9' })
        assertTrue(UltraShareCore.isValidPairingSecret(first))
        assertTrue(UltraShareCore.isValidPairingSecret(second))
        assertFalse(first == second)
    }

    @Test
    fun pairingPinDerivationMatchesDesktopProtocolVector() {
        val challenge = ByteArray(32) { it.toByte() }
        val derived = UltraShareCore.derivePairingKey("004219837", challenge)
        val actual = derived.joinToString("") { "%02x".format(it) }
        assertEquals(
            "313f3930d3a5684ebdd21a38ea0be31fb255fe5436476972f2a387b2ded57150",
            actual
        )
    }

    @Test
    fun pairingSecretRejectsMalformedValues() {
        assertFalse(UltraShareCore.isValidPairingSecret(""))
        assertFalse(UltraShareCore.isValidPairingSecret("123456"))
        assertTrue(UltraShareCore.isValidPairingSecret("123456789"))
        assertFalse(UltraShareCore.isValidPairingSecret("1234567890"))
        assertFalse(UltraShareCore.isValidPairingSecret("1234567890123"))
        assertFalse(UltraShareCore.isValidPairingSecret("12345678A"))
        assertFalse(UltraShareCore.isValidPairingSecret("١٢٣٤٥٦٧٨٩"))
        assertFalse(UltraShareCore.isValidPairingSecret("z".repeat(64)))
        assertFalse(UltraShareCore.isValidPairingSecret("a".repeat(63)))
    }

    @Test
    fun fileNameSanitizerRemovesPathComponents() {
        assertEquals("video.mp4", UltraShareCore.sanitizeFileName("C:\\Users\\Malek\\video.mp4"))
        assertEquals("song.mp3", UltraShareCore.sanitizeFileName("../../music/song.mp3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fileNameSanitizerRejectsBlankNames() {
        UltraShareCore.sanitizeFileName("...")
    }

    @Test
    fun fileNameSanitizerRemovesControlAndBidirectionalFormattingCharacters() {
        val unsafe = "movie\u202Egpj.exe\u0085.mp4"
        assertEquals("moviegpj.exe.mp4", UltraShareCore.sanitizeFileName(unsafe))
    }

    @Test
    fun fileNameSanitizerBoundsUtf8LengthWithoutSplittingUnicodeCodePoints() {
        val sanitized = UltraShareCore.sanitizeFileName("🎬".repeat(100))
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 180)
        var index = 0
        while (index < sanitized.length) {
            val current = sanitized[index]
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < sanitized.length && Character.isLowSurrogate(sanitized[index + 1]))
            }
            if (Character.isLowSurrogate(current)) {
                assertTrue(index > 0 && Character.isHighSurrogate(sanitized[index - 1]))
            }
            index++
        }
    }

    @Test
    fun mimeTypeSanitizerAcceptsValidTypesAndRejectsHeaderInjection() {
        assertEquals("video/mp4", UltraShareCore.sanitizeMimeType(" video/mp4 "))
        assertEquals(
            "application/octet-stream",
            UltraShareCore.sanitizeMimeType("video/mp4\r\nX-Evil: yes")
        )
        assertEquals("application/octet-stream", UltraShareCore.sanitizeMimeType("not a mime type"))
    }

    @Test
    fun transferIdentityMatchesDesktopProtocolVector() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("sample content".toByteArray(Charsets.UTF_8))
        assertEquals(
            "0b9008e02c8c871dcedefd6a",
            UltraShareCore.buildTransferId("مقطع.mp4", 123456L, digest)
        )
    }

    @Test
    fun resumeIdentitySeparatesDifferentContentWithSameNameAndSize() {
        val firstDigest = ByteArray(32) { 1 }
        val secondDigest = ByteArray(32) { 2 }
        val first = UltraShareCore.buildTransferId("movie.mp4", 4096L, firstDigest)
        val sameContent = UltraShareCore.buildTransferId("movie.mp4", 4096L, firstDigest)
        val differentContent = UltraShareCore.buildTransferId("movie.mp4", 4096L, secondDigest)

        assertEquals(first, sameContent)
        assertFalse(first == differentContent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resumeIdentityRejectsInvalidDigestLength() {
        UltraShareCore.buildTransferId("movie.mp4", 4096L, ByteArray(31))
    }

    @Test
    fun completedReceiptRequiresMatchingSizeAndContentDigest() {
        val digest = "ab".repeat(32)
        val marker = "4096:" + digest + ":movie.mp4"

        assertEquals(
            "movie.mp4",
            UltraShareCore.completedReceiptFileName(marker, 4096L, digest)
        )
        assertEquals(
            null,
            UltraShareCore.completedReceiptFileName(marker, 4097L, digest)
        )
        assertEquals(
            null,
            UltraShareCore.completedReceiptFileName(marker, 4096L, "cd".repeat(32))
        )
    }

    @Test
    fun completedReceiptSanitizesStoredFileNameBeforeResolvingIt() {
        val digest = "ef".repeat(32)
        val marker = "12:" + digest + ":../../received.mp4"

        assertEquals(
            "received.mp4",
            UltraShareCore.completedReceiptFileName(marker, 12L, digest)
        )
    }

    @Test
    fun privateLanHostValidationRejectsNonLocalTargets() {
        assertTrue(UltraShareCore.isValidPrivateLanHost("192.168.1.25"))
        assertTrue(UltraShareCore.isValidPrivateLanHost("10.0.0.8"))
        assertFalse(UltraShareCore.isValidPrivateLanHost("127.0.0.1"))
        assertFalse(UltraShareCore.isValidPrivateLanHost("8.8.8.8"))
        assertFalse(UltraShareCore.isValidPrivateLanHost("example.com"))
        assertFalse(UltraShareCore.isValidPrivateLanHost("192.168.1.999"))
    }

    @Test
    fun streamReaderMakesProgressWhenBulkReadReturnsZero() {
        val delegate = java.io.ByteArrayInputStream(byteArrayOf(10, 20, 30))
        val unusual = object : java.io.InputStream() {
            private var returnZero = true
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }
        val buffer = ByteArray(3)
        assertEquals(1, readStreamWithProgress(unusual, buffer))
        assertEquals(10.toByte(), buffer[0])
        assertEquals(2, readStreamWithProgress(unusual, buffer, 1, 2))
        assertEquals(listOf<Byte>(10, 20, 30), buffer.toList())
        assertEquals(-1, readStreamWithProgress(unusual, buffer))
    }


    @Test
    fun completedReceiptIsSyncedAndAtomicallyPublishedFromTemporaryFile() {
        val directory = createTempDir(prefix = "ultrashare-receipt-test")
        try {
            val temporary = java.io.File(directory, "receipt.tmp")
            val marker = java.io.File(directory, "receipt.received")
            val expected = "4096:" + "ab".repeat(32) + ":movie.mp4"
            marker.writeText("stale receipt", Charsets.UTF_8)

            writeUltraShareReceiptAtomically(temporary, marker, expected)

            assertTrue(marker.isFile)
            assertEquals(expected, marker.readText(Charsets.UTF_8))
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }



    @Test
    fun destinationPublicationLockSerializesConcurrentReceiveProtocols() {
        val directory = createTempDir(prefix = "ultrashare-publish-lock-test")
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maximumActive = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = List(8) {
            Thread {
                repeat(20) {
                    UltraShareCore.withDestinationPublicationLock(directory) {
                        val now = active.incrementAndGet()
                        maximumActive.updateAndGet { previous -> maxOf(previous, now) }
                        try {
                            Thread.sleep(2)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }
        try {
            threads.forEach(Thread::start)
            threads.forEach { it.join(5_000) }
            assertTrue(threads.none { it.isAlive })
            assertEquals(1, maximumActive.get())
        } finally {
            threads.forEach { it.interrupt() }
            directory.deleteRecursively()
        }
    }



}
