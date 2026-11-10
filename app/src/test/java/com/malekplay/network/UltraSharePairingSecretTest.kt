package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePairingSecretTest {

    @Test
    fun generatedSecretAlwaysContainsExactlyNineAsciiDigits() {
        repeat(256) {
            val secret = UltraShareTransport.createPairingSecret()
            assertEquals(9, secret.length)
            assertTrue(secret.all { it in '0'..'9' })
            assertTrue(UltraShareTransport.isValidPairingSecret(secret))
        }
    }

    @Test
    fun validatorRejectsWrongLengthAndNonAsciiDigits() {
        assertFalse(UltraShareTransport.isValidPairingSecret(""))
        assertFalse(UltraShareTransport.isValidPairingSecret("12345678"))
        assertFalse(UltraShareTransport.isValidPairingSecret("1234567890"))
        assertFalse(UltraShareTransport.isValidPairingSecret("12345a789"))
        assertFalse(UltraShareTransport.isValidPairingSecret("١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun validatorAcceptsNineAsciiDigitsIncludingLeadingZero() {
        assertTrue(UltraShareTransport.isValidPairingSecret("012345678"))
    }
}
