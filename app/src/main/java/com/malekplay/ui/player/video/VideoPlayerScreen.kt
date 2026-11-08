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
import com.malekplay.network.DlnaRendererClient
import com.malekplay.player.cast.LocalMediaBridgeRegistry
import com.malekplay.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

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
    val subtitleTranslationError by viewModel.subtitleTranslationError.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.sleepTimerEndsAtMs.collectAsStateWithLifecycle()
    val inPip by viewModel.pipController.inPip.collectAsStateWithLifecycle()

    val eqAvailable by viewModel.audioEffects.available.collectAsStateWithLifecycle()
    val bandCount by viewModel.audioEffects.bandCount.collectAsStateWithLifecycle()
    val levelRange by viewModel.audioEffects.levelRange.collectAsStateWithLifecycle()

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

    // -------------------------------------------------------------- ui state
    var chromeVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var railExpanded by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(AspectMode.FIT.ordinal) }
    val aspectMode = AspectMode.entries[aspect]

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showTextTracks by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showSubtitleStyle by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTranslationLanguages by remember { mutableStateOf(false) }
    var showOnlineSubtitles by remember { mutableStateOf(false) }
    var showDlnaPicker by remember { mutableStateOf(false) }
    var showRemoteOutputDialog by remember { mutableStateOf(false) }
    var activeDlnaDevice by remember { mutableStateOf<DiscoveredNetworkDevice?>(null) }
    var remoteDlnaPlaying by remember { mutableStateOf(false) }
    var remotePositionMs by remember { mutableLongStateOf(0L) }
    var remoteDurationMs by remember { mutableLongStateOf(0L) }
    val dlnaClient = remember { DlnaRendererClient() }
    val displayedPositionMs = if (activeDlnaDevice != null) remotePositionMs else positionMs
    val displayedDurationMs = if (activeDlnaDevice != null && remoteDurationMs > 0L) remoteDurationMs else durationMs

    LaunchedEffect(currentVideoId) {
        activeDlnaDevice = null
        remoteDlnaPlaying = false
        remotePositionMs = 0L
        remoteDurationMs = 0L
    }

    LaunchedEffect(activeDlnaDevice) {
        val remote = activeDlnaDevice ?: return@LaunchedEffect
        while (true) {
            runCatching { dlnaClient.getPosition(remote) }
                .onSuccess { position ->
                    if (position != null) {
                        remotePositionMs = position.positionMs
                        remoteDurationMs = position.durationMs
                    }
                }
            delay(1_000L)
        }
    }
    val detectedEpisode = remember(title) {
        Regex("(?i)(?:S(\\d{1,2})[ ._-]*E(\\d{1,2})|(\\d{1,2})x(\\d{1,2}))").find(title)
    }
    val defaultSubtitleTitle = remember(title, detectedEpisode) {
        detectedEpisode?.let { title.substring(0, it.range.first).trim(' ', '.', '_', '-') }?.ifBlank { title } ?: title
    }
    var onlineQuery by remember(title) { mutableStateOf(defaultSubtitleTitle) }
    var onlineLanguage by remember { mutableStateOf("ar,en") }
    var onlineSeason by remember(title) {
        mutableStateOf(detectedEpisode?.let { it.groupValues[1].ifBlank { it.groupValues[3] } }.orEmpty())
    }
    var onlineEpisode by remember(title) {
        mutableStateOf(detectedEpisode?.let { it.groupValues[2].ifBlank { it.groupValues[4] } }.orEmpty())
    }

    LaunchedEffect(subtitleTranslationError) {
        subtitleTranslationError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(chromeVisible, isPlaying, locked) {
        if (chromeVisible && isPlaying && !locked) {
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
        val remote = activeDlnaDevice
        if (remote == null) {
            viewModel.seekBy(deltaMs)
        } else {
            scope.launch {
                runCatching { dlnaClient.seek(remote, (remotePositionMs + deltaMs).coerceAtLeast(0L)) }
                    .onFailure { failure ->
                        Toast.makeText(context, failure.message ?: "تعذر تغيير موضع الفيديو على التلفزيون.", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    fun togglePlayback() {
        val remote = activeDlnaDevice
        if (remote == null) {
            viewModel.togglePlayPause()
        } else {
            scope.launch {
                runCatching {
                    if (remoteDlnaPlaying) dlnaClient.pause(remote) else dlnaClient.resume(remote)
                }.onSuccess {
                    remoteDlnaPlaying = !remoteDlnaPlaying
                }.onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "تعذر التحكم بالتلفزيون.", Toast.LENGTH_LONG).show()
                }
            }
        }
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

    // ---------------------------------------------------------------- layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
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
                applySubtitleStyle(view, settings)
                playerView = view
            },
            onRelease = { view ->
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
                            val remote = activeDlnaDevice
                            if (remote == null) {
                                viewModel.seekTo(targetMs)
                            } else {
                                scope.launch {
                                    runCatching { dlnaClient.seek(remote, targetMs) }
                                        .onFailure { failure ->
                                            Toast.makeText(context, failure.message ?: "تعذر تقديم الفيديو على التلفزيون.", Toast.LENGTH_LONG).show()
                                        }
                                }
                            }
                        }
                    )
                    }


                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PlayerMainControls(
                        isPlaying = if (activeDlnaDevice != null) remoteDlnaPlaying else isPlaying,
                        hasNext = currentIndex < queue.lastIndex,
                        hasPrevious = currentIndex > 0,
                        onPrevious = {
                            val remote = activeDlnaDevice
                            if (remote != null) {
                                scope.launch { runCatching { dlnaClient.stop(remote) } }
                                activeDlnaDevice = null
                                remoteDlnaPlaying = false
                            }
                            viewModel.seekToPrevious()
                        },
                        onPlayPause = ::togglePlayback,
                        onNext = {
                            val remote = activeDlnaDevice
                            if (remote != null) {
                                scope.launch { runCatching { dlnaClient.stop(remote) } }
                                activeDlnaDevice = null
                                remoteDlnaPlaying = false
                            }
                            viewModel.seekToNext()
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
                    onClick = { showMenu = false; onlineQuery = title; showOnlineSubtitles = true }
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
                    text = { Text(stringResource(R.string.player_bookmarks)) },
                    onClick = { showMenu = false; showBookmarks = true }
                )
            }
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
            AlertDialog(
                onDismissRequest = { if (!onlineSubtitleLoading) showOnlineSubtitles = false },
                title = { Text(stringResource(R.string.player_search_subtitle_online)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(onlineQuery, { onlineQuery = it },
                            label = { Text(stringResource(R.string.subtitle_search_title)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onlineLanguage = "ar,en" }) { Text("العربية + English") }
                            TextButton(onClick = { onlineLanguage = "ar" }) { Text("العربية") }
                            TextButton(onClick = { onlineLanguage = "en" }) { Text("English") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(onlineSeason, { onlineSeason = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.subtitle_season)) },
                                modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(onlineEpisode, { onlineEpisode = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.subtitle_episode)) },
                                modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Text(stringResource(R.string.subtitle_service_setup), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { viewModel.searchOnlineSubtitles(onlineQuery, onlineLanguage, onlineSeason.toIntOrNull(), onlineEpisode.toIntOrNull()) },
                            enabled = onlineQuery.isNotBlank() && !onlineSubtitleLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.subtitle_search)) }
                        if (onlineSubtitleLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        onlineSubtitleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(onlineSubtitleResults, key = { it.provider + ":" + it.sourceId }) { item ->
                                TextButton(
                                    onClick = { viewModel.downloadOnlineSubtitle(item) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !onlineSubtitleLoading
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(item.release.ifBlank { item.format.uppercase() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(item.language.uppercase() + " · " + item.format.uppercase() + " · " +
                                            item.downloads + " " + context.getString(R.string.subtitle_downloads),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOnlineSubtitles = false }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            )
        }

        if (showStats) {
            StatsSheet(stats = stats, onDismiss = { showStats = false })
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
                    val remote = activeDlnaDevice
                    if (remote == null) viewModel.seekTo(bookmark.positionMs)
                    else scope.launch {
                        runCatching { dlnaClient.seek(remote, bookmark.positionMs) }
                            .onFailure { failure -> Toast.makeText(context, failure.message ?: "تعذر الانتقال إلى العلامة.", Toast.LENGTH_LONG).show() }
                    }
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
                title = { Text("الفيديو يُعرض على التلفزيون") },
                text = { Text(remote?.name ?: "جهاز DLNA") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRemoteOutputDialog = false
                            if (remote != null) {
                                scope.launch {
                                    runCatching { dlnaClient.stop(remote) }
                                    controller?.let { local ->
                                        local.seekTo(remotePositionMs)
                                        local.play()
                                    }
                                    activeDlnaDevice = null
                                    remoteDlnaPlaying = false
                                }
                            }
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
                    scope.launch {
                        val item = viewModel.currentMediaItem()
                        val originalUri = item?.localConfiguration?.uri
                        if (originalUri == null) {
                            Toast.makeText(context, "لا يوجد فيديو حالي لإرساله.", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val scheme = originalUri.scheme?.lowercase()
                        val receiverUri = when (scheme) {
                            "content", "file" -> LocalMediaBridgeRegistry.urlFor(originalUri)
                            "http", "https" -> originalUri
                            else -> null
                        }
                        if (receiverUri == null) {
                            Toast.makeText(
                                context,
                                "مصدر هذا الفيديو لا يمكن تقديمه للتلفزيون حالياً. المصادر المحلية وHTTP/HTTPS مدعومة في هذه المرحلة.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val mimeType = item.localConfiguration?.mimeType
                            ?: context.contentResolver.getType(originalUri)
                            ?: "video/mp4"
                        runCatching {
                            dlnaClient.play(
                                device = device,
                                mediaUrl = receiverUri.toString(),
                                title = currentVideoTitle,
                                mimeType = mimeType
                            )
                        }.onSuccess {
                            activeDlnaDevice = device
                            remoteDlnaPlaying = true
                            controller?.pause()
                            Toast.makeText(context, "تم إرسال أمر التشغيل إلى " + device.name, Toast.LENGTH_LONG).show()
                        }.onFailure { failure ->
                            Toast.makeText(
                                context,
                                failure.message ?: "تعذر إرسال الفيديو إلى التلفزيون.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
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
 * Grabs the current frame and stores it in Pictures/MalekPlay.
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
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MalekPlay")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching {
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return@withContext false

    val written = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } != null
    }.getOrDefault(false)
    if (!written) return@withContext false

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    runCatching { context.contentResolver.update(uri, values, null, null) }
    true
}
