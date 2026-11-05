package com.malekplay.data.repository

import android.net.Uri
import android.os.SystemClock
import com.malekplay.data.local.ExternalPlaybackProgressStore
import com.malekplay.data.local.isPlaybackCompleted
import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.dao.UserDataDao
import com.malekplay.data.local.entity.BookmarkEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.local.model.MediaWithState
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.domain.model.AlbumSummary
import com.malekplay.domain.model.ArtistSummary
import com.malekplay.domain.model.GenreSummary
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.LibraryStats
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.toDomain
import com.malekplay.domain.model.toLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val userDataDao: UserDataDao,
    private val scanner: MediaStoreScanner,
    private val externalProgressStore: ExternalPlaybackProgressStore
) {

    private val refreshMutex = Mutex()

    private companion object {
        const val MAX_SEARCH_QUERY_LENGTH = 256
        const val DEFAULT_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L
    }

    /** Media rows joined with the favourite set, so lists can render star state. */
    private fun Flow<List<MediaWithState>>.asLibraryItems(): Flow<List<LibraryItem>> =
        combine(this, userDataDao.observeFavouriteIds()) { rows, favourites ->
            val favSet = favourites.toSet()
            rows.map { it.toLibraryItem(favSet) }
        }.flowOn(Dispatchers.Default)

    fun observeAll(): Flow<List<LibraryItem>> = mediaDao.observeAll().asLibraryItems()
    fun observeVideos(): Flow<List<LibraryItem>> = mediaDao.observeVideos().asLibraryItems()
    fun observeAudio(): Flow<List<LibraryItem>> = mediaDao.observeAudio().asLibraryItems()
    fun observeFolder(path: String): Flow<List<LibraryItem>> =
        mediaDao.observeFolder(path).asLibraryItems()
    fun observeSearch(query: String): Flow<List<LibraryItem>> {
        val normalized = query.trim().take(MAX_SEARCH_QUERY_LENGTH)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return mediaDao.observeSearch(normalized).asLibraryItems()
    }
    fun observeContinueWatching(limit: Int = 20): Flow<List<LibraryItem>> =
        mediaDao.observeContinueWatching(limit).asLibraryItems()
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<LibraryItem>> =
        mediaDao.observeRecentlyPlayed(limit).asLibraryItems()

    fun observeFolders(): Flow<List<MediaFolder>> =
        mediaDao.observeFolders().map { rows ->
            rows.map { row ->
                MediaFolder(
                    path = row.path,
                    name = row.name,
                    itemCount = row.itemCount,
                    videoCount = row.videoCount,
                    audioCount = row.audioCount,
                    totalBytes = row.totalBytes,
                    lastAddedMs = row.lastAddedMs,
                    sampleUri = row.sampleUri?.let { Uri.parse(it) }
                )
            }
        }.flowOn(Dispatchers.Default)

    fun observeAlbums(unknownLabel: String): Flow<List<AlbumSummary>> =
        mediaDao.observeAlbums(unknownLabel).map { rows ->
            rows.map {
                AlbumSummary(
                    name = it.name,
                    artist = it.artist,
                    albumId = it.albumId,
                    trackCount = it.trackCount,
                    sampleUri = it.sampleUri?.let(Uri::parse)
                )
            }
        }.flowOn(Dispatchers.Default)

    fun observeArtists(unknownLabel: String): Flow<List<ArtistSummary>> =
        mediaDao.observeArtists(unknownLabel).map { rows ->
            rows.map {
                ArtistSummary(
                    name = it.name,
                    artistId = it.artistId,
                    trackCount = it.trackCount,
                    sampleUri = it.sampleUri?.let(Uri::parse)
                )
            }
        }.flowOn(Dispatchers.Default)

    fun observeGenres(unknownLabel: String): Flow<List<GenreSummary>> =
        mediaDao.observeGenres(unknownLabel).map { rows ->
            rows.map {
                GenreSummary(
                    name = it.name,
                    trackCount = it.trackCount,
                    sampleUri = it.sampleUri?.let(Uri::parse)
                )
            }
        }.flowOn(Dispatchers.Default)

    fun observeStats(): Flow<LibraryStats> = combine(
        mediaDao.observeVideoCount(),
        mediaDao.observeAudioCount(),
        mediaDao.observeTotalBytes(),
        mediaDao.observeWatchedMs()
    ) { videos, audio, bytes, watched ->
        LibraryStats(
            videoCount = videos,
            audioCount = audio,
            totalBytes = bytes,
            watchedMs = watched
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Re-scans MediaStore and reconciles the cache only after a complete scan.
     * Scanner failures are propagated, so an incomplete snapshot cannot erase
     * rows that were temporarily hidden by a provider/permission error.
     */
    // Monotonic timestamp avoids repeating an expensive MediaStore scan when
    // the user quickly revisits the library during the same app process.
    private var lastSuccessfulRefreshElapsedMs = 0L

    /** Force a full scan (used by the user's explicit pull-to-refresh action). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            refreshLocked()
            lastSuccessfulRefreshElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Refresh on first library entry, then skip redundant scans for five minutes.
     * This is only an optimization: explicit refresh() always performs a scan.
     */
    suspend fun refreshIfStale(minIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS) =
        withContext(Dispatchers.IO) {
            require(minIntervalMs >= 0L)
            refreshMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                if (lastSuccessfulRefreshElapsedMs > 0L &&
                    now - lastSuccessfulRefreshElapsedMs < minIntervalMs
                ) return@withLock
                refreshLocked()
                lastSuccessfulRefreshElapsedMs = SystemClock.elapsedRealtime()
            }
        }

    private suspend fun refreshLocked() {
        // Persist bounded batches as the cursor is read; retain only compact IDs
        // for reconciliation after the complete scan has finished.
        val scan = scanner.scan { batch ->
            currentCoroutineContext().ensureActive()
            mediaDao.upsertMedia(batch)
        }

        // Selected-only access can add/update visible rows, but must never be
        // interpreted as a complete snapshot for deletion.
        currentCoroutineContext().ensureActive()
        if (scan.videosComplete) {
            mediaDao.reconcileVideos(scan.videoIds)
        }

        if (scan.audioComplete) {
            mediaDao.reconcileAudio(scan.audioIds)
        }
    }

    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            mediaDao.clearAll()
            lastSuccessfulRefreshElapsedMs = 0L
        }
    }

    // -------------------------------------------------------------- progress

    suspend fun saveProgress(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        markPlayed: Boolean
    ) = withContext(Dispatchers.IO) {
        require(mediaId.isNotBlank()) { "Media id must not be blank" }
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        val completed = isPlaybackCompleted(safePosition, safeDuration)
        val progress = PlaybackProgressEntity(
            mediaId = mediaId,
            positionMs = if (completed) 0L else safePosition.coerceAtMost(safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE),
            durationMs = safeDuration,
            playbackSpeed = safeSpeed,
            isCompleted = completed,
            lastPlayedAtMs = System.currentTimeMillis(),
            playCount = 0 // Assigned atomically by the DAO or external store.
        )
        val savedInLibrary = mediaDao.saveProgressIfMediaExists(progress, markPlayed)
        if (!savedInLibrary) {
            externalProgressStore.save(mediaId, safePosition, safeDuration, safeSpeed, markPlayed)
        }
        if (markPlayed) recordHistory(mediaId)

    }

    suspend fun getProgress(mediaId: String) = withContext(Dispatchers.IO) {
        mediaDao.getProgress(mediaId) ?: externalProgressStore.get(mediaId)
    }

    suspend fun getTrackPreference(mediaId: String) =
        withContext(Dispatchers.IO) { mediaDao.getTrackPreference(mediaId) }

    suspend fun saveTrackPreference(preference: TrackPreferenceEntity) =
        withContext(Dispatchers.IO) {
            mediaDao.saveTrackPreferenceIfMediaExists(preference)
        }

    // -------------------------------------------------------------- bookmarks

    fun observeBookmarks(mediaId: String) = mediaDao.observeBookmarks(mediaId)

    suspend fun addBookmark(mediaId: String, positionMs: Long, label: String) =
        withContext(Dispatchers.IO) {
            mediaDao.addBookmarkIfMediaExists(
                BookmarkEntity(mediaId = mediaId, positionMs = positionMs, label = label)
            )
        }

    suspend fun deleteBookmark(bookmarkId: Long) =
        withContext(Dispatchers.IO) { mediaDao.deleteBookmark(bookmarkId) }

    // ---------------------------------------------------------------- history

    /** Records a play with a single atomic SQL upsert. */
    private suspend fun recordHistory(mediaId: String) {
        userDataDao.recordHistoryPlay(mediaId, System.currentTimeMillis())
        userDataDao.trimHistory()
    }

    suspend fun getMedia(mediaId: String) =
        withContext(Dispatchers.IO) { mediaDao.getMedia(mediaId)?.media?.toDomain() }

    suspend fun getMediaList(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        // Keep each query below SQLite's bind-variable limit, then rebuild the
        // exact requested order (including intentionally repeated queue entries).
        val rowsById = ids.distinct().chunked(900)
            .flatMap { mediaDao.getMediaList(it) }
            .associateBy { it.media.mediaId }
        ids.mapNotNull { id -> rowsById[id]?.media?.toDomain() }
    }
}
