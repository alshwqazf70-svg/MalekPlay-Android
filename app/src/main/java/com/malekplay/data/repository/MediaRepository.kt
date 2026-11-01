package com.malekplay.data.repository

import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.domain.model.MediaWithDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class MediaRepository(
    private val dao: MediaDao,
    private val scanner: MediaStoreScanner
) {
    fun observeLibrary(): Flow<List<MediaWithDetails>> = flow {
        dao.upsertMedia(scanner.scan())
        emitAll(dao.observeAllMediaWithDetails())
    }.flowOn(Dispatchers.IO)

    suspend fun savePlayback(mediaId: String, position: Long, speed: Float, duration: Long) {
        val completed = duration > 0L && position >= duration - 2_000L
        dao.upsertPlaybackState(
            PlaybackStateEntity(
                mediaId = mediaId,
                position = if (completed) 0L else position.coerceAtLeast(0L),
                playbackSpeed = speed.coerceIn(0.25f, 4.0f),
                isCompleted = completed
            )
        )
    }

    suspend fun getPlaybackState(mediaId: String) = dao.getPlaybackState(mediaId)
    suspend fun getTrackPreference(mediaId: String) = dao.getTrackPreference(mediaId)
    suspend fun saveTrackPreference(preference: TrackPreferenceEntity) =
        dao.upsertTrackPreference(preference)
}
