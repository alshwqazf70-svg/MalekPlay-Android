package com.malekplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.malekplay.data.local.entity.PlaylistEntity
import com.malekplay.data.local.entity.PlaylistItemEntity
import com.malekplay.data.local.model.PlaylistWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Upsert
    suspend fun upsertItems(items: List<PlaylistItemEntity>)

    @Upsert
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlist_items")
    suspend fun clearAllItems()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    /** Replaces playlist data atomically so repeated restores cannot duplicate lists. */
    @Transaction
    suspend fun replaceAll(playlists: List<PlaylistEntity>, items: List<PlaylistItemEntity>) {
        clearAllItems()
        clearAllPlaylists()
        if (playlists.isNotEmpty()) upsertPlaylists(playlists)
        if (items.isNotEmpty()) upsertItems(items)
    }

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeItem(playlistId: String, mediaId: String)

    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAtMs ASC")
    fun observePlaylists(): Flow<List<PlaylistWithItems>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId LIMIT 1")
    fun observePlaylist(playlistId: String): Flow<PlaylistWithItems?>

    @Query("SELECT mediaId FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistMediaIds(playlistId: String): List<String>

    /** Appends a batch atomically, assigning unique positions and skipping duplicates. */
    @Transaction
    suspend fun appendMedia(playlistId: String, mediaIds: List<String>, addedAtMs: Long) {
        if (getPlaylist(playlistId) == null || mediaIds.isEmpty()) return
        val existing = getPlaylistMediaIds(playlistId).toHashSet()
        var position = nextPosition(playlistId)
        val fresh = mediaIds.distinct().filter { it !in existing }
        if (fresh.isEmpty()) return
        upsertItems(
            fresh.map { mediaId ->
                PlaylistItemEntity(
                    playlistId = playlistId,
                    mediaId = mediaId,
                    position = position++,
                    addedAtMs = addedAtMs
                )
            }
        )
    }

    /** Playlists that already contain this file, for quick "add/remove" state. */
    @Query("SELECT playlistId FROM playlist_items WHERE mediaId = :mediaId")
    fun observePlaylistsContaining(mediaId: String): Flow<List<String>>
}
