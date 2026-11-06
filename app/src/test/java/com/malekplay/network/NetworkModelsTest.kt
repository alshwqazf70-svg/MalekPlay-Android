package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test

class NetworkModelsTest {

    @Test
    fun classifiesCommonMdnsServicesWithoutClaimingEveryDeviceIsAFileServer() {
        assertEquals(
            NetworkDeviceKind.MEDIA_RENDERER,
            classifyMdnsService("_googlecast._tcp.local")
        )
        assertEquals(
            NetworkDeviceKind.MEDIA_RENDERER,
            classifyMdnsService("_airplay._tcp.local")
        )
        assertEquals(
            NetworkDeviceKind.WINDOWS_COMPUTER,
            classifyMdnsService("_smb._tcp.local")
        )
        assertEquals(
            NetworkDeviceKind.NETWORK_DEVICE,
            classifyMdnsService("_companion-link._tcp.local")
        )
    }

    @Test
    fun dlnaHostMustBePrivateOrLinkLocal() {
        assertEquals(true, isPrivateLanHost("192.168.1.20"))
        assertEquals(true, isPrivateLanHost("10.0.0.12"))
        assertEquals(true, isPrivateLanHost("172.16.0.8"))
        assertEquals(false, isPrivateLanHost("8.8.8.8"))
    }

    @Test
    fun dlnaUrlsMustRemainOnTheDiscoveredDeviceHost() {
        assertEquals(
            "192.168.1.20",
            validateDlnaDeviceUrl("http://192.168.1.20:8200/media/movie.mp4", "192.168.1.20").host
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("http://192.168.1.99:8200/media/movie.mp4", "192.168.1.20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("file:///sdcard/movie.mp4", "192.168.1.20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("http://user@192.168.1.20/movie.mp4", "192.168.1.20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("http://192.168.1.20/movie.mp4#fragment", "192.168.1.20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("http://192.168.1.20:70000/movie.mp4", "192.168.1.20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDlnaDeviceUrl("http://8.8.8.8/movie.mp4", "8.8.8.8")
        }
    }

    @Test
    fun discoveredServiceEndpointsMustMatchTheRespondingLanHost() {
        assertEquals(true, isSameDiscoveryHost("192.168.1.20", "192.168.1.20"))
        assertEquals(false, isSameDiscoveryHost("203.0.113.8", "192.168.1.20"))
        assertEquals(false, isSameDiscoveryHost(null, "192.168.1.20"))
    }

    @Test
    fun classifiesUpnpMediaServersAndRenderersFromAdvertisedServiceTypes() {
        assertEquals(
            NetworkDeviceKind.MEDIA_SERVER,
            classifySsdpDevice("urn:schemas-upnp-org:device:MediaServer:1", "")
        )
        assertEquals(
            NetworkDeviceKind.MEDIA_RENDERER,
            classifySsdpDevice("urn:schemas-upnp-org:device:MediaRenderer:1", "")
        )
        assertEquals(
            NetworkDeviceKind.NETWORK_DEVICE,
            classifySsdpDevice("upnp:rootdevice", "uuid:device-123")
        )
    }

    @Test
    fun identifiesWindowsComputersOnlyFromExplicitDiscoveryHints() {
        assertEquals(
            NetworkDeviceKind.WINDOWS_COMPUTER,
            classifyWsDiscoveryDevice("pub:Computer", "")
        )
        assertEquals(
            NetworkDeviceKind.WINDOWS_COMPUTER,
            classifyWsDiscoveryDevice("", "computer")
        )
        assertEquals(
            NetworkDeviceKind.NETWORK_DEVICE,
            classifyWsDiscoveryDevice("wsdp:Device", "network-device")
        )
    }


    @Test
    fun knownHostsImportStopsBeforeWritingBeyondConfiguredLimit() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyKnownHostsBounded(ByteArrayInputStream(ByteArray(11)), output, maxBytes = 10L)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun knownHostsImportCopiesFilesWithinLimit() {
        val source = "trusted-host-key".toByteArray()
        val output = ByteArrayOutputStream()
        assertEquals(source.size.toLong(), copyKnownHostsBounded(
            ByteArrayInputStream(source), output, maxBytes = source.size.toLong()
        ))
        assertEquals(source.toList(), output.toByteArray().toList())
    }

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
    fun canonicalizesEncodedWebDavHrefPathsBeforeBoundaryChecks() {
        assertEquals("/library/Film 1.mkv", canonicalWebDavHrefPath("/library/Film%201.mkv"))
        assertEquals(null, canonicalWebDavHrefPath("/library/%2e%2e/private"))
        assertEquals(null, canonicalWebDavHrefPath("/library/%2Fprivate"))
        assertEquals(null, canonicalWebDavHrefPath("/library/%252e%252e/private"))
    }

    @Test
    fun normalizesSafeAbsoluteSftpPaths() {
        assertEquals("/media/videos", normalizeSftpRemotePath("//media/videos/"))
        assertEquals("/", normalizeSftpRemotePath("/"))
    }

    @Test
    fun rejectsSftpPathTraversalAndUnsafeComponents() {
        listOf(
            "relative/path",
            "/media/../private",
            "/media/./private",
            "/media/bad\\name",
            "/" + "x".repeat(256)
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeSftpRemotePath(path)
            }
        }
    }

    @Test
    fun acceptsOnlySingleSafeRemoteEntryNames() {
        assertEquals(true, isSafeNetworkEntryName("episode-01.mkv"))
        listOf("", ".", "..", "folder/file", "folder\\file", "bad\u0000name")
            .forEach { name ->
                assertEquals(false, isSafeNetworkEntryName(name))
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
