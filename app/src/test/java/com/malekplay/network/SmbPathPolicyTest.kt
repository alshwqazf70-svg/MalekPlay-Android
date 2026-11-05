package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathPolicyTest {

    @Test
    fun rootPathIsConvertedToShareRelativePath() {
        assertEquals("media" to "videos", SmbPathPolicy.resolve("media/videos", "media/videos"))
    }

    @Test
    fun nestedBrowserPathDoesNotRepeatShareName() {
        assertEquals("media" to "videos/films", SmbPathPolicy.resolve("media/videos", "media/videos/films"))
    }

    @Test
    fun emptyRequestedPathUsesConfiguredRoot() {
        assertEquals("media" to "videos", SmbPathPolicy.resolve("media/videos", ""))
    }

    @Test
    fun supportsShareRootWithoutRelativeDirectory() {
        assertEquals("media" to "", SmbPathPolicy.resolve("media", "media"))
    }

    @Test
    fun rejectsTraversalAndControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("media", "media/../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("media", "media/folder\u0000/file")
        }
    }

    @Test
    fun rejectsMissingShare() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("/", "")
        }
    }
}
