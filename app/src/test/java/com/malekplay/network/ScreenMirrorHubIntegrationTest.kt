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
                val configDeadline = System.currentTimeMillis() + 3_000L
                while (fastFrames.isEmpty() && System.currentTimeMillis() < configDeadline) {
                    Thread.sleep(10L)
                }
                // Push 24 video frames at ~100fps; fast viewer must receive all without blocking,
                // while slow viewer backpressure queue stays bounded and never stalls fast viewer.
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
                    Thread.sleep(10L)
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

    @Test fun interleavedAudioAndConfigsNeverBreakVideoMediaSeqOrTriggerFalseDrops() {
        val authorized = setOf(viewerOneId)
        val hub = ScreenMirrorBroadcasterHub(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            streamKey = streamKey,
            authorizedViewerIdsProvider = { authorized },
            allowLoopbackForTest = true
        )

        hub.use { server ->
            val port = server.start(0)
            val received = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.MediaFrame>())
            val done = CountDownLatch(14) // 2 vConfig + 1 aConfig + 2 protected + 5 video + 4 audio
            val tracker = VideoStreamSequenceTracker()
            var falseKeyframes = 0

            val client = ScreenMirrorViewerClient(
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
                    received.add(frame)
                    if (frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR ||
                        frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
                    ) {
                        val isIdr = frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
                        val d = tracker.onVideoFrame(frame.header.mediaSeq, isIdr)
                        if (d.shouldRequestKeyframe) falseKeyframes++
                        assertTrue("Video frame must never be dropped when audio/config/protected frames are interleaved", d.shouldFeedToDecoder)
                    }
                    done.countDown()
                }
            )

            client.use {
                client.start()
                val joinedDeadline = System.currentTimeMillis() + 3_000L
                while (server.activeViewerCount() == 0 && System.currentTimeMillis() < joinedDeadline) {
                    Thread.sleep(20L)
                }

                server.publishVideoConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1), 720, 1280, 0)
                server.publishAudioConfig(byteArrayOf(0x11, 0x90.toByte()))

                // Interleave audio frames, mid-stream VIDEO_CONFIG, and SCREEN_PROTECTED between video frames.
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1), isKeyframe = true, 1_000L, 720, 1280, 0)
                server.publishAudioFrame(byteArrayOf(1, 2, 3), 2_000L)
                server.publishAudioFrame(byteArrayOf(4, 5, 6), 3_000L)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 2), isKeyframe = false, 33_000L, 720, 1280, 0)
                server.publishVideoConfig(byteArrayOf(0, 0, 0, 1, 0x67, 2), 720, 1280, 90)
                server.publishProtectedScreenState(true)
                server.publishProtectedScreenState(false)
                server.publishAudioFrame(byteArrayOf(7, 8, 9), 34_000L)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 3), isKeyframe = false, 66_000L, 720, 1280, 90)
                server.publishAudioFrame(byteArrayOf(10, 11, 12), 67_000L)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 4), isKeyframe = false, 99_000L, 720, 1280, 90)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 5), isKeyframe = false, 132_000L, 720, 1280, 90)

                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(0, falseKeyframes)
                assertEquals(0L, client.status.value.droppedMediaFrames)

                val videoSeqs = received.filter {
                    it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR ||
                        it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
                }.map { it.header.mediaSeq }
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L), videoSeqs)

                val audioSeqs = received.filter {
                    it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.AUDIO_FRAME
                }.map { it.header.mediaSeq }
                assertEquals(listOf(1L, 2L, 3L, 4L), audioSeqs)
            }
        }
    }

    @Test fun videoOnlyStreamWithoutAudioMaintainsContinuousSequence() {
        val authorized = setOf(viewerOneId)
        val hub = ScreenMirrorBroadcasterHub(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            streamKey = streamKey,
            authorizedViewerIdsProvider = { authorized },
            allowLoopbackForTest = true
        )

        hub.use { server ->
            val port = server.start(0)
            val videoSeqs = Collections.synchronizedList(mutableListOf<Long>())
            val done = CountDownLatch(4)
            val client = ScreenMirrorViewerClient(
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
                    if (frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR ||
                        frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA
                    ) {
                        videoSeqs.add(frame.header.mediaSeq)
                        done.countDown()
                    }
                }
            )

            client.use {
                client.start()
                val joinedDeadline = System.currentTimeMillis() + 3_000L
                while (server.activeViewerCount() == 0 && System.currentTimeMillis() < joinedDeadline) {
                    Thread.sleep(20L)
                }

                server.publishVideoConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1), 720, 1280, 0)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1), isKeyframe = true, 1_000L, 720, 1280, 0)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 2), isKeyframe = false, 33_000L, 720, 1280, 0)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 3), isKeyframe = false, 66_000L, 720, 1280, 0)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x41, 4), isKeyframe = false, 99_000L, 720, 1280, 0)

                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1L, 2L, 3L, 4L), videoSeqs)
                assertEquals(0L, client.status.value.audioPacketsReceived)
                assertEquals(0L, client.status.value.droppedMediaFrames)

                // Receiving frames alone must keep phase at CONNECTED_AWAITING_FIRST_FRAME until Surface render confirms.
                assertEquals(
                    ScreenMirrorViewerClient.ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME,
                    client.status.value.phase
                )
                assertEquals(
                    MirrorViewerStage.FRAME_RECEIVED,
                    client.status.value.stage
                )

                client.notifyFrameDecoded(renderedToSurface = true)
                assertEquals(
                    ScreenMirrorViewerClient.ConnectionPhase.CONNECTED_AWAITING_FIRST_FRAME,
                    client.status.value.phase
                )
                assertEquals(MirrorViewerStage.FRAME_DECODED, client.status.value.stage)

                // Only FIRST_FRAME_RENDERED promotes phase to STREAMING.
                client.notifyFirstFrameRendered()
                assertEquals(
                    ScreenMirrorViewerClient.ConnectionPhase.STREAMING,
                    client.status.value.phase
                )
                assertEquals(MirrorViewerStage.FIRST_FRAME_RENDERED, client.status.value.stage)

                // Decoder error transitions immediately to ERROR with clear recovery message.
                client.notifyDecoderFailure("خطأ في مفكك الترميز")
                assertEquals(ScreenMirrorViewerClient.ConnectionPhase.ERROR, client.status.value.phase)
                assertEquals("خطأ في مفكك الترميز", client.status.value.errorMessage)
            }
        }
    }
}
