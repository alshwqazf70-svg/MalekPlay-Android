package com.malekplay.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.malekplay.data.local.MalekPlayDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {

    @Test
    fun restorePreservesPlaylistIdsAndItemOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            MalekPlayDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = PlaylistRepository(database.playlistDao())
            repository.restoreAll(
                listOf(
                    PlaylistRestoreEntry(
                        playlistId = "stable-playlist-id",
                        name = "Watch later",
                        mediaIds = listOf("media-1", "media-2", "media-1")
                    )
                )
            )

            val restored = repository.snapshotPlaylists().single()
            assertEquals("stable-playlist-id", restored.playlist.playlistId)
            assertEquals("Watch later", restored.playlist.name)
            assertEquals(listOf("media-1", "media-2"), restored.items.sortedBy { it.position }.map { it.mediaId })
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingPlaylistCascadesToItsItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            MalekPlayDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = PlaylistRepository(database.playlistDao())
            repository.restoreAll(
                listOf(PlaylistRestoreEntry("delete-me", "Temporary", listOf("media-1")))
            )
            repository.delete("delete-me")
            assertEquals(emptyList<com.malekplay.data.local.model.PlaylistWithItems>(), repository.snapshotPlaylists())
        } finally {
            database.close()
        }
    }
}
