package com.malekplay.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream

class MediaArtworkLoaderTest {

    @Test
    fun artworkSamplingBoundsTheLongestEdgeForPortraitAndLandscapeImages() {
        assertEquals(16, calculateArtworkSampleSize(6000, 400, 320))
        assertEquals(16, calculateArtworkSampleSize(400, 6000, 320))
        assertEquals(8, calculateArtworkSampleSize(3000, 3000, 320))
    }

    @Test
    fun artworkSamplingHandlesInvalidDimensionsSafely() {
        assertEquals(1, calculateArtworkSampleSize(0, 6000, 320))
        assertEquals(1, calculateArtworkSampleSize(6000, -1, 320))
        assertEquals(1, calculateArtworkSampleSize(6000, 400, 0))
    }

    @Test
    fun boundedReaderMakesProgressWhenProviderReturnsZero() {
        val source = object : InputStream() {
            private val data = byteArrayOf(1, 2, 3)
            private var first = true
            private var position = 0

            override fun read(): Int =
                if (position >= data.size) -1 else data[position++].toInt() and 0xff

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    return 0
                }
                if (position >= data.size) return -1
                val count = minOf(length, data.size - position)
                data.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), source.readLimitedBytes(16))
    }

    @Test
    fun boundedReaderRejectsOversizedArtwork() {
        val source = java.io.ByteArrayInputStream(ByteArray(17))
        assertThrows(IllegalArgumentException::class.java) {
            source.readLimitedBytes(16)
        }
    }
}
