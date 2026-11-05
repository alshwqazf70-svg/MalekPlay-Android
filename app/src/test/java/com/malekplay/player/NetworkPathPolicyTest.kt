package com.malekplay.player

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NetworkPathPolicyTest {
    @Test
    fun acceptsNormalNestedPathSegments() {
        NetworkPathPolicy.validateSegments(listOf("media", "films", "movie.mkv"))
    }

    @Test
    fun rejectsDotTraversalSegments() {
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "..", "secret.mkv")) }
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf(".")) }
    }

    @Test
    fun rejectsLiteralSeparatorsInsideDecodedSegment() {
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "../secret.mkv")) }
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "folder\\secret.mkv")) }
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "folder/secret.mkv")) }
    }

    @Test
    fun rejectsPercentEncodedSeparatorsAfterUriDecoding() {
        val decoded = Uri.parse("maleksmb://net_test/media/%2e%2e%2fsecret.mkv").pathSegments
        assertEquals("../secret.mkv", decoded.last())
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(decoded) }
    }

    @Test
    fun rejectsControlCharactersAndEmptySegments() {
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "bad\u0000name.mkv")) }
        assertThrows(IOException::class.java) { NetworkPathPolicy.validateSegments(listOf("media", "")) }
    }

    @Test
    fun acceptsOnlyCredentialFreeInternalPlaybackUris() {
        val uri = Uri.parse("maleksmb://net_test_123/media/movie.mkv")
        assertEquals("net_test_123", validateNetworkPlaybackUri(uri))
    }

    @Test
    fun rejectsCredentialsPortsQueriesAndFragmentsInPlaybackUris() {
        listOf(
            "maleksmb://user@net_test/media/movie.mkv",
            "maleksmb://net_test:445/media/movie.mkv",
            "maleksmb://net_test/media/movie.mkv?token=secret",
            "maleksmb://net_test/media/movie.mkv#fragment",
            "maleksmb://bad%20id/media/movie.mkv"
        ).forEach { raw ->
            assertThrows(IOException::class.java) {
                validateNetworkPlaybackUri(Uri.parse(raw))
            }
        }
    }

    @Test
    fun rejectsOverlongSegmentsAndExcessivePathDepth() {
        assertThrows(IOException::class.java) {
            NetworkPathPolicy.validateSegments(listOf("a".repeat(NetworkPathPolicy.MAX_SEGMENT_LENGTH + 1)))
        }
        assertThrows(IOException::class.java) {
            NetworkPathPolicy.validateSegments(List(NetworkPathPolicy.MAX_SEGMENTS + 1) { "x" })
        }
    }

    @Test
    fun rejectsPathsOverMaximumTotalLength() {
        assertThrows(IOException::class.java) {
            NetworkPathPolicy.validateSegments(List(17) { "x".repeat(255) })
        }
    }
}
