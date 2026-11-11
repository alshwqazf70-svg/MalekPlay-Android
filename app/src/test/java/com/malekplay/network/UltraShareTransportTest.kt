package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareTransportTest {

    @Test
    fun shareMimePolicyAllowsOnlyImagesAudioAndVideo() {
        listOf("video/mp4", "video/x-matroska", "audio/mpeg", "image/jpeg")
            .forEach { assertTrue(UltraShareTransport.isSupportedShareMimeType(it)) }
        listOf("application/pdf", "application/vnd.android.package-archive",
            "application/zip", "text/plain", "application/octet-stream", "video", "audio/")
            .forEach { assertFalse(UltraShareTransport.isSupportedShareMimeType(it)) }
    }

    @Test
    fun transferChunkTuningAcceptsWireCompatibleSizes() {
        assertEquals(UltraShareTransport.MIN_TUNABLE_CHUNK_BYTES, UltraShareTransport.validateChunkBytes(64 * 1024))
        assertEquals(128 * 1024, UltraShareTransport.validateChunkBytes(128 * 1024))
        assertEquals(UltraShareTransport.CHUNK_BYTES, UltraShareTransport.validateChunkBytes(256 * 1024))
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferChunkTuningRejectsSizesBelowSafeMinimum() {
        UltraShareTransport.validateChunkBytes(32 * 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun transferChunkTuningRejectsFramesLargerThanV1ReceiverLimit() {
        UltraShareTransport.validateChunkBytes(512 * 1024)
    }

    @Test
    fun pairingSecretIsCryptographicallyRandomFormat() {
        val first = UltraShareTransport.createPairingSecret()
        val second = UltraShareTransport.createPairingSecret()

        assertEquals(9, first.length)
        assertTrue(first.all { it in '0'..'9' })
        assertTrue(UltraShareTransport.isValidPairingSecret(first))
        assertTrue(UltraShareTransport.isValidPairingSecret(second))
        assertFalse(first == second)
    }

    @Test
    fun pairingPinDerivationMatchesDesktopProtocolVector() {
        val challenge = ByteArray(32) { it.toByte() }
        val derived = UltraShareTransport.derivePairingKey("004219837", challenge)
        val actual = derived.joinToString("") { "%02x".format(it) }
        assertEquals(
            "313f3930d3a5684ebdd21a38ea0be31fb255fe5436476972f2a387b2ded57150",
            actual
        )
    }

    @Test
    fun pairingSecretRejectsMalformedValues() {
        assertFalse(UltraShareTransport.isValidPairingSecret(""))
        assertFalse(UltraShareTransport.isValidPairingSecret("123456"))
        assertTrue(UltraShareTransport.isValidPairingSecret("123456789"))
        assertFalse(UltraShareTransport.isValidPairingSecret("1234567890"))
        assertFalse(UltraShareTransport.isValidPairingSecret("1234567890123"))
        assertFalse(UltraShareTransport.isValidPairingSecret("12345678A"))
        assertFalse(UltraShareTransport.isValidPairingSecret("١٢٣٤٥٦٧٨٩"))
        assertFalse(UltraShareTransport.isValidPairingSecret("z".repeat(64)))
        assertFalse(UltraShareTransport.isValidPairingSecret("a".repeat(63)))
    }

    @Test
    fun fileNameSanitizerRemovesPathComponents() {
        assertEquals("video.mp4", UltraShareTransport.sanitizeFileName("C:\\Users\\Malek\\video.mp4"))
        assertEquals("song.mp3", UltraShareTransport.sanitizeFileName("../../music/song.mp3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fileNameSanitizerRejectsBlankNames() {
        UltraShareTransport.sanitizeFileName("...")
    }

    @Test
    fun fileNameSanitizerRemovesControlAndBidirectionalFormattingCharacters() {
        val unsafe = "movie\u202Egpj.exe\u0085.mp4"
        assertEquals("moviegpj.exe.mp4", UltraShareTransport.sanitizeFileName(unsafe))
    }

    @Test
    fun fileNameSanitizerBoundsUtf8LengthWithoutSplittingUnicodeCodePoints() {
        val sanitized = UltraShareTransport.sanitizeFileName("🎬".repeat(100))
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
        assertEquals("video/mp4", UltraShareTransport.sanitizeMimeType(" video/mp4 "))
        assertEquals(
            "application/octet-stream",
            UltraShareTransport.sanitizeMimeType("video/mp4\r\nX-Evil: yes")
        )
        assertEquals("application/octet-stream", UltraShareTransport.sanitizeMimeType("not a mime type"))
    }

    @Test
    fun transferIdentityMatchesDesktopProtocolVector() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("sample content".toByteArray(Charsets.UTF_8))
        assertEquals(
            "0b9008e02c8c871dcedefd6a",
            UltraShareTransport.buildTransferId("مقطع.mp4", 123456L, digest)
        )
    }

    @Test
    fun resumeIdentitySeparatesDifferentContentWithSameNameAndSize() {
        val firstDigest = ByteArray(32) { 1 }
        val secondDigest = ByteArray(32) { 2 }
        val first = UltraShareTransport.buildTransferId("movie.mp4", 4096L, firstDigest)
        val sameContent = UltraShareTransport.buildTransferId("movie.mp4", 4096L, firstDigest)
        val differentContent = UltraShareTransport.buildTransferId("movie.mp4", 4096L, secondDigest)

        assertEquals(first, sameContent)
        assertFalse(first == differentContent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resumeIdentityRejectsInvalidDigestLength() {
        UltraShareTransport.buildTransferId("movie.mp4", 4096L, ByteArray(31))
    }

    @Test
    fun completedReceiptRequiresMatchingSizeAndContentDigest() {
        val digest = "ab".repeat(32)
        val marker = "4096:" + digest + ":movie.mp4"

        assertEquals(
            "movie.mp4",
            UltraShareTransport.completedReceiptFileName(marker, 4096L, digest)
        )
        assertEquals(
            null,
            UltraShareTransport.completedReceiptFileName(marker, 4097L, digest)
        )
        assertEquals(
            null,
            UltraShareTransport.completedReceiptFileName(marker, 4096L, "cd".repeat(32))
        )
    }

    @Test
    fun completedReceiptSanitizesStoredFileNameBeforeResolvingIt() {
        val digest = "ef".repeat(32)
        val marker = "12:" + digest + ":../../received.mp4"

        assertEquals(
            "received.mp4",
            UltraShareTransport.completedReceiptFileName(marker, 12L, digest)
        )
    }

    @Test
    fun privateLanHostValidationRejectsNonLocalTargets() {
        assertTrue(UltraShareTransport.isValidPrivateLanHost("192.168.1.25"))
        assertTrue(UltraShareTransport.isValidPrivateLanHost("10.0.0.8"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("127.0.0.1"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("8.8.8.8"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("example.com"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("192.168.1.999"))
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
                    UltraShareTransport.withDestinationPublicationLock(directory) {
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
