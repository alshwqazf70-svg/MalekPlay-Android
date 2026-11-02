package com.malekplay.ui.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.malekplay.player.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * نموذج بيانات لحفظ حالة التشغيل واستئناف الفيديو (MX Player Resume Feature)
 */
data class SavedPlaybackState(
    val position: Long,
    val playbackSpeed: Float,
    val isCompleted: Boolean = false
)

data class SavedTrackPreference(
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context
) : ViewModel() {

    private val prefs = app.getSharedPreferences("malekplay_playback_state", Context.MODE_PRIVATE)

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller = _controller.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError = _connectionError.asStateFlow()

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var sleepTimerJob: Job? = null
    private val _sleepTimerActive = MutableStateFlow(false)
    val sleepTimerActive = _sleepTimerActive.asStateFlow()

    /**
     * الاتصال بخدمة المشغل بخلفية النظام
     */
    fun connect() {
        if (_controller.value != null) return
        val pending = controllerFuture
        if (pending != null && !pending.isDone) return

        _connectionError.value = false
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = runCatching { future.get() }.getOrNull()
                if (connected != null) {
                    _controller.value = connected
                    _connectionError.value = false
                } else {
                    controllerFuture = null
                    _connectionError.value = true
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    /**
     * تشغيل قائمة المقاطع مع دعم العناوين، واستعادة موضع الفيديو، وسرعة التشغيل
     */
    fun playQueue(
        uris: List<Uri>,
        startIndex: Int,
        startPositionMs: Long,
        speed: Float = 1f,
        title: String = "",
        trackPreference: SavedTrackPreference? = null
    ) {
        val player = _controller.value ?: return
        if (uris.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, uris.lastIndex)

        // بناء قائمة MediaItems مع إرفاق العناوين والـ Metadata
        val mediaItems = uris.mapIndexed { index, uri ->
            val itemTitle = if (index == safeIndex && title.isNotBlank()) {
                title
            } else {
                Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').ifBlank { "فيديو ${index + 1}" }
            }

            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uri.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(itemTitle)
                        .setDisplayTitle(itemTitle)
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * استرجاع نقطة التوقف السابقة للفيديو
     */
    suspend fun getSavedPlaybackState(uriString: String): SavedPlaybackState? = withContext(Dispatchers.IO) {
        val pos = prefs.getLong("pos:$uriString", -1L)
        if (pos <= 0L) return@withContext null
        val speed = prefs.getFloat("spd:$uriString", 1f)
        val completed = prefs.getBoolean("done:$uriString", false)
        SavedPlaybackState(pos, speed, completed)
    }

    /**
     * حفظ موضع توقف الفيديو تلقائياً
     */
    fun savePlaybackState(uriString: String, positionMs: Long, speed: Float, isCompleted: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit()
                .putLong("pos:$uriString", positionMs)
                .putFloat("spd:$uriString", speed)
                .putBoolean("done:$uriString", isCompleted)
                .apply()
        }
    }

    /**
     * استرجاع تفضيلات الصوت والترجمة للفيديو
     */
    suspend fun getSavedTrackPreference(uriString: String): SavedTrackPreference? = withContext(Dispatchers.IO) {
        val audio = prefs.getString("audio:$uriString", null)
        val sub = prefs.getString("sub:$uriString", null)
        if (audio == null && sub == null) null else SavedTrackPreference(audio, sub)
    }

    /**
     * مؤقت النوم (Sleep Timer)
     */
    fun setSleepTimer(minutes: Int) {
        require(minutes in 1..240) { "Sleep timer must be between 1 and 240 minutes" }
        sleepTimerJob?.cancel()
        _sleepTimerActive.value = true
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            _controller.value?.pause()
            _sleepTimerActive.value = false
            sleepTimerJob = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerActive.value = false
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controller.value = null
        super.onCleared()
    }
}
