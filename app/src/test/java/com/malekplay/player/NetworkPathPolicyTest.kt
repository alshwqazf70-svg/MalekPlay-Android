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
}
