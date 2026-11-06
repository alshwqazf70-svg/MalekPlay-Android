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
}
