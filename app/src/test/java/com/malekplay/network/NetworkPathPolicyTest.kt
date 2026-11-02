package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkPathPolicyTest {
    @Test fun normalizesWindowsSeparatorsForSmb() {
        assertEquals("media\\videos", NetworkPathPolicy.normalize("media/videos", '\\', false))
    }

    @Test fun preservesAbsoluteSftpPaths() {
        assertEquals("/home/user/media", NetworkPathPolicy.normalize("/home/user/media"))
    }

    @Test fun collapsesRepeatedSeparators() {
        assertEquals("media/videos", NetworkPathPolicy.normalize("//media///videos/"))
    }

    @Test fun rejectsParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPathPolicy.normalize("media/../private")
        }
    }

    @Test fun rejectsCurrentDirectorySegments() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPathPolicy.normalize("media/./videos")
        }
    }
}
