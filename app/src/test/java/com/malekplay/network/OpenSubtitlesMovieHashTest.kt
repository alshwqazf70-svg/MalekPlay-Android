package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OpenSubtitlesMovieHashTest {
    @Test fun hashIsStableAndSixteenHexCharacters() {
        val file = File.createTempFile("subtitle-hash", ".bin")
        try {
            file.writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
            val first = OpenSubtitlesMovieHash.calculate(file)
            assertEquals(16, first.length)
            assertEquals(first, OpenSubtitlesMovieHash.calculate(file))
        } finally { file.delete() }
    }
}
