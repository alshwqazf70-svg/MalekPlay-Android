package com.malekplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            }
        }
    }

    @Test fun lateJoiningViewerAlwaysReceivesVideoConfigBeforeFirstVideoFrame() {
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
            // Publish the codec config BEFORE the viewer connects: this exercises the
            // late-join cache that lets a viewer decode the very first frame it receives.
            val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
            server.publishVideoConfig(spsPps, 720, 1280, 0)

            val received = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.MediaFrame>())
            val configSeen = AtomicBoolean(false)
            val videoBeforeConfig = AtomicBoolean(false)
            val videoReady = CountDownLatch(1)

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
                    when (frame.header.frameType) {
                        UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG -> configSeen.set(true)
                        UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                        UltraShareScreenMirrorProtocol.FrameType.VIDEO_DELTA -> {
                            if (!configSeen.get()) videoBeforeConfig.set(true)
                            videoReady.countDown()
                        }
                        else -> Unit
                    }
                }
            )

            client.use {
                client.start()
                // Let the viewer receive the cached config before the host pushes video.
                Thread.sleep(500L)
                server.publishVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), isKeyframe = true, 1_000L, 720, 1280, 0)

                assertTrue("Viewer must receive at least one video frame", videoReady.await(5, TimeUnit.SECONDS))
                assertTrue("Viewer must receive VIDEO_CONFIG before any video frame", configSeen.get())
                assertFalse("A video frame must never arrive before its codec config", videoBeforeConfig.get())
                assertEquals(
                    spsPps.toList(),
                    received.first { it.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG }.payload.toList()
                )
            }
        }
    }

    @Test
    fun lateJoiningViewerImmediatelyReceivesConfigThenCachedKeyframeAndEntersStreamingOnlyAfterRender() {
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
            val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
            val keyframePayload = byteArrayOf(0, 0, 0, 1, 0x65, 9, 8, 7, 6)
            // Host publishes both VIDEO_CONFIG and the initial IDR BEFORE the viewer connects
            // (simulating a static Android 6/10 screen where no new frame is emitted right after join).
            server.publishVideoConfig(spsPps, 720, 1280, 0)
            server.publishVideoFrame(
                encodedBytes = keyframePayload,
                isKeyframe = true,
                timestampUs = 42_000L,
                width = 720,
                height = 1280,
                rotation = 0
            )

            val receivedTypes = Collections.synchronizedList(mutableListOf<UltraShareScreenMirrorProtocol.FrameType>())
            val idrLatch = CountDownLatch(1)

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
                    receivedTypes.add(frame.header.frameType)
                    if (frame.header.frameType == UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR) {
                        idrLatch.countDown()
                    }
                }
            )

            client.use {
                client.start()
                assertTrue("Late-joining viewer must receive cached VIDEO_IDR immediately without waiting for new screen movement", idrLatch.await(5, TimeUnit.SECONDS))
                assertEquals(
                    listOf(
                        UltraShareScreenMirrorProtocol.FrameType.VIDEO_CONFIG,
                        UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR
                    ),
                    receivedTypes.take(2)
                )

                // Before Surface renders the decoded frame, viewer must NOT falsely claim STREAMING:
                val beforeRender = client.status.value
                assertFalse("Viewer must not report firstFrameRendered before Surface render callback", beforeRender.firstFrameRendered)
                assertEquals(
                    ScreenMirrorViewerClient.ConnectionPhase.FRAME_RECEIVED_RENDERING,
                    beforeRender.phase
                )

                // Once the decoder renders onto Surface, phase transitions to STREAMING:
                client.notifyFirstFrameRendered()
                val afterRender = client.status.value
                assertTrue(afterRender.firstFrameRendered)
                assertEquals(
                    ScreenMirrorViewerClient.ConnectionPhase.STREAMING,
                    afterRender.phase
                )

                // Detaching Surface resets firstFrameRendered cleanly:
                client.notifySurfaceDetached()
                assertFalse(client.status.value.firstFrameRendered)
            }
        }
    }

    @Test
    fun mirrorAudioModePolicyAndMutualExclusionCoverAndroid6To15() {
        // Android 6.0 (API 23) & Android 9 (API 28): NONE + MICROPHONE only; SYSTEM_AUDIO unavailable.
        for (sdk in listOf(23, 28)) {
            assertEquals(
                listOf(
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                ),
                UltraShareScreenMirrorPolicy.availableAudioModes(sdk)
            )
            assertEquals(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                    requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                    sdkInt = sdk,
                    recordAudioGranted = true
                )
            )
            assertEquals(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                    requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                    sdkInt = sdk,
                    recordAudioGranted = true
                )
            )
            // Missing RECORD_AUDIO degrades cleanly to video-only NONE without failing mirroring:
            assertEquals(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                    requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                    sdkInt = sdk,
                    recordAudioGranted = false
                )
            )
        }

        // Android 10–15 (API 29, 33, 35): NONE, SYSTEM_AUDIO, and MICROPHONE available.
        for (sdk in listOf(29, 33, 35)) {
            assertEquals(
                listOf(
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                ),
                UltraShareScreenMirrorPolicy.availableAudioModes(sdk)
            )
            assertEquals(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                    requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                    sdkInt = sdk,
                    recordAudioGranted = true
                )
            )
            assertEquals(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                    requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                    sdkInt = sdk,
                    recordAudioGranted = true
                )
            )
        }

        // Mutual exclusion guard: SYSTEM_AUDIO and MICROPHONE can never be active simultaneously.
        val guard = MirrorAudioModeGuard()
        assertFalse(guard.tryActivate(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE))
        assertTrue(guard.tryActivate(UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO))
        assertFalse(guard.tryActivate(UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE))
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
            guard.replaceActiveMode(UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE)
        )
        assertEquals(UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE, guard.currentActiveMode())
        guard.deactivate(UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE)
        assertEquals(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE, guard.currentActiveMode())

        // Notification labels reflect actual audio state accurately:
        assertEquals(
            "فيديو فقط (بدون صوت)",
            UltraShareScreenMirrorPolicy.audioModeLabelAr(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                audioActive = false
            )
        )
        assertEquals(
            "صوت الميكروفون مفعّل",
            UltraShareScreenMirrorPolicy.audioModeLabelAr(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                audioActive = true
            )
        )
        assertEquals(
            "صوت النظام الداخلي مفعّل",
            UltraShareScreenMirrorPolicy.audioModeLabelAr(
                UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                audioActive = true
            )
        )
    }

    @Test
    fun microphoneCandidateFallbackSelectsFirstWorkingSourceAndReleasesFailedProbes() {
        val candidates = listOf(
            MicrophoneCaptureCandidate(audioSource = 7, sampleRateHz = 48_000, channelCount = 1),
            MicrophoneCaptureCandidate(audioSource = 1, sampleRateHz = 44_100, channelCount = 1),
            MicrophoneCaptureCandidate(audioSource = 5, sampleRateHz = 16_000, channelCount = 1)
        )
        val releasedHandles = mutableListOf<String>()
        val chosen = selectFirstWorkingMicrophoneCandidate(
            candidates = candidates,
            probe = { candidate ->
                if (candidate.audioSource == 7) {
                    null // e.g. VOICE_COMMUNICATION rejected by OEM HAL
                } else {
                    "handle-${candidate.audioSource}-${candidate.sampleRateHz}"
                }
            },
            releaseHandle = { handle -> releasedHandles += handle }
        )
        assertNotNull(chosen)
        assertEquals(1, chosen!!.first.audioSource)
        assertEquals(44_100, chosen.first.sampleRateHz)
        assertEquals("handle-1-44100", chosen.second)
        assertTrue(releasedHandles.isEmpty())

        // If every candidate fails, returns null cleanly so video mirroring proceeds uninterrupted.
        val noneWorking = selectFirstWorkingMicrophoneCandidate<String>(
            candidates = candidates,
            probe = { null },
            releaseHandle = { releasedHandles += it }
        )
        assertNull(noneWorking)
    }

    @Test
    fun mirrorViewerAutoOpenValidatesPairedPeerAndNeverRequiresMediaProjectionOnViewer() {
        val coordinator = MirrorViewerAutoOpenCoordinator()
        assertFalse(
            "Paired viewer must NEVER request MediaProjection permission",
            coordinator.requiresMediaProjectionPermissionForViewer
        )
        val validOffer = IncomingMirrorOffer(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            hostName = "Galaxy Host",
            hostAddress = "192.168.43.1",
            streamPort = 39_123,
            width = 720,
            height = 1280,
            fps = 30,
            audioEnabled = true,
            streamKey = streamKey,
            revision = 1L,
            transferSessionId = "room-session-1",
            transferSessionGeneration = 2L
        )

        // 1. Unpaired peer offer is rejected:
        val unpairedEval = coordinator.evaluateOffer(
            offer = validOffer,
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.99"),
            pairedPeerIds = setOf(viewerTwoId),
            isLocalHostSharing = false
        )
        assertEquals(
            MirrorViewerAutoOpenCoordinator.Evaluation.Rejected(
                MirrorViewerAutoOpenCoordinator.RejectReason.PEER_NOT_PAIRED
            ),
            unpairedEval
        )

        // 2. Public IP offer is rejected:
        val publicHostEval = coordinator.evaluateOffer(
            offer = validOffer.copy(hostAddress = "8.8.8.8"),
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("8.8.8.8"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertEquals(
            MirrorViewerAutoOpenCoordinator.Evaluation.Rejected(
                MirrorViewerAutoOpenCoordinator.RejectReason.INVALID_PRIVATE_LAN_HOST
            ),
            publicHostEval
        )

        // 3. Stale transfer session offer is rejected:
        val staleSessionEval = coordinator.evaluateOffer(
            offer = validOffer.copy(transferSessionId = "ended-room-0"),
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.1"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertEquals(
            MirrorViewerAutoOpenCoordinator.Evaluation.Rejected(
                MirrorViewerAutoOpenCoordinator.RejectReason.STALE_TRANSFER_SESSION
            ),
            staleSessionEval
        )

        // 4. Valid paired host offer auto-opens immediately & sends JOINED once:
        val openedEval = coordinator.evaluateOffer(
            offer = validOffer,
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.1"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertTrue(openedEval is MirrorViewerAutoOpenCoordinator.Evaluation.AutoOpen)
        val autoOpen = openedEval as MirrorViewerAutoOpenCoordinator.Evaluation.AutoOpen
        assertTrue(autoOpen.sendJoinedControlMessage)
        assertTrue(autoOpen.clearWaitingBanner)
        assertTrue(autoOpen.clearHostStartPrompt)

        // 5. Duplicate state emission for the same open session is deduplicated:
        val duplicateEval = coordinator.evaluateOffer(
            offer = validOffer,
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.1"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertEquals(
            MirrorViewerAutoOpenCoordinator.Evaluation.Rejected(
                MirrorViewerAutoOpenCoordinator.RejectReason.ALREADY_OPEN
            ),
            duplicateEval
        )

        // 6. User dismisses revision 1 -> revision 1 stays dismissed, revision 2 auto-opens cleanly:
        coordinator.markDismissedByUser(sessionId, revision = 1L)
        val afterDismissSameRev = coordinator.evaluateOffer(
            offer = validOffer,
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.1"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertEquals(
            MirrorViewerAutoOpenCoordinator.Evaluation.Rejected(
                MirrorViewerAutoOpenCoordinator.RejectReason.DISMISSED_BY_USER
            ),
            afterDismissSameRev
        )
        val nextRevEval = coordinator.evaluateOffer(
            offer = validOffer.copy(revision = 2L),
            localPeerId = viewerOneId,
            activeTransferSessionId = "room-session-1",
            activeTransferSessionGeneration = 2L,
            pairedPeerHosts = setOf("192.168.43.1"),
            pairedPeerIds = setOf(hostPeerId),
            isLocalHostSharing = false
        )
        assertTrue(nextRevEval is MirrorViewerAutoOpenCoordinator.Evaluation.AutoOpen)
    }

    @Test
    fun decoderOutputRenderGateAndBoundedFirstFrameTimeoutPreventInfiniteBlackScreen() {
        // DecoderOutputRenderGate: never renders when surface is invalid; only confirms render on valid surface.
        val invalidSurfaceDecision = DecoderOutputRenderGate.decide(
            isCodecConfigBuffer = false,
            payloadSizeBytes = 4096,
            surfaceValid = false
        )
        assertFalse(invalidSurfaceDecision.renderToSurface)
        assertFalse(invalidSurfaceDecision.confirmFrameRendered)

        val validRenderDecision = DecoderOutputRenderGate.decide(
            isCodecConfigBuffer = false,
            payloadSizeBytes = 4096,
            surfaceValid = true
        )
        assertTrue(validRenderDecision.renderToSurface)
        assertTrue(validRenderDecision.confirmFrameRendered)

        // Bounded first-frame timeout: host sends VIDEO_CONFIG + heartbeats, but NEVER a video frame.
        val authorized = setOf(viewerOneId)
        val idrRequests = AtomicInteger(0)
        val decoderResets = AtomicInteger(0)
        val hub = ScreenMirrorBroadcasterHub(
            sessionId = sessionId,
            hostPeerId = hostPeerId,
            streamKey = streamKey,
            authorizedViewerIdsProvider = { authorized },
            allowLoopbackForTest = true
        )
        hub.use { server ->
            val port = server.start(0)
            val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
            server.publishVideoConfig(spsPps, 720, 1280, 0)

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
                firstFrameTimeoutMs = 250L,
                maxFirstFrameTimeoutRetries = 1,
                onKeyframeNeeded = { idrRequests.incrementAndGet() },
                onFirstFrameTimeoutReset = { decoderResets.incrementAndGet() },
                frameSink = { }
            )
            client.use {
                client.start()
                val deadline = System.currentTimeMillis() + 4_000L
                while (System.currentTimeMillis() < deadline &&
                    client.status.value.phase != ScreenMirrorViewerClient.ConnectionPhase.ERROR
                ) {
                    Thread.sleep(50L)
                }
                val finalStatus = client.status.value
                assertEquals(
                    "Viewer must fail deterministically instead of hanging forever on a black screen",
                    ScreenMirrorViewerClient.ConnectionPhase.ERROR,
                    finalStatus.phase
                )
                assertFalse(finalStatus.isDisplayingStream)
                assertTrue("Viewer must request IDR recovery before failing", idrRequests.get() >= 1)
                assertTrue("Viewer must reset decoder once on first-frame timeout", decoderResets.get() >= 1)
                assertNotNull(finalStatus.errorMessage)
            }
        }
    }
}
