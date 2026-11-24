package com.malekplay.ui.player.video

import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.malekplay.MainActivity
import com.malekplay.R
import com.google.mlkit.nl.translate.TranslateLanguage
import com.malekplay.core.ui.theme.PlayerChrome
import com.malekplay.core.ui.util.DecoderInfo
import com.malekplay.core.ui.util.findActivity
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatSpeed
import com.malekplay.core.ui.util.titleWithoutExtension
import com.malekplay.data.local.entity.BookmarkEntity
import com.malekplay.domain.model.EqualizerPreset
import com.malekplay.domain.model.GestureAction
import com.malekplay.domain.model.MediaItem as DomainMedia
import com.malekplay.ui.player.PlayerViewModel
import com.malekplay.player.effects.VideoEnhancementProfile
import com.malekplay.player.effects.GoogleMediaEnhancementPlayerBridge
import com.malekplay.player.effects.GoogleMediaEnhancementState
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.videos.VideoKindClassifier
import com.malekplay.ui.videos.EpisodeSortKey
import com.malekplay.ui.player.components.EqualizerSheet
import com.malekplay.ui.player.components.GestureIndicator
import com.malekplay.ui.player.components.PlayerControlRail
import com.malekplay.ui.player.components.PlayerMainControls
import com.malekplay.ui.player.components.PlayerSeekRow
import com.malekplay.ui.player.components.PlayerTopBar
import com.malekplay.ui.player.components.SeekPulse
import com.malekplay.ui.player.components.SleepTimerDialog
import com.malekplay.ui.player.components.SpeedPickerDialog
import com.malekplay.ui.player.components.StatsSheet
import com.malekplay.ui.player.components.SubtitleStyleSheet
import com.malekplay.ui.player.components.TrackPickerSheet
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/** Video fit modes cycled by the control rail. */
@UnstableApi
private enum class AspectMode(val resizeMode: Int) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

private const val CHROME_AUTO_HIDE_MS = 4_000L
private const val LONG_PRESS_MS = 450L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
/** Milliseconds travelled by a full-width horizontal swipe. */
private const val FULL_SWIPE_SEEK_MS = 120_000L
private const val DOUBLE_TAP_SEEK_MS = 10_000L
private const val SPEED_BOOST = 2.5f

