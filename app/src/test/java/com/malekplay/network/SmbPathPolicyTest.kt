package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathPolicyTest {
    @Test
    fun stripsShareNameFromRootAndNestedBrowserPaths() {
        assertEquals("media" to "", SmbPathPolicy.resolve("media/videos", "media"))
        assertEquals("media" to "videos", SmbPathPolicy.resolve("media/videos", "media/videos"))
        assertEquals(
            "media" to "videos/season one",
            SmbPathPolicy.resolve("media/videos", "media/videos/season one")
        )
    }

    @Test
    fun rejectsOtherSharesAndTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("media/videos", "private/videos")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("media/videos", "media/videos/../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbPathPolicy.resolve("/", "media/videos")
        }
    }
}
