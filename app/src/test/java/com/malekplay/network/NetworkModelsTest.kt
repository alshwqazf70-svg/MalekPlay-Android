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
    fun validatesAndDecodesWebDavPathSegmentsSafely() {
        assertEquals(
            listOf("Movies", "Film 1.mkv", "a+b"),
            validateWebDavPath("/Movies/Film%201.mkv/a+b")
        )
        assertEquals(emptyList<String>(), validateWebDavPath("/"))
    }

    @Test
    fun rejectsWebDavTraversalAndEncodedSeparators() {
        listOf(
            "../private",
            "/safe/%2e%2e/private",
            "/safe/%2Fprivate",
            "/safe/%5cprivate",
            "/safe/%252e%252e/private",
            "/safe/file?download=1",
            "/safe/file#fragment"
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                validateWebDavPath(path)
            }
        }
    }

    @Test
    fun rejectsOversizedWebDavPathsAndSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            validateWebDavPath("/" + "a".repeat(256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateWebDavPath("/" + (1..129).joinToString("/") { "x" })
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
