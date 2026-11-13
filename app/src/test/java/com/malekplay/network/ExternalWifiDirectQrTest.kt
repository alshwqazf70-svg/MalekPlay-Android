package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWifiDirectQrTest {
    private val payload =
        "http://kuaiya.cn/q?sid=DIRECT-test-REVORU5F&freq=5240&ip=192.168.49.1" +
            "&ps=QUJDRA%3D%3D&t=2&k=d0de"

    @Test
    fun parsesObservedKuaiyaFieldsWithoutTreatingThemAsCredentials() {
        val details = ExternalWifiDirectQr.parse(payload)

        assertEquals("DIRECT-test-REVORU5F", details.sessionId)
        assertEquals("192.168.49.1", details.hostAddress)
        assertEquals(5240, details.frequencyMhz)
        assertEquals("QUJDRA==", details.opaquePasswordToken)
        assertEquals("2", details.connectionType)
        assertEquals("d0de", details.opaqueVerificationToken)
        assertFalse(details.supportsAutomaticJoin)
    }

    @Test
    fun parsesObservedAndroid6QrWithoutOptionalWifiDirectFields() {
        val android6Payload =
            "http://kuaiya.cn/q?sid=d2B03U00tSjMyN1A&ip=192.168.43.1&t=2&k=4f65"

        val details = ExternalWifiDirectQr.parse(android6Payload)

        assertEquals("d2B03U00tSjMyN1A", details.sessionId)
        assertEquals("192.168.43.1", details.hostAddress)
        assertEquals(null, details.frequencyMhz)
        assertEquals(null, details.opaquePasswordToken)
        assertEquals("2", details.connectionType)
        assertEquals("4f65", details.opaqueVerificationToken)
        assertFalse(details.supportsAutomaticJoin)
    }

    @Test
    fun parsesObservedAndroid10QrMetadata() {
        val android10Payload =
            "http://kuaiya.cn/q?sid=DIRECT-z8-rxAAABBU00tTjk2MFU&freq=5240" +
                "&ip=192.168.49.1&ps=RX0r13HAG1Fa7gaShLTcvw%3D%3D&t=2&k=d0de"

        val details = ExternalWifiDirectQr.parse(android10Payload)

        assertEquals("192.168.49.1", details.hostAddress)
        assertEquals(5240, details.frequencyMhz)
        assertEquals("RX0r13HAG1Fa7gaShLTcvw==", details.opaquePasswordToken)
    }

    @Test
    fun redactsOpaqueTokensFromDiagnosticText() {
        val details = ExternalWifiDirectQr.parse(payload)
        val diagnostic = details.toString()

        assertTrue(diagnostic.contains("[REDACTED]"))
        assertFalse(diagnostic.contains("QUJDRA"))
        assertFalse(diagnostic.contains("d0de"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherDomains() {
        ExternalWifiDirectQr.parse(
            "http://example.com/q?sid=test&ip=192.168.49.1"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPublicInternetAddresses() {
        ExternalWifiDirectQr.parse(
            "http://kuaiya.cn/q?sid=test&ip=8.8.8.8"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateParameters() {
        ExternalWifiDirectQr.parse(
            "http://kuaiya.cn/q?sid=one&sid=two&ip=192.168.49.1"
        )
    }
}
