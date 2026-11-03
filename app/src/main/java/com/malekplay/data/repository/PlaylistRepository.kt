package com.malekplay.data.repository

import com.malekplay.data.local.dao.PlaylistDao
import com.malekplay.data.local.entity.PlaylistEntity
import com.malekplay.data.local.entity.PlaylistItemEntity
import com.malekplay.data.local.model.PlaylistWithItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {

    fun observePlaylists(): Flow<List<PlaylistWithItems>> = playlistDao.observePlaylists()
    fun observePlaylist(id: String): Flow<PlaylistWithItems?> = playlistDao.observePlaylist(id)
    fun observePlaylistsContaining(mediaId: String): Flow<List<String>> =
        playlistDao.observePlaylistsContaining(mediaId)

    suspend fun create(name: String): String = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Playlist name cannot be empty" }
        val id = UUID.randomUUID().toString()
        playlistDao.upsertPlaylist(
            PlaylistEntity(
                playlistId = id,
                name = trimmed,
                createdAtMs = System.currentTimeMillis()
            )
        )
        id
    }

    suspend fun rename(playlistId: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        val existing = playlistDao.getPlaylist(playlistId) ?: return@withContext
        playlistDao.upsertPlaylist(existing.copy(name = trimmed))
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistCompletely(playlistId)
    }

    /** Appends media atomically and skips duplicate ids. */
    suspend fun addMedia(playlistId: String, mediaIds: List<String>) =
        withContext(Dispatchers.IO) {
            playlistDao.appendMedia(playlistId, mediaIds, System.currentTimeMillis())
        }

    /** Replaces all playlists during restore; each invocation is idempotent. */
    suspend fun replaceAll(playlists: List<Pair<String, List<String>>>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val playlistEntities = playlists.mapIndexedNotNull { index, (name, _) ->
            name.trim().takeIf { it.isNotEmpty() }?.let { trimmed ->
                PlaylistEntity(UUID.randomUUID().toString(), trimmed, now + index, index)
            }
        }
        val itemEntities = playlistEntities.zip(playlists.filter { it.first.isNotBlank() }).flatMap { (playlist, source) ->
            source.second.distinct().mapIndexed { position, mediaId ->
                PlaylistItemEntity(playlist.playlistId, mediaId, position, now)
            }
        }
        playlistDao.replaceAll(playlistEntities, itemEntities)
    }

    suspend fun removeMedia(playlistId: String, mediaId: String) =
        withContext(Dispatchers.IO) { playlistDao.removeItem(playlistId, mediaId) }

    suspend fun mediaIds(playlistId: String): List<String> =
        withContext(Dispatchers.IO) { playlistDao.getPlaylistMediaIds(playlistId) }

    /** One-shot snapshot used by the backup exporter. */
    suspend fun snapshotPlaylists(): List<PlaylistWithItems> = withContext(Dispatchers.IO) {
        runCatching { playlistDao.observePlaylists().first() }.getOrDefault(emptyList())
    }
}
