package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Subf2mSubtitleProviderTest {
    private val provider = Subf2mSubtitleProvider()

    @Test fun acceptsExactSeasonEpisodeReleaseNames() {
        assertTrue(provider.isExactEpisode("Show.S01E03.1080p.WEB-DL", 1, 3))
        assertTrue(provider.isExactEpisode("Show.1x03.720p", 1, 3))
        assertTrue(provider.isExactEpisode("Show Season 1 - 3 HDTV", 1, 3))
    }

    @Test fun rejectsAdjacentEpisodesAndSeasonOnlyPacks() {
        assertFalse(provider.isExactEpisode("Show.S01E02.1080p", 1, 3))
        assertFalse(provider.isExactEpisode("Show.S02E03.1080p", 1, 3))
        assertFalse(provider.isExactEpisode("Show.S01.Complete.Season", 1, 3))
        assertFalse(provider.isExactEpisode("Show.S01E030.1080p", 1, 3))
    }
}
