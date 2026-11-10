package com.malekplay.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NewMediaCountPolicyTest {

    @Test
    fun countsOnlyNewUnseenItemsPerFolder() {
        val baseline = 1_000L
        val records = sequenceOf(
            NewMediaRecord("/Movies", "old-movie", 999L),
            NewMediaRecord("/Movies", "new-movie-1", 1_000L),
            NewMediaRecord("/Movies", "new-movie-2", 1_001L),
            NewMediaRecord("/Music", "new-song", 1_002L)
        )

        assertEquals(
            mapOf("/Movies" to 2, "/Music" to 1),
            unseenNewMediaCountsByFolder(records, baseline, emptySet())
        )
    }

    @Test
    fun playbackAcknowledgementDecrementsOnlyThatFolderCount() {
        val records = sequenceOf(
            NewMediaRecord("/Movies", "movie-1", 1_000L),
            NewMediaRecord("/Movies", "movie-2", 1_001L),
            NewMediaRecord("/Music", "song-1", 1_002L)
        )

        assertEquals(
            mapOf("/Movies" to 1, "/Music" to 1),
            unseenNewMediaCountsByFolder(records, 1_000L, setOf("movie-1"))
        )
    }

    @Test
    fun missingBaselineDoesNotMarkExistingLibraryAsNew() {
        val records = sequenceOf(
            NewMediaRecord("/Movies", "movie-1", 1_000L),
            NewMediaRecord("/Music", "song-1", 1_002L)
        )

        assertEquals(
            emptyMap<String, Int>(),
            unseenNewMediaCountsByFolder(records, 0L, emptySet())
        )
    }

    @Test
    fun emptyFoldersAreNotAddedToTheBadgeMap() {
        assertEquals(
            emptyMap<String, Int>(),
            unseenNewMediaCountsByFolder(emptySequence(), 1_000L, emptySet())
        )
    }
}
