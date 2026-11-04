package com.malekplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class NetworkMediaDataSourceTest {

    @Test
    fun retriesTransientZeroReadsUntilDataArrives() {
        var calls = 0
        val result = readNetworkChunk {
            calls++
            if (calls < 3) 0 else 128
        }
        assertEquals(128, result)
        assertEquals(3, calls)
    }

    @Test
    fun failsAfterRepeatedZeroReadsInsteadOfReturningNoProgress() {
        var calls = 0
        assertThrows(IOException::class.java) {
            readNetworkChunk {
                calls++
                0
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun preservesEndOfInputResult() {
        assertEquals(-1, readNetworkChunk { -1 })
    }

    @Test
    fun rejectsPrematureEndOfInputForKnownLengthFiles() {
        assertThrows(EOFException::class.java) {
            validateNetworkReadCount(-1, requested = 4096)
        }
    }

    @Test
    fun rejectsReadsLargerThanRequestedBuffer() {
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(4097, requested = 4096)
        }
    }

    @Test
    fun acceptsValidPartialReads() {
        assertEquals(1024, validateNetworkReadCount(1024, requested = 4096))
    }
}
