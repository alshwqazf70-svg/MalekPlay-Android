package com.malekplay.network

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TvMazeEpisodeGuideParserTest {
    @Test
    fun selectsOnlyExactTitleAndRequestedYear() {
        val results = JSONArray(
            """[
              {"score":1.0,"show":{"id":1,"name":"The Office","premiered":"2001-03-24"}},
              {"score":0.9,"show":{"id":2,"name":"The Office","premiered":"2005-03-24"}}
            ]"""
        )
        assertEquals(2L, TvMazeEpisodeGuideParser.selectExactShow(results, "The Office", 2005)?.id)
        assertNull(TvMazeEpisodeGuideParser.selectExactShow(results, "The Office", 1999))
    }

    @Test
    fun refusesAmbiguousSameNameShowsWhenYearIsNotProvided() {
        val results = JSONArray(
            """[
              {"show":{"id":1,"name":"The Office","premiered":"2001-03-24"}},
              {"show":{"id":2,"name":"The Office","premiered":"2005-03-24"}}
            ]"""
        )
        assertNull(TvMazeEpisodeGuideParser.selectExactShow(results, "The Office", null))
    }

    @Test
    fun doesNotAcceptFuzzyNearMatchesAsTheRequestedShow() {
        val results = JSONArray(
            """[{"show":{"id":10,"name":"The Office Space","premiered":"2005-01-01"}}]"""
        )
        assertNull(TvMazeEpisodeGuideParser.selectExactShow(results, "The Office", 2005))
    }

    @Test
    fun groupsSortedEpisodesAndExcludesSpecialsAndInvalidNumbers() {
        val show = TvMazeShowIdentity(1, "Example Show", 2020)
        val episodes = JSONArray(
            """[
              {"season":2,"number":3},
              {"season":1,"number":2},
              {"season":0,"number":1},
              {"season":1,"number":1},
              {"season":2,"number":3},
              {"season":1,"number":0},
              {"season":-1,"number":5}
            ]"""
        )
        val guide = TvMazeEpisodeGuideParser.parseEpisodes(show, episodes)
        assertEquals(listOf(1, 2), guide?.seasons?.map { it.number })
        assertEquals(listOf(1, 2), guide?.seasons?.first()?.episodes)
        assertEquals(listOf(3), guide?.seasons?.last()?.episodes)
    }

    @Test
    fun doesNotCreateAnEmptyGuideWhenNoRegularEpisodesExist() {
        val show = TvMazeShowIdentity(1, "Example Show", 2020)
        assertNull(TvMazeEpisodeGuideParser.parseEpisodes(
            show, JSONArray("""[{"season":0,"number":1}]""")
        ))
    }
}
