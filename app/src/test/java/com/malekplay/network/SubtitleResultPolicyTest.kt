package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleResultPolicyTest {
    private fun item(language: String, id: String, downloads: Int = 0) = OnlineSubtitle(
        fileId = 0L, language = language, release = id, downloads = downloads,
        format = "srt", sourceId = id, provider = "test"
    )

    @Test fun ArabicRanksFirstAndLanguageFiltersAreLocal() {
        val results = listOf(item("en", "english", 100), item("ar", "arabic", 1))
        assertEquals(listOf("arabic", "english"),
            SubtitleResultPolicy.rankAndFilter(results, "ar,en").map { it.release })
        assertEquals(listOf("english"),
            SubtitleResultPolicy.rankAndFilter(results, "en").map { it.release })
    }

    @Test fun cacheReusesCombinedResultsAcrossLanguageFilters() {
        val cache = SubtitleSearchResultCache()
        cache.put("show s01e01", listOf(item("ar", "ar"), item("en", "en")))
        val cached = cache.get("show s01e01")
        assertNotNull(cached)
        assertEquals(listOf("ar"), SubtitleResultPolicy.rankAndFilter(cached!!, "ar").map { it.language })
        assertEquals(listOf("en"), SubtitleResultPolicy.rankAndFilter(cached, "en").map { it.language })
        assertNull(cache.get("another query"))
    }

    @Test fun combinesAllTitleCandidatesBeforeApplyingArabicFilter() {
        val englishExact = item("en", "Avatar S01E02 English")
        val arabicFallback = item("ar", "Avatar The Last Airbender S01E02 Arabic")
        val combined = SubtitleResultPolicy.combineCandidates(
            listOf(listOf(englishExact), listOf(arabicFallback))
        )
        assertEquals(
            listOf("Avatar The Last Airbender S01E02 Arabic"),
            SubtitleResultPolicy.rankAndFilter(combined, "ar", 1, 2).map { it.release }
        )
    }

    @Test fun emptySearchResultsAreNotCachedAndCanBeRetried() {
        val cache = SubtitleSearchResultCache()
        cache.put("avatar s01e02", emptyList())
        assertNull(cache.get("avatar s01e02"))
        cache.put("avatar s01e02", listOf(item("ar", "arabic")))
        assertEquals(1, cache.get("avatar s01e02")?.size)
    }

    @Test
    fun episodeSearchFallsBackToTitleWhenProviderReturnsNoEpisodeMatches() {
        var scopedCalls = 0
        var titleCalls = 0
        val results = SubtitleResultPolicy.withEpisodeFallback(
            season = 1,
            episode = 2,
            episodeSearch = { scopedCalls++; emptyList() },
            titleSearch = { titleCalls++; listOf(item("ar", "Show S01E02 Arabic")) }
        )
        assertEquals(1, scopedCalls)
        assertEquals(1, titleCalls)
        assertEquals(listOf("Show S01E02 Arabic"), results.map { it.release })
    }

    @Test
    fun episodeSearchDoesNotRunBroadFallbackWhenSpecificResultsExist() {
        var titleCalls = 0
        val results = SubtitleResultPolicy.withEpisodeFallback(
            season = 1,
            episode = 2,
            episodeSearch = { listOf(item("ar", "Show S01E02 Arabic")) },
            titleSearch = { titleCalls++; emptyList() }
        )
        assertEquals(0, titleCalls)
        assertEquals(1, results.size)
    }

    @Test
    fun titleSearchWithoutEpisodeScopeDoesNotRepeatAnEmptyRequest() {
        var titleCalls = 0
        val results = SubtitleResultPolicy.withEpisodeFallback(
            season = null,
            episode = null,
            episodeSearch = { emptyList() },
            titleSearch = { titleCalls++; listOf(item("ar", "Show Arabic")) }
        )
        assertEquals(0, titleCalls)
        assertEquals(0, results.size)
    }

    @Test fun providerEpisodeMetadataRejectsWrongSeasonAndEpisodeEvenWithoutReleaseTags() {
        val exact = item("ar", "plain exact").copy(providerSeasonNumber = 1, providerEpisodeNumber = 3)
        val wrongEpisode = item("ar", "wrong episode").copy(providerSeasonNumber = 1, providerEpisodeNumber = 4)
        val wrongSeason = item("ar", "wrong season").copy(providerSeasonNumber = 2, providerEpisodeNumber = 3)
        assertEquals(listOf("plain exact"), SubtitleResultPolicy.rankAndFilter(
            listOf(wrongEpisode, wrongSeason, exact), "ar", 1, 3).map { it.release })
    }

    @Test fun wholeSeasonPacksAreNotPresentedAsSingleEpisodeSubtitles() {
        val pack = item("ar", "Show Season 1").copy(providerSeasonNumber = 1, fullSeasonPack = true)
        assertEquals(emptyList<String>(), SubtitleResultPolicy.rankAndFilter(
            listOf(pack), "ar", 1, 3).map { it.release })
    }

    @Test fun providerConfirmedEpisodeRanksAheadOfUnnumberedFallback() {
        val fallback = item("ar", "Show Arabic")
        val exact = item("en", "Generic release").copy(providerSeasonNumber = 1, providerEpisodeNumber = 3)
        assertEquals(listOf("Generic release", "Show Arabic"), SubtitleResultPolicy.rankAndFilter(
            listOf(fallback, exact), "ar,en", 1, 3).map { it.release })
    }

    @Test fun cacheEvictsLeastRecentlyUsedEntry() {
        val cache = SubtitleSearchResultCache(2)
        cache.put("one", listOf(item("ar", "1")))
        cache.put("two", listOf(item("ar", "2")))
        cache.get("one")
        cache.put("three", listOf(item("ar", "3")))
        assertNotNull(cache.get("one"))
        assertNull(cache.get("two"))
    }

    @Test
    fun ranksMatchingSeasonAndEpisodeBeforeGenericReleases() {
        val results = listOf(
            item("ar", "Show S01E03 Arabic", 900),
            item("en", "Show S01E02 English", 100),
            item("ar", "Show S01E02 Arabic", 10)
        )
        assertEquals(
            listOf("Show S01E02 Arabic", "Show S01E02 English", "Show S01E03 Arabic"),
            SubtitleResultPolicy.rankAndFilter(results, "ar,en", 1, 2).map { it.release }
        )
    }

    @Test
    fun episodeSearchAugmentsEnglishOnlyHitsWithBroaderArabicResults() {
        val results = SubtitleResultPolicy.withEpisodeFallback(
            season = 1,
            episode = 3,
            episodeSearch = { listOf(item("en", "Show S01E03 English")) },
            titleSearch = { listOf(item("ar", "Show The Last Airbender Arabic")) },
            shouldFallback = { scoped ->
                !scoped.map { SubtitleQueryCleaner.normalizeLanguage(it.language) }
                    .toSet().containsAll(setOf("ar", "en"))
            }
        )
        assertEquals(
            listOf("Show S01E03 English", "Show The Last Airbender Arabic"),
            results.map { it.release }
        )
        assertEquals(
            listOf("Show The Last Airbender Arabic"),
            SubtitleResultPolicy.rankAndFilter(results, "ar", 1, 3).map { it.release }
        )
    }

    @Test
    fun completeBilingualEpisodeResultsDoNotTriggerBroadFallback() {
        var broadCalls = 0
        val results = SubtitleResultPolicy.withEpisodeFallback(
            season = 1,
            episode = 3,
            episodeSearch = { listOf(item("ar", "Arabic"), item("en", "English")) },
            titleSearch = { broadCalls++; emptyList() },
            shouldFallback = { scoped ->
                !scoped.map { SubtitleQueryCleaner.normalizeLanguage(it.language) }
                    .toSet().containsAll(setOf("ar", "en"))
            }
        )
        assertEquals(0, broadCalls)
        assertEquals(2, results.size)
    }

    @Test
    fun excludesExplicitlyWrongEpisodeButKeepsUnnumberedArabicFallback() {
        val results = listOf(
            item("ar", "Avatar S01E02 Arabic"),
            item("ar", "Avatar S01E03 Arabic"),
            item("ar", "Avatar The Last Airbender Arabic")
        )
        assertEquals(
            listOf("Avatar S01E03 Arabic", "Avatar The Last Airbender Arabic"),
            SubtitleResultPolicy.rankAndFilter(results, "ar", 1, 3).map { it.release }
        )
    }

    @Test
    fun excludesWrongSeasonEvenWhenEpisodeNumberMatches() {
        val results = listOf(
            item("ar", "Avatar S02E03 Arabic"),
            item("ar", "Avatar 1x03 Arabic")
        )
        assertEquals(listOf("Avatar 1x03 Arabic"),
            SubtitleResultPolicy.rankAndFilter(results, "ar", 1, 3).map { it.release })
    }

}
