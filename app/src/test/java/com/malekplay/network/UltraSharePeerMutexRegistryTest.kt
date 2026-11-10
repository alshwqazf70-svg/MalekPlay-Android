package com.malekplay.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraSharePeerMutexRegistryTest {
    @Test
    fun samePeerActionsAreSerializedEvenWithConcurrentWaiters() = runBlocking {
        val registry = UltraSharePeerMutexRegistry()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        coroutineScope {
            repeat(6) {
                async {
                    registry.withLock("stable-peer") {
                        val now = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, now) }
                        delay(15)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, maximum.get())
    }

    @Test
    fun idleEntriesAreEvictedWhenRetentionLimitIsExceeded() = runBlocking {
        val registry = UltraSharePeerMutexRegistry(maxRetainedEntries = 2)
        registry.withLock("peer-a") { Unit }
        registry.withLock("peer-b") { Unit }
        registry.withLock("peer-c") { Unit }
        assertTrue(registry.retainedEntryCount() <= 2)
    }

    @Test
    fun activeEntryIsNotEvictedWhileAnotherCoroutineWaitsForIt() = runBlocking {
        val registry = UltraSharePeerMutexRegistry(maxRetainedEntries = 1)
        coroutineScope {
            val first = async {
                registry.withLock("active-peer") { delay(50) }
            }
            delay(5)
            val second = async {
                registry.withLock("active-peer") { Unit }
            }
            registry.withLock("another-peer") { Unit }
            first.await()
            second.await()
        }
        assertTrue(registry.retainedEntryCount() <= 1)
    }
}
