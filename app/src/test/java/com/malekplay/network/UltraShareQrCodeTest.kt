package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UltraShareQrCodeTest {

    @Test
    fun generatedPairingCodeIsExactlyNineNumericDigits() {
        val code = UltraShareTransport.createPairingSecret()
        assertEquals(9, code.length)
        assertTrue(code.all { it in '0'..'9' })
        assertTrue(UltraShareTransport.isValidPairingSecret(code))
    }

    @Test
    fun shortPinDerivesSessionKeyFromFreshChallenge() {
        val pin = "004219837615"
        val challenge = ByteArray(32) { it.toByte() }
        assertTrue(UltraShareTransport.derivePairingKey(pin, challenge).contentEquals(
            UltraShareTransport.derivePairingKey(pin, challenge.copyOf())
        ))
        val freshChallenge = ByteArray(32) { (it + 1).toByte() }
        assertFalse(UltraShareTransport.derivePairingKey(pin, challenge).contentEquals(
            UltraShareTransport.derivePairingKey(pin, freshChallenge)
        ))
    }

    @Test
    fun pairingCodeRejectsLettersAndWrongLengths() {
        assertFalse(UltraShareTransport.isValidPairingSecret("12345678"))
        assertFalse(UltraShareTransport.isValidPairingSecret("12345678A"))
        assertFalse(UltraShareTransport.isValidPairingSecret("1234567890"))
    }

    @Test
    fun pairingPayloadRoundTripsEndpointAndSecret() {
        val secret = UltraShareTransport.createPairingSecret()
        val payload = UltraShareQrCode.createPayload(
            host = "192.168.1.25",
            port = UltraShareTransport.DEFAULT_PORT,
            pairingSecret = secret
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.1.25", decoded.host)
        assertEquals(UltraShareTransport.DEFAULT_PORT, decoded.port)
        assertEquals(secret, decoded.pairingSecret)
    }

    @Test
    fun pairingPayloadRoundTripsMultipleNetworkAddresses() {
        val secret = UltraShareTransport.createPairingSecret()
        val payload = UltraShareQrCode.createPayload(
            hosts = listOf("192.168.1.25", "192.168.43.1", "10.0.0.4"),
            port = UltraShareTransport.DEFAULT_PORT,
            pairingSecret = secret
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.1.25", decoded.host)
        assertEquals(listOf("192.168.1.25", "192.168.43.1", "10.0.0.4"), decoded.hosts)
        assertEquals(secret, decoded.pairingSecret)
    }

    @Test
    fun pairingParserContinuesToAcceptLegacyTwelveDigitQrCodes() {
        val secret = "004219837615"
        val decoded = UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=192.168.1.25&port=${UltraShareTransport.DEFAULT_PORT}&secret=$secret"
        )
        assertEquals("192.168.1.25", decoded.host)
        assertEquals(listOf("192.168.1.25"), decoded.hosts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsForeignSchemes() {
        UltraShareQrCode.parsePayload("https://example.com/?secret=anything")
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsUnsupportedVersions() {
        val secret = UltraShareTransport.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=99&host=192.168.1.25&port=${UltraShareTransport.DEFAULT_PORT}&secret=$secret"
        )
    }
    @Test(expected = IllegalArgumentException::class)
    fun pairingPayloadRejectsPublicInternetHosts() {
        UltraShareQrCode.createPayload(
            host = "example.com",
            port = UltraShareTransport.DEFAULT_PORT,
            pairingSecret = UltraShareTransport.createPairingSecret()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsLoopbackHosts() {
        val secret = UltraShareTransport.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=127.0.0.1&port=${UltraShareTransport.DEFAULT_PORT}&secret=$secret"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsMalformedIpv4Hosts() {
        val secret = UltraShareTransport.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=192.168.1.999&port=${UltraShareTransport.DEFAULT_PORT}&secret=$secret"
        )
    }

}
