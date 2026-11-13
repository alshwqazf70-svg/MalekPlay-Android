package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWifiDirectQrTest {
    @Test
    fun parsesObservedAndroid6QrWithoutTreatingTokensAsCredentials() {
        val details = ExternalWifiDirectQr.parse(
            "http://kuaiya.cn/q?sid=d2B03U00tSjMyN1A&ip=192.168.43.1&t=2&k=4f65"
        )
        assertEquals("d2B03U00tSjMyN1A", details.sessionId)
        assertEquals("192.168.43.1", details.hostAddress)
        assertEquals(null, details.frequencyMhz)
        assertFalse(details.supportsAutomaticJoin)
    }

    @Test
    fun parsesObservedAndroid10QrAndRedactsOpaqueTokens() {
        val details = ExternalWifiDirectQr.parse(
            "http://kuaiya.cn/q?sid=DIRECT-z8-rxAAABBU00tTjk2MFU&freq=5240" +
                "&ip=192.168.49.1&ps=RX0r13HAG1Fa7gaShLTcvw%3D%3D&t=2&k=d0de"
        )
        assertEquals(5240, details.frequencyMhz)
        assertEquals("192.168.49.1", details.hostAddress)
        assertTrue(details.toString().contains("[REDACTED]"))
        assertFalse(details.toString().contains("RX0r13HAG1Fa7gaShLTcvw"))
        assertFalse(details.toString().contains("d0de"))
    }

    @Test
    fun preservesLiteralPlusInOpaqueQueryToken() {
        val details = ExternalWifiDirectQr.parse(
            "https://kuaiya.cn/q?sid=direct&ip=192.168.49.1&ps=AA+BB%3D%3D"
        )
        assertEquals("AA+BB==", details.opaquePasswordToken)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownHosts() {
        ExternalWifiDirectQr.parse("http://example.com/q?sid=test&ip=192.168.49.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPrivateAddresses() {
        ExternalWifiDirectQr.parse("http://kuaiya.cn/q?sid=test&ip=8.8.8.8")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateParameters() {
        ExternalWifiDirectQr.parse(
            "http://kuaiya.cn/q?sid=one&sid=two&ip=192.168.49.1"
        )
    }
}
