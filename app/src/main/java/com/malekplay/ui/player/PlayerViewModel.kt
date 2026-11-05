package com.malekplay.ui.player

import android.content.ComponentName
import android.util.Log
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.malekplay.data.local.entity.BookmarkEntity
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem as DomainMedia
import com.malekplay.network.OnlineSubtitle
import com.malekplay.network.OnlineSubtitleRepository
import com.malekplay.player.PipController
import com.malekplay.player.PlaybackService
import com.malekplay.player.PlaybackValuePolicy
import com.malekplay.player.mediaItemFromUri
import com.malekplay.player.audio.AudioEffectsController
import com.malekplay.player.toMedia3Item
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A selectable audio or text track surfaced from the current Tracks info. */
data class TrackDescriptor(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val mimeType: String?,
    val selected: Boolean
)

/** Live codec/format telemetry shown in the "Playback info" sheet. */
data class PlaybackStats(
    val videoCodec: String = "—",
    val videoMimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Long = 0L,
    val audioCodec: String = "—",
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val containerBitrate: Long = 0L
)

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val mediaRepository: MediaRepository,
    private val userDataRepository: UserDataRepository,
    val audioEffects: AudioEffectsController,
    val pipController: PipController,
    private val onlineSubtitleRepository: OnlineSubtitleRepository
) : ViewModel() {

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedMs = MutableStateFlow(0L)
    val bufferedMs: StateFlow<Long> = _bufferedMs.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackDescriptor>>(emptyList())
    val audioTracks: StateFlow<List<TrackDescriptor>> = _audioTracks.asStateFlow()

    private val _textTracks = MutableStateFlow<List<TrackDescriptor>>(emptyList())
    val textTracks: StateFlow<List<TrackDescriptor>> = _textTracks.asStateFlow()

    private val _textTrackDisabled = MutableStateFlow(false)
    val textTrackDisabled: StateFlow<Boolean> = _textTrackDisabled.asStateFlow()

    private val _stats = MutableStateFlow(PlaybackStats())
    val stats: StateFlow<PlaybackStats> = _stats.asStateFlow()

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    private val _onlineSubtitleResults = MutableStateFlow<List<OnlineSubtitle>>(emptyList())
    val onlineSubtitleResults: StateFlow<List<OnlineSubtitle>> = _onlineSubtitleResults.asStateFlow()
    private val _onlineSubtitleLoading = MutableStateFlow(false)
    val onlineSubtitleLoading: StateFlow<Boolean> = _onlineSubtitleLoading.asStateFlow()
    private val _onlineSubtitleError = MutableStateFlow<String?>(null)
    val onlineSubtitleError: StateFlow<String?> = _onlineSubtitleError.asStateFlow()
    private val _onlineSubtitleCredentialsReady = MutableStateFlow(onlineSubtitleRepository.hasCredentials())
    val onlineSubtitleCredentialsReady: StateFlow<Boolean> = _onlineSubtitleCredentialsReady.asStateFlow()

    private val _sleepTimerEndsAtMs = MutableStateFlow(0L)
    val sleepTimerEndsAtMs: StateFlow<Long> = _sleepTimerEndsAtMs.asStateFlow()

    private val _abStartMs = MutableStateFlow<Long?>(null)
    val abStartMs: StateFlow<Long?> = _abStartMs.asStateFlow()

    private val _abEndMs = MutableStateFlow<Long?>(null)
    val abEndMs: StateFlow<Long?> = _abEndMs.asStateFlow()

    /** Queue of items handed to the player, kept so the UI can render "up next". */
    private val _queue = MutableStateFlow<List<DomainMedia>>(emptyList())
    val queue: StateFlow<List<DomainMedia>> = _queue.asStateFlow()

    val favouriteIds: StateFlow<Set<String>> = userDataRepository.observeFavouriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastGroups: List<Tracks.Group> = emptyList()
    private var currentMediaId: String? = null

    // ---------------------------------------------------------- lifecycle

    init {
        connect()
    }

    /** Idempotent: repeated calls are safe and a failed attempt can be retried. */
    fun connect() {
        if (_controller.value != null) return
        // Keep a completed future until its main-thread listener has published
        // the controller or reported failure; otherwise a second connect can
        // replace it before that callback runs and orphan its MediaController.
        if (controllerFuture != null) return

        _connectionError.value = false
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = runCatching { future.get() }.getOrNull()
                if (connected != null) {
                    connected.addListener(playerListener)
                    _controller.value = connected
                    currentMediaId = connected.currentMediaItem?.mediaId
                    _connectionError.value = false
                    _isPlaying.value = connected.isPlaying
                    publishPlayerSnapshot(connected)
                    if (connected.isPlaying) startTicker()
                } else {
                    // Clear so a later connect() can retry instead of returning early.
                    controllerFuture = null
                    _connectionError.value = true
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startTicker()
            } else {
                tickerJob?.cancel()
                tickerJob = null
                _controller.value?.let(::publishPlayerSnapshot)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                refreshTracks()
                refreshStats()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Stop at the end of the episode when that sleep-timer flavour is armed.
            if (sleepTimerEndOfEpisode && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                _controller.value?.pause()
                cancelSleepTimer()
            }
            _positionMs.value = newPosition.positionMs
            applyAbBounds(newPosition.positionMs)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player already points at the new item here. Do not save its
            // position under the previous item's id.
            currentMediaId = mediaItem?.mediaId
            _currentIndex.value = _controller.value?.currentMediaItemIndex ?: 0
            _queueSize.value = _controller.value?.mediaItemCount ?: 0
            viewModelScope.launch {
                mediaItem?.mediaId?.let { userDataRepository.isFavourite(it) }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _speed.value = playbackParameters.speed
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onPlayerError(error: PlaybackException) {
            // Keep the full codec/renderer cause in Logcat for diagnosis, but
            // never expose raw MediaCodec internals to the viewer.
            // Media3 exceptions can include source Uris and local file paths.
            // Keep diagnostics to the stable error code rather than logging raw
            // exception messages or stack traces that may expose private paths.
            Log.e(LOG_TAG, "Playback failed: code=${error.errorCodeName}")
            _playerError.value =
                "تعذّر تشغيل هذا الفيديو. قد تكون الصيغة أو وحدة فك الترميز غير متوافقة؛ حاول تشغيله مرة أخرى."
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioEffects.attach(audioSessionId)
        }
    }

    private fun publishPlayerSnapshot(player: Player) {
        _positionMs.value = player.currentPosition
        _durationMs.value = player.duration.coerceAtLeast(0L)
        _bufferedMs.value = player.bufferedPosition
        _currentIndex.value = player.currentMediaItemIndex
        _queueSize.value = player.mediaItemCount
    }

    /** The UI ticker exists only during active playback, never while paused or idle. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val player = _controller.value ?: break
                if (!player.isPlaying) {
                    publishPlayerSnapshot(player)
                    break
                }
                publishPlayerSnapshot(player)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------ playback

    /**
     * Replaces the queue and starts playback.
     * `startPositionMs` is ignored when the caller passes -1 and resume is enabled.
     */
    fun playQueue(
        items: List<DomainMedia>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        speed: Float = 1f,
        shuffle: Boolean = false
    ) {
        val player = _controller.value ?: return
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        _queue.value = items
        currentMediaId = items[safeIndex].id
        _playerError.value = null
        player.setMediaItems(items.map { it.toMedia3Item() }, safeIndex, startPositionMs.coerceAtLeast(0L))
        player.shuffleModeEnabled = shuffle
        player.setPlaybackSpeed(PlaybackValuePolicy.normalizeSpeed(speed))
        player.prepare()
        player.playWhenReady = true
        refreshStats()
    }

    /** Convenience wrapper for the common "open this one file" case. */
    fun playSingle(item: DomainMedia, startPositionMs: Long = 0L, speed: Float = 1f) =
        playQueue(listOf(item), 0, startPositionMs, speed)

    /**
     * Plays a Uri that has no library row — a file opened from another app or a
     * network stream. The Uri doubles as the media id so progress still sticks.
     */
    fun playExternalUri(uri: Uri, startPositionMs: Long = 0L, speed: Float = 1f) {
        val player = _controller.value ?: return
        _queue.value = emptyList()
        currentMediaId = uri.toString()
        _playerError.value = null
        player.setMediaItem(mediaItemFromUri(uri))
        player.seekTo(startPositionMs.coerceAtLeast(0L))
        player.setPlaybackSpeed(PlaybackValuePolicy.normalizeSpeed(speed))
        player.prepare()
        player.playWhenReady = true
        refreshStats()
    }

    fun togglePlayPause() {
        val player = _controller.value ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        val player = _controller.value ?: return
        val target = positionMs.coerceIn(0L, player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.seekTo(target)
        _positionMs.value = target
    }

    fun seekBy(deltaMs: Long) {
        val player = _controller.value ?: return
        seekTo(PlaybackValuePolicy.addSeekDelta(player.currentPosition, deltaMs))
    }

    fun seekToNext() = _controller.value?.seekToNext()
    fun seekToPrevious() = _controller.value?.seekToPrevious()

    fun setSpeed(value: Float) {
        val safeSpeed = PlaybackValuePolicy.normalizeSpeed(value)
        _controller.value?.setPlaybackSpeed(safeSpeed)
        _speed.value = safeSpeed
    }

    fun cycleRepeatMode() {
        val player = _controller.value ?: return
        val next = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        _repeatMode.value = next
    }

    fun toggleShuffle() {
        val player = _controller.value ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        _shuffleEnabled.value = player.shuffleModeEnabled
    }

    /** Adds an external subtitle without discarding the current playback queue. */
    fun addExternalSubtitle(uri: Uri) {
        val player = _controller.value ?: run {
            _playerError.value = "مشغل الفيديو غير جاهز"
            return
        }
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        val mimeType = when (extension) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            else -> null
        }
        if (mimeType == null) {
            _playerError.value = "صيغة الترجمة غير مدعومة. استخدم ASS أو SSA أو SRT أو VTT"
            return
        }
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem
        if (current == null || index !in 0 until player.mediaItemCount) {
            _playerError.value = "لا يوجد فيديو نشط لإضافة الترجمة"
            return
        }
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
        if (existing.any { it.uri == uri }) return

        val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon()
            .setSubtitleConfigurations(existing + configuration)
            .build()
        val queue = (0 until player.mediaItemCount).map { itemIndex ->
            if (itemIndex == index) updated else player.getMediaItemAt(itemIndex)
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        player.setMediaItems(queue, index, position)
        player.prepare()
        player.playWhenReady = playWhenReady
        _playerError.value = null
    }

    // --------------------------------------------------------------- tracks

    private fun refreshTracks(tracks: Tracks? = null) {
        val player = _controller.value ?: return
        val current = tracks ?: player.currentTracks
        lastGroups = current.groups
        val audio = mutableListOf<TrackDescriptor>()
        val text = mutableListOf<TrackDescriptor>()
        current.groups.forEachIndexed { groupIndex, group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val selected = group.isTrackSelected(trackIndex)
                val descriptor = TrackDescriptor(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = buildTrackLabel(format, trackIndex),
                    mimeType = format.sampleMimeType,
                    selected = selected
                )
                if (type == C.TRACK_TYPE_AUDIO) audio += descriptor else text += descriptor
            }
        }
        _audioTracks.value = audio
        _textTracks.value = text
        _textTrackDisabled.value = player.trackSelectionParameters.disabledTrackTypes
            .contains(C.TRACK_TYPE_TEXT)
    }

    private fun buildTrackLabel(format: Format, index: Int): String {
        val language = format.language
        val label = format.label
        val parts = mutableListOf<String>()
        if (!label.isNullOrBlank()) parts += label
        if (!language.isNullOrBlank()) parts += language
        if (format.bitrate > 0) parts += "%d kbps".format(format.bitrate / 1000)
        if (format.channelCount > 0) parts += channelLabel(format.channelCount)
        if (parts.isEmpty()) parts += "Track ${index + 1}"
        return parts.joinToString(" • ")
    }

    private fun channelLabel(count: Int): String = when (count) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count ch"
    }

    fun selectAudioTrack(descriptor: TrackDescriptor) {
        val player = _controller.value ?: return
        val group = lastGroups.getOrNull(descriptor.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, descriptor.trackIndex)
            )
            .build()
    }

    fun selectTextTrack(descriptor: TrackDescriptor) {
        val player = _controller.value ?: return
        val group = lastGroups.getOrNull(descriptor.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, descriptor.trackIndex)
            )
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        val player = _controller.value ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        _textTrackDisabled.value = !enabled
    }

    // ----------------------------------------------------------------- stats

    private fun refreshStats() {
        val player = _controller.value ?: return
        var video: Format? = null
        var audio: Format? = null
        player.currentTracks.groups.forEach { group ->
            if (!group.isSelected) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (video == null) video = group.getTrackFormat(i)
                    C.TRACK_TYPE_AUDIO -> if (audio == null) audio = group.getTrackFormat(i)
                }
                break
            }
        }
        _stats.value = PlaybackStats(
            videoCodec = com.malekplay.core.ui.util.DecoderInfo.shortCodec(video?.sampleMimeType),
            videoMimeType = video?.sampleMimeType,
            width = video?.width ?: 0,
            height = video?.height ?: 0,
            frameRate = video?.frameRate ?: 0f,
            bitrate = video?.bitrate?.toLong() ?: 0L,
            audioCodec = com.malekplay.core.ui.util.DecoderInfo.shortCodec(audio?.sampleMimeType),
            audioChannels = audio?.channelCount ?: 0,
            audioSampleRate = audio?.sampleRate ?: 0,
            containerBitrate = video?.bitrate?.toLong() ?: 0L
        )
    }

    // ----------------------------------------------------------- sleep timer

    private var sleepTimerEndOfEpisode = false

    fun setSleepTimer(minutes: Int) {
        require(minutes in 1..240) { "Sleep timer must be between 1 and 240 minutes" }
        cancelSleepTimer()
        sleepTimerEndOfEpisode = false
        _sleepTimerEndsAtMs.value = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            _controller.value?.pause()
            _sleepTimerEndsAtMs.value = 0L
            sleepTimerJob = null
        }
    }

    /** Pauses when the current item ends instead of after a fixed delay. */
    fun setSleepTimerEndOfEpisode() {
        cancelSleepTimer()
        sleepTimerEndOfEpisode = true
        _sleepTimerEndsAtMs.value = Long.MAX_VALUE
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndOfEpisode = false
        _sleepTimerEndsAtMs.value = 0L
    }

    // ------------------------------------------------------------ favourites

    fun toggleFavourite(mediaId: String) {
        viewModelScope.launch {
            runCatchingCancellable { userDataRepository.toggleFavourite(mediaId) }
        }
    }

    // ------------------------------------------------------------- bookmarks

    fun observeBookmarks(mediaId: String): Flow<List<BookmarkEntity>> = mediaRepository.observeBookmarks(mediaId)

    fun addBookmark(mediaId: String, positionMs: Long, label: String) {
        viewModelScope.launch {
            runCatchingCancellable { mediaRepository.addBookmark(mediaId, positionMs, label) }
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            runCatchingCancellable { mediaRepository.deleteBookmark(bookmarkId) }
        }
    }

    // --------------------------------------------------------------- A-B loop

    fun setAbStart() {
        _abStartMs.value = _controller.value?.currentPosition ?: 0L
        _abEndMs.value = null
    }

    fun setAbEnd() {
        val player = _controller.value ?: return
        val start = _abStartMs.value
        val position = player.currentPosition
        if (start == null || position <= start) return
        _abEndMs.value = position
        player.seekTo(start)
    }

    fun clearAbRepeat() {
        _abStartMs.value = null
        _abEndMs.value = null
    }

    private fun applyAbBounds(positionMs: Long) {
        val start = _abStartMs.value ?: return
        val end = _abEndMs.value ?: return
        if (positionMs >= end) _controller.value?.seekTo(start)
    }

    // -------------------------------------------------------------- progress

    /** Reads the saved resume point so the UI can offer "Resume from 12:34". */
    suspend fun savedPositionFor(mediaId: String): Long {
        val progress = runCatchingCancellable { mediaRepository.getProgress(mediaId) }.getOrNull()
            ?: return 0L
        if (progress.isCompleted) return 0L
        return progress.positionMs
    }

    /**
     * Restores the last speed used for this media item. The global preference is
     * only a fallback for first-time playback or missing progress records.
     */
    suspend fun savedPlaybackSpeedFor(mediaId: String, fallback: Float): Float {
        val safeFallback = PlaybackValuePolicy.normalizeSpeed(fallback)
        val progress = runCatchingCancellable { mediaRepository.getProgress(mediaId) }.getOrNull()
            ?: return safeFallback
        return PlaybackValuePolicy.normalizeSpeed(progress.playbackSpeed)
    }

    /** Loads one media row so the player can render its title and artwork. */
    suspend fun loadMedia(mediaId: String): DomainMedia? =
        runCatchingCancellable { mediaRepository.getMedia(mediaId) }.getOrNull()

    /**
     * Builds the complete local music queue for MediaSession controls.
     * A single-item queue disables the system's previous/next actions, even
     * when the user launched that song from the full Music library.
     */
    suspend fun loadAudioQueue(): List<DomainMedia> =
        mediaRepository.observeAudio().first().map { it.media }

    suspend fun toLibraryItems(uris: List<Uri>): List<LibraryItem> =
        runCatchingCancellable { mediaRepository.getMediaList(uris.map { it.toString() }) }
            .getOrDefault(emptyList())
            .map { LibraryItem(media = it) }

    fun currentMediaItem(): MediaItem? = _controller.value?.currentMediaItem

    fun saveOnlineSubtitleCredentials(apiKey: String, username: String, password: String) {
        runCatching { onlineSubtitleRepository.saveCredentials(apiKey, username, password) }
            .onSuccess { _onlineSubtitleCredentialsReady.value = true; _onlineSubtitleError.value = null }
            .onFailure { _onlineSubtitleError.value = it.message ?: "تعذر حفظ بيانات الخدمة" }
    }

    fun searchOnlineSubtitles(title: String, language: String, season: Int?, episode: Int?) {
        if (_onlineSubtitleLoading.value) return
        viewModelScope.launch {
            _onlineSubtitleLoading.value = true
            _onlineSubtitleError.value = null
            _onlineSubtitleResults.value = emptyList()
            try {
                val results = withContext(Dispatchers.IO) {
                    onlineSubtitleRepository.searchAutomatic(title, language, season, episode)
                }
                _onlineSubtitleResults.value = results
                if (results.isEmpty()) _onlineSubtitleError.value = "لم نعثر على ترجمة مطابقة. جرّب تعديل اسم العنوان أو اللغة."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _onlineSubtitleError.value = error.message ?: "تعذر البحث عن الترجمة"
            } finally {
                _onlineSubtitleLoading.value = false
            }
        }
    }

    fun downloadOnlineSubtitle(subtitle: OnlineSubtitle) {
        viewModelScope.launch {
            _onlineSubtitleLoading.value = true
            _onlineSubtitleError.value = null
            try {
                val uri = withContext(Dispatchers.IO) { onlineSubtitleRepository.download(subtitle) }
                addExternalSubtitle(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _onlineSubtitleError.value = error.message ?: "تعذر تنزيل الترجمة"
            } finally {
                _onlineSubtitleLoading.value = false
            }
        }
    }

    override fun onCleared() {
        tickerJob?.cancel()
        sleepTimerJob?.cancel()
        _controller.value?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controller.value = null
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "MalekPlayPlayer"
        const val TICK_INTERVAL_MS = 500L
    }
}


/** Preserve structured coroutine cancellation when converting recoverable failures. */
private suspend fun <T> runCatchingCancellable(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
