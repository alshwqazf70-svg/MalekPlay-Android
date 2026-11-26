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
            assertFalse(UltraShareScreenMirrorPolicy.requiresTypedMediaProjectionForegroundService(sdk))
            assertEquals(0, UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(sdk))
        }
        (29..36).forEach { sdk ->
            assertTrue(UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(sdk))
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
}
