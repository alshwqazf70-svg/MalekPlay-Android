package com.malekplay.network

import android.content.pm.ServiceInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors

class UltraShareScreenMirrorProtocolAndPolicyTest {

    private val sessionId = "0123456789abcdef0123456789abcdef"
    private val hostPeerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val viewerPeerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val streamKey = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test fun policyEnforcesAndroid6Through16CompatibilityGates() {
        (23..36).forEach { sdk ->
            assertTrue("API $sdk must support MediaProjection", UltraShareScreenMirrorPolicy.supportsMediaProjection(sdk))
        }
        (23..28).forEach { sdk ->
            assertFalse(UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(sdk))
            assertFalse(UltraShareScreenMirrorPolicy.supportsAudioPlaybackCapture(sdk))
            assertFalse(UltraShareScreenMirrorPolicy.requiresTypedMediaProjectionForegroundService(sdk))
            assertEquals(0, UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(sdk))
        }
        (29..36).forEach { sdk ->
            assertTrue(UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(sdk))
            assertTrue(UltraShareScreenMirrorPolicy.supportsAudioPlaybackCapture(sdk))
            assertTrue(UltraShareScreenMirrorPolicy.requiresTypedMediaProjectionForegroundService(sdk))
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(sdk)
            )
        }
        (23..33).forEach { sdk ->
            assertFalse(UltraShareScreenMirrorPolicy.requiresMediaProjectionCallback(sdk))
        }
        (34..36).forEach { sdk ->
            assertTrue(UltraShareScreenMirrorPolicy.requiresMediaProjectionCallback(sdk))
        }
    }

    @Test fun alignedVideoSizePreservesAspectRatioAndCodecAlignment() {
        val portrait = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            sourceWidth = 1080,
            sourceHeight = 2400,
            maxLongEdge = 1280,
            maxShortEdge = 720,
            widthAlignment = 16,
            heightAlignment = 16
        )
        assertTrue(portrait.first % 16 == 0)
        assertTrue(portrait.second % 16 == 0)
        assertTrue(portrait.second > portrait.first)
        assertTrue(portrait.second <= 1280)

        val landscape = UltraShareScreenMirrorPolicy.computeAlignedVideoSize(
            sourceWidth = 2400,
            sourceHeight = 1080,
            maxLongEdge = 1280,
            maxShortEdge = 720,
            widthAlignment = 16,
            heightAlignment = 16
        )
        assertTrue(landscape.first % 16 == 0)
        assertTrue(landscape.second % 16 == 0)
        assertTrue(landscape.first > landscape.second)
        assertTrue(landscape.first <= 1280)
    }

    @Test fun controlMessagesRoundTripAndStayIsolatedFromMeshMpmc() {
        val startMsg = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
            sessionId = sessionId,
            senderPeerId = hostPeerId,
            revision = 4L,
            streamPort = 38_746,
            streamKey = streamKey,
            config = UltraShareScreenMirrorProtocol.StreamConfig(1280, 720, 30, 2_500_000, audioEnabled = true)
        )
        val wire = UltraShareScreenMirrorProtocol.encodeControl(startMsg)
        assertTrue(UltraShareScreenMirrorProtocol.isMirrorControlPayload(wire))
        assertEquals(null, UltraShareMeshControlProtocol.decode(wire))

        val decoded = UltraShareScreenMirrorProtocol.decodeControl(wire)
        assertEquals(startMsg.type, decoded.type)
        assertEquals(sessionId, decoded.sessionId)
        assertEquals(hostPeerId, decoded.senderPeerId)
        assertEquals(4L, decoded.revision)
        assertEquals(38_746, decoded.streamPort)
        assertArrayEquals(streamKey, decoded.streamKey)
        assertEquals(startMsg.config, decoded.config)

        val viewerMsg = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_VIEWER_STATE,
            sessionId = sessionId,
            senderPeerId = viewerPeerId,
            revision = 1L,
            viewerAction = UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR
        )
        val decodedViewer = UltraShareScreenMirrorProtocol.decodeControl(
            UltraShareScreenMirrorProtocol.encodeControl(viewerMsg)
        )
        assertEquals(UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR, decodedViewer.viewerAction)
    }

    @Test fun malformedControlFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareScreenMirrorProtocol.decodeControl(byteArrayOf(1, 2, 3, 4))
        }
        val badKeyMsg = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
            sessionId = sessionId,
            senderPeerId = hostPeerId,
            revision = 1L,
            streamPort = 38_746,
            streamKey = ByteArray(8),
            config = UltraShareScreenMirrorProtocol.StreamConfig(720, 1280, 30, 2_000_000, false)
        )
        assertThrows(IllegalArgumentException::class.java) {
            UltraShareScreenMirrorProtocol.encodeControl(badKeyMsg)
        }
    }

    @Test fun mutualHandshakeAuthenticatesAuthorizedPeersAndRejectsImpostors() {
        val hostToViewerIn = PipedInputStream()
        val hostToViewerOut = PipedOutputStream(hostToViewerIn)
        val viewerToHostIn = PipedInputStream()
        val viewerToHostOut = PipedOutputStream(viewerToHostIn)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val hostFuture = executor.submit<UltraShareScreenMirrorProtocol.StreamCipherSession> {
                UltraShareScreenMirrorProtocol.performHostStreamHandshake(
                    input = viewerToHostIn,
                    output = hostToViewerOut,
                    sessionId = sessionId,
                    hostPeerId = hostPeerId,
                    streamKey = streamKey,
                    isAuthorizedViewer = { id -> id == viewerPeerId }
                ).second
            }
            val viewerFuture = executor.submit<UltraShareScreenMirrorProtocol.StreamCipherSession> {
                UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                    input = hostToViewerIn,
                    output = viewerToHostOut,
                    expectedSessionId = sessionId,
                    expectedHostPeerId = hostPeerId,
                    localViewerPeerId = viewerPeerId,
                    streamKey = streamKey
                )
            }

            val hostCipher = hostFuture.get()
            val viewerCipher = viewerFuture.get()
            hostCipher.use { h ->
                viewerCipher.use { v ->
                    val secretVideo = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x42, 0x11, 0x7F)
                    val wireBuf = ByteArrayOutputStream()
                    h.writeFrame(
                        out = wireBuf,
                        frameType = UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR,
                        flags = 1,
                        mediaSeq = 42L,
                        timestampUs = 999_000L,
                        width = 720,
                        height = 1280,
                        rotation = 0,
                        payload = secretVideo
                    )
                    val wireBytes = wireBuf.toByteArray()
                    assertNotEquals(secretVideo.toList(), wireBytes.takeLast(secretVideo.size))

                    val received = v.readFrame(ByteArrayInputStream(wireBytes))
                    assertEquals(UltraShareScreenMirrorProtocol.FrameType.VIDEO_IDR, received.header.frameType)
                    assertEquals(42L, received.header.mediaSeq)
                    assertEquals(720, received.header.width)
                    assertEquals(1280, received.header.height)
                    assertArrayEquals(secretVideo, received.payload)

                    // Tampering with encrypted payload must fail GCM authentication.
                    val tampered = wireBytes.copyOf()
                    tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0xFF).toByte()
                    assertThrows(SecurityException::class.java) {
                        v.readFrame(ByteArrayInputStream(tampered))
                    }

                    // Replaying wireBytes must fail strict monotonic wireSeq check.
                    assertThrows(SecurityException::class.java) {
                        v.readFrame(ByteArrayInputStream(wireBytes))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun unauthorizedViewerIsRejectedDuringHandshake() {
        val hostToViewerIn = PipedInputStream()
        val hostToViewerOut = PipedOutputStream(hostToViewerIn)
        val viewerToHostIn = PipedInputStream()
        val viewerToHostOut = PipedOutputStream(viewerToHostIn)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val hostFuture = executor.submit<Throwable?> {
                try {
                    UltraShareScreenMirrorProtocol.performHostStreamHandshake(
                        input = viewerToHostIn,
                        output = hostToViewerOut,
                        sessionId = sessionId,
                        hostPeerId = hostPeerId,
                        streamKey = streamKey,
                        isAuthorizedViewer = { false }
                    )
                    null
                } catch (t: Throwable) {
                    t
                }
            }
            val viewerFuture = executor.submit<Throwable?> {
                try {
                    UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                        input = hostToViewerIn,
                        output = viewerToHostOut,
                        expectedSessionId = sessionId,
                        expectedHostPeerId = hostPeerId,
                        localViewerPeerId = viewerPeerId,
                        streamKey = streamKey
                    )
                    null
                } catch (t: Throwable) {
                    t
                }
            }

            assertTrue(hostFuture.get() is SecurityException)
            assertTrue(viewerFuture.get() != null)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun wrongStreamKeyIsRejectedByViewer() {
        val hostToViewerIn = PipedInputStream()
        val hostToViewerOut = PipedOutputStream(hostToViewerIn)
        val viewerToHostIn = PipedInputStream()
        val viewerToHostOut = PipedOutputStream(viewerToHostIn)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val hostFuture = executor.submit<Throwable?> {
                try {
                    UltraShareScreenMirrorProtocol.performHostStreamHandshake(
                        input = viewerToHostIn,
                        output = hostToViewerOut,
                        sessionId = sessionId,
                        hostPeerId = hostPeerId,
                        streamKey = streamKey,
                        isAuthorizedViewer = { true }
                    )
                    null
                } catch (t: Throwable) {
                    t
                }
            }
            val viewerFuture = executor.submit<Throwable?> {
                try {
                    UltraShareScreenMirrorProtocol.performViewerStreamHandshake(
                        input = hostToViewerIn,
                        output = viewerToHostOut,
                        expectedSessionId = sessionId,
                        expectedHostPeerId = hostPeerId,
                        localViewerPeerId = viewerPeerId,
                        streamKey = ByteArray(32) { 0x55 }
                    )
                    null
                } catch (t: Throwable) {
                    t
                }
            }

            assertTrue(hostFuture.get() is SecurityException)
            assertTrue(viewerFuture.get() is SecurityException)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun videoSequenceTrackerDetectsTrueVideoLossWithoutFalseDropsOnAudio() {
        val tracker = VideoStreamSequenceTracker()
        // Before IDR arrives, orphan P-frames are ignored.
        val orphan = tracker.onVideoFrame(mediaSeq = 1L, isIdr = false)
        assertFalse(orphan.shouldFeedToDecoder)
        assertFalse(orphan.shouldRequestKeyframe)

        // IDR unlocks decoding; consecutive video seqs 2, 3, 4 proceed uninterrupted.
        val idr = tracker.onVideoFrame(mediaSeq = 2L, isIdr = true)
        assertTrue(idr.shouldFeedToDecoder)
        assertFalse(tracker.awaitingIdr)

        assertTrue(tracker.onVideoFrame(mediaSeq = 3L, isIdr = false).shouldFeedToDecoder)
        assertTrue(tracker.onVideoFrame(mediaSeq = 4L, isIdr = false).shouldFeedToDecoder)

        // Real video frame 5 is lost -> frame 6 triggers awaitingIdr + keyframe request.
        val gap = tracker.onVideoFrame(mediaSeq = 6L, isIdr = false)
        assertFalse(gap.shouldFeedToDecoder)
        assertTrue(gap.shouldRequestKeyframe)
        assertTrue(gap.droppedGapDetected)
        assertTrue(tracker.awaitingIdr)

        // Subsequent P-frame 7 remains blocked until IDR 8 arrives.
        assertFalse(tracker.onVideoFrame(mediaSeq = 7L, isIdr = false).shouldFeedToDecoder)
        val recover = tracker.onVideoFrame(mediaSeq = 8L, isIdr = true)
        assertTrue(recover.shouldFeedToDecoder)
        assertFalse(tracker.awaitingIdr)
    }

    @Test fun decoderInputFeederHandlesUnavailableBufferWithoutQueueBuildup() {
        val tracker = VideoStreamSequenceTracker().apply { awaitingIdr = false }
        var keyframeRequests = 0
        var drops = 0
        var drainCalls = 0
        val adapter = object : VideoCodecBufferAdapter {
            override fun dequeueInputBuffer(timeoutUs: Long): Int = -1
            override fun getInputBuffer(index: Int) = null
            override fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
                throw AssertionError("Must not queue when no input buffer was acquired")
            }
            override fun drainOutputs(renderToSurface: Boolean) {
                drainCalls++
            }
        }

        val result = VideoDecoderBufferFeeder.feedFrame(
            adapter = adapter,
            bytes = byteArrayOf(0, 0, 0, 1, 0x41, 0x10),
            ptsUs = 1_000L,
            isIdr = false,
            renderToSurface = true,
            sequenceTracker = tracker,
            onRequestKeyframe = { keyframeRequests++ },
            onFrameDropped = { drops++ }
        )

        assertEquals(VideoInputFeedResult.DROPPED_NO_INPUT_BUFFER, result)
        assertEquals(1, keyframeRequests)
        assertEquals(1, drops)
        assertTrue(drainCalls >= 2)
        assertTrue(tracker.awaitingIdr)
        // Follow-up delta frame must be skipped until IDR arrives.
        assertFalse(tracker.onVideoFrame(mediaSeq = 10L, isIdr = false).shouldFeedToDecoder)
    }

    @Test fun decoderInputFeederNeverTruncatesOversizedFrameAndRecoversCleanly() {
        val tracker = VideoStreamSequenceTracker().apply { awaitingIdr = false }
        var keyframeRequests = 0
        var drops = 0
        val smallBuffer = java.nio.ByteBuffer.allocate(8)
        var queuedSize = -1
        var queuedIndex = -1
        val adapter = object : VideoCodecBufferAdapter {
            override fun dequeueInputBuffer(timeoutUs: Long): Int = 3
            override fun getInputBuffer(index: Int) = smallBuffer
            override fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
                queuedIndex = index
                queuedSize = size
            }
            override fun drainOutputs(renderToSurface: Boolean) = Unit
        }

        val oversized = ByteArray(32) { 0x5A }
        val result = VideoDecoderBufferFeeder.feedFrame(
            adapter = adapter,
            bytes = oversized,
            ptsUs = 2_000L,
            isIdr = false,
            renderToSurface = true,
            sequenceTracker = tracker,
            onRequestKeyframe = { keyframeRequests++ },
            onFrameDropped = { drops++ }
        )

        assertEquals(VideoInputFeedResult.REJECTED_OVERSIZED_OR_EMPTY_FRAME, result)
        // Returned the dequeued slot empty (size 0) without writing truncated frame bytes.
        assertEquals(3, queuedIndex)
        assertEquals(0, queuedSize)
        assertEquals(0, smallBuffer.position())
        assertEquals(1, keyframeRequests)
        assertEquals(1, drops)
        assertTrue(tracker.awaitingIdr)

        // When a sufficiently sized buffer arrives for IDR, the full frame is queued.
        val largeBuffer = java.nio.ByteBuffer.allocate(64)
        val okAdapter = object : VideoCodecBufferAdapter {
            override fun dequeueInputBuffer(timeoutUs: Long): Int = 4
            override fun getInputBuffer(index: Int) = largeBuffer
            override fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
                queuedIndex = index
                queuedSize = size
            }
            override fun drainOutputs(renderToSurface: Boolean) = Unit
        }
        val idrPayload = ByteArray(24) { 0x65 }
        val fed = VideoDecoderBufferFeeder.feedFrame(
            adapter = okAdapter,
            bytes = idrPayload,
            ptsUs = 3_000L,
            isIdr = true,
            renderToSurface = true,
            sequenceTracker = tracker,
            onRequestKeyframe = { keyframeRequests++ }
        )
        assertEquals(VideoInputFeedResult.FED, fed)
        assertEquals(4, queuedIndex)
        assertEquals(24, queuedSize)
    }

    @Test fun encoderSelectionSkipsIncompatibleHardwareAndFallsBackOnFailure() {
        val profile = UltraShareScreenMirrorPolicy.CaptureProfile(
            maxWidth = 1280,
            maxHeight = 720,
            fps = 30,
            bitrateBps = 2_500_000
        )
        val incompatibleHw = H264CodecDescriptor(
            name = "OMX.vendor.bad.avc",
            isSoftwareOnly = false,
            supportsSurfaceColorFormat = true,
            widthAlignment = 16,
            heightAlignment = 16,
            maxSupportedFps = 15,
            maxSupportedBitrateBps = 1_000_000,
            supportsSizeAndRate = { _, _, _ -> false }
        )
        val secondaryHw = H264CodecDescriptor(
            name = "OMX.vendor.good.avc",
            isSoftwareOnly = false,
            supportsSurfaceColorFormat = true,
            widthAlignment = 16,
            heightAlignment = 16,
            maxSupportedFps = 30,
            maxSupportedBitrateBps = 4_000_000,
            supportsSizeAndRate = { w, h, fps -> w <= 1280 && h <= 1280 && fps <= 30 }
        )
        val softwareFallback = H264CodecDescriptor(
            name = "c2.android.avc.encoder",
            isSoftwareOnly = true,
            supportsSurfaceColorFormat = true,
            widthAlignment = 2,
            heightAlignment = 2,
            maxSupportedFps = 30,
            maxSupportedBitrateBps = 2_000_000,
            supportsSizeAndRate = { _, _, _ -> true }
        )

        val candidates = selectH264EncoderCandidates(
            descriptors = listOf(incompatibleHw, secondaryHw, softwareFallback),
            sourceWidth = 1080,
            sourceHeight = 2400,
            baseProfile = profile
        )
        // Incompatible hardware must be excluded; secondary hardware precedes software fallback.
        assertEquals(listOf("OMX.vendor.good.avc", "c2.android.avc.encoder"), candidates.map { it.codecName })

        // If secondary hardware fails configure(), configureFirstWorkingH264Encoder falls back to software.
        val attempted = mutableListOf<String?>()
        val (chosen, token) = configureFirstWorkingH264Encoder(candidates) { candidate ->
            attempted += candidate.codecName
            if (candidate.codecName == "OMX.vendor.good.avc") {
                throw IllegalStateException("Simulated configure failure")
            }
            "configured:${candidate.codecName}"
        }
        assertEquals(listOf("OMX.vendor.good.avc", "c2.android.avc.encoder"), attempted)
        assertEquals("c2.android.avc.encoder", chosen.codecName)
        assertTrue(chosen.isSoftwareFallback)
        assertEquals("configured:c2.android.avc.encoder", token)

        // When every candidate fails, an IllegalStateException is surfaced cleanly after trying all candidates.
        val allFailedAttempted = mutableListOf<String?>()
        val failure = runCatching {
            configureFirstWorkingH264Encoder(candidates) { candidate ->
                allFailedAttempted += candidate.codecName
                throw IllegalStateException("Simulated failure for ${candidate.codecName}")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("OMX.vendor.good.avc", "c2.android.avc.encoder"), allFailedAttempted)
    }

    @Test fun staleMirrorStopNeverClearsNewerSessionOnSameHost() {
        val oldSessionId = "11111111111111111111111111111111"
        val newSessionId = "22222222222222222222222222222222"
        val cfg = UltraShareScreenMirrorProtocol.StreamConfig(720, 1280, 30, 2_500_000, true)
        val terminated = mutableMapOf<String, Long>()

        // Start session 1, then start session 2 on the same hostAddress.
        val startOld = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
            sessionId = oldSessionId,
            senderPeerId = hostPeerId,
            revision = 1L,
            streamPort = 38_746,
            streamKey = streamKey,
            config = cfg
        )
        val startNew = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_START,
            sessionId = newSessionId,
            senderPeerId = hostPeerId,
            revision = 1L,
            streamPort = 38_746,
            streamKey = streamKey,
            config = cfg
        )
        var offer = applyIncomingMirrorControlMessage(
            currentOffer = null,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = startOld,
            terminatedSessionRevisions = terminated
        )
        offer = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = startNew,
            terminatedSessionRevisions = terminated
        )
        assertEquals(newSessionId, offer?.sessionId)

        // Late MIRROR_STOP from oldSessionId on the exact same hostAddress must NOT clear newSessionId.
        val staleStopOldSession = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_STOP,
            sessionId = oldSessionId,
            senderPeerId = hostPeerId,
            revision = 2L
        )
        offer = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = staleStopOldSession,
            terminatedSessionRevisions = terminated
        )
        assertEquals(newSessionId, offer?.sessionId)

        // Update newSessionId to revision 3; stale MIRROR_STOP with revision 2 for newSessionId must be ignored.
        val updateNew = startNew.copy(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_UPDATE,
            revision = 3L
        )
        offer = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = updateNew,
            terminatedSessionRevisions = terminated
        )
        assertEquals(3L, offer?.revision)

        val staleStopLowerRev = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_STOP,
            sessionId = newSessionId,
            senderPeerId = hostPeerId,
            revision = 2L
        )
        offer = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = staleStopLowerRev,
            terminatedSessionRevisions = terminated
        )
        assertEquals(newSessionId, offer?.sessionId)

        // Valid MIRROR_STOP for newSessionId with revision >= 3 stops it cleanly.
        val validStop = UltraShareScreenMirrorProtocol.ControlMessage(
            type = UltraShareScreenMirrorProtocol.ControlType.MIRROR_STOP,
            sessionId = newSessionId,
            senderPeerId = hostPeerId,
            revision = 4L
        )
        offer = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = validStop,
            terminatedSessionRevisions = terminated
        )
        assertEquals(null, offer)

        // Replayed/late MIRROR_START for stopped oldSessionId at revision <= 2 must stay ignored.
        val replayOld = applyIncomingMirrorControlMessage(
            currentOffer = offer,
            hostAddress = "192.168.43.1",
            authorityId = hostPeerId,
            hostDisplayName = "Host",
            message = startOld,
            terminatedSessionRevisions = terminated
        )
        assertEquals(null, replayOld)
    }
}
