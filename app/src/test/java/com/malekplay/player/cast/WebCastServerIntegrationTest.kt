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
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WebCastServerIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loopbackProvider: () -> List<Inet4Address> = {
        listOf(InetAddress.getByName("127.0.0.1") as Inet4Address)
    }

    @Test
    fun tokenRotatesAndOldSessionTokenIsRejectedAfterStopAndRestart() {
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
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
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
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
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
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
        val bridge = CastMediaHttpServer(
            context = context,
            upstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
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
            "192.168.1.44",
            "10.0.0.8",
            "172.16.5.9",
            "169.254.10.2",
            "100.64.0.5",
            "fe80::1",
            "fd12:3456:789a::1"
        ).forEach { ip ->
            assertTrue("Expected allowed LAN IP: $ip", LanNetworkSupport.isAllowedLanPeerAddress(InetAddress.getByName(ip)))
        }

        // Production peer check strictly rejects loopback unless test opt-in is passed.
        listOf(
            "127.0.0.1",
            "::1",
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

        assertTrue(
            LanNetworkSupport.isPeerOnActiveLocalLan(
                peer = InetAddress.getByName("127.0.0.1"),
                local = InetAddress.getByName("127.0.0.1"),
                allowLoopback = true
            )
        )
        assertFalse(
            LanNetworkSupport.isPeerOnActiveLocalLan(
                peer = InetAddress.getByName("127.0.0.1"),
                local = InetAddress.getByName("127.0.0.1"),
                allowLoopback = false
            )
        )
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

    @Test
    fun lanNetworkBindingThrowsDirectlyWithoutFallingBackToDefaultNetwork() {
        val defaultCalled = AtomicBoolean(false)
        val url = URL("http://192.168.1.50:8080/dmr.xml")

        val thrown = runCatching {
            LanNetworkSupport.openLanHttpConnectionWithOpeners(
                url = url,
                hasBoundNetwork = true,
                boundOpener = { throw IOException("Wi-Fi socket bind failed") },
                defaultOpener = {
                    defaultCalled.set(true)
                    throw AssertionError("Default network fallback must never be called")
                },
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000
            )
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertFalse("Default network fallback must not be attempted when bound Network fails", defaultCalled.get())
    }

    @Test
    fun mdnsPreservesLinkLocalScopeIdAndBuildsValidQueries() {
        val linkLocalBytes = InetAddress.getByName("fe80::1234").address
        val packet = buildDnsPacket(
            records = listOf(
                buildDnsRecord("roku-tv.local", MdnsDiscoveryClient.TYPE_AAAA, linkLocalBytes)
            )
        )
        val parsed = MdnsDiscoveryClient().parseRecords(packet, fallbackScopeId = "4")
        assertEquals(1, parsed.size)
        val scopedAddr = parsed.first().address!!
        assertTrue("Expected %4 scope id in $scopedAddr", scopedAddr.endsWith("%4"))
        assertEquals("http://[fe80:0:0:0:0:0:0:1234%254]:8060/query/device-info", "http://${LanNetworkSupport.formatUriAuthorityHost(scopedAddr)}:8060/query/device-info")
        assertEquals("[fe80:0:0:0:0:0:0:1234%4]", LanNetworkSupport.formatUrlHost(scopedAddr))

        val query = MdnsDiscoveryClient().buildPtrQueryPacket("_googlecast._tcp.local")
        assertTrue(query.size > 12)
        assertEquals(0, query[2].toInt() and 0x80) // standard query flag QR=0
    }

    @Test
    fun webCastStopForciblyInterruptsLongRunningStreamAndIsIdempotent() {
        val started = CountDownLatch(1)
        val closedByStop = CountDownLatch(1)
        val slowFactory = DataSource.Factory {
            object : BaseDataSource(false) {
                private var offset = 0L
                override fun open(dataSpec: DataSpec): Long {
                    started.countDown()
                    return 50L * 1024L * 1024L
                }
                override fun read(buffer: ByteArray, off: Int, length: Int): Int {
                    Thread.sleep(25)
                    buffer[off] = 'A'.code.toByte()
                    offset += 1
                    return 1
                }
                override fun getUri(): Uri? = Uri.parse("content://slow/1")
                override fun close() {
                    closedByStop.countDown()
                }
            }
        }
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = slowFactory,
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
        try {
            val state = server.start(requirePin = false)
            server.updateQueue(
                listOf(WebCastItem(id = "slow1", title = "Slow", uri = Uri.parse("content://slow/1"), mimeType = "video/mp4"))
            )
            val base = state.primaryUrl!!

            val readFinished = CountDownLatch(1)
            val readerThread = Thread {
                runCatching {
                    val conn = URL("$base/stream/slow1/stream.mp4").openConnection() as HttpURLConnection
                    conn.readTimeout = 5000
                    conn.inputStream.use { it.readBytes() }
                }
                readFinished.countDown()
            }.apply { start() }

            assertTrue("Stream did not start in time", started.await(3, TimeUnit.SECONDS))
            server.stop()
            // Second stop() must be safely idempotent.
            server.stop()

            assertTrue("Active stream was not interrupted on stop()", readFinished.await(3, TimeUnit.SECONDS))
            assertTrue("Upstream DataSource was not closed on stop()", closedByStop.await(2, TimeUnit.SECONDS))

            val rejected = runCatching {
                (URL("$base/api/state").openConnection() as HttpURLConnection).responseCode
            }
            assertTrue("Server socket must refuse new connections after stop()", rejected.isFailure)
            readerThread.join(1000)
        } finally {
            server.close()
        }
    }

    @Test
    fun castMediaHttpServerCloseInterruptsActiveStream() {
        val started = CountDownLatch(1)
        val closedByServer = CountDownLatch(1)
        val slowFactory = DataSource.Factory {
            object : BaseDataSource(false) {
                override fun open(dataSpec: DataSpec): Long {
                    started.countDown()
                    return 50L * 1024L * 1024L
                }
                override fun read(buffer: ByteArray, off: Int, length: Int): Int {
                    Thread.sleep(25)
                    buffer[off] = 'Z'.code.toByte()
                    return 1
                }
                override fun getUri(): Uri? = Uri.parse("content://slow/2")
                override fun close() {
                    closedByServer.countDown()
                }
            }
        }
        val bridge = CastMediaHttpServer(
            context = context,
            upstreamFactory = slowFactory,
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true
        )
        try {
            val streamUri = bridge.urlFor(Uri.parse("content://slow/2"), "video/mp4", "slow.mp4")
            val finished = CountDownLatch(1)
            val worker = Thread {
                runCatching {
                    (URL(streamUri.toString()).openConnection() as HttpURLConnection).inputStream.use { it.readBytes() }
                }
                finished.countDown()
            }.apply { start() }

            assertTrue(started.await(3, TimeUnit.SECONDS))
            bridge.close()
            bridge.close() // idempotent
            assertTrue("Bridge stream not interrupted on close()", finished.await(3, TimeUnit.SECONDS))
            assertTrue("Bridge upstream DataSource not closed", closedByServer.await(2, TimeUnit.SECONDS))
            worker.join(1000)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun productionWebCastAndBridgeRejectLoopbackWithoutTestOptIn() {
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = false
        )
        val bridge = CastMediaHttpServer(
            context = context,
            upstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = false
        )
        try {
            val state = server.start(requirePin = false)
            val serverRejected = runCatching {
                val conn = URL("${state.primaryUrl}/api/state").openConnection() as HttpURLConnection
                conn.responseCode
            }
            assertTrue("Production WebCastServer must drop loopback peer", serverRejected.isFailure)

            val bridgeUrl = bridge.urlFor(Uri.parse("content://a/b.mp4"), "video/mp4", "b.mp4")
            val bridgeRejected = runCatching {
                (URL(bridgeUrl.toString()).openConnection() as HttpURLConnection).responseCode
            }
            assertTrue("Production CastMediaHttpServer must drop loopback peer", bridgeRejected.isFailure)
        } finally {
            server.close()
            bridge.close()
        }
    }

    @Test
    fun pinAttemptMapEnforcesMaxSizeEvictsOldestUsesMonotonicClockAndExpiresCleanly() {
        val fakeClockNs = AtomicLong(1_000_000_000L)
        val fakeHostCounter = AtomicInteger(0)
        val server = WebCastServer(
            context = context,
            initialUpstreamFactory = DefaultDataSource.Factory(context),
            privateIpv4Provider = loopbackProvider,
            allowLoopbackForTest = true,
            nanoTimeProvider = { fakeClockNs.get() },
            peerValidator = { _, _ -> true }
        )
        try {
            val state = server.start(requirePin = true)
            val base = state.primaryUrl!!
            val wrongPin = if (state.pinCode == "000000") "111111" else "000000"

            // Trigger 5 failures on the same host (all on 127.0.0.1) -> locked for 30s
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

            // 6th attempt within 30s is rejected with 429
            val lockedConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            lockedConn.outputStream.use { it.write("""{"pin":"$wrongPin"}""".toByteArray(StandardCharsets.UTF_8)) }
            assertEquals(429, lockedConn.responseCode)
            lockedConn.disconnect()

            // Advance monotonic clock past PIN_ATTEMPT_WINDOW_NS (180s) -> lock state expires cleanly
            fakeClockNs.addAndGet(WebCastServer.PIN_ATTEMPT_WINDOW_NS + 1_000_000_000L)
            val afterExpiryConn = (URL("$base/api/auth").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            afterExpiryConn.outputStream.use { it.write("""{"pin":"${state.pinCode}"}""".toByteArray(StandardCharsets.UTF_8)) }
            assertEquals(200, afterExpiryConn.responseCode)
            afterExpiryConn.disconnect()
            assertEquals(0, server.pinAttemptMapSizeForTest())

            // Verify global rate limit (25 failures per 60s window across distinct hosts) and max map size (64)
            repeat(WebCastServer.MAX_GLOBAL_PIN_FAILURES_PER_WINDOW) { idx ->
                fakeClockNs.addAndGet(1_000_000L)
                assertEquals(403, server.evaluatePinAttempt("192.168.1.${idx + 10}", wrongPin).first)
            }
            // 26th failure from a brand new host within the same 60s window triggers global 429
            assertEquals(429, server.evaluatePinAttempt("192.168.1.240", wrongPin).first)

            // Advance 61s (still < 180s expiry window) across 3 windows to insert > 64 unique hosts and verify eviction
            for (batch in 1..3) {
                fakeClockNs.addAndGet(WebCastServer.GLOBAL_PIN_WINDOW_NS + 1_000_000L)
                repeat(25) { i ->
                    fakeClockNs.addAndGet(1_000_000L)
                    val hostNum = batch * 25 + i + 10
                    server.evaluatePinAttempt("192.168.2.$hostNum", wrongPin)
                }
            }
            assertTrue(
                "Map size must remain capped at MAX_TRACKED_HOSTS=${WebCastServer.MAX_TRACKED_HOSTS}, got ${server.pinAttemptMapSizeForTest()}",
                server.pinAttemptMapSizeForTest() <= WebCastServer.MAX_TRACKED_HOSTS
            )
            assertEquals(WebCastServer.MAX_TRACKED_HOSTS, server.pinAttemptMapSizeForTest())
        } finally {
            server.close()
        }
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
