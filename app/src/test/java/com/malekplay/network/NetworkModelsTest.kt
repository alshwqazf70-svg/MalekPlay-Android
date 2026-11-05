package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkModelsTest {

    @Test
    fun credentialValuesRejectTheReservedSeparator() {
        validateNetworkCredentials("viewer", "secret")
        assertThrows(IllegalArgumentException::class.java) {
            validateNetworkCredentials("view\u0000er", "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateNetworkCredentials("viewer", "sec\u0000ret")
        }
    }

    @Test
    fun normalizesSchemesAndIpv6Brackets() {
        assertEquals("media.example.test", normalizeNetworkHost(" https://media.example.test/ "))
        assertEquals("2001:db8::1", normalizeNetworkHost("[2001:db8::1]"))
    }

    @Test
    fun rejectsHostPathsCredentialsAndEmbeddedPorts() {
        listOf(
            "media.example.test/videos",
            "user@media.example.test",
            "media.example.test:445",
            "media server"
        ).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeNetworkHost(host)
            }
        }
    }

    @Test
    fun formatsIpv6HttpsEndpointsWithBrackets() {
        assertEquals(
            "https://[2001:db8::1]:8443",
            httpsNetworkEndpoint("2001:db8::1", 8443)
        )
        assertEquals(
            "https://media.example.test:443",
            httpsNetworkEndpoint("media.example.test", 443)
        )
    }
}
