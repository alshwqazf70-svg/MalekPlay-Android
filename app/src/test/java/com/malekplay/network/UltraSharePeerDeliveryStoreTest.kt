package com.malekplay.network

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UltraSharePeerDeliveryStoreTest {
    @Test
    fun decodeKeepsIndependentFileAndPeerDeliveries() {
        val raw = """[
            {"fileUri":"content://files/a","peerSessionId":"peer-a","peerHost":"192.168.1.2","displayName":"a.mp4","status":"completed"},
            {"fileUri":"content://files/a","peerSessionId":"peer-b","peerHost":"192.168.1.3","displayName":"a.mp4","status":"failed","error":"offline"},
            null,
            {"fileUri":"content://files/a","peerSessionId":"peer-a","peerHost":"192.168.1.2","displayName":"duplicate","status":"pending"}
        ]"""
        val entries = UltraSharePeerDeliveryStore.decode(raw)
        assertEquals(2, entries.size)
        assertEquals(
            listOf("peer-a", "peer-b"),
            entries.map { it.peerSessionId }
        )
        assertEquals(UltraSharePeerDeliveryStore.STATUS_COMPLETED, entries[0].status)
        assertEquals(UltraSharePeerDeliveryStore.STATUS_FAILED, entries[1].status)
    }

    @Test
    fun prepareCreatesOneIndependentRecordPerFileAndPeer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val fileA = Uri.parse("content://files/a")
        val fileB = Uri.parse("content://files/b")
        val peers = listOf(
            UltraShareMultiRecipientCoordinator.Target("session-a", "192.168.1.2"),
            UltraShareMultiRecipientCoordinator.Target("session-b", "192.168.1.3")
        )
        UltraSharePeerDeliveryStore.clear(context)
        try {
            UltraSharePeerDeliveryStore.prepare(
                context,
                listOf(fileA to "a.mp4", fileB to "b.mp4"),
                peers
            )
            val entries = UltraSharePeerDeliveryStore.observe(context).first()
            assertEquals(4, entries.size)
            assertTrue(entries.all { it.status == UltraSharePeerDeliveryStore.STATUS_PENDING })
            assertEquals(2, entries.count { it.peerSessionId == "session-a" })
            assertEquals(2, entries.count { it.peerSessionId == "session-b" })
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

    @Test
    fun completingOnePeerDoesNotMarkTheOtherPeerCompleted() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://files/shared")
        UltraSharePeerDeliveryStore.clear(context)
        try {
            val peers = listOf(
                UltraShareMultiRecipientCoordinator.Target("peer-a", "192.168.1.2"),
                UltraShareMultiRecipientCoordinator.Target("peer-b", "192.168.1.3")
            )
            UltraSharePeerDeliveryStore.prepare(context, listOf(uri to "shared.mp4"), peers)
            UltraSharePeerDeliveryStore.update(
                context, uri, "peer-a", UltraSharePeerDeliveryStore.STATUS_COMPLETED
            )
            val entries = UltraSharePeerDeliveryStore.observe(context).first()
            assertEquals(
                UltraSharePeerDeliveryStore.STATUS_COMPLETED,
                entries.first { it.peerSessionId == "peer-a" }.status
            )
            assertEquals(
                UltraSharePeerDeliveryStore.STATUS_PENDING,
                entries.first { it.peerSessionId == "peer-b" }.status
            )
            assertEquals(1, UltraSharePeerDeliveryStore.pending(context, "peer-b").size)
            assertTrue(UltraSharePeerDeliveryStore.pending(context, "peer-a").isEmpty())
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

    @Test
    fun interruptedPeerDeliveryIsRecoveredWithoutChangingCompletedPeers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://files/recovery")
        UltraSharePeerDeliveryStore.clear(context)
        try {
            val peers = listOf(
                UltraShareMultiRecipientCoordinator.Target("done", "192.168.1.2"),
                UltraShareMultiRecipientCoordinator.Target("interrupted", "192.168.1.3")
            )
            UltraSharePeerDeliveryStore.prepare(context, listOf(uri to "recover.mp4"), peers)
            UltraSharePeerDeliveryStore.update(context, uri, "done", UltraSharePeerDeliveryStore.STATUS_COMPLETED)
            UltraSharePeerDeliveryStore.update(context, uri, "interrupted", UltraSharePeerDeliveryStore.STATUS_SENDING)
            UltraSharePeerDeliveryStore.recoverInterrupted(context)
            val entries = UltraSharePeerDeliveryStore.observe(context).first()
            assertEquals(UltraSharePeerDeliveryStore.STATUS_COMPLETED, entries.first { it.peerSessionId == "done" }.status)
            assertEquals(UltraSharePeerDeliveryStore.STATUS_PENDING, entries.first { it.peerSessionId == "interrupted" }.status)
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

    @Test
    fun updateRejectsUnknownFilePeerPairInsteadOfSilentlySucceeding() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        UltraSharePeerDeliveryStore.clear(context)
        try {
            val failure = runCatching {
                UltraSharePeerDeliveryStore.update(
                    context,
                    Uri.parse("content://files/missing"),
                    "unknown-peer",
                    UltraSharePeerDeliveryStore.STATUS_COMPLETED
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(UltraSharePeerDeliveryStore.observe(context).first().isEmpty())
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

    @Test
    fun corruptRootIsNotOverwrittenByMutation() {
        assertFalse(runCatching {
            UltraSharePeerDeliveryStore.decodeForMutation("{bad")
        }.isSuccess)
        assertTrue(UltraSharePeerDeliveryStore.decode("{bad").isEmpty())
    }
    @Test
    fun preparingRetryDoesNotResetFilesAlreadyDeliveredToAnotherPeer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://files/retry")
        val firstPeer = UltraShareMultiRecipientCoordinator.Target("peer-a", "192.168.1.2")
        val secondPeer = UltraShareMultiRecipientCoordinator.Target("peer-b", "192.168.1.3")
        UltraSharePeerDeliveryStore.clear(context)
        try {
            UltraSharePeerDeliveryStore.prepare(context, listOf(uri to "retry.mp4"), listOf(firstPeer))
            UltraSharePeerDeliveryStore.update(
                context, uri, firstPeer.sessionId, UltraSharePeerDeliveryStore.STATUS_COMPLETED
            )
            UltraSharePeerDeliveryStore.prepare(
                context, listOf(uri to "retry.mp4"), listOf(firstPeer, secondPeer)
            )
            val entries = UltraSharePeerDeliveryStore.observe(context).first()
            assertEquals(
                UltraSharePeerDeliveryStore.STATUS_COMPLETED,
                entries.first { it.peerSessionId == firstPeer.sessionId }.status
            )
            assertEquals(
                UltraSharePeerDeliveryStore.STATUS_PENDING,
                entries.first { it.peerSessionId == secondPeer.sessionId }.status
            )
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

    @Test
    fun intentionalNewSendCanResetPreviouslyCompletedReceipt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://files/new-send")
        val peer = UltraShareMultiRecipientCoordinator.Target("peer-a", "192.168.1.2")
        UltraSharePeerDeliveryStore.clear(context)
        try {
            UltraSharePeerDeliveryStore.prepare(context, listOf(uri to "again.mp4"), listOf(peer))
            UltraSharePeerDeliveryStore.update(
                context, uri, peer.sessionId, UltraSharePeerDeliveryStore.STATUS_COMPLETED
            )
            UltraSharePeerDeliveryStore.prepare(
                context,
                listOf(uri to "again.mp4"),
                listOf(peer),
                preserveCompleted = false
            )
            val entry = UltraSharePeerDeliveryStore.observe(context).first().single()
            assertEquals(UltraSharePeerDeliveryStore.STATUS_PENDING, entry.status)
        } finally {
            UltraSharePeerDeliveryStore.clear(context)
        }
    }

}
