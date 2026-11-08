package com.malekplay.player

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubtitleResolverTest {

    @Test
    fun exactBasenameIsAccepted() {
        assertNotNull(LocalSubtitleResolver.matchingScore("Movie.mp4", "Movie.srt"))
    }

    @Test
    fun languageSuffixIsAccepted() {
        assertNotNull(LocalSubtitleResolver.matchingScore("Movie.mkv", "Movie.ar.srt"))
        assertNotNull(LocalSubtitleResolver.matchingScore("Movie.mkv", "Movie.en.ass"))
    }

    @Test
    fun unrelatedPrefixIsRejected() {
        assertNull(LocalSubtitleResolver.matchingScore("Movie.mp4", "Movie2.srt"))
        assertNull(LocalSubtitleResolver.matchingScore("Movie.mp4", "OtherMovie.srt"))
    }

    @Test
    fun unsupportedSubtitleExtensionIsRejected() {
        assertNull(LocalSubtitleResolver.matchingScore("Movie.mp4", "Movie.txt"))
    }
}
