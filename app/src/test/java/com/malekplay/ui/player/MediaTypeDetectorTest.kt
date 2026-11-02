package com.malekplay.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeDetectorTest {
    @Test fun mimeTypeTakesPriorityOverExtension() {
        assertEquals(false, MediaTypeDetector.isVideo("audio/mpeg", "movie.mp4", true))
        assertEquals(true, MediaTypeDetector.isVideo("video/mp4", "song.mp3", false))
    }

    @Test fun recognizesCommonVideoExtensions() {
        assertEquals(true, MediaTypeDetector.isVideo(null, "episode.mkv", false))
        assertEquals(true, MediaTypeDetector.isVideo(null, "movie.mp4?token=abc", false))
    }

    @Test fun recognizesCommonAudioExtensions() {
        assertEquals(false, MediaTypeDetector.isVideo(null, "track.flac", true))
    }

    @Test fun unknownTypeUsesFallback() {
        assertEquals(true, MediaTypeDetector.isVideo("application/octet-stream", "file.bin", true))
    }
}
