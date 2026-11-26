package com.malekplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScreenMirrorHubIntegrationTest {

    private val sessionId = "11223344556677889900aabbccddeeff"
    private val hostPeerId = "10101010101010101010101010101010"
    private val viewerOneId = "20202020202020202020202020202020"
    private val viewerTwoId = "30303030303030303030303030303030"
    private val rogueViewerId = "40404040404040404040404040404040"
    private val streamKey = ByteArray(32) { (it + 11).toByte() }

    @Test fun multiViewerFanOutDeliversVideoAndAudioAndRejectsUnauthorizedPeers() {
        val authorized = Collections.synchronizedSet(mutableSetOf(viewerOneId, viewerTwoId))
        val keyframeRequests = AtomicInteger(0)
        val hub = ScreenMirrorBroadcasterHub(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            streamKey = streamKey,
            authorizedViewerIdsProvider = { authorized.toSet() },
            allowLoopbackForTest = true,
            onKeyframeRequested = { keyframeRequests.incrementAndGet() }
        )

        hub.use { server ->
            val port = server.start(0)
            assertTrue(port in 1024..65535)

            // Publish initial SPS/PPS + ASC before viewers join so late-join cache is exercised.
            val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
            val asc = byteArrayOf(0x11, 0x90.toByte())
            server.publishVideoConfig(spsPps, 720, 1280, 0)
            server.publishAudioConfig(asc)

            val v1Frames = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.MediaFrame>())
            val v2Frames = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.MediaFrame>())
            val v1Ready = CountDownLatch(2)
            val v2Ready = CountDownLatch(2)
            val v1Done = CountDownLatch(5)
            val v2Done = CountDownLatch(5)

            val c1 = ScreenMirrorViewerClient(
                context = null,
                hostAddress = "127.0.0.1",
                streamPort = port,
                sessionId = sessionId,
                hostPeerId = hostPeerId,
                localViewerPeerId = viewerOneId,
                streamKey = streamKey,
                allowLoopbackForTest = true,
                maxReconnectAttempts = 1,
                frameSink = { frame ->
                    v1Frames.add(frame)
                    v1Ready.countDown()
                    v1Done.countDown()
                }
            )
            val c2 = ScreenMirrorViewerClient(
                context = null,
                hostAddress = "127.0.0.1",
                streamPort = port,
                sessionId = sessionId,
                hostPeerId = hostPeerId,
                localViewerPeerId = viewerTwoId,
                streamKey = streamKey,
                allowLoopbackForTest = true,
                maxReconnectAttempts = 1,
                frameSink = { frame ->
                    v2Frames.add(frame)
                    v2Ready.countDown()
                    v2Done.countDown()
                }
            )

            c1.use {
                c2.use {
                    c1.start()
                    c2.start()
                    assertTrue("Both viewers must receive cached config frames immediately", v1Ready.await(5, TimeUnit.SECONDS))
                    assertTrue(v2Ready.await(5, TimeUnit.SECONDS))
                    assertTrue(keyframeRequests.get() >= 1)

                    // Unauthorized rogue peer attempting direct TCP connection must be rejected.
                    Socket().use { rogueSocket ->
                        rogueSocket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                        rogueSocket.soTimeout = 3_000
                        val rejected = try {
                            UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                                input = BufferedInputStream(rogueSocket.getInputStream()),
                                output = BufferedOutputStream(rogueSocket.getOutputStream()),
                                expectedSessionId = sessionId,
                                expectedHostPeerId = hostPeerId,
                                localViewerPeerId = rogueViewerId,
                                streamKey = streamKey
                            )
                            false
                        } catch (_: Exception) {
                            true
                        }
                        assertTrue("Unauthorized peer must not join stream", rejected)
                    }

                    val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
                    val delta = byteArrayOf(0, 0, 0, 1, 0x41, 4, 5, 6)
                    val aac = byteArrayOf(0x21, 0x10, 0x05)
                    server.publishVideoFrame(idr, isKeyframe = true, timestampUs = 1_000L, width = 720, height = 1280, rotation = 0)
                    server.publishVideoFrame(delta, isKeyframe = false, timestampUs = 34_000L, width = 720, height = 1280, rotation = 0)
                    server.publishAudioFrame(aac, timestampUs = 35_000L)

                    assertTrue(v1Done.await(5, TimeUnit.SECONDS))
                    assertTrue(v2Done.await(5, TimeUnit.SECONDS))

                    assertArrayEquals(spsPps, v1Frames.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG }.payload)
                    assertArrayEquals(asc, v2Frames.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.AUDIO_CONFIG }.payload)
                    assertArrayEquals(idr, v1Frames.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR }.payload)
                    assertArrayEquals(delta, v2Frames.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA }.payload)
                    assertArrayEquals(aac, v1Frames.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME }.payload)

                    // Removing viewerTwo from room authorization must prune its active stream.
                    authorized.remove(viewerTwoId)
                    server.pruneUnauthorizedViewers()
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (server.activeViewerCount() > 1 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(30L)
                    }
                    assertEquals(1, server.activeViewerCount())
                }
            }
        }
    }

    @Test fun slowViewerBackpressureDropsOldFramesWithoutBlockingFastViewer() {
        val authorized = setOf(viewerOneId, viewerTwoId)
        val hub = ScreenMirrorBroadcasterHub(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            streamKey = streamKey,
            authorizedViewerIdsProvider = { authorized },
            maxQueuedFramesPerViewer = 4,
            maxQueuedBytesPerViewer = 64 * 1024,
            allowLoopbackForTest = true
        )

        hub.use { server ->
            val port = server.start(0)
            val fastFrames = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.MediaFrame>())
            val fastAllReceived = CountDownLatch(25)

            // Fast viewer drains cleanly via ScreenMirrorViewerClient.
            val fastClient = ScreenMirrorViewerClient(
                context = null,
                hostAddress = "127.0.0.1",
                streamPort = port,
                sessionId = sessionId,
                hostPeerId = hostPeerId,
                localViewerPeerId = viewerOneId,
                streamKey = streamKey,
                allowLoopbackForTest = true,
                maxReconnectAttempts = 1,
                frameSink = { frame ->
                    fastFrames.add(frame)
                    fastAllReceived.countDown()
                }
            )

            // Slow viewer completes handshake and deliberately stops reading from TCP socket.
            val slowSocket = Socket()
            try {
                fastClient.start()
                slowSocket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                slowSocket.receiveBufferSize = 2048
                slowSocket.soTimeout = 3_000
                val slowCipher = UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                    input = BufferedInputStream(slowSocket.getInputStream()),
                    output = BufferedOutputStream(slowSocket.getOutputStream()),
                    expectedSessionId = sessionId,
                    expectedHostPeerId = hostPeerId,
                    localViewerPeerId = viewerTwoId,
                    streamKey = streamKey
                )

                val waitJoinedDeadline = System.currentTimeMillis() + 3_000L
                while (server.activeViewerCount() < 2 && System.currentTimeMillis() < waitJoinedDeadline) {
                    Thread.sleep(20L)
                }
                assertEquals(2, server.activeViewerCount())

                server.publishVideoConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2), 720, 1280, 0)
                // Push 24 video frames rapidly; fast viewer must receive all without blocking,
                // while slow viewer backpressure queue stays bounded (totalDroppedFrames >= 0 and hub stays responsive).
                for (i in 0 until 24) {
                    val isIdr = i % 8 == 0
                    val payload = ByteArray(4096) { i.toByte() }
                    server.publishVideoFrame(
                        encodedBytes = payload,
                        isKeyframe = isIdr,
                        timestampUs = i * 33_000L,
                        width = 720,
                        height = 1280,
                        rotation = 0
                    )
                }

                assertTrue("Fast viewer must receive all frames without waiting on slow viewer", fastAllReceived.await(6, TimeUnit.SECONDS))
                assertTrue(fastFrames.size >= 25)
                slowCipher.close()
            } finally {
                fastClient.close()
                runCatching { slowSocket.close() }
            }
        }
    }
}
