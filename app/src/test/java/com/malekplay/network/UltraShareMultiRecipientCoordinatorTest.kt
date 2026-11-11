package com.malekplay.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareMultiRecipientCoordinatorTest {
    private fun targets(count: Int) = (1..count).map {
        UltraShareMultiRecipientCoordinator.Target("session-$it", "192.168.1.$it")
    }

    @Test
    fun reconnectingKnownHostRetainsDeliverySessionIdentity() {
        val retained = listOf("192.168.1.20" to "stable-peer-session")
        assertEquals(
            "stable-peer-session",
            UltraShareMultiRecipientCoordinator.resolveSessionId(
                "192.168.1.20", retained, "new-random-session"
            )
        )
        assertEquals(
            "new-random-session",
            UltraShareMultiRecipientCoordinator.resolveSessionId(
                "192.168.1.21", retained, "new-random-session"
            )
        )
    }

    @Test
    fun sendsToEveryPeerAndPreservesTargetOrder() = runBlocking {
        val results = UltraShareMultiRecipientCoordinator.execute(targets(5)) { target ->
            "sent:" + target.sessionId
        }
        assertEquals(5, results.size)
        assertTrue(results.all { it.status == UltraShareMultiRecipientCoordinator.Status.COMPLETED })
        assertEquals(targets(5), results.map { it.target })
    }

    @Test
    fun neverExceedsConfiguredPeerConcurrency() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val results = UltraShareMultiRecipientCoordinator.execute(
            targets = targets(9),
            maxParallelPeers = 3
        ) { target ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            delay(20)
            active.decrementAndGet()
            target.sessionId
        }
        assertEquals(9, results.size)
        assertTrue(maximum.get() <= 3)
        assertTrue(maximum.get() > 1)
    }

    @Test
    fun failedPeerDoesNotPreventOtherRecipientsFromCompleting() = runBlocking {
        val results = UltraShareMultiRecipientCoordinator.execute(targets(4)) { target ->
            if (target.sessionId == "session-2") error("peer unavailable")
            target.sessionId
        }
        assertEquals(
            listOf(
                UltraShareMultiRecipientCoordinator.Status.COMPLETED,
                UltraShareMultiRecipientCoordinator.Status.FAILED,
                UltraShareMultiRecipientCoordinator.Status.COMPLETED,
                UltraShareMultiRecipientCoordinator.Status.COMPLETED
            ),
            results.map { it.status }
        )
        assertEquals("peer unavailable", results[1].error)
    }

    @Test
    fun rejectsDuplicateSessionOrHostBeforeSending() = runBlocking {
        val duplicateSession = listOf(
            UltraShareMultiRecipientCoordinator.Target("same", "192.168.1.2"),
            UltraShareMultiRecipientCoordinator.Target("same", "192.168.1.3")
        )
        val duplicateHost = listOf(
            UltraShareMultiRecipientCoordinator.Target("one", "192.168.1.2"),
            UltraShareMultiRecipientCoordinator.Target("two", "192.168.1.2")
        )
        assertFalse(runCatching {
            UltraShareMultiRecipientCoordinator.execute(duplicateSession) { Unit }
        }.isSuccess)
        assertFalse(runCatching {
            UltraShareMultiRecipientCoordinator.execute(duplicateHost) { Unit }
        }.isSuccess)
    }

    @Test
    fun parentCancellationIsNotConvertedIntoPeerFailure() = runBlocking {
        val job = launch {
            UltraShareMultiRecipientCoordinator.execute(targets(4)) {
                delay(5_000)
                it.sessionId
            }
        }
        delay(30)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun parallelismMustRemainWithinSafeBound() = runBlocking {
        assertFalse(runCatching {
            UltraShareMultiRecipientCoordinator.execute(
                targets = targets(1),
                maxParallelPeers = 5
            ) { Unit }
        }.isSuccess)
    }
}
