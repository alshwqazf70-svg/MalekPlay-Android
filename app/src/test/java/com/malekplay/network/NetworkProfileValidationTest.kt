package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkProfileValidationTest {
    @Test
    fun acceptsDnsIpv4AndIpv6Hosts() {
        assertEquals("media.example.com", profile(host = "media.example.com").host)
        assertEquals("192.168.1.20", profile(host = "192.168.1.20").host)
        assertEquals("2001:db8::1", profile(host = "2001:db8::1").host)
        assertEquals("[2001:db8::1]", profile(host = "[2001:db8::1]").host)
    }

    @Test
    fun rejectsUrlComponentsAndWhitespaceInHost() {
        listOf(
            "",
            "   ",
            " media.example.com",
            "media.example.com ",
            "https://media.example.com",
            "media.example.com/path",
            "user@media.example.com",
            "media.example.com?token=x",
            "media.example.com#fragment",
            "media example.com",
            "media.example.com\r\nHost:evil"
        ).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                profile(host = host)
            }
        }
    }

    @Test
    fun rejectsInvalidPortAndControlCharactersInMetadata() {
        assertThrows(IllegalArgumentException::class.java) { profile(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { profile(port = 65536) }
        assertThrows(IllegalArgumentException::class.java) { profile(rootPath = "/media\u0000/private") }
        assertThrows(IllegalArgumentException::class.java) { profile(username = "admin\r\n") }
        assertThrows(IllegalArgumentException::class.java) { profile(displayName = "NAS\nserver") }
    }

    private fun profile(
        host: String = "nas.local",
        port: Int = 443,
        rootPath: String = "/",
        username: String = "malek",
        displayName: String = host
    ) = NetworkProfile(
        id = "test_nas",
        protocol = NetworkProtocol.WEBDAV,
        host = host,
        port = port,
        username = username,
        rootPath = rootPath,
        displayName = displayName
    )
}
