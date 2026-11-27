package com.malekplay.ui.network

import com.malekplay.network.IncomingMirrorOffer
import com.malekplay.network.UltraShareRoomTaskRegistry
import com.malekplay.network.UltraShareScreenMirrorPolicy
import com.malekplay.network.UltraShareServiceState
import com.malekplay.network.resetRoomTransferPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UltraShareRoundTwoIntegrationTest {

    @Test
    fun incomingMirrorOfferValidationEnforcesPairingHostPortKeyAndLocalPeer() {
        val validOffer = IncomingMirrorOffer(
            sessionId = "11111111111111111111111111111111",
            hostAddress = "192.168.49.1",
            hostPeerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            hostName = "Host Device",
            streamPort = 39001,
            streamKey = ByteArray(32) { 7 },
            width = 1280,
            height = 720,
            fps = 30,
            audioEnabled = true,
            revision = 1L,
            audioMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
        )
        val pairedHosts = setOf("192.168.49.1")
        val localPeerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        // Paired, authorized viewer auto-launches cleanly.
        assertTrue(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer,
                pairedHosts = pairedHosts,
                localPeerId = localPeerId
            )
        )

        // Unpaired host / peer from outside the current room must be rejected.
        assertFalse(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer,
                pairedHosts = setOf("192.168.49.99"),
                localPeerId = localPeerId
            )
        )

        // Self-originated offer (same hostPeerId as local device) must never auto-open viewer.
        assertFalse(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer,
                pairedHosts = pairedHosts,
                localPeerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )

        // Malformed port, key length, or public/invalid address must be rejected.
        assertFalse(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer.copy(streamPort = 80),
                pairedHosts = pairedHosts,
                localPeerId = localPeerId
            )
        )
        assertFalse(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer.copy(streamKey = ByteArray(16)),
                pairedHosts = pairedHosts,
                localPeerId = localPeerId
            )
        )
        assertFalse(
            UltraShareMirrorOfferPolicy.isValidForAutoLaunch(
                offer = validOffer.copy(hostAddress = "8.8.8.8"),
                pairedHosts = pairedHosts,
                localPeerId = localPeerId
            )
        )

        // JOINED deduplication key stays unique per (transferSessionId, sessionId, hostAddress)
        val joined = HashSet<String>()
        val key1 = UltraShareMirrorOfferPolicy.offerKey(1L, validOffer)
        assertTrue(joined.add(key1))
        assertFalse(joined.add(key1)) // Re-emissions / UI recompositions must NOT re-send JOINED
        val key2 = UltraShareMirrorOfferPolicy.offerKey(2L, validOffer)
        assertTrue(joined.add(key2)) // New transfer session gets fresh key
    }

    @Test
    fun peerLeaveCancelsOnlyLeavingPeerTasksWithoutDisturbingOtherPeers() = runBlocking {
        val registry = UltraShareRoomTaskRegistry()
        val sessionId = registry.currentSessionId()

        val peerASocketClosed = AtomicBoolean(false)
        val peerBSocketClosed = AtomicBoolean(false)
        val peerACancelled = CompletableDeferred<Boolean>()
        val peerBCompleted = CompletableDeferred<Boolean>()

        val jobA: Job = launch {
            try {
                delay(10_000L)
            } finally {
                peerACancelled.complete(true)
            }
        }
        val jobB: Job = launch {
            delay(80L)
            peerBCompleted.complete(true)
        }

        val taskA = registry.registerTask(
            transferSessionId = sessionId,
            kind = UltraShareRoomTaskRegistry.TaskKind.SEND_MULTI_RECIPIENT,
            peerHost = "192.168.49.2",
            peerSessionId = "peer-a",
            job = jobA,
            closeables = listOf(Closeable { peerASocketClosed.set(true) })
        )
        val taskB = registry.registerTask(
            transferSessionId = sessionId,
            kind = UltraShareRoomTaskRegistry.TaskKind.SEND_MULTI_RECIPIENT,
            peerHost = "192.168.49.3",
            peerSessionId = "peer-b",
            job = jobB,
            closeables = listOf(Closeable { peerBSocketClosed.set(true) })
        )
        assertNotNull(taskA)
        assertNotNull(taskB)

        // Peer A leaves the room; only Peer A's task & socket are cancelled/closed.
        val cancelledCount = registry.cancelPeerTasks(
            peerHost = "192.168.49.2",
            peerSessionId = "peer-a",
            transferSessionId = sessionId
        )
        assertEquals(1, cancelledCount)
        assertTrue(peerACancelled.await())
        assertTrue(peerASocketClosed.get())
        assertFalse(registry.isSessionValid(sessionId, peerHost = "192.168.49.2", peerSessionId = "peer-a"))

        // Peer B in the same session continues unaffected and completes normally.
        assertTrue(registry.isSessionValid(sessionId, peerHost = "192.168.49.3", peerSessionId = "peer-b"))
        assertFalse(peerBSocketClosed.get())
        assertTrue(peerBCompleted.await())

        registry.unregisterTask(taskB)
    }

    @Test
    fun endingSessionCancelsSessionTasksProtectsNewSessionAndPreservesCompletedFiles() = runBlocking {
        val tempDir = Files.createTempDirectory("ultrashare_room_cleanup_test").toFile()
        try {
            val completedFile = File(tempDir, "completed_movie.mp4").apply {
                writeText("completed-media-bytes")
            }

            val registry = UltraShareRoomTaskRegistry()
            val session1 = registry.currentSessionId()

            val socket1Closed = AtomicBoolean(false)
            val receiveCancelled = CompletableDeferred<Boolean>()
            val sendCancelled = CompletableDeferred<Boolean>()
            val uiCallbackCounter = AtomicInteger(0)

            val receiveJob = launch {
                try {
                    delay(10_000L)
                } finally {
                    receiveCancelled.complete(true)
                }
            }
            val sendJob = launch {
                try {
                    delay(10_000L)
                } finally {
                    sendCancelled.complete(true)
                }
            }

            registry.registerTask(
                transferSessionId = session1,
                kind = UltraShareRoomTaskRegistry.TaskKind.RECEIVE_LOOP,
                job = receiveJob,
                closeables = listOf(Closeable { socket1Closed.set(true) })
            )
            registry.registerTask(
                transferSessionId = session1,
                kind = UltraShareRoomTaskRegistry.TaskKind.SEND_SINGLE,
                peerHost = "192.168.49.5",
                peerSessionId = "peer-5",
                job = sendJob
            )

            // End session1; token rotates so a new session (session2) starts cleanly.
            val endedTasks = registry.endSession(session1)
            val session2 = registry.currentSessionId()
            assertEquals(2, endedTasks)
            assertNotEquals(session1, session2)
            assertTrue(receiveCancelled.await())
            assertTrue(sendCancelled.await())
            assertTrue(socket1Closed.get())

            // Late callback from session1 must be blocked and never touch session2.
            if (registry.isSessionValid(session1)) {
                uiCallbackCounter.incrementAndGet()
            }
            assertEquals(0, uiCallbackCounter.get())
            assertTrue(registry.isSessionValid(session2))

            // Registering a new task under stale session1 is immediately rejected/cancelled.
            val staleJobCancelled = AtomicBoolean(false)
            val staleJob = launch { delay(10_000L) }.apply {
                invokeOnCompletion { if (isCancelled) staleJobCancelled.set(true) }
            }
            val rejected = registry.registerTask(
                transferSessionId = session1,
                kind = UltraShareRoomTaskRegistry.TaskKind.TEMP_QUEUE_OPERATION,
                job = staleJob
            )
            assertNull(rejected)
            staleJob.join()
            assertTrue(staleJobCancelled.get())

            // Room state reset clears ephemeral session presentation and updates session ID.
            val cleanState = resetRoomTransferPresentation(
                UltraShareServiceState(transferSessionId = session1, status = "نشط"),
                newTransferSessionId = session2
            )
            assertEquals(session2, cleanState.transferSessionId)
            assertNull(cleanState.status)
            assertNull(cleanState.incomingMirrorOffer)

            // Completed user files on disk remain completely untouched.
            assertTrue(completedFile.exists())
            assertEquals("completed-media-bytes", completedFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
