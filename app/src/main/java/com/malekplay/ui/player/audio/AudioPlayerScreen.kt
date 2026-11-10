package com.malekplay.ui.player.audio

import android.Manifest
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CanvasSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.malekplay.R
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatSpeed
import com.malekplay.core.ui.util.titleWithoutExtension
import com.malekplay.domain.model.EqualizerPreset
import com.malekplay.domain.model.MediaItem as DomainMedia
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.player.PlayerViewModel
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.player.components.EqualizerSheet
import com.malekplay.ui.player.components.SleepTimerDialog
import com.malekplay.ui.player.components.SpeedPickerDialog
import com.malekplay.ui.player.components.StatsSheet
import com.malekplay.ui.settings.SettingsViewModel

@UnstableApi
@Composable
fun AudioPlayerScreen(
    mediaId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val parentalControl = remember(context) { ParentalControlManager(context) }
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val favouriteIds by viewModel.favouriteIds.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sleepEndsAt by viewModel.sleepTimerEndsAtMs.collectAsStateWithLifecycle()
    val controller by viewModel.controller.collectAsStateWithLifecycle()

    val eqAvailable by viewModel.audioEffects.available.collectAsStateWithLifecycle()
    val bandCount by viewModel.audioEffects.bandCount.collectAsStateWithLifecycle()
    val levelRange by viewModel.audioEffects.levelRange.collectAsStateWithLifecycle()
    val waveform by viewModel.audioEffects.waveform.collectAsStateWithLifecycle()

    var media by remember { mutableStateOf<DomainMedia?>(null) }
    var title by remember { mutableStateOf("") }
    var resolved by remember(mediaId) { mutableStateOf(false) }
    var resumeMs by remember(mediaId) { mutableStateOf(0L) }

    LaunchedEffect(mediaId) {
        val item = viewModel.loadMedia(mediaId)
        if (item != null) {
            media = item
            title = item.displayTitle
            resumeMs = if (settings.resumePlayback) viewModel.savedPositionFor(mediaId) else 0L
        } else {
            title = mediaId.substringAfterLast('/').let { titleWithoutExtension(it) }
            resumeMs = 0L
        }
        resolved = true
    }

    LaunchedEffect(resolved, controller) {
        if (!resolved || controller == null) return@LaunchedEffect
        val item = media
        if (item != null) {
            // Keep the entire music library in the service queue. Media3 then
            // exposes previous/next commands to the notification and lock screen.
            val discoveredQueue = viewModel.loadAudioQueue()
            val audioQueue = if (parentalControl.isEnabled) {
                val approvedIds = parentalControl.allowedMediaIds
                discoveredQueue.filter { it.id in approvedIds }
            } else discoveredQueue
            val selectedIndex = audioQueue.indexOfFirst { it.id == item.id }
            if (selectedIndex >= 0) {
                viewModel.playQueue(
                    items = audioQueue,
                    startIndex = selectedIndex,
                    startPositionMs = resumeMs,
                    speed = settings.defaultSpeed
                )
            } else {
                viewModel.playSingle(item, resumeMs, settings.defaultSpeed)
            }
        } else {
            viewModel.playExternalUri(android.net.Uri.parse(mediaId), resumeMs, settings.defaultSpeed)
        }
    }

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

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var visualizerOn by remember { mutableStateOf(false) }

    // The visualizer needs RECORD_AUDIO; ask only when the user turns it on.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            visualizerOn = true
            viewModel.audioEffects.startVisualizer()
        }
    }

    fun toggleVisualizer() {
        if (visualizerOn) {
            visualizerOn = false
            viewModel.audioEffects.stopVisualizer()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (visualizerOn) viewModel.audioEffects.stopVisualizer()
        }
    }

    // Media3 updates currentIndex on every automatic or manual track change.
    // Resolve visible metadata from that queue item so title and artwork stay in sync.
    val item = if (queue.any { it.id == mediaId }) queue.getOrNull(currentIndex) ?: media else media
    val displayTitle = item?.displayTitle ?: title
    val currentMediaId = item?.id ?: mediaId
    val accent = MaterialTheme.colorScheme.primary
    val subtitle = buildList {
        item?.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
        item?.album?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (isEmpty()) add(stringResource(R.string.common_unknown))
    }.joinToString("  •  ")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.nav_music),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.player_more))
                }
            }
            }


            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(26.dp),
                        ambientColor = accent.copy(alpha = 0.28f),
                        spotColor = accent.copy(alpha = 0.18f)
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.48f),
                                accent.copy(alpha = 0.62f),
                                Color.White.copy(alpha = 0.10f)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    ),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 4.dp
            ) {
                if (item != null) {
                    MediaThumbnail(
                        media = item,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp)),
                        targetSize = Size(720, 720),
                        cornerRadius = 26.dp,
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        accent.copy(alpha = 0.24f),
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(88.dp)
                        )
                    }
                }
            }

            if (visualizerOn) {
                Spacer(Modifier.height(12.dp))
                VisualizerBars(
                    waveform = waveform,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))

            SyncedLyricsPanel(
                mediaId = currentMediaId,
                positionMs = positionMs,
                isPlaying = isPlaying,
                player = controller
            )

            val total = durationMs.takeIf { it > 0L } ?: 0L
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Slider(
                value = (if (total > 0L) positionMs.toFloat() / total else 0f).coerceIn(0f, 1f),
                onValueChange = { value -> viewModel.seekTo((value * total).toLong()) },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatDuration(positionMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(total),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }


            Spacer(Modifier.weight(1f))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = viewModel::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = stringResource(
                            if (shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off
                        ),
                        tint = if (shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = viewModel::seekToPrevious, enabled = currentIndex > 0) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous))
                }
                Surface(
                    onClick = viewModel::togglePlayPause,
                    shape = RoundedCornerShape(50),
                    color = accent,
                    modifier = Modifier
                        .size(74.dp)
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(50),
                            ambientColor = accent.copy(alpha = 0.42f),
                            spotColor = accent.copy(alpha = 0.26f)
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.player_pause else R.string.player_play
                            ),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
                IconButton(onClick = viewModel::seekToNext, enabled = currentIndex < queue.lastIndex) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next))
                }
                IconButton(onClick = viewModel::cycleRepeatMode) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = stringResource(R.string.player_repeat),
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }


            Spacer(Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val favourite = currentMediaId in favouriteIds
                IconButton(onClick = { viewModel.toggleFavourite(currentMediaId) }) {
                    Icon(
                        imageVector = if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(
                            if (favourite) R.string.lib_remove_favourite else R.string.lib_add_favourite
                        ),
                        tint = if (favourite) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = stringResource(R.string.lib_playlists))
                }
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = stringResource(R.string.player_sleep_timer),
                        tint = if (sleepEndsAt > 0L) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = ::toggleVisualizer) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = stringResource(R.string.player_visualizer),
                        tint = if (visualizerOn) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showEqualizer = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.player_equalizer))
                }
                IconButton(onClick = { showSpeed = true }) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = stringResource(R.string.player_speed))
                }
            }
            }


            Spacer(Modifier.height(16.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
        ) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_restart)) },
                    onClick = { showMenu = false; viewModel.seekTo(0L) }
                )
                DropdownMenuItem(
                    text = { Text("${stringResource(R.string.player_speed)}  ${formatSpeed(speed)}") },
                    onClick = { showMenu = false; showSpeed = true }
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
                    text = { Text(stringResource(R.string.player_stats)) },
                    onClick = { showMenu = false; showStats = true }
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

        if (showStats) {
            StatsSheet(stats = stats, onDismiss = { showStats = false })
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

        if (showQueue) {
            AudioQueueSheet(
                items = queue,
                currentIndex = currentIndex,
                onSelect = { index ->
                    viewModel.playQueue(queue, index)
                    showQueue = false
                },
                onDismiss = { showQueue = false }
            )
        }
    }
}

/** Live 32-bar spectrum fed by the platform Visualizer. */
@Composable
private fun VisualizerBars(
    waveform: FloatArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val count = waveform.size
        if (count == 0) return@Canvas
        val slot = size.width / count
        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
        val offset = (slot - barWidth) / 2f
        waveform.forEachIndexed { index, value ->
            val barHeight = (value * size.height).coerceIn(2f, size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(
                    x = index * slot + offset,
                    y = size.height - barHeight
                ),
                size = CanvasSize(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = 0.35f + value * 0.65f
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioQueueSheet(
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
                items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}", fontSize = 12.sp)
                            Spacer(Modifier.width(12.dp))
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
