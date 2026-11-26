package com.malekplay.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DlnaRendererClientIntegrationTest {

    @Test
    fun rokuPauseAndResumeVerifyPlayerStateInsteadOfBlindToggle() = runBlocking {
        val playerState = AtomicReference("pause")
        val recordedRequests = CopyOnWriteArrayList<String>()

        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = startTinyHttpServer(server) { method, path, _, _ ->
            recordedRequests += "$method $path"
            when {
                method == "GET" && path == "/query/media-player" -> {
                    val xml = """<player error="false" state="${playerState.get()}"><position>5000 ms</position><duration>60000 ms</duration></player>"""
                    200 to xml
                }
                method == "POST" && path == "/keypress/Play" -> {
                    playerState.set(if (playerState.get() == "play") "pause" else "play")
                    200 to ""
                }
                else -> 404 to ""
            }
        }

        try {
            val client = DlnaRendererClient(rokuPort = server.localPort)
            val roku = DiscoveredNetworkDevice(
                id = "roku-1",
                name = "Roku TV",
                host = "127.0.0.1",
                kind = NetworkDeviceKind.MEDIA_RENDERER,
                services = setOf("Roku ECP"),
                castProtocols = setOf(TvCastProtocol.ROKU_ECP),
                playbackVerified = true
            )

            // Already paused -> pause() MUST NOT toggle playback back on.
            client.pause(roku)
            assertEquals("pause", playerState.get())
            assertFalse(recordedRequests.contains("POST /keypress/Play"))

            // Paused -> resume() sends Play once and transitions to play.
            client.resume(roku)
            assertEquals("play", playerState.get())
            assertEquals(1, recordedRequests.count { it == "POST /keypress/Play" })

            // Already playing -> resume() MUST NOT toggle back to pause.
            client.resume(roku)
            assertEquals("play", playerState.get())
            assertEquals(1, recordedRequests.count { it == "POST /keypress/Play" })

            // Playing -> pause() sends Play and returns to pause.
            client.pause(roku)
            assertEquals("pause", playerState.get())
            assertEquals(2, recordedRequests.count { it == "POST /keypress/Play" })

            // getPosition reads state, position, and duration from Roku /query/media-player.
            val pos = client.getPosition(roku)
            assertNotNull(pos)
            assertEquals(5000L, pos?.positionMs)
            assertEquals(60000L, pos?.durationMs)
            assertEquals("PAUSED_PLAYBACK", pos?.transportState)
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun rokuReportsUnsupportedCapabilitiesAndThrowsRatherThanSilentSuccess() {
        val client = DlnaRendererClient(rokuPort = 8060)
        val roku = DiscoveredNetworkDevice(
            id = "roku-2",
            name = "Roku Stick",
            host = "127.0.0.1",
            kind = NetworkDeviceKind.MEDIA_RENDERER,
            services = setOf("Roku ECP"),
            castProtocols = setOf(TvCastProtocol.ROKU_ECP),
            playbackVerified = true
        )

        val caps = client.capabilitiesFor(roku)
        assertFalse(caps.supportsSeek)
        assertFalse(caps.supportsAbsoluteVolume)
        assertFalse(caps.supportsExplicitMute)
        assertTrue(caps.supportsPositionReporting)

        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { client.seek(roku, 12_000L) }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { client.setVolume(roku, 50) }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { client.setMute(roku, true) }
        }
    }

    @Test
    fun dlnaExplicitMuteGetMuteAndSoapFaultAreHandledCorrectly() = runBlocking {
        val mutedState = AtomicReference(false)
        val returnSoapFaultOnNext = AtomicReference(false)

        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val worker = startTinyHttpServer(server) { method, path, headers, body ->
            when {
                method == "GET" && path == "/dmr.xml" -> {
                    val xml = """
                        <root xmlns="urn:schemas-upnp-org:device-1-0">
                          <device>
                            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                            <friendlyName>Living Room TV</friendlyName>
                            <serviceList>
                              <service>
                                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                                <controlURL>/upnp/control/AVTransport1</controlURL>
                              </service>
                              <service>
                                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                                <controlURL>/upnp/control/RenderingControl1</controlURL>
                              </service>
                            </serviceList>
                          </device>
                        </root>
                    """.trimIndent()
                    200 to xml
                }
                method == "POST" && path == "/upnp/control/RenderingControl1" -> {
                    val action = headers["soapaction"].orEmpty()
                    if (action.contains("SetMute")) {
                        mutedState.set(body.contains("<DesiredMute>1</DesiredMute>"))
                        200 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:SetMuteResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"/></s:Body></s:Envelope>"""
                    } else if (action.contains("GetMute")) {
                        val valStr = if (mutedState.get()) "1" else "0"
                        200 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetMuteResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><CurrentMute>$valStr</CurrentMute></u:GetMuteResponse></s:Body></s:Envelope>"""
                    } else 404 to ""
                }
                method == "POST" && path == "/upnp/control/AVTransport1" -> {
                    if (returnSoapFaultOnNext.get()) {
                        200 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultstring>UPnPError</faultstring><detail><UPnPError><errorDescription>Action Failed</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
                    } else {
                        200 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:PauseResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body></s:Envelope>"""
                    }
                }
                else -> 404 to ""
            }
        }

        try {
            val client = DlnaRendererClient()
            val tv = DiscoveredNetworkDevice(
                id = "dlna-tv",
                name = "Living Room TV",
                host = "127.0.0.1",
                kind = NetworkDeviceKind.MEDIA_RENDERER,
                services = setOf("DLNA MediaRenderer"),
                descriptionUrl = "http://127.0.0.1:$port/dmr.xml",
                castProtocols = setOf(TvCastProtocol.DLNA),
                playbackVerified = true
            )

            client.setMute(tv, true)
            assertEquals(true, client.getMute(tv))
            client.setMute(tv, false)
            assertEquals(false, client.getMute(tv))

            returnSoapFaultOnNext.set(true)
            val err = runCatching { client.pause(tv) }.exceptionOrNull()
            assertTrue(err is IOException)
            assertTrue(err?.message?.contains("Action Failed") == true)
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun airplayOnlyDeviceIsRejectedWithoutSendingRokuCommands() {
        val rokuCalled = AtomicReference(false)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = startTinyHttpServer(server) { _, _, _, _ ->
            rokuCalled.set(true)
            200 to ""
        }
        try {
            val client = DlnaRendererClient(rokuPort = server.localPort)
            val airPlayOnly = DiscoveredNetworkDevice(
                id = "airplay-only",
                name = "Bedroom Apple TV",
                host = "127.0.0.1",
                kind = NetworkDeviceKind.MEDIA_RENDERER,
                services = setOf("mDNS/DNS-SD", "_airplay._tcp"),
                castProtocols = setOf(TvCastProtocol.AIRPLAY),
                playbackVerified = false
            )
            assertThrows(UnsupportedOperationException::class.java) {
                runBlocking {
                    client.play(airPlayOnly, "http://127.0.0.1:8080/video.mp4", "Demo", "video/mp4")
                }
            }
            assertFalse(rokuCalled.get())
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    private fun startTinyHttpServer(
        server: ServerSocket,
        handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>
    ): Thread = thread(isDaemon = true) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) {
                client.use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val reqLine = readLine(input) ?: return@use
                    val parts = reqLine.split(' ')
                    if (parts.size < 2) return@use
                    val method = parts[0].uppercase()
                    val path = parts[1]
                    val headers = linkedMapOf<String, String>()
                    var contentLength = 0
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            val name = line.substring(0, idx).trim().lowercase()
                            val value = line.substring(idx + 1).trim()
                            headers[name] = value
                            if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                    val bodyBytes = ByteArray(contentLength.coerceIn(0, 65536))
                    var readTotal = 0
                    while (readTotal < bodyBytes.size) {
                        val n = input.read(bodyBytes, readTotal, bodyBytes.size - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    val (status, respBody) = handler(method, path, headers, String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8))
                    val bytes = respBody.toByteArray(StandardCharsets.UTF_8)
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 $status OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    if (bytes.isNotEmpty()) out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else String(buf.toByteArray(), StandardCharsets.US_ASCII)
            if (b == 10) break
            if (b != 13) buf.add(b.toByte())
        }
        return String(buf.toByteArray(), StandardCharsets.US_ASCII)
    }
}
