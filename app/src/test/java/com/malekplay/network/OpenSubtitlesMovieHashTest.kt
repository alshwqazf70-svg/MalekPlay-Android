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
            assertEquals("bf7b30eba75ef876", first)
            assertEquals(first, OpenSubtitlesMovieHash.calculate(file))
            file.inputStream().use { input ->
                assertEquals(first, OpenSubtitlesMovieHash.calculate(input, file.length()))
            }
        } finally { file.delete() }
    }
}
