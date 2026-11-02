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
    @Upsert
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    // مراقبة كل الوسائط مرتبة بالأحدث
    @Transaction
    @Query("SELECT * FROM media_items ORDER BY dateAdded DESC")
    fun observeAllMediaWithDetails(): Flow<List<MediaWithDetails>>

    // مراقبة وسائط مجلد معين فقط (MX Player Fast Folder Query)
    @Transaction
    @Query("SELECT * FROM media_items WHERE bucketId = :bucketId ORDER BY dateAdded DESC")
    fun observeMediaByBucket(bucketId: String): Flow<List<MediaWithDetails>>

    // مراقبة الفيديوهات فقط
    @Transaction
    @Query("SELECT * FROM media_items WHERE isVideo = 1 ORDER BY dateAdded DESC")
    fun observeVideos(): Flow<List<MediaWithDetails>>

    // مراقبة الصوتيات فقط (مشغل الموسيقى)
    @Transaction
    @Query("SELECT * FROM media_items WHERE isVideo = 0 ORDER BY dateAdded DESC")
    fun observeAudios(): Flow<List<MediaWithDetails>>

    @Transaction
    @Query("SELECT * FROM media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getMediaWithDetails(mediaId: String): MediaWithDetails?

    // البحث المباشر في قاعدة البيانات
    @Transaction
    @Query("SELECT * FROM media_items WHERE title LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchMedia(query: String): Flow<List<MediaWithDetails>>

    // حذف الملفات التي تم مسحها من الهاتف تلقائياً
    @Query("DELETE FROM media_items WHERE mediaId NOT IN (:validMediaIds)")
    suspend fun deleteMissingMedia(validMediaIds: List<String>)

    @Query("DELETE FROM media_items WHERE mediaId = :mediaId")
    suspend fun deleteMediaById(mediaId: String)

    @Upsert
    suspend fun upsertPlaybackState(state: PlaybackStateEntity)

    @Upsert
    suspend fun upsertTrackPreference(preference: TrackPreferenceEntity)

    @Query("SELECT * FROM playback_states WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getPlaybackState(mediaId: String): PlaybackStateEntity?

    @Query("SELECT * FROM track_preferences WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getTrackPreference(mediaId: String): TrackPreferenceEntity?
}
