package com.malekplay.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MalekPlayBackupManagerTest {

    private fun validBackup() = JSONObject()
        .put("favourites", JSONArray().put("content://media/1"))
        .put("playlists", JSONArray().put(
            JSONObject().put("id", "watch_later").put("name", "Watch later")
                .put("mediaIds", JSONArray().put("media-1"))
        ))
        .put("settings", JSONObject())

    @Test
    fun acceptsACompleteWellFormedBackup() {
        val result = validateBackupCollections(validBackup())
        assertEquals(1, result.favourites.length())
        assertEquals(1, result.playlists.length())
        assertEquals("Watch later", result.playlists.getJSONObject(0).getString("name"))
    }

    @Test
    fun rejectsMissingCollectionsInsteadOfTreatingThemAsEmpty() {
        val backup = JSONObject().put("settings", JSONObject())
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupCollections(backup)
        }
    }

    @Test
    fun rejectsMalformedFavouriteEntries() {
        val backup = validBackup().put("favourites", JSONArray().put(JSONObject()))
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupCollections(backup)
        }
    }

    @Test
    fun rejectsMalformedPlaylistEntriesAndMissingMediaLists() {
        val backup = validBackup().put(
            "playlists",
            JSONArray().put(JSONObject().put("name", "Broken"))
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupCollections(backup)
        }
    }

    @Test
    fun rejectsDuplicateStablePlaylistIds() {
        val playlist = JSONObject().put("id", "same").put("name", "List")
            .put("mediaIds", JSONArray())
        val backup = validBackup().put(
            "playlists",
            JSONArray().put(playlist).put(JSONObject(playlist.toString()))
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupCollections(backup)
        }
    }
}
