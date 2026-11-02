package com.malekplay.data.repository

import com.malekplay.data.local.dao.MediaDao
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity
import com.malekplay.data.scanner.MediaStoreScanner
import com.malekplay.domain.model.MediaWithDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * مستودع الوسائط الذكي (يدعم العرض الفوري والتحديث التلقائي في الخلفية)
 */
class MediaRepository(
    private val dao: MediaDao,
    private val scanner: MediaStoreScanner
) {
    private val scanMutex = Mutex()
    private var lastScanTime = 0L

    /**
     * تدفق الوسائط الفوري: يعرض البيانات المخزنة فوراً ثم يفحص الهاتف في الخلفية
     */
    fun observeLibrary(): Flow<List<MediaWithDetails>> = channelFlow {
        // 1. مراقبة وإرسال البيانات المخزنة فوراً إلى الشاشة (بدون أي تأخير)
        val observationJob = launch {
            dao.observeAllMediaWithDetails().collect { records ->
                send(records)
            }
        }

        // 2. تحديث المكتبة في الخلفية بأمان إذا مرت 15 ثانية على آخر فحص
        launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (now - lastScanTime > 15_000L) {
                scanMutex.withLock {
                    runCatching {
                        val freshMedia = scanner.scan()
                        if (freshMedia.isNotEmpty()) {
                            dao.upsertMedia(freshMedia)
                        }
                        lastScanTime = System.currentTimeMillis()
                    }
                }
            }
        }

        // إبقاء القناة مفتوحة للاستماع لأي تغييرات جديدة
        observationJob.join()
    }.flowOn(Dispatchers.IO)

    /**
     * حفظ تقدم المشاهدة وموضع الفيديو بدقة
     */
    suspend fun savePlayback(mediaId: String, position: Long, speed: Float, duration: Long) {
        val completed = duration > 0L && position >= duration - 3_000L
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
