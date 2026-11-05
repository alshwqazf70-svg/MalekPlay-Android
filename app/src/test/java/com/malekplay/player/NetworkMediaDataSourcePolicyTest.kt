package com.malekplay.player

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkMediaDataSourcePolicyTest {
    @Test
    fun rejectsPrematureEofAndInvalidReadCounts() {
        assertThrows(EOFException::class.java) {
            validateNetworkReadCount(-1, 1024)
        }
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(0, 1024)
        }
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(1025, 1024)
        }
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(1, 0)
        }
        assertEquals(512, validateNetworkReadCount(512, 1024))
    }

    @Test
    fun retriesZeroLengthReadsOnlyWithinBoundedAttempts() {
        var calls = 0
        val result = readNetworkChunk {
            calls++
            if (calls < 3) 0 else 128
        }
        assertEquals(128, result)
        assertEquals(3, calls)

        calls = 0
        assertThrows(IOException::class.java) {
            readNetworkChunk {
                calls++
                0
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun reconnectsOnceAfterIoFailureAndRetriesAtSameReadOperation() {
        var readCalls = 0
        var reconnectCalls = 0
        val result = readWithReconnect(
            read = {
                readCalls++
                if (readCalls == 1) throw IOException("temporary disconnect")
                256
            },
            reconnect = { reconnectCalls++ }
        )
        assertEquals(256, result)
        assertEquals(2, readCalls)
        assertEquals(1, reconnectCalls)
    }

    @Test
    fun doesNotReconnectWhenReadSucceedsInitially() {
        var reconnectCalls = 0
        val result = readWithReconnect(
            read = { 64 },
            reconnect = { reconnectCalls++ }
        )
        assertEquals(64, result)
        assertEquals(0, reconnectCalls)
    }
}
