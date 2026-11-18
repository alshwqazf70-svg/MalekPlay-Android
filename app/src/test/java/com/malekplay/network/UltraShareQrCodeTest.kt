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
        val code = UltraShareCore.createPairingSecret()
        assertEquals(9, code.length)
        assertTrue(code.all { it in '0'..'9' })
        assertTrue(UltraShareCore.isValidPairingSecret(code))
    }

    @Test
    fun shortPinDerivesSessionKeyFromFreshChallenge() {
        val pin = "004219837"
        val challenge = ByteArray(32) { it.toByte() }
        assertTrue(UltraShareCore.derivePairingKey(pin, challenge).contentEquals(
            UltraShareCore.derivePairingKey(pin, challenge.copyOf())
        ))
        val freshChallenge = ByteArray(32) { (it + 1).toByte() }
        assertFalse(UltraShareCore.derivePairingKey(pin, challenge).contentEquals(
            UltraShareCore.derivePairingKey(pin, freshChallenge)
        ))
    }

    @Test
    fun pairingCodeRejectsLettersAndWrongLengths() {
        assertFalse(UltraShareCore.isValidPairingSecret("12345678"))
        assertFalse(UltraShareCore.isValidPairingSecret("12345678A"))
        assertFalse(UltraShareCore.isValidPairingSecret("1234567890"))
    }

    @Test
    fun pairingPayloadRoundTripsEndpointAndSecret() {
        val secret = UltraShareCore.createPairingSecret()
        val payload = UltraShareQrCode.createPayload(
            host = "192.168.1.25",
            port = UltraShareControlChannel.DEFAULT_PORT,
            pairingSecret = secret
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.1.25", decoded.host)
        assertEquals(UltraShareControlChannel.DEFAULT_PORT, decoded.port)
        assertEquals(secret, decoded.pairingSecret)
    }

    @Test
    fun receiverPayloadAlwaysUsesControlChannelEndpoint() {
        val secret = UltraShareCore.createPairingSecret()
        val payload = UltraShareQrCode.createReceiverPayload("192.168.1.25", secret)
        val decoded = UltraShareQrCode.parsePayload(payload)
        assertEquals(UltraShareControlChannel.DEFAULT_PORT, decoded.port)
        assertEquals(secret, decoded.pairingSecret)
    }

    @Test
    fun multiInterfaceReceiverQrRoundTripsDistinctPrivateHosts() {
        val secret = UltraShareCore.createPairingSecret()
        val payload = UltraShareQrCode.createReceiverPayload(
            listOf(
                "192.168.1.25",
                "192.168.1.25",
                "192.168.43.1",
                "10.0.0.1",
                "8.8.8.8"
            ),
            secret
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.1.25", decoded.host)
        assertEquals(listOf("192.168.1.25", "192.168.43.1", "10.0.0.1"), decoded.hosts)
        assertEquals(secret, decoded.pairingSecret)
        assertEquals(UltraShareControlChannel.DEFAULT_PORT, decoded.port)
    }

    @Test
    fun invitationQrRoundTripsPeerIdentityAndOptionalLanAddresses() {
        val secret = UltraShareCore.createPairingSecret()
        val invitationId = "malekplay-peer_123"
        val payload = UltraShareQrCode.createReceiverPayload(
            hosts = emptyList(),
            pairingSecret = secret,
            invitationId = invitationId
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("", decoded.host)
        assertTrue(decoded.hosts.isEmpty())
        assertEquals(invitationId, decoded.invitationId)
        assertEquals(secret, decoded.pairingSecret)
        assertEquals(UltraShareControlChannel.DEFAULT_PORT, decoded.port)
    }

    @Test
    fun invitationQrRetainsLanFallbackHosts() {
        val secret = UltraShareCore.createPairingSecret()
        val payload = UltraShareQrCode.createReceiverPayload(
            hosts = listOf("192.168.49.1", "192.168.1.25"),
            pairingSecret = secret,
            invitationId = "peer-abc"
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.49.1", decoded.host)
        assertEquals(listOf("192.168.49.1", "192.168.1.25"), decoded.hosts)
        assertEquals("peer-abc", decoded.invitationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invitationQrRejectsMalformedPeerIdentity() {
        UltraShareQrCode.createReceiverPayload(
            hosts = emptyList(),
            pairingSecret = UltraShareCore.createPairingSecret(),
            invitationId = "bad peer identity"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsForeignSchemes() {
        UltraShareQrCode.parsePayload("https://example.com/?secret=anything")
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsUnsupportedVersions() {
        val secret = UltraShareCore.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=99&host=192.168.1.25&port=${UltraShareControlChannel.DEFAULT_PORT}&secret=$secret"
        )
    }
    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsDataChannelPort() {
        val secret = UltraShareCore.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=192.168.1.25&port=${UltraShareBatchTransport.DEFAULT_PORT}&secret=$secret"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingPayloadRejectsDataPortInsteadOfControlPort() {
        UltraShareQrCode.createPayload(
            host = "192.168.1.25",
            port = UltraShareBatchTransport.DEFAULT_PORT,
            pairingSecret = UltraShareCore.createPairingSecret()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingPayloadRejectsPublicInternetHosts() {
        UltraShareQrCode.createPayload(
            host = "example.com",
            port = UltraShareControlChannel.DEFAULT_PORT,
            pairingSecret = UltraShareCore.createPairingSecret()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsLoopbackHosts() {
        val secret = UltraShareCore.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=127.0.0.1&port=${UltraShareControlChannel.DEFAULT_PORT}&secret=$secret"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pairingParserRejectsMalformedIpv4Hosts() {
        val secret = UltraShareCore.createPairingSecret()
        UltraShareQrCode.parsePayload(
            "malekplay://ultra-share?v=1&host=192.168.1.999&port=${UltraShareControlChannel.DEFAULT_PORT}&secret=$secret"
        )
    }


    @Test
    fun hotspotQrRoundTripsCredentialsAndMultipleCandidateHosts() {
        val secret = UltraShareCore.createPairingSecret()
        val payload = UltraShareQrCode.createHotspotReceiverPayload(
            hosts = listOf("192.168.43.1", "192.168.43.1", "10.0.0.1", "8.8.8.8"),
            pairingSecret = secret,
            ssid = "MalekPlay-Share",
            password = "share1234"
        )

        val decoded = UltraShareQrCode.parsePayload(payload)

        assertEquals("192.168.43.1", decoded.host)
        assertEquals(listOf("192.168.43.1", "10.0.0.1"), decoded.hosts)
        assertEquals(secret, decoded.pairingSecret)
        assertEquals("MalekPlay-Share", decoded.hotspotSsid)
        assertEquals("share1234", decoded.hotspotPassword)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hotspotQrRejectsPasswordShorterThanWpa2Minimum() {
        UltraShareQrCode.createHotspotReceiverPayload(
            hosts = listOf("192.168.43.1"),
            pairingSecret = UltraShareCore.createPairingSecret(),
            ssid = "MalekPlay-Share",
            password = "short"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun hotspotQrRejectsPayloadWithoutUsableLocalHost() {
        UltraShareQrCode.createHotspotReceiverPayload(
            hosts = listOf("8.8.8.8", "127.0.0.1"),
            pairingSecret = UltraShareCore.createPairingSecret(),
            ssid = "MalekPlay-Share",
            password = "share1234"
        )
    }

}
