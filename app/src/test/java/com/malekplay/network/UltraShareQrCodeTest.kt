package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UltraShareQrCodeTest {

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
