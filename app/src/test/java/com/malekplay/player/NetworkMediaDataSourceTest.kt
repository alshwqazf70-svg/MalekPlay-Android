package com.malekplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.malekplay.network.NetworkProtocol
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
    fun retriesOneFailedRemoteReadAfterReconnection() {
        var attempts = 0
        var reconnects = 0
        val count = readWithReconnect(
            read = {
                attempts++
                if (attempts == 1) throw IOException("connection dropped")
                512
            },
            reconnect = { reconnects++ }
        )
        assertEquals(512, count)
        assertEquals(2, attempts)
        assertEquals(1, reconnects)
    }

    @Test
    fun propagatesFailureWhenReconnectAlsoFails() {
        var reconnects = 0
        assertThrows(IOException::class.java) {
            readWithReconnect(
                read = { throw IOException("connection dropped") },
                reconnect = {
                    reconnects++
                    throw IOException("server unavailable")
                }
            )
        }
        assertEquals(1, reconnects)
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

    @Test
    fun acceptsEndOfInputOnlyForUnknownLengthAtCallerBoundary() {
        // The helper rejects premature EOF for known-length streams; the
        // Media3 end marker remains represented by -1 for unknown-length reads.
        assertEquals(-1, readNetworkChunk { -1 })
    }

    @Test
    fun rejectsZeroAsAValidatedReadCount() {
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(0, requested = 1024)
        }
    }

    @Test
    fun rejectsNegativeRequestedReadLengthsThroughValidationContract() {
        assertThrows(IOException::class.java) {
            validateNetworkReadCount(1, requested = -1)
        }
    }

    @Test
    fun doesNotAttemptASecondReconnectAfterRetryReadFails() {
        var reads = 0
        var reconnects = 0
        assertThrows(IOException::class.java) {
            readWithReconnect(
                read = {
                    reads++
                    throw IOException("remote server still unavailable")
                },
                reconnect = { reconnects++ }
            )
        }
        assertEquals(2, reads)
        assertEquals(1, reconnects)
    }

    @Test
    fun mapsOnlySupportedPrivatePlaybackSchemes() {
        assertEquals(NetworkProtocol.SMB, networkProtocolForScheme("maleksmb"))
        assertEquals(NetworkProtocol.SFTP, networkProtocolForScheme("maleksftp"))
        assertEquals(NetworkProtocol.SMB, networkProtocolForScheme("MALEKSMB"))
    }

    @Test
    fun rejectsUnknownOrMissingPlaybackSchemes() {
        assertThrows(IOException::class.java) { networkProtocolForScheme("https") }
        assertThrows(IOException::class.java) { networkProtocolForScheme(null) }
    }

    @Test
    fun acceptsZeroLengthRemoteFiles() {
        assertEquals(0L, validateRemoteFileLength(0L))
    }

    @Test
    fun rejectsUnknownOrNegativeRemoteFileLengths() {
        assertThrows(IOException::class.java) { validateRemoteFileLength(-1L) }
    }
}
