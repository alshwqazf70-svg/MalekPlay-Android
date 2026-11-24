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

    @Test fun parsesLiveSearchResultTitleAnchorStructure() {
        val html = """
            <ul>
              <li><div class="title">
                <a href="/subtitles/chernobyl-first-season">Chernobyl - First Season (2019)</a>
              </div></li>
              <li><div class="title">
                <a href="/subtitles/another-show">Another Show (2020)</a>
              </div></li>
            </ul>
        """.trimIndent()
        val results = provider.parseSearchResultCandidates(
            html, "Chernobyl", 2019, 1
        )
        assertEquals(1, results.size)
        assertEquals("/subtitles/chernobyl-first-season", results.single().first)
    }

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

    @Test fun rejectsMultiEpisodeRangesEvenWhenTheRequestedEpisodeIsTheFirstOne() {
        assertFalse(provider.isExactEpisode("Show.S01E02-E03.1080p", 1, 2))
        assertFalse(provider.isExactEpisode("Show.S01E02-03.1080p", 1, 2))
        assertFalse(provider.isExactEpisode("Show.1x02-03.720p", 1, 2))
        assertFalse(provider.isExactEpisode("Show.Season.1.Episodes.2-3", 1, 2))
        assertFalse(provider.isExactEpisode("Show.S01E02-E03.1080p", 1, 3))
        assertFalse(provider.isExactEpisode("Show.1x02-03.720p", 1, 3))
    }

    @Test fun parsesRealSubF2mSearchResultMarkupAndSeasonOrdinal() {
        val html = """
            <ul>
              <li><div class="title">
                <a href="/subtitles/chernobyl-first-season">Chernobyl - First Season (2019)</a>
              </div></li>
            </ul>
        """.trimIndent()
        val matches = provider.parseSearchResultCandidates(
            html, "Chernobyl", 2019, 1
        )
        assertEquals(1, matches.size)
        assertEquals("/subtitles/chernobyl-first-season", matches.single().first)
    }

    @Test fun rejectsDifferentYearForTvSearchWhenSiteProvidesYear() {
        val html = """
            <ul>
              <li><div class="title">
                <a href="/subtitles/show-first-season">Show - First Season (2018)</a>
              </div></li>
            </ul>
        """.trimIndent()
        assertTrue(provider.parseSearchResultCandidates(
            html, "Show", 2020, 1
        ).isEmpty())
    }

    @Test fun rejectsWrongSeasonSearchResult() {
        val html = """
            <ul>
              <li><div class="title">
                <a href="/subtitles/show-second-season">Show - Second Season</a>
              </div></li>
            </ul>
        """.trimIndent()
        assertTrue(provider.parseSearchResultCandidates(html, "Show", null, 1).isEmpty())
    }

    @Test fun resolvesActualSubF2mDownloadContainerLinkSafely() {
        val html = """
            <div class="download">
              <a href="/download/subtitle/3093578">Download</a>
            </div>
        """.trimIndent()
        assertEquals(
            "https://subf2m.co/download/subtitle/3093578",
            provider.resolveDownloadPageLink(
                html, "https://subf2m.co/subtitles/chernobyl/arabic/3093578"
            )
        )
        val hostile = """<div class="download"><a href="https://evil.example/file.zip">Download</a></div>"""
        assertEquals(null, provider.resolveDownloadPageLink(
            hostile, "https://subf2m.co/subtitles/chernobyl/arabic/3093578"
        ))
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

    @Test fun rejectsMismatchedPageTitleWhenImdbIdentityIsUnavailable() {
        val html = """
            <html><head><title>Subtitles for Another Show - First Season</title></head><body>
              <ul class="sublist">
                <li class="item">
                  <ul class="scrolllist"><li>Another.Show.S01E03.1080p</li></ul>
                  <a class="download" href="/subtitles/another-show/arabic/3093579"></a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        assertTrue(provider.parseSubtitlePage(
            html, "ar", "Chernobyl - First Season", 1, 3, null
        ).isEmpty())
    }

    @Test fun matchingImdbIdentityCanConfirmAlternateTitle() {
        val html = """
            <html><head><title>Subtitles for Chernobyl - First Season</title></head><body>
              <a class="imdb" href="https://www.imdb.com/title/tt7366338">Imdb</a>
              <ul class="sublist">
                <li class="item">
                  <ul class="scrolllist"><li>Show.S01E03.1080p</li></ul>
                  <a class="download" href="/subtitles/another-title/arabic/3093580"></a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        assertEquals(1, provider.parseSubtitlePage(
            html, "ar", "Another Title", 1, 3, "tt7366338"
        ).size)
    }

    @Test fun resolvesCurrentDirectDownloadAnchorAndRelativeDownloadHref() {
        val detail = "https://subf2m.co/subtitles/example/arabic/12345"
        val currentLayout = """<a id="downloadButton" href="/subtitles/example/arabic/12345/download">Download</a>"""
        assertEquals(
            "https://subf2m.co/subtitles/example/arabic/12345/download",
            provider.resolveDownloadPageLink(currentLayout, detail)
        )
        val legacyLayout = """<div class="download"><a href="/subtitles/example/arabic/12345/download">Download</a></div>"""
        assertEquals(
            "https://subf2m.co/subtitles/example/arabic/12345/download",
            provider.resolveDownloadPageLink(legacyLayout, detail)
        )
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
