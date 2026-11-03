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

    /** Playlists that already contain this file, for quick "add/remove" state. */
    @Query("SELECT playlistId FROM playlist_items WHERE mediaId = :mediaId")
    fun observePlaylistsContaining(mediaId: String): Flow<List<String>>
}
