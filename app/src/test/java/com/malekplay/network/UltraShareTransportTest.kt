package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareTransportTest {

    @Test
    fun pairingSecretIsCryptographicallyRandomFormat() {
        val first = UltraShareTransport.createPairingSecret()
        val second = UltraShareTransport.createPairingSecret()

        assertEquals(64, first.length)
        assertTrue(UltraShareTransport.isValidPairingSecret(first))
        assertTrue(UltraShareTransport.isValidPairingSecret(second))
        assertFalse(first == second)
    }

    @Test
    fun pairingSecretRejectsMalformedValues() {
        assertFalse(UltraShareTransport.isValidPairingSecret(""))
        assertFalse(UltraShareTransport.isValidPairingSecret("123456"))
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


}
