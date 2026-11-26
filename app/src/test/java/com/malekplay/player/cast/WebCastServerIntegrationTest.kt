@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.player.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.LanNetworkSupport
import com.malekplay.network.MdnsDiscoveryClient
import com.malekplay.network.NetworkDeviceKind
import com.malekplay.network.TvCastProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WebCastServerIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loopbackProvider: () -> List<Inet4Address> = {
        listOf(InetAddress.getByName("127.0.0.1") as Inet4Address)
    }

    @Test
    fun tokenRotatesAndOldSessionTokenIsRejectedAfterStopAndRestart() {
        val server = WebCastServer(context, DefaultDataSource.Factory(context), loopbackProvider)
        try {
            val firstState = server.start(requirePin = true)
            val firstQuickUrl = firstState.quickConnectUrl!!
            val firstToken = Uri.parse(firstQuickUrl).getQueryParameter("k")!!

            val firstConn = (URL("${firstState.primaryUrl}/api/state?k=$firstToken").openConnection() as HttpURLConnection)
            assertEquals(200, firstConn.responseCode)
            firstConn.disconnect()

            server.stop()
            val secondState = server.start(requirePin = true)
            val secondToken = Uri.parse(secondState.quickConnectUrl!!).getQueryParameter("k")!!
            assertNotEquals(firstToken, secondToken)

            val staleConn = (URL("${secondState.primaryUrl}/api/state?k=$firstToken").openConnection() as HttpURLConnection)
            assertEquals(401, staleConn.responseCode)
            staleConn.disconnect()

            val freshConn = (URL("${secondState.primaryUrl}/api/state?k=$secondToken").openConnection() as HttpURLConnection)
            assertEquals(200, freshConn.responseCode)
            freshConn.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun pinAuthRateLimitsRepeatedWrongGuesses() {
        val server = WebCastServer(context, DefaultDataSource.Factory(context), loopbackProvider)
        try {
            val state = server.start(requirePin = true)
            val base = state.primaryUrl!!
            val wrongPin = if (state.pinCode == "000000") "111111" else "000000"

            repeat(5) {
                val conn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("""{"pin":"$wrongPin"}""".toByteArray(StandardCharsets.UTF_8)) }
                assertEquals(403, conn.responseCode)
                conn.disconnect()
            }

            val lockedConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            lockedConn.outputStream.use { it.write("""{"pin":"$wrongPin"}""".toByteArray(StandardCharsets.UTF_8)) }
            assertEquals(429, lockedConn.responseCode)
            lockedConn.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun servesHeadAndByteRangesAndAbortsOnTruncatedUpstream() {
        val tempFile = File.createTempFile("malek_webcast_test", ".mp4", context.cacheDir).apply {
            writeBytes("0123456789ABCDEF".toByteArray(StandardCharsets.US_ASCII))
            deleteOnExit()
        }
        val server = WebCastServer(context, DefaultDataSource.Factory(context), loopbackProvider)
        try {
            val state = server.start(requirePin = false)
            server.updateQueue(
                listOf(
                    WebCastItem(
                        id = "vid1",
                        title = "Demo",
                        uri = Uri.fromFile(tempFile),
                        mimeType = "video/mp4"
                    )
                )
            )
            val base = state.primaryUrl!!

            val headConn = (URL("$base/stream/vid1/stream.mp4").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
            }
            assertEquals(200, headConn.responseCode)
            assertEquals("16", headConn.getHeaderField("Content-Length"))
            assertEquals("bytes", headConn.getHeaderField("Accept-Ranges"))
            headConn.disconnect()

            val rangeConn = (URL("$base/stream/vid1/stream.mp4").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=4-9")
            }
            assertEquals(206, rangeConn.responseCode)
            assertEquals("bytes 4-9/16", rangeConn.getHeaderField("Content-Range"))
            assertEquals("456789", rangeConn.inputStream.bufferedReader().readText())
            rangeConn.disconnect()

            val invalidRangeConn = (URL("$base/stream/vid1/stream.mp4").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=99-120")
            }
            assertEquals(416, invalidRangeConn.responseCode)
            assertEquals("bytes */16", invalidRangeConn.getHeaderField("Content-Range"))
            invalidRangeConn.disconnect()

            // Verify truncated upstream throws IOException rather than silently succeeding.
            val truncatedFactory = DataSource.Factory {
                object : BaseDataSource(false) {
                    private var emitted = false
                    override fun open(dataSpec: DataSpec): Long = 100L
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (emitted) return C.RESULT_END_OF_INPUT
                        emitted = true
                        val chunk = "abc".toByteArray(StandardCharsets.US_ASCII)
                        chunk.copyInto(buffer, offset)
                        return chunk.size
                    }
                    override fun getUri(): Uri? = Uri.parse("content://truncated/1")
                    override fun close() {}
                }
            }
            server.updateUpstreamFactory(truncatedFactory)
            server.updateQueue(
                listOf(
                    WebCastItem(
                        id = "trunc",
                        title = "Broken",
                        uri = Uri.parse("content://truncated/1"),
                        mimeType = "video/mp4"
                    )
                )
            )
            val truncConn = URL("$base/stream/trunc/stream.mp4").openConnection() as HttpURLConnection
            assertEquals(200, truncConn.responseCode)
            assertEquals("100", truncConn.getHeaderField("Content-Length"))
            val bodyRead = truncConn.inputStream.readBytes()
            assertTrue("Expected incomplete stream (< 100 bytes), got ${bodyRead.size}", bodyRead.size < 100)
            truncConn.disconnect()
        } finally {
            server.close()
            tempFile.delete()
        }
    }

    @Test
    fun castMediaHttpServerServesRangesAndInvalidatesUrlsOnClose() {
        val tempFile = File.createTempFile("malek_bridge_test", ".mp4", context.cacheDir).apply {
            writeBytes("ABCDEFGHIJ".toByteArray(StandardCharsets.US_ASCII))
            deleteOnExit()
        }
        val bridge = CastMediaHttpServer(context, DefaultDataSource.Factory(context), loopbackProvider)
        try {
            val streamUri = bridge.urlFor(Uri.fromFile(tempFile), "video/mp4", "sample.mp4")
            val rangeConn = (URL(streamUri.toString()).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=2-5")
            }
            assertEquals(206, rangeConn.responseCode)
            assertEquals("CDEF", rangeConn.inputStream.bufferedReader().readText())
            rangeConn.disconnect()

            bridge.close()
            val afterClose = runCatching {
                (URL(streamUri.toString()).openConnection() as HttpURLConnection).responseCode
            }
            assertTrue(afterClose.isFailure)
        } finally {
            bridge.close()
            tempFile.delete()
        }
    }

    @Test
    fun lanNetworkSupportValidatesIpv4AndIpv6Independently() {
        listOf(
            "127.0.0.1",
            "192.168.1.44",
            "10.0.0.8",
            "172.16.5.9",
            "169.254.10.2",
            "100.64.0.5",
            "::1",
            "fe80::1",
            "fd12:3456:789a::1"
        ).forEach { ip ->
            assertTrue("Expected allowed LAN IP: $ip", LanNetworkSupport.isAllowedLanPeerAddress(InetAddress.getByName(ip)))
        }

        listOf(
            "8.8.8.8",
            "1.1.1.1",
            "2001:4860:4860::8888",
            "2606:4700:4700::1111",
            "224.0.0.251",
            "ff02::fb",
            "0.0.0.0"
        ).forEach { ip ->
            assertFalse("Expected rejected IP: $ip", LanNetworkSupport.isAllowedLanPeerAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun mdnsParserExtractsPrivateIpv6AaaaAndRejectsPublicIps() {
        val packet = buildDnsPacket(
            records = listOf(
                buildDnsRecord("tv.local", MdnsDiscoveryClient.TYPE_AAAA, InetAddress.getByName("fd00::55").address),
                buildDnsRecord("evil.local", MdnsDiscoveryClient.TYPE_AAAA, InetAddress.getByName("2001:4860:4860::8888").address),
                buildDnsRecord("tv4.local", MdnsDiscoveryClient.TYPE_A, InetAddress.getByName("192.168.1.77").address)
            )
        )
        val parsed = MdnsDiscoveryClient().parseRecords(packet)
        assertEquals("fd00:0:0:0:0:0:0:55", parsed[0].address)
        assertEquals(null, parsed[1].address)
        assertEquals("192.168.1.77", parsed[2].address)
    }

    @Test
    fun discoveredDeviceCapabilitiesRequireVerificationBeforeDirectPlayback() {
        val unverifiedAirPlay = DiscoveredNetworkDevice(
            id = "airplay-1",
            name = "Apple TV",
            host = "192.168.1.30",
            kind = NetworkDeviceKind.MEDIA_RENDERER,
            services = setOf("mDNS/DNS-SD", "_airplay._tcp"),
            castProtocols = setOf(TvCastProtocol.AIRPLAY),
            playbackVerified = false
        )
        assertFalse(unverifiedAirPlay.supportsDirectPlayback())
        assertTrue(unverifiedAirPlay.isDiscoveredWithoutDirectPlayback())

        val verifiedDlna = DiscoveredNetworkDevice(
            id = "dlna-1",
            name = "Samsung TV",
            host = "192.168.1.40",
            kind = NetworkDeviceKind.MEDIA_RENDERER,
            services = setOf("SSDP/UPnP", "DLNA MediaRenderer"),
            descriptionUrl = "http://192.168.1.40:9197/dmr",
            castProtocols = setOf(TvCastProtocol.DLNA),
            playbackVerified = true
        )
        assertTrue(verifiedDlna.supportsDirectPlayback())
    }

    private fun buildDnsPacket(records: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: ID=0, Flags=0x8400 (response), QD=0, AN=records.size, NS=0, AR=0
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, records.size.toByte(), 0, 0, 0, 0))
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun buildDnsRecord(name: String, type: Int, rdata: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        name.split('.').forEach { label ->
            val b = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        // Type, Class IN (1), TTL=120, RDLength, RData
        out.write(byteArrayOf((type shr 8).toByte(), (type and 0xFF).toByte()))
        out.write(byteArrayOf(0, 1))
        out.write(byteArrayOf(0, 0, 0, 120))
        out.write(byteArrayOf((rdata.size shr 8).toByte(), (rdata.size and 0xFF).toByte()))
        out.write(rdata)
        return out.toByteArray()
    }
}
