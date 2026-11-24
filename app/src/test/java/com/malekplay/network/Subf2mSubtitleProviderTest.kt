package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
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

    @Test fun parsesArabicEpisodeAndRejectsWrongEpisodeEvenIfCommentMentionsRequestedOne() {
        val html = """
            <html><head><title>Subtitles for Chernobyl - First Season</title></head><body>
            <h2>Chernobyl - First Season
              <a class="imdb" href="https://www.imdb.com/title/tt7366338">Imdb</a>
            </h2>
            <ul class="sublist">
              <li class="item">
                <div class="col-info"><ul class="scrolllist">
                  <li>Chernobyl.S01E02.1080p.WEB-DL</li>
                </ul></div>
                <div class="comment-col"><p>Actually intended for S01E03</p></div>
                <a class="download icon-download" href="/subtitles/chernobyl/arabic/3093577"></a>
              </li>
            </ul></body></html>
        """.trimIndent()
        val results = provider.parseSubtitlePage(
            html, "ar", "Chernobyl - First Season (2019)", 1, 3, "tt7366338"
        )
        assertTrue(results.isEmpty())
    }

    @Test fun parsesCorrectArabicEpisodeAndKeepsDirectDetailPage() {
        val html = """
            <html><head><title>Subtitles for Chernobyl - First Season</title></head><body>
            <h2>Chernobyl - First Season
              <a class="imdb" href="https://www.imdb.com/title/tt7366338">Imdb</a>
            </h2>
            <ul class="sublist">
              <li class="item">
                <div class="col-info"><ul class="scrolllist">
                  <li>Chernobyl.S01E03.1080p.WEB-DL</li>
                </ul></div>
                <div class="comment-col"><p>Arabic subtitle</p></div>
                <a class="download icon-download" href="/subtitles/chernobyl/arabic/3093578"></a>
              </li>
            </ul></body></html>
        """.trimIndent()
        val results = provider.parseSubtitlePage(
            html, "ar", "Chernobyl - First Season (2019)", 1, 3, "tt7366338"
        )
        assertEquals(1, results.size)
        assertEquals("ar", results.single().language)
        assertEquals("subf2m", results.single().provider)
        assertEquals("ar:3093578", results.single().sourceId)
        assertEquals("https://subf2m.co/subtitles/chernobyl/arabic/3093578", results.single().downloadUrl)
    }

    @Test fun rejectsDifferentImdbSeriesIdentity() {
        val html = """
            <html><head><title>Subtitles for Another Show</title></head><body>
            <a class="imdb" href="https://www.imdb.com/title/tt9999999">Imdb</a>
            </body></html>
        """.trimIndent()
        assertTrue(provider.parseSubtitlePage(
            html, "ar", "Chernobyl - First Season", 1, 3, "tt7366338"
        ).isEmpty())
    }
}
