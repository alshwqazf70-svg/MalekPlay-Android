package com.malekplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.domain.model.MediaWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    // Upsert preserves dependent playback/track rows; REPLACE can trigger FK cascades.
    @Upsert
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE isVideo = :isVideo AND lastSeenAt < :scanTimestamp")
    suspend fun deleteMediaNotSeenSince(isVideo: Boolean, scanTimestamp: Long)

    @Transaction
    @Query("SELECT * FROM media_items ORDER BY dateAdded DESC")
    fun observeAllMediaWithDetails(): Flow<List<MediaWithDetails>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getMediaWithDetails(mediaId: String): MediaWithDetails?

    @Upsert
    suspend fun upsertPlaybackState(state: PlaybackStateEntity)

    @Upsert
    suspend fun upsertTrackPreference(preference: TrackPreferenceEntity)

    @Query("SELECT * FROM playback_states WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getPlaybackState(mediaId: String): PlaybackStateEntity?

    @Query("SELECT * FROM track_preferences WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getTrackPreference(mediaId: String): TrackPreferenceEntity?
}
