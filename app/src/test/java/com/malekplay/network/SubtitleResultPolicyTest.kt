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

    @Test fun cacheEvictsLeastRecentlyUsedEntry() {
        val cache = SubtitleSearchResultCache(2)
        cache.put("one", listOf(item("ar", "1")))
        cache.put("two", listOf(item("ar", "2")))
        cache.get("one")
        cache.put("three", listOf(item("ar", "3")))
        assertNotNull(cache.get("one"))
        assertNull(cache.get("two"))
    }
}