@UnstableApi
@Composable
fun VideoPlayerScreen(
    mediaId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val parentalControl = remember(context) { ParentalControlManager(context) }
    val externalSubtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                viewModel.addExternalSubtitle(uri)
            }
        }
    )
    val activity = remember(context) { context.findActivity() }
    val mainActivity = activity as? MainActivity
    val scope = rememberCoroutineScope()

    val brightnessLabel = stringResource(R.string.player_brightness)
    val volumeLabel = stringResource(R.string.player_volume)
    val boostLabel = stringResource(R.string.player_speed_boost)

    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val controller by viewModel.controller.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val videoUpscalingEnabled by viewModel.videoUpscalingEnabled.collectAsStateWithLifecycle()
    val videoNoiseReductionEnabled by viewModel.videoNoiseReductionEnabled.collectAsStateWithLifecycle()
    val videoEnhancementProfile by viewModel.videoEnhancementProfile.collectAsStateWithLifecycle()
    val videoUpscalingError by viewModel.videoUpscalingError.collectAsStateWithLifecycle()
    val videoEnhancementDiagnostics by viewModel.videoEnhancementDiagnostics.collectAsStateWithLifecycle()
    val latestVideoUpscalingEnabled by rememberUpdatedState(videoUpscalingEnabled)
    val latestVideoNoiseReductionEnabled by rememberUpdatedState(videoNoiseReductionEnabled)
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val bufferedMs by viewModel.bufferedMs.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    val textTracks by viewModel.textTracks.collectAsStateWithLifecycle()
    val textTrackDisabled by viewModel.textTrackDisabled.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val onlineSubtitleResults by viewModel.onlineSubtitleResults.collectAsStateWithLifecycle()
    val onlineSubtitleLoading by viewModel.onlineSubtitleLoading.collectAsStateWithLifecycle()
    val onlineSubtitleError by viewModel.onlineSubtitleError.collectAsStateWithLifecycle()
    val subtitleTranslationAvailable by viewModel.subtitleTranslationAvailable.collectAsStateWithLifecycle()
    val subtitleTranslationLoading by viewModel.subtitleTranslationLoading.collectAsStateWithLifecycle()
    val subtitleTranslationCompletedCues by viewModel.subtitleTranslationCompletedCues.collectAsStateWithLifecycle()
    val subtitleTranslationTotalCues by viewModel.subtitleTranslationTotalCues.collectAsStateWithLifecycle()
    val subtitleTranslationError by viewModel.subtitleTranslationError.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.sleepTimerEndsAtMs.collectAsStateWithLifecycle()
    val inPip by viewModel.pipController.inPip.collectAsStateWithLifecycle()
    val dlnaPlayback by viewModel.dlnaPlayback.collectAsStateWithLifecycle()


    val eqAvailable by viewModel.audioEffects.available.collectAsStateWithLifecycle()
    val bandCount by viewModel.audioEffects.bandCount.collectAsStateWithLifecycle()
    val levelRange by viewModel.audioEffects.levelRange.collectAsStateWithLifecycle()
    val virtualizerSupported by viewModel.audioEffects.virtualizerSupported.collectAsStateWithLifecycle()
    val outputRouteLabel by viewModel.audioEffects.outputRouteLabel.collectAsStateWithLifecycle()
    val processingMetrics by viewModel.audioEffects.processingMetrics.collectAsStateWithLifecycle()
    val processingStatus = when {
        !processingMetrics.configured -> "مسار المعالجة ينتظر صوت PCM مدعوماً؛ قد لا تعمل المؤثرات مع إخراج الصوت المشفّر المباشر."
        processingMetrics.effectFrames > 0L -> "تعمل المؤثرات فعلياً: تمت معالجة ${processingMetrics.effectFrames} إطاراً صوتياً."
        processingMetrics.processedFrames > 0L -> "مسار الصوت متصل؛ لم يتم تفعيل تأثير غير مسطح بعد."
        else -> "لم تصل إطارات صوتية إلى معالج المؤثرات حتى الآن."
    }

    // ------------------------------------------------------------ media load
    var media by remember { mutableStateOf<DomainMedia?>(null) }
    var title by remember { mutableStateOf("") }
    val currentVideo = if (queue.any { it.id == mediaId }) queue.getOrNull(currentIndex) else media
    val currentVideoId = currentVideo?.id ?: mediaId
    val currentVideoTitle = currentVideo?.displayTitle ?: title
    val bookmarkFlow = remember(currentVideoId) { viewModel.observeBookmarks(currentVideoId) }
    val bookmarks by bookmarkFlow.collectAsStateWithLifecycle(initialValue = emptyList<com.malekplay.data.local.entity.BookmarkEntity>())
    var resolved by remember(mediaId) { mutableStateOf(false) }
    var resumeMs by remember(mediaId) { mutableLongStateOf(0L) }
    var resumeSpeed by remember(mediaId) { mutableFloatStateOf(settings.defaultSpeed) }

    LaunchedEffect(mediaId) {
        val item = viewModel.loadMedia(mediaId)
        media = item
        if (item != null) {
            title = item.displayTitle
        } else {
            // File opened from another app: the Uri is also the progress-store key.
            val uri = Uri.parse(mediaId)
            title = uri.lastPathSegment?.let { titleWithoutExtension(it) } ?: mediaId
        }
        resumeMs = if (settings.resumePlayback) viewModel.savedPositionFor(mediaId) else 0L
        resumeSpeed = viewModel.savedPlaybackSpeedFor(mediaId, settings.defaultSpeed)
        resolved = true
    }

    LaunchedEffect(resolved, controller) {
        if (!resolved || controller == null) return@LaunchedEffect
        val item = media
        if (item != null) {
            // Series playback stays inside the same show and follows numeric
            // season/episode order; movies retain the normal library queue.
            val discoveredQueue = viewModel.loadVideoQueue()
            val videoQueue = if (parentalControl.isEnabled) {
                val approvedIds = parentalControl.allowedMediaIds
                discoveredQueue.filter { it.id in approvedIds }
            } else discoveredQueue
            val selectedEpisode = VideoKindClassifier.episodeSortKey(
                item.displayTitle, item.folderName, item.folderPath
            )
            val playbackQueue = if (selectedEpisode != null) {
                videoQueue.mapNotNull { candidate ->
                    val key = VideoKindClassifier.episodeSortKey(
                        candidate.displayTitle, candidate.folderName, candidate.folderPath
                    )
                    if (key?.seriesKey == selectedEpisode.seriesKey) candidate to key else null
                }.sortedWith(
                    compareBy<Pair<DomainMedia, EpisodeSortKey>>(
                        { it.second.season },
                        { it.second.episode }
                    )
                ).map { it.first }
            } else {
                videoQueue
            }
            val selectedIndex = playbackQueue.indexOfFirst { it.id == item.id }
            if (selectedIndex >= 0) {
                viewModel.playQueue(
                    items = playbackQueue,
                    startIndex = selectedIndex,
                    startPositionMs = resumeMs,
                    speed = resumeSpeed
                )
            } else {
                viewModel.playSingle(item, resumeMs, resumeSpeed)
            }
        } else {
            viewModel.playExternalUri(Uri.parse(mediaId), resumeMs, resumeSpeed)
        }
    }

    // --------------------------------------------------------- audio effects
    LaunchedEffect(settings.equalizerPresetIndex, settings.equalizerBands) {
        if (settings.equalizerPresetIndex == EqualizerPreset.CUSTOM.ordinal) {
            viewModel.audioEffects.setBands(settings.equalizerBands)
        } else {
            viewModel.audioEffects.applyPreset(EqualizerPreset.fromIndex(settings.equalizerPresetIndex))
        }
    }
    LaunchedEffect(
        settings.bassBoostStrength,
        settings.virtualizerStrength,
        settings.loudnessGainDb
    ) {
        viewModel.audioEffects.setBassBoost(settings.bassBoostStrength)
        viewModel.audioEffects.setVirtualizer(settings.virtualizerStrength)
        viewModel.audioEffects.setLoudness(settings.loudnessGainDb)
    }

    // -------------------------------------------------------- screen + power
    DisposableEffect(activity) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // Restore normal system bars when leaving the video player.
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            if (!inPip) mainActivity?.setOrientation(false)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Never leave the user with a dimmed or blown-out screen.
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
        }
    }

    // GPU processing is opt-in and scoped to the visible video-player session.
    // Leaving the screen always releases the additional GL effect, even if audio
    // playback continues in the background.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.setVideoUpscalingEnabled(false)
            viewModel.setVideoNoiseReductionEnabled(false)
        }
    }

    // -------------------------------------------------------------- ui state
    var chromeVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var railExpanded by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(AspectMode.FIT.ordinal) }
    val aspectMode = AspectMode.entries[aspect]
    var videoZoom by remember { mutableFloatStateOf(1f) }
    val latestVideoZoom by rememberUpdatedState(videoZoom)

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showTextTracks by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val displayHdrCapabilities = remember(showStats, activity) {
        if (!showStats) "—"
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) "غير متاح على هذا الإصدار"
        else runCatching {
            HdrInfoPolicy.displayCapabilities(
                activity?.window?.decorView?.display?.hdrCapabilities?.supportedHdrTypes
            )
        }.getOrDefault("غير معروف")
    }
    var showSubtitleStyle by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showVideoDiagnostics by remember { mutableStateOf(false) }
    var showTranslationLanguages by remember { mutableStateOf(false) }
    var showOnlineSubtitles by remember { mutableStateOf(false) }
    var showDlnaPicker by remember { mutableStateOf(false) }
    var showRemoteOutputDialog by remember { mutableStateOf(false) }
    var chromeInteractionVersion by remember { mutableIntStateOf(0) }
    val hasOpenPlayerOverlay = showSpeed || showSleep || showAudioTracks || showTextTracks ||
        showStats || showSubtitleStyle || showEqualizer || showBookmarks || showQueue ||
        showMenu || showTranslationLanguages || showOnlineSubtitles || showDlnaPicker ||
        showRemoteOutputDialog
    val activeDlnaDevice = dlnaPlayback.activeDevice
    val remoteDlnaPlaying = dlnaPlayback.isPlaying
    val displayedPositionMs = if (activeDlnaDevice != null) dlnaPlayback.positionMs else positionMs
    val displayedDurationMs = if (activeDlnaDevice != null && dlnaPlayback.durationMs > 0L) dlnaPlayback.durationMs else durationMs

    LaunchedEffect(currentVideoId) {
        videoZoom = 1f
    }

    LaunchedEffect(dlnaPlayback.errorMessage) {
        dlnaPlayback.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearDlnaError()
        }
    }

    LaunchedEffect(dlnaPlayback.activeDevice?.id) {
        dlnaPlayback.activeDevice?.let { device ->
            Toast.makeText(context, "تم إرسال أمر التشغيل إلى ${device.name}", Toast.LENGTH_LONG).show()
        }
    }
    val subtitleQuery = remember(currentVideoId, currentVideoTitle) {
        com.malekplay.network.SubtitleQueryCleaner.clean(currentVideoTitle)
    }
    // Include a parsed release year in the editable query, but never include
    // resolution/codec/release-group noise or the SxxExx suffix.
    val initialSubtitleQuery = remember(subtitleQuery) {
        subtitleQuery.title + (subtitleQuery.year?.let { " $it" } ?: "")
    }
    var onlineQuery by remember(currentVideoId, currentVideoTitle) {
        mutableStateOf(initialSubtitleQuery)
    }
    var onlineLanguage by remember { mutableStateOf("ar,en") }
    var onlineSeason by remember(currentVideoId, currentVideoTitle) {
        mutableStateOf(subtitleQuery.season?.toString().orEmpty())
    }
    var onlineEpisode by remember(currentVideoId, currentVideoTitle) {
        mutableStateOf(subtitleQuery.episode?.toString().orEmpty())
    }

    LaunchedEffect(videoUpscalingError) {
        videoUpscalingError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearVideoUpscalingError()
        }
    }

    LaunchedEffect(subtitleTranslationError) {
        subtitleTranslationError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(chromeVisible, isPlaying, locked, chromeInteractionVersion, hasOpenPlayerOverlay) {
        if (chromeVisible && isPlaying && !locked && !hasOpenPlayerOverlay) {
            delay(CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    LaunchedEffect(inPip) {
        if (inPip) chromeVisible = false
    }

    // ------------------------------------------------- brightness and volume
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    var brightness by remember {
        val initial = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (initial in 0f..1f) initial else 0.5f)
    }
    var volume by remember(audioManager) {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        mutableFloatStateOf((current.toFloat() / maxVolume).coerceIn(0f, 1f))
    }

    fun applyBrightness(value: Float) {
        brightness = value.coerceIn(0.01f, 1f)
        activity?.window?.let { window ->
            val params = window.attributes
            params.screenBrightness = brightness
            window.attributes = params
        }
    }

    fun applyVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        val manager = audioManager ?: return
        runCatching {
            val target = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    // --------------------------------------------------------- gesture layer
    var indicator by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var boostActive by remember { mutableStateOf(false) }

    fun pushSeek(deltaFraction: Float) {
        seekDeltaMs += (deltaFraction * FULL_SWIPE_SEEK_MS).toLong()
    }

    fun seekByPlayback(deltaMs: Long) {
        if (activeDlnaDevice == null) viewModel.seekBy(deltaMs)
        else viewModel.seekDlnaBy(deltaMs)
    }

    fun togglePlayback() {
        if (activeDlnaDevice == null) viewModel.togglePlayPause()
        else viewModel.toggleDlnaPlayback()
    }

    fun commitSeek() {
        if (seekDeltaMs != 0L) seekByPlayback(seekDeltaMs)
        seekDeltaMs = 0L
    }

    val gestureModifier = if (locked) {
        Modifier
    } else {
        Modifier.pointerInput(settings) {
            detectPlayerGestures(
                onTap = { chromeVisible = !chromeVisible },
                onPinch = { factor -> videoZoom = PlayerZoomPolicy.scaleBy(latestVideoZoom, factor) },
                onDoubleTap = { fraction ->
                    when (settings.gestureDoubleTap) {
                        GestureAction.SEEK -> {
                            val forward = fraction >= 0.5f
                            seekByPlayback(if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS)
                        }
                        GestureAction.PLAY_PAUSE -> togglePlayback()
                        else -> Unit
                    }
                },
                onLongPressStart = {
                    if (settings.gestureLongPress == GestureAction.SPEED_BOOST && !boostActive) {
                        boostActive = true
                        viewModel.setSpeed(SPEED_BOOST)
                        indicator = boostLabel to null
                    }
                },
                onLongPressEnd = {
                    if (boostActive) {
                        boostActive = false
                        viewModel.setSpeed(settings.defaultSpeed)
                        indicator = null
                    }
                },
                onDragStart = { chromeVisible = false },
                onDrag = { startXFraction, dxFraction, dyFraction ->
                    if (abs(dxFraction) > abs(dyFraction)) {
                        if (settings.gestureSwipeHorizontal == GestureAction.SEEK) {
                            pushSeek(dxFraction)
                        }
                    } else {
                        val action = if (startXFraction < 0.5f) {
                            settings.gestureVerticalLeft
                        } else {
                            settings.gestureVerticalRight
                        }
                        when (action) {
                            GestureAction.BRIGHTNESS -> {
                                if (settings.rememberBrightness) {
                                    applyBrightness(brightness + dyFraction)
                                    indicator = brightnessLabel to brightness
                                }
                            }
                            GestureAction.VOLUME -> {
                                applyVolume(volume + dyFraction)
                                indicator = volumeLabel to volume
                            }
                            // Vertical seek follows the finger's vertical movement.
                            GestureAction.SEEK -> pushSeek(dyFraction)
                            else -> Unit
                        }
                    }
                },
                onDragEnd = {
                    commitSeek()
                    indicator = null
                }
            )
        }
    }

    // ------------------------------------------------------------ screenshot
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var googleAiEnabled by remember { mutableStateOf(false) }
    // Keep the enhancement backend lifecycle aligned with the actual queue item.
    // The internal GPU fallback is service-managed and does not self-restart on transitions.
    var googleAiMediaId by remember { mutableStateOf(currentVideoId) }
    var internalAiFallback by remember { mutableStateOf(false) }
    var internalAiFallbackPending by remember { mutableStateOf(false) }
    var googleAiRequestVersion by remember { mutableIntStateOf(0) }
    var googleAiState by remember {
        mutableStateOf(GoogleMediaEnhancementState.DISABLED)
    }
    var googleAiProgress by remember { mutableIntStateOf(0) }
    var googleAiMessage by remember { mutableStateOf<String?>(null) }
    var showGoogleAiInstallDialog by remember { mutableStateOf(false) }
    var googleAiBridge by remember {
        mutableStateOf<GoogleMediaEnhancementPlayerBridge?>(null)
    }
    fun takeScreenshot() {
        val view = playerView ?: return
        scope.launch {
            val saved = captureAndSave(context, view, currentVideoTitle)
            val message = if (saved) {
                context.getString(R.string.player_screenshot_saved)
            } else {
                context.getString(R.string.player_screenshot_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun activateInternalAiFallback() {
        if (internalAiFallback || internalAiFallbackPending) return
        // Stop observing/retrying the Google surface backend before switching
        // the same player back to its native output and internal GPU effects.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) googleAiBridge?.close()
        googleAiBridge = null
        internalAiFallbackPending = true
        val requestVersion = googleAiRequestVersion
        viewModel.setInternalGpuEnhancementEnabled(true) { applied ->
            // A completion from an older video/toggle must not clear a newer
            // pending request or disable the GPU chain already enabled for it.
            if (requestVersion != googleAiRequestVersion) {
                if (!googleAiEnabled && applied) {
                    viewModel.setInternalGpuEnhancementEnabled(false)
                }
                return@setInternalGpuEnhancementEnabled
            }
            internalAiFallbackPending = false
            if (!googleAiEnabled) {
                if (applied) viewModel.setInternalGpuEnhancementEnabled(false)
                return@setInternalGpuEnhancementEnabled
            }
            if (applied) {
                internalAiFallback = true
                googleAiState = if (videoEnhancementDiagnostics.totalShaderDraws > 0L) {
                    GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE
                } else {
                    GoogleMediaEnhancementState.INTERNAL_GPU_WAITING_FOR_FIRST_FRAME
                }
                googleAiProgress = 0
                googleAiMessage = if (videoEnhancementDiagnostics.totalShaderDraws > 0L) {
                    "محرك GPU يعالج إطارات الفيديو فعلياً مع تحسين التفاصيل وتقليل الضوضاء"
                } else {
                    "تم تجهيز مسار GPU؛ ننتظر أول إطار تتم معالجته قبل تأكيد عمل التحسين"
                }
            } else {
                internalAiFallback = false
                googleAiState = GoogleMediaEnhancementState.UNSUPPORTED
                googleAiMessage = "تعذر تشغيل محرك GPU على مسار الإخراج الحالي؛ لم يتم تغيير سطح الفيديو."
            }
        }
    }

    LaunchedEffect(internalAiFallback, videoEnhancementDiagnostics.totalShaderDraws) {
        if (!internalAiFallback) return@LaunchedEffect
        if (videoEnhancementDiagnostics.totalShaderDraws > 0L) {
            googleAiState = GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE
            googleAiMessage = "محرك GPU يعالج إطارات الفيديو فعلياً مع تحسين التفاصيل وتقليل الضوضاء"
        } else if (googleAiState == GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE) {
            googleAiState = GoogleMediaEnhancementState.INTERNAL_GPU_WAITING_FOR_FIRST_FRAME
            googleAiMessage = "ننتظر أول إطار تتم معالجته بعد تغيير مسار الفيديو"
        }
    }

    LaunchedEffect(googleAiEnabled, controller, playerView, currentVideoId) {
        if (googleAiMediaId != currentVideoId) {
            // PlaybackService intentionally clears per-video GPU flags on a queue
            // transition. Recreate the selected AI backend for the new item instead
            // of leaving the screen's local fallback flag latched to the old item.
            googleAiMediaId = currentVideoId
            googleAiRequestVersion++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) googleAiBridge?.close()
            googleAiBridge = null
            internalAiFallback = false
            internalAiFallbackPending = false
            googleAiState = if (googleAiEnabled) {
                GoogleMediaEnhancementState.WAITING_FOR_VIDEO
            } else {
                GoogleMediaEnhancementState.DISABLED
            }
            googleAiProgress = 0
            googleAiMessage = null
        }
        if (!googleAiEnabled) {
            // If fallback already completed, disable it here. If its enable command
            // is still pending, its generation-checked callback owns the single
            // compensating disable; sending one here would race and duplicate it.
            if (internalAiFallback) {
                viewModel.setInternalGpuEnhancementEnabled(false)
            }
            internalAiFallback = false
            internalAiFallbackPending = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) googleAiBridge?.close()
            googleAiBridge = null
            googleAiState = GoogleMediaEnhancementState.DISABLED
            googleAiProgress = 0
            googleAiMessage = null
            showGoogleAiInstallDialog = false
            return@LaunchedEffect
        }

        // A pending fallback is already switching the service to its internal
        // GPU path. Do not start a second Google surface session if playerView or
        // controller changes while that command is still in flight.
        if (internalAiFallback || internalAiFallbackPending) return@LaunchedEffect

        // Wait until the service confirms both OpenGL effects are off before routing
        // decoded frames into the separate Google AI surface chain.
        if (latestVideoUpscalingEnabled || latestVideoNoiseReductionEnabled) {
            // Switch both GPU stages off once, then wait for the authoritative
            // service state before attaching Google's alternative output surface.
            viewModel.setInternalGpuEnhancementEnabled(false)
            snapshotFlow {
                latestVideoUpscalingEnabled || latestVideoNoiseReductionEnabled
            }.first { active -> !active }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activateInternalAiFallback()
            return@LaunchedEffect
        }

        val activePlayer = controller
        val activeView = playerView
        val videoSurface = activeView?.videoSurfaceView as? SurfaceView
        if (activePlayer == null) {
            googleAiState = GoogleMediaEnhancementState.WAITING_FOR_VIDEO
            return@LaunchedEffect
        }
        if (videoSurface == null) {
            // TextureView or OEM-specific output routing cannot use the Google
            // surface bridge; keep AI enhancement available through the GPU path.
            activateInternalAiFallback()
            return@LaunchedEffect
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) googleAiBridge?.close()
        googleAiBridge = null
        googleAiState = GoogleMediaEnhancementState.CHECKING_DEVICE
        googleAiMessage = null
        val bridge = runCatching {
            GoogleMediaEnhancementPlayerBridge(
                context = context,
                player = activePlayer,
                surfaceView = videoSurface,
                listener = object : GoogleMediaEnhancementPlayerBridge.Listener {
                    override fun onStateChanged(
                        state: GoogleMediaEnhancementState,
                        progress: Int,
                        message: String?
                    ) {
                        if (state == GoogleMediaEnhancementState.UNSUPPORTED ||
                            state == GoogleMediaEnhancementState.FAILED
                        ) {
                            activateInternalAiFallback()
                        } else {
                            googleAiState = state
                            googleAiProgress = progress
                            googleAiMessage = message
                        }
                        if (state == GoogleMediaEnhancementState.INSTALL_REQUIRED) {
                            showGoogleAiInstallDialog = true
                        }
                    }
                }
            )
        }.getOrElse { error ->
            activateInternalAiFallback()
            return@LaunchedEffect
        }
        googleAiBridge = bridge
        runCatching { bridge.setEnabled(true) }
            .onFailure { activateInternalAiFallback() }
    }

    // ---------------------------------------------------------------- layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any touch (including toolbar buttons) restarts the chrome timeout.
            // Do not consume the event; child controls and gesture handling keep working.
            .pointerInput(chromeVisible) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    chromeInteractionVersion++
                }
            }
    ) {
        if (subtitleTranslationLoading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = Color(0xF0181822),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ترجمة الترجمة إلى العربية", color = Color.White)
                        TextButton(onClick = viewModel::cancelSubtitleTranslation) {
                            Text("إلغاء")
                        }
                    }
                    val total = subtitleTranslationTotalCues
                    val completed = subtitleTranslationCompletedCues
                    val fraction = if (total > 0) completed.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = fraction.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (total > 0) "$completed / $total مقطع" else
                            "جارٍ تجهيز نموذج اللغة أو تحليل ملف الترجمة…",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (internalAiFallback) {
            Text(
                text = "يعمل محرك الذكاء البصري الداخلي عبر GPU (الوضع الذكي)",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color(0xCC211638))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                view.player = controller
                view.resizeMode = aspectMode.resizeMode
                view.scaleX = videoZoom
                view.scaleY = videoZoom
                applySubtitleStyle(view, settings)
                playerView = view
            },
            onRelease = { view ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) googleAiBridge?.close()
                googleAiBridge = null
                view.player = null
                playerView = null
            }
        )

        // Gesture surface sits above the video but below the chrome.
        Box(modifier = Modifier.fillMaxSize().then(gestureModifier))

        if (isBuffering && !inPip) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = PlayerChrome.ProgressActive
            )
        }

        AnimatedVisibility(visible = seekDeltaMs != 0L) {
            SeekPulse(
                label = buildString {
                    val target = (positionMs + seekDeltaMs).coerceIn(
                        0L,
                        durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                    )
                    append(formatDuration(target))
                    append(" / ")
                    append(formatDuration(durationMs))
                },
                isForward = seekDeltaMs >= 0,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        indicator?.let { (text, progress) ->
            GestureIndicator(
                text = text,
                progress = progress,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        playerError?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = MaterialTheme.shapes.medium,
                color = PlayerChrome.SurfaceStrong
            ) {
                Text(
                    error,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && !inPip && !locked,
            enter = fadeIn() + slideInVertically(
                initialOffsetY = { height -> height / 18 }
            ),
            exit = fadeOut() + slideOutVertically(
                targetOffsetY = { height -> height / 24 }
            )
        ) {
            // Cinematic chrome: soft top/bottom vignettes keep controls legible
            // without changing the video surface, gestures, or Media3 controller.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xCC05030A),
                                0.20f to Color(0x2605030A),
                                0.52f to Color.Transparent,
                                0.76f to Color(0x3305030A),
                                1.0f to Color(0xE605030A)
                            )
                        )
                    )
            )
            Column(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PlayerTopBar(
                    title = currentVideoTitle,
                    decoderBadge = if (stats.videoMimeType != null) {
                        if (DecoderInfo.isHardwareAccelerated(stats.videoMimeType)) {
                            stringResource(R.string.player_hw)
                        } else {
                            stringResource(R.string.player_sw)
                        }
                    } else null,
                    onBack = onBack,
                    onAudioTrack = { showAudioTracks = true },
                    onSubtitles = { showTextTracks = true },
                    onNetworkOutput = {
                        if (activeDlnaDevice == null) showDlnaPicker = true
                        else showRemoteOutputDialog = true
                    },
                    onMore = { showMenu = true },
                    modifier = Modifier.statusBarsPadding()
                )
                }


                Spacer(modifier = Modifier.weight(1f))

                Column(modifier = Modifier.navigationBarsPadding()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PlayerControlRail(
                        speedLabel = formatSpeed(speed),
                        expanded = railExpanded,
                        onExpandToggle = { railExpanded = !railExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        onEqualizer = { showEqualizer = true },
                        onSpeed = { showSpeed = true },
                        onScreenshot = ::takeScreenshot,
                        onRotate = {
                            landscape = !landscape
                            mainActivity?.setOrientation(landscape)
                        },
                        onAspect = { aspect = aspectMode.next().ordinal },
                        onSleep = { showSleep = true },
                        onSubtitleStyle = { showSubtitleStyle = true },
                        onStats = { showStats = true },
                        onBookmarks = { showBookmarks = true },
                        onPip = { viewModel.pipController.request() },
                        onRepeat = viewModel::cycleRepeatMode,
                        repeatActive = repeatMode != Player.REPEAT_MODE_OFF
                    )
                    }


                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PlayerSeekRow(
                        positionMs = displayedPositionMs,
                        durationMs = displayedDurationMs,
                        bufferedMs = bufferedMs,
                        onScrubFinished = { targetMs ->
                            if (activeDlnaDevice == null) viewModel.seekTo(targetMs)
                            else viewModel.seekDlnaTo(targetMs)
                        }
                    )
                    }


                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PlayerMainControls(
                        isPlaying = if (activeDlnaDevice != null) remoteDlnaPlaying else isPlaying,
                        hasNext = currentIndex < queue.lastIndex,
                        hasPrevious = currentIndex > 0,
                        onPrevious = {
                            if (activeDlnaDevice != null) {
                                viewModel.stopDlnaPlayback(resumeLocal = true) {
                                    viewModel.seekToPrevious()
                                }
                            } else {
                                viewModel.seekToPrevious()
                            }
                        },
                        onPlayPause = ::togglePlayback,
                        onNext = {
                            if (activeDlnaDevice != null) {
                                viewModel.stopDlnaPlayback(resumeLocal = true) {
                                    viewModel.seekToNext()
                                }
                            } else {
                                viewModel.seekToNext()
                            }
                        },
                        onQueue = { showQueue = true },
                        onPip = { viewModel.pipController.request() },
                        onLock = { locked = true }
                    )
                    }

                }
            }
        }

        if (locked) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.player_unlock),
                    tint = Color.White
                )
            }
        }

        // -------------------------------------------------- menus and dialogs
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
        ) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_open_subtitle)) },
                    onClick = {
                        showMenu = false
                        externalSubtitlePicker.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_search_subtitle_online)) },
                    onClick = { showMenu = false; showOnlineSubtitles = true }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (subtitleTranslationLoading) R.string.subtitle_translate_ar_loading
                                else R.string.subtitle_translate_ar
                            )
                        )
                    },
                    enabled = subtitleTranslationAvailable && !subtitleTranslationLoading,
                    onClick = {
                        showMenu = false
                        showTranslationLanguages = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_restart)) },
                    onClick = { showMenu = false; viewModel.seekTo(0L) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_ab_set_a)) },
                    onClick = { showMenu = false; viewModel.setAbStart() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_ab_set_b)) },
                    onClick = { showMenu = false; viewModel.setAbEnd() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_ab_clear)) },
                    onClick = { showMenu = false; viewModel.clearAbRepeat() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_add_bookmark)) },
                    onClick = {
                        showMenu = false
                        viewModel.addBookmark(currentVideoId, positionMs, formatDuration(positionMs))
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "تحسين جودة الفيديو: " +
                                if (videoUpscalingEnabled || videoNoiseReductionEnabled) "مفعّل" else "متوقف"
                        )
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setInternalGpuEnhancementEnabled(
                            !(videoUpscalingEnabled || videoNoiseReductionEnabled)
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (videoEnhancementProfile == VideoEnhancementProfile.ECONOMY) "✓ " else "") +
                                stringResource(R.string.player_video_quality_economy)
                        )
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setVideoEnhancementProfile(VideoEnhancementProfile.ECONOMY)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (videoEnhancementProfile == VideoEnhancementProfile.BALANCED) "✓ " else "") +
                                stringResource(R.string.player_video_quality_balanced)
                        )
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setVideoEnhancementProfile(VideoEnhancementProfile.BALANCED)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (videoEnhancementProfile == VideoEnhancementProfile.ULTRA) "✓ " else "") +
                                stringResource(R.string.player_video_quality_ultra)
                        )
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setVideoEnhancementProfile(VideoEnhancementProfile.ULTRA)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (videoUpscalingEnabled) R.string.player_gpu_upscaling_on
                                else R.string.player_gpu_upscaling_off
                            )
                        )
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setVideoUpscalingEnabled(!videoUpscalingEnabled)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(
                            if (videoNoiseReductionEnabled) R.string.player_noise_reduction_on
                            else R.string.player_noise_reduction_off
                        ))
                    },
                    enabled = !googleAiEnabled,
                    onClick = {
                        showMenu = false
                        viewModel.setVideoNoiseReductionEnabled(!videoNoiseReductionEnabled)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_video_diagnostics)) },
                    onClick = { showMenu = false; showVideoDiagnostics = true }
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(
                            if (googleAiEnabled) R.string.player_google_ai_on
                            else R.string.player_google_ai_off
                        ))
                    },
                    onClick = {
                        showMenu = false
                        googleAiRequestVersion++
                        googleAiEnabled = !googleAiEnabled
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_bookmarks)) },
                    onClick = { showMenu = false; showBookmarks = true }
                )
            }
        }

        if (showGoogleAiInstallDialog) {
            AlertDialog(
                onDismissRequest = { showGoogleAiInstallDialog = false },
                title = { Text(stringResource(R.string.player_google_ai_install_title)) },
                text = { Text(stringResource(R.string.player_google_ai_install_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showGoogleAiInstallDialog = false
                        googleAiBridge?.installModule()
                    }) {
                        Text(stringResource(R.string.player_google_ai_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGoogleAiInstallDialog = false }) {
                        Text(stringResource(R.string.player_google_ai_later))
                    }
                }
            )
        }

        DisposableEffect(showVideoDiagnostics, viewModel) {
            viewModel.setVideoDiagnosticsVisible(showVideoDiagnostics)
            onDispose {
                // A retained navigation ViewModel must not keep polling after
                // the player screen or diagnostics sheet leaves composition.
                viewModel.setVideoDiagnosticsVisible(false)
            }
        }

        if (showVideoDiagnostics) {
            val diagnostics = videoEnhancementDiagnostics
            AlertDialog(
                onDismissRequest = { showVideoDiagnostics = false },
                title = { Text(stringResource(R.string.player_video_diagnostics)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val internalGpuState =
                            googleAiState == GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE ||
                                googleAiState == GoogleMediaEnhancementState.INTERNAL_GPU_WAITING_FOR_FIRST_FRAME
                        val googleAiStatusLabel = when (googleAiState) {
                            GoogleMediaEnhancementState.DISABLED -> R.string.player_google_ai_disabled
                            GoogleMediaEnhancementState.WAITING_FOR_VIDEO -> R.string.player_google_ai_waiting
                            GoogleMediaEnhancementState.CHECKING_DEVICE -> R.string.player_google_ai_checking
                            GoogleMediaEnhancementState.INSTALL_REQUIRED -> R.string.player_google_ai_install_required
                            GoogleMediaEnhancementState.INSTALLING -> R.string.player_google_ai_installing
                            GoogleMediaEnhancementState.WAITING_FOR_FIRST_FRAME -> R.string.player_google_ai_first_frame
                            GoogleMediaEnhancementState.ACTIVE -> R.string.player_google_ai_active
                            GoogleMediaEnhancementState.INTERNAL_GPU_WAITING_FOR_FIRST_FRAME -> R.string.player_internal_gpu_waiting
                            GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE -> R.string.player_internal_gpu_active
                            GoogleMediaEnhancementState.UNSUPPORTED -> R.string.player_google_ai_unsupported
                            GoogleMediaEnhancementState.FAILED -> R.string.player_google_ai_failed
                        }
                        Text(
                            stringResource(
                                if (internalGpuState) R.string.player_internal_gpu_status
                                else R.string.player_google_ai_status,
                                stringResource(googleAiStatusLabel)
                            )
                        )
                        Text(
                            stringResource(R.string.player_google_ai_requirement),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (googleAiState == GoogleMediaEnhancementState.INSTALLING) {
                            Text(stringResource(R.string.player_google_ai_progress, googleAiProgress))
                        }
                        googleAiMessage?.let { message ->
                            Text(
                                message,
                                color = if (googleAiState == GoogleMediaEnhancementState.INTERNAL_GPU_ACTIVE)
                                    MaterialTheme.colorScheme.primary
                                else if (googleAiState == GoogleMediaEnhancementState.INTERNAL_GPU_WAITING_FOR_FIRST_FRAME)
                                    MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "تحسين GPU: " +
                                if (diagnostics.upscalingEnabled || diagnostics.noiseReductionEnabled) "مفعّل" else "متوقف"
                        )
                        Text(stringResource(if (diagnostics.bridgeAttached) R.string.player_diag_bridge_ready else R.string.player_diag_bridge_missing))
                        Text(stringResource(if (diagnostics.upscalingSubmitted) R.string.player_diag_lanczos_submitted else R.string.player_diag_lanczos_not_submitted))
                        Text(stringResource(R.string.player_diag_denoise_draws, diagnostics.denoiseDraws))
                        Text(stringResource(R.string.player_diag_detail_draws, diagnostics.detailDraws))
                        Text(stringResource(R.string.player_diag_total_draws, diagnostics.totalShaderDraws))
                        Text("إنشاء برنامج Shader: ${diagnostics.programCreations}")
                        Text("استدعاءات تهيئة الأبعاد: ${diagnostics.configureCalls}")
                        Text("دخول drawFrame: ${diagnostics.drawFrameCalls}")
                        Text("نجاح رسم OpenGL: ${diagnostics.drawFrameSuccesses}")
                        Text("تأكيد Media3 لوصول أول إطار للمخرج: ${diagnostics.renderedFirstFrames}")
                        Text("نوع مخرج العرض: ${diagnostics.lastRenderedOutput}")
                        Text("وقت آخر تأكيد إطار (ms): ${diagnostics.lastRenderedFirstFrameTimeMs}")
                        Text("آخر أبعاد مهيأة: ${diagnostics.configuredWidth}×${diagnostics.configuredHeight}")
                        Text("آخر مرحلة في المسار: ${diagnostics.pipelineStage}")
                        Text(stringResource(R.string.player_diag_renderer_frames, diagnostics.rendererFrames))
                        Text(stringResource(R.string.player_diag_processing_offset, diagnostics.averageProcessingOffsetUs))
                        Text(stringResource(R.string.player_diag_dropped_frames, diagnostics.droppedFrames))
                        Text(stringResource(R.string.player_diag_last_effect, diagnostics.lastEffect))
                        diagnostics.lastFailure?.let { Text(stringResource(R.string.player_diag_last_failure, it), color = MaterialTheme.colorScheme.error) }
                        if (diagnostics.totalShaderDraws == 0L && (diagnostics.noiseReductionEnabled || diagnostics.upscalingEnabled)) {
                            val message = if (diagnostics.rendererFrames > 0L) R.string.player_diag_gpu_pipeline_stalled else R.string.player_diag_waiting_first_frame
                            Text(stringResource(message), color = if (diagnostics.rendererFrames > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                        }
                        Text(stringResource(R.string.player_diag_lanczos_note), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { showVideoDiagnostics = false }) { Text(stringResource(R.string.common_close)) } }
            )
        }

        if (showSpeed) {
            SpeedPickerDialog(
                current = speed,
                onSelect = viewModel::setSpeed,
                onDismiss = { showSpeed = false }
            )
        }

        if (showSleep) {
            SleepTimerDialog(
                active = sleepEndsAt > 0L,
                onMinutes = viewModel::setSleepTimer,
                onEndOfEpisode = viewModel::setSleepTimerEndOfEpisode,
                onCancel = viewModel::cancelSleepTimer,
                onDismiss = { showSleep = false }
            )
        }

        if (showAudioTracks) {
            TrackPickerSheet(
                title = stringResource(R.string.player_audio_track),
                tracks = audioTracks,
                disabled = false,
                onSelect = viewModel::selectAudioTrack,
                onSetDisabled = {},
                onDismiss = { showAudioTracks = false },
                showDisableOption = false
            )
        }

        if (showTextTracks) {
            TrackPickerSheet(
                title = stringResource(R.string.player_subtitle_track),
                tracks = textTracks,
                disabled = textTrackDisabled,
                onSelect = viewModel::selectTextTrack,
                onSetDisabled = { disabled -> viewModel.setSubtitlesEnabled(!disabled) },
                onDismiss = { showTextTracks = false }
            )
        }


        if (showTranslationLanguages) {
            val sourceLanguages = remember {
                TranslateLanguage.getAllLanguages()
                    .filterNot { it == TranslateLanguage.ARABIC }
                    .sorted()
            }
            AlertDialog(
                onDismissRequest = { showTranslationLanguages = false },
                title = { Text("اختر لغة الترجمة الأصلية") },
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    ) {
                        items(sourceLanguages, key = { it }) { languageCode ->
                            TextButton(
                                onClick = {
                                    showTranslationLanguages = false
                                    viewModel.translateCurrentSubtitleToArabic(languageCode)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !subtitleTranslationLoading
                            ) {
                                Text(
                                    text = java.util.Locale.forLanguageTag(languageCode)
                                        .getDisplayLanguage(java.util.Locale("ar"))
                                        .ifBlank { languageCode } + "  ·  " + languageCode,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTranslationLanguages = false }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            )
        }

        if (showOnlineSubtitles) {
            OnlineSubtitleSearchSheet(
                query = onlineQuery,
                onQueryChange = { onlineQuery = it },
                language = onlineLanguage,
                onLanguageChange = { onlineLanguage = it },
                season = onlineSeason,
                onSeasonChange = { onlineSeason = it },
                episode = onlineEpisode,
                onEpisodeChange = { onlineEpisode = it },
                results = onlineSubtitleResults,
                loading = onlineSubtitleLoading,
                error = onlineSubtitleError,
                translationLoading = subtitleTranslationLoading,
                translationCompletedCues = subtitleTranslationCompletedCues,
                translationTotalCues = subtitleTranslationTotalCues,
                onSearch = {
                    viewModel.searchOnlineSubtitles(
                        onlineQuery, onlineLanguage,
                        onlineSeason.toIntOrNull(), onlineEpisode.toIntOrNull()
                    )
                },
                onDownload = viewModel::downloadOnlineSubtitle,
                onDownloadAndTranslate = viewModel::downloadAndTranslateOnlineSubtitleToArabic,
                onCancelTranslation = viewModel::cancelSubtitleTranslation,
                onSaveProviderKeys = viewModel::saveOnlineSubtitleProviderKeys,
                onSaveWyzieWorkerUrl = viewModel::saveWyzieWorkerUrl,
                onDismiss = { if (!onlineSubtitleLoading) showOnlineSubtitles = false }
            )
        }

        if (showStats) {
            StatsSheet(
                stats = stats,
                displayHdrCapabilities = displayHdrCapabilities,
                onDismiss = { showStats = false }
            )
        }

        if (showSubtitleStyle) {
            SubtitleStyleSheet(
                settings = settings,
                onChange = { next -> settingsViewModel.update { next } },
                onDismiss = { showSubtitleStyle = false }
            )
        }

        if (showEqualizer) {
            EqualizerSheet(
                available = eqAvailable,
                bandCount = bandCount,
                levelRange = levelRange,
                presetIndex = settings.equalizerPresetIndex,
                bands = settings.equalizerBands,
                bassBoost = settings.bassBoostStrength,
                virtualizer = settings.virtualizerStrength,
                loudness = settings.loudnessGainDb,
                outputRouteLabel = outputRouteLabel,
                processingStatus = processingStatus,
                virtualizerSupported = virtualizerSupported,
                onPreset = { preset ->
                    viewModel.audioEffects.applyPreset(preset)
                    settingsViewModel.update { it.copy(equalizerPresetIndex = preset.ordinal) }
                },
                onBand = { index, value ->
                    val next = settings.equalizerBands.toMutableList().apply {
                        while (size <= index) add(0)
                        this[index] = value
                    }
                    viewModel.audioEffects.setBands(next)
                    settingsViewModel.update {
                        it.copy(
                            equalizerBands = next,
                            equalizerPresetIndex = EqualizerPreset.CUSTOM.ordinal
                        )
                    }
                },
                onBass = { value ->
                    viewModel.audioEffects.setBassBoost(value)
                    settingsViewModel.update { it.copy(bassBoostStrength = value) }
                },
                onVirtualizer = { value ->
                    viewModel.audioEffects.setVirtualizer(value)
                    settingsViewModel.update { it.copy(virtualizerStrength = value) }
                },
                onLoudness = { value ->
                    viewModel.audioEffects.setLoudness(value)
                    settingsViewModel.update { it.copy(loudnessGainDb = value) }
                },
                onDismiss = { showEqualizer = false }
            )
        }

        if (showBookmarks) {
            BookmarksSheet(
                bookmarks = bookmarks,
                onSelect = { bookmark ->
                    if (activeDlnaDevice == null) viewModel.seekTo(bookmark.positionMs)
                    else viewModel.seekDlnaTo(bookmark.positionMs)
                    showBookmarks = false
                },
                onDelete = viewModel::deleteBookmark,
                onDismiss = { showBookmarks = false }
            )
        }

        if (showQueue) {
            QueueSheet(
                items = queue,
                currentIndex = currentIndex,
                onSelect = { index ->
                    viewModel.playQueue(queue, index)
                    showQueue = false
                },
                onDismiss = { showQueue = false }
            )
        }

        if (showRemoteOutputDialog) {
            val remote = activeDlnaDevice
            AlertDialog(
                onDismissRequest = { showRemoteOutputDialog = false },
                title = {
                    Text(
                        if (dlnaPlayback.isConnected) "الفيديو يُعرض على التلفزيون"
                        else "الاتصال بالتلفزيون غير مستقر"
                    )
                },
                text = { Text(remote?.name ?: "جهاز DLNA") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRemoteOutputDialog = false
                            if (remote != null) viewModel.stopDlnaPlayback(resumeLocal = true)
                        }
                    ) { Text("إيقاف الإرسال والعودة للهاتف") }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoteOutputDialog = false }) { Text("متابعة على التلفزيون") }
                }
            )
        }

        if (showDlnaPicker) {
            DlnaDevicePickerDialog(
                onDismiss = { showDlnaPicker = false },
                onDeviceSelected = { device: DiscoveredNetworkDevice ->
                    showDlnaPicker = false
                    viewModel.startDlnaPlayback(device, currentVideoTitle)
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (boostActive) viewModel.setSpeed(settings.defaultSpeed)
        }
    }
}

/** Pushes the saved subtitle look onto the platform SubtitleView. */
@UnstableApi
private fun applySubtitleStyle(view: PlayerView, settings: com.malekplay.domain.model.PlayerSettings) {
    val subtitles = view.subtitleView ?: return
    subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp.toFloat())
    subtitles.setBottomPaddingFraction(settings.subtitleBottomPadding / 100f)
    subtitles.setStyle(
        CaptionStyleCompat(
            settings.subtitleColorInt,
            if (settings.subtitleBackground) AndroidColor.BLACK else AndroidColor.TRANSPARENT,
            AndroidColor.TRANSPARENT,
            if (settings.subtitleBackground) CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else CaptionStyleCompat.EDGE_TYPE_NONE,
            AndroidColor.BLACK,
            if (settings.subtitleBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        )
    )
}

// ------------------------------------------------------------------- sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onSelect: (BookmarkEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.player_bookmarks),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.lib_no_results_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(bookmarks, key = { it.bookmarkId }) { bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(bookmark) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                bookmark.label.ifBlank { formatDuration(bookmark.positionMs) },
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                formatDuration(bookmark.positionMs),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                            IconButton(onClick = { onDelete(bookmark.bookmarkId) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.lib_delete)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    items: List<DomainMedia>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.lib_playlists),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.lib_no_results_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(items) { item ->
                        val index = items.indexOf(item)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("${index + 1}", fontSize = 12.sp)
                            Text(
                                item.displayTitle,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (index == currentIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatDuration(item.durationMs),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ gesture

/**
 * One pointer loop that serves taps, double taps, long press and drags.
 *
 * A single loop is used instead of stacked `detectTapGestures`/`detectDragGestures`
 * so the two never fight over the same down event.
 */
private suspend fun PointerInputScope.detectPlayerGestures(
    onTap: () -> Unit,
    onDoubleTap: (Float) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onPinch: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float, Float) -> Unit,
    onDragEnd: () -> Unit
) = coroutineScope {
    var lastTapMs = 0L
    var tapJob: Job? = null

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()

        var longPressed = false
        var longPressTriggered = false
        var dragging = false
        var pinching = false
        var previousPinchDistance = 0f
        var origin = down.position
        var previous = down.position

        val longPressJob = launch {
            delay(LONG_PRESS_MS)
            longPressed = true
            longPressTriggered = true
            // A long press is its own gesture; it must never be reclassified
            // as a tap or as the second half of a double tap on release.
            lastTapMs = 0L
            onLongPressStart()
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val pressedPointers = event.changes.filter { it.pressed }
            if (pressedPointers.size >= 2) {
                pinching = true
                dragging = false
                lastTapMs = 0L
                tapJob?.cancel()
                longPressJob.cancel()
                if (longPressed) {
                    longPressed = false
                    onLongPressEnd()
                }
                val first = pressedPointers[0].position
                val second = pressedPointers[1].position
                val deltaX = first.x - second.x
                val deltaY = first.y - second.y
                val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                if (previousPinchDistance > 0f && distance > 0f) {
                    onPinch(distance / previousPinchDistance)
                }
                previousPinchDistance = distance
                pressedPointers.forEach { it.consume() }
                continue
            }
            if (pinching) {
                event.changes.forEach { it.consume() }
                if (!change.pressed) break
                continue
            }
            if (!change.pressed) break

            val dxTotal = change.position.x - origin.x
            val dyTotal = change.position.y - origin.y
            if (!dragging && (abs(dxTotal) > viewConfiguration.touchSlop ||
                    abs(dyTotal) > viewConfiguration.touchSlop)
            ) {
                dragging = true
                lastTapMs = 0L
                longPressJob.cancel()
                if (longPressed) {
                    longPressed = false
                    onLongPressEnd()
                }
                // Keep the original touch origin for left/right zone detection and
                // include the movement that crossed touch slop in the first update.
                previous = down.position
                onDragStart()
            }
            if (dragging) {
                val width = size.width.toFloat().coerceAtLeast(1f)
                val height = size.height.toFloat().coerceAtLeast(1f)
                val dx = change.position.x - previous.x
                val dy = change.position.y - previous.y
                previous = change.position
                onDrag(origin.x / width, dx / width, -dy / height)
                change.consume()
            }
        }

        longPressJob.cancel()
        if (longPressed) {
            longPressed = false
            onLongPressEnd()
        }

        when {
            pinching -> Unit
            dragging -> onDragEnd()
            longPressTriggered -> Unit
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < DOUBLE_TAP_TIMEOUT_MS) {
                    tapJob?.cancel()
                    tapJob = null
                    lastTapMs = 0L
                    onDoubleTap(down.position.x / size.width.toFloat().coerceAtLeast(1f))
                } else {
                    lastTapMs = now
                    tapJob?.cancel()
                    tapJob = launch {
                        delay(DOUBLE_TAP_TIMEOUT_MS)
                        onTap()
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- screenshot

/**
 * Grabs the current frame and stores it in Pictures/MalekPlayer.
 *
 * SurfaceView content is not part of the view hierarchy, so PixelCopy is used
 * on API 26+; a plain draw is the fallback and works for TextureView surfaces.
 */
@UnstableApi
private suspend fun captureAndSave(
    context: Context,
    playerView: PlayerView,
    title: String
): Boolean = withContext(Dispatchers.IO) {
    val target = runCatching { playerView.videoSurfaceView }.getOrNull() ?: playerView
    val width = target.width
    val height = target.height
    if (width <= 0 || height <= 0) return@withContext false

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val copied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target is SurfaceView) {
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    target,
                    bitmap,
                    { result -> continuation.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper())
                )
            } catch (t: Throwable) {
                continuation.resume(false)
            }
        }
    } else {
        runCatching {
            target.draw(Canvas(bitmap))
            true
        }.getOrDefault(false)
    }
    if (!copied) return@withContext false

    val safeTitle = title.replace(Regex("[^A-Za-z0-9\\-_ ]"), "").take(48).ifBlank { "frame" }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$safeTitle-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MalekPlayer")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching {
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return@withContext false

    val written = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } == true
    }.getOrDefault(false)
    if (!written) {
        // Remove the unpublished row so a failed screenshot never leaves a
        // permanently pending item in the user's Pictures collection.
        runCatching { context.contentResolver.delete(uri, null, null) }
        return@withContext false
    }

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    val published = runCatching {
        context.contentResolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)
    if (!published) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        return@withContext false
    }
    true
}
