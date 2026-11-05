package com.malekplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.malekplay.data.local.entity.BookmarkEntity
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.local.model.MediaWithState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // ---------------------------------------------------------------- writes

    /**
     * Upsert (not REPLACE): REPLACE deletes the row first, which would fire the
     * foreign-key cascade and wipe progress/track rows on every rescan.
     */
    @Upsert
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    /** Media ids are read and reconciled in one transaction without SQLite bind limits. */
    @Query("""
        SELECT mediaId FROM media_items
        WHERE isVideo = 1 AND mediaId > :afterId
        ORDER BY mediaId ASC LIMIT :limit
    """)
    suspend fun getVideoMediaIdsAfter(afterId: String, limit: Int): List<String>

    @Query("""
        SELECT mediaId FROM media_items
        WHERE isVideo = 0 AND mediaId > :afterId
        ORDER BY mediaId ASC LIMIT :limit
    """)
    suspend fun getAudioMediaIdsAfter(afterId: String, limit: Int): List<String>

    @Query("DELETE FROM media_items WHERE mediaId IN (:mediaIds)")
    suspend fun deleteMediaByIds(mediaIds: List<String>): Int

    @Transaction
    suspend fun reconcileVideos(keepIds: Set<String>) {
        var afterId = ""
        while (true) {
            // Reconciliation may span many pages on large libraries. Honor job
            // cancellation between pages so Room can roll back this transaction.
            currentCoroutineContext().ensureActive()
            val page = getVideoMediaIdsAfter(afterId, RECONCILE_PAGE_SIZE)
            if (page.isEmpty()) break
            afterId = page.last()
            val staleIds = page.filterNot(keepIds::contains)
            if (staleIds.isNotEmpty()) deleteMediaByIds(staleIds)
        }
    }

    @Transaction
    suspend fun reconcileAudio(keepIds: Set<String>) {
        var afterId = ""
        while (true) {
            // Keep cancellation behavior symmetric with video reconciliation.
            currentCoroutineContext().ensureActive()
            val page = getAudioMediaIdsAfter(afterId, RECONCILE_PAGE_SIZE)
            if (page.isEmpty()) break
            afterId = page.last()
            val staleIds = page.filterNot(keepIds::contains)
            if (staleIds.isNotEmpty()) deleteMediaByIds(staleIds)
        }
    }

    @Upsert
    suspend fun upsertProgress(progress: PlaybackProgressEntity)

    /** Saves progress and its play counter atomically only while the parent media row exists. */
    @Transaction
    suspend fun saveProgressIfMediaExists(
        progress: PlaybackProgressEntity,
        markPlayed: Boolean
    ): Boolean {
        if (getMedia(progress.mediaId) == null) return false
        val existing = getProgress(progress.mediaId)
        val count = (existing?.playCount ?: 0) + if (markPlayed) 1 else 0
        upsertProgress(progress.copy(playCount = count))
        return true
    }

    @Upsert
    suspend fun upsertTrackPreference(preference: TrackPreferenceEntity)

    @Transaction
    suspend fun saveTrackPreferenceIfMediaExists(preference: TrackPreferenceEntity) {
        if (getMedia(preference.mediaId) != null) upsertTrackPreference(preference)
    }

    @Upsert
    suspend fun upsertBookmark(bookmark: BookmarkEntity): Long

    @Transaction
    suspend fun addBookmarkIfMediaExists(bookmark: BookmarkEntity): Boolean {
        if (getMedia(bookmark.mediaId) == null) return false
        upsertBookmark(bookmark)
        return true
    }

    @Query("DELETE FROM bookmarks WHERE bookmarkId = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("DELETE FROM media_items")
    suspend fun clearAll()

    // ---------------------------------------------------------------- reads

    @Transaction
    @Query("SELECT * FROM media_items ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<MediaWithState>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE isVideo = 1 ORDER BY addedAtMs DESC")
    fun observeVideos(): Flow<List<MediaWithState>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE isVideo = 0 ORDER BY addedAtMs DESC")
    fun observeAudio(): Flow<List<MediaWithState>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE folderPath = :folderPath ORDER BY title COLLATE NOCASE ASC")
    fun observeFolder(folderPath: String): Flow<List<MediaWithState>>

    @Transaction
    @Query(
        """SELECT * FROM media_items
           WHERE title LIKE '%' || :query || '%' ESCAPE '\'
              OR folderName LIKE '%' || :query || '%' ESCAPE '\'
              OR IFNULL(artist,'') LIKE '%' || :query || '%' ESCAPE '\'
              OR IFNULL(album,'') LIKE '%' || :query || '%' ESCAPE '\'
           ORDER BY addedAtMs DESC"""
    )
    fun observeSearch(query: String): Flow<List<MediaWithState>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getMedia(mediaId: String): MediaWithState?

    @Transaction
    @Query("SELECT * FROM media_items WHERE mediaId IN (:ids)")
    suspend fun getMediaList(ids: List<String>): List<MediaWithState>

    @Transaction
    @Query(
        """SELECT m.* FROM media_items m
           INNER JOIN playback_progress p ON p.mediaId = m.mediaId
           WHERE p.isCompleted = 0 AND p.positionMs > 1000
           ORDER BY p.lastPlayedAtMs DESC LIMIT :limit"""
    )
    fun observeContinueWatching(limit: Int = 20): Flow<List<MediaWithState>>

    @Transaction
    @Query(
        """SELECT m.* FROM media_items m
           INNER JOIN history h ON h.mediaId = m.mediaId
           ORDER BY h.lastPlayedAtMs DESC LIMIT :limit"""
    )
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<MediaWithState>>

    // ------------------------------------------------------------ aggregates

    @Query(
        """SELECT folderPath AS path, folderName AS name,
                  COUNT(*) AS itemCount,
                  SUM(CASE WHEN isVideo = 1 THEN 1 ELSE 0 END) AS videoCount,
                  SUM(CASE WHEN isVideo = 0 THEN 1 ELSE 0 END) AS audioCount,
                  SUM(sizeBytes) AS totalBytes,
                  MAX(addedAtMs) AS lastAddedMs,
                  MIN(uri) AS sampleUri
           FROM media_items
           GROUP BY folderPath
           ORDER BY folderName COLLATE NOCASE ASC"""
    )
    fun observeFolders(): Flow<List<FolderRow>>

    @Query(
        """SELECT IFNULL(NULLIF(album,''), :unknown) AS name,
                  IFNULL(NULLIF(artist,''), :unknown) AS artist,
                  albumId AS albumId,
                  COUNT(*) AS trackCount,
                  MIN(uri) AS sampleUri
           FROM media_items
           WHERE isVideo = 0
           GROUP BY album, artist
           ORDER BY album COLLATE NOCASE ASC"""
    )
    fun observeAlbums(unknown: String): Flow<List<AlbumRow>>

    @Query(
        """SELECT IFNULL(NULLIF(artist,''), :unknown) AS name,
                  artistId AS artistId,
                  COUNT(*) AS trackCount,
                  MIN(uri) AS sampleUri
           FROM media_items
           WHERE isVideo = 0
           GROUP BY artist
           ORDER BY artist COLLATE NOCASE ASC"""
    )
    fun observeArtists(unknown: String): Flow<List<ArtistRow>>

    @Query(
        """SELECT IFNULL(NULLIF(genre,''), :unknown) AS name,
                  COUNT(*) AS trackCount,
                  MIN(uri) AS sampleUri
           FROM media_items
           WHERE isVideo = 0
           GROUP BY genre
           ORDER BY genre COLLATE NOCASE ASC"""
    )
    fun observeGenres(unknown: String): Flow<List<GenreRow>>

    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 1")
    fun observeVideoCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 0")
    fun observeAudioCount(): Flow<Int>

    @Query("SELECT IFNULL(SUM(sizeBytes),0) FROM media_items")
    fun observeTotalBytes(): Flow<Long>

    @Query(
        """SELECT IFNULL(SUM(CASE WHEN p.isCompleted = 1 THEN m.durationMs ELSE p.positionMs END),0)
           FROM playback_progress p INNER JOIN media_items m ON m.mediaId = p.mediaId"""
    )
    fun observeWatchedMs(): Flow<Long>

    // ------------------------------------------------------------- progress

    @Query("SELECT * FROM playback_progress WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getProgress(mediaId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM track_preferences WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getTrackPreference(mediaId: String): TrackPreferenceEntity?

    @Query("SELECT * FROM bookmarks WHERE mediaId = :mediaId ORDER BY positionMs ASC")
    fun observeBookmarks(mediaId: String): Flow<List<BookmarkEntity>>
}

private const val RECONCILE_PAGE_SIZE = 500

/** Projection returned by [MediaDao.observeFolders]. Column names must match. */
data class FolderRow(
    val path: String,
    val name: String,
    val itemCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val totalBytes: Long,
    val lastAddedMs: Long,
    val sampleUri: String?
)

data class AlbumRow(
    val name: String,
    val artist: String,
    val albumId: Long?,
    val trackCount: Int,
    val sampleUri: String?
)

data class ArtistRow(
    val name: String,
    val artistId: Long?,
    val trackCount: Int,
    val sampleUri: String?
)

data class GenreRow(
    val name: String,
    val trackCount: Int,
    val sampleUri: String?
)
