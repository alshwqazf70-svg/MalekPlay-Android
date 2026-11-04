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

data class PlaylistRestoreEntry(
    val playlistId: String?,
    val name: String,
    val mediaIds: List<String>
)

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
        require(trimmed.length <= MAX_PLAYLIST_NAME_LENGTH) { "Playlist name is too long" }
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
        require(trimmed.length <= MAX_PLAYLIST_NAME_LENGTH) { "Playlist name is too long" }
        val existing = playlistDao.getPlaylist(playlistId) ?: return@withContext
        playlistDao.upsertPlaylist(existing.copy(name = trimmed))
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistCompletely(playlistId)
    }

    /** Appends media atomically and skips duplicate ids. */
    suspend fun addMedia(playlistId: String, mediaIds: List<String>) =
        withContext(Dispatchers.IO) {
            require(mediaIds.size <= MAX_MEDIA_IDS_PER_APPEND) { "Too many items in one playlist update" }
            require(mediaIds.all { it.isNotBlank() && it.length <= MAX_MEDIA_ID_LENGTH }) {
                "Playlist contains an invalid media identifier"
            }
            playlistDao.appendMedia(playlistId, mediaIds.distinct(), System.currentTimeMillis())
        }

    /** Replaces all playlists atomically and preserves valid IDs from backups. */
    suspend fun restoreAll(playlists: List<PlaylistRestoreEntry>) = withContext(Dispatchers.IO) {
        require(playlists.size <= MAX_PLAYLISTS) { "Backup contains too many playlists" }
        require(playlists.sumOf { it.mediaIds.size.toLong() } <= MAX_TOTAL_MEDIA_IDS) {
            "Backup contains too many playlist items"
        }
        val now = System.currentTimeMillis()
        val suppliedIds = playlists.mapNotNull { it.playlistId?.takeIf(String::isNotBlank) }
        require(suppliedIds.distinct().size == suppliedIds.size) {
            "Duplicate playlist identifiers in backup"
        }
        val normalized = playlists.mapIndexed { index, entry ->
            val name = entry.name.trim()
            require(name.isNotEmpty()) { "Playlist name cannot be empty" }
            require(name.length <= MAX_PLAYLIST_NAME_LENGTH) { "Playlist name is too long" }
            require(entry.mediaIds.size <= MAX_MEDIA_IDS_PER_PLAYLIST) { "Playlist contains too many items" }
            require(entry.mediaIds.all { it.isNotBlank() && it.length <= MAX_MEDIA_ID_LENGTH }) {
                "Playlist contains an invalid media identifier"
            }
            val id = entry.playlistId
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                ?: UUID.randomUUID().toString()
            PlaylistEntity(id, name, now + index, index) to entry.mediaIds.distinct()
        }
        val playlistEntities = normalized.map { it.first }
        val itemEntities = normalized.flatMap { (playlist, mediaIds) ->
            mediaIds.mapIndexed { position, mediaId ->
                PlaylistItemEntity(playlist.playlistId, mediaId, position, now)
            }
        }
        playlistDao.replaceAll(playlistEntities, itemEntities)
    }

    /** Backward-compatible restore path for callers without stable IDs. */
    suspend fun replaceAll(playlists: List<Pair<String, List<String>>>) =
        restoreAll(playlists.map { PlaylistRestoreEntry(null, it.first, it.second) })

    suspend fun removeMedia(playlistId: String, mediaId: String) =
        withContext(Dispatchers.IO) { playlistDao.removeItem(playlistId, mediaId) }

    suspend fun mediaIds(playlistId: String): List<String> =
        withContext(Dispatchers.IO) { playlistDao.getPlaylistMediaIds(playlistId) }

    /** One-shot snapshot used by the backup exporter. */
    suspend fun snapshotPlaylists(): List<PlaylistWithItems> = withContext(Dispatchers.IO) {
        playlistDao.observePlaylists().first()
    }

    private companion object {
        const val MAX_PLAYLIST_NAME_LENGTH = 256
        const val MAX_MEDIA_ID_LENGTH = 4_096
        const val MAX_PLAYLISTS = 500
        const val MAX_MEDIA_IDS_PER_APPEND = 5_000
        const val MAX_MEDIA_IDS_PER_PLAYLIST = 5_000
        const val MAX_TOTAL_MEDIA_IDS = 50_000L
    }
}
