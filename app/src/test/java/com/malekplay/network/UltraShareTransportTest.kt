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
    fun privateLanHostValidationRejectsNonLocalTargets() {
        assertTrue(UltraShareTransport.isValidPrivateLanHost("192.168.1.25"))
        assertTrue(UltraShareTransport.isValidPrivateLanHost("10.0.0.8"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("127.0.0.1"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("8.8.8.8"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("example.com"))
        assertFalse(UltraShareTransport.isValidPrivateLanHost("192.168.1.999"))
    }

}
