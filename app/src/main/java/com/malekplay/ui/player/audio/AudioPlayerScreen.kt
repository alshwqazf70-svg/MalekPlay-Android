package com.malekplay.ui.player.audio

import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
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
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatSpeed
import com.malekplay.core.ui.util.titleWithoutExtension
import com.malekplay.domain.model.EqualizerPreset
import com.malekplay.domain.model.MediaItem as DomainMedia
import com.malekplay.ui.components.AmbientArtworkPalette
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.components.loadAmbientArtworkPalette
import com.malekplay.ui.player.PlayerViewModel
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.player.components.EqualizerSheet
import com.malekplay.ui.player.components.SleepTimerDialog
import com.malekplay.ui.player.components.SpeedPickerDialog
import com.malekplay.ui.player.components.StatsSheet
import com.malekplay.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
    val virtualizerSupported by viewModel.audioEffects.virtualizerSupported.collectAsStateWithLifecycle()
    val outputRouteLabel by viewModel.audioEffects.outputRouteLabel.collectAsStateWithLifecycle()
    val processingMetrics by viewModel.audioEffects.processingMetrics.collectAsStateWithLifecycle()
    val processingStatus = when {
        !processingMetrics.configured -> "مسار المعالجة ينتظر صوت PCM مدعوماً؛ قد لا تعمل المؤثرات مع إخراج الصوت المشفّر المباشر."
        processingMetrics.effectFrames > 0L -> "تعمل المؤثرات فعلياً: تمت معالجة ${processingMetrics.effectFrames} إطاراً صوتياً."
        processingMetrics.processedFrames > 0L -> "مسار الصوت متصل؛ لم يتم تفعيل تأثير غير مسطح بعد."
        else -> "لم تصل إطارات صوتية إلى معالج المؤثرات حتى الآن."
    }
    val waveform by viewModel.audioEffects.waveform.collectAsStateWithLifecycle()
    val pcmSpectrum by viewModel.audioEffects.pcmSpectrum.collectAsStateWithLifecycle()

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
        val activeController = controller ?: return@LaunchedEffect
        if (!resolved) return@LaunchedEffect
        // If returning from the MiniPlayer for the track already active in Media3,
        // keep current playback position and queue intact instead of restarting.
        if (activeController.currentMediaItem?.mediaId == mediaId &&
            activeController.playbackState != Player.STATE_IDLE
        ) {
            return@LaunchedEffect
        }
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
    var visualizerOn by remember { mutableStateOf(true) }
    var vinylMode by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        viewModel.audioEffects.setPcmSpectrumEnabled(true)
        onDispose {
            viewModel.audioEffects.setPcmSpectrumEnabled(false)
            viewModel.audioEffects.stopVisualizer()
        }
    }

    fun toggleVisualizer() {
        visualizerOn = !visualizerOn
        viewModel.audioEffects.setPcmSpectrumEnabled(visualizerOn)
    }

    // Media3 updates currentIndex on every automatic or manual track change.
    // Resolve visible metadata from that queue item so title and artwork stay in sync.
    val item = if (queue.any { it.id == mediaId }) queue.getOrNull(currentIndex) ?: media else media
    val displayTitle = item?.displayTitle ?: title
    val currentMediaId = item?.id ?: mediaId

    var artworkPalette by remember(currentMediaId) { mutableStateOf<AmbientArtworkPalette?>(null) }
    LaunchedEffect(currentMediaId, item?.artworkUri, item?.uri) {
        artworkPalette = withContext(Dispatchers.IO) {
            loadAmbientArtworkPalette(
                context = context,
                artworkUri = item?.artworkUri,
                mediaUri = item?.uri,
                cacheKey = currentMediaId
            )
        }
    }

    val themePrimary = MaterialTheme.colorScheme.primary
    val themeTertiary = MaterialTheme.colorScheme.tertiary
    val accent by animateColorAsState(
        targetValue = artworkPalette?.let { Color(it.primaryArgb) } ?: themePrimary,
        animationSpec = tween(750),
        label = "audio-ambient-primary"
    )
    val secondaryGlow by animateColorAsState(
        targetValue = artworkPalette?.let { Color(it.secondaryArgb) } ?: themeTertiary,
        animationSpec = tween(750),
        label = "audio-ambient-secondary"
    )

    val activeSpectrum = remember(pcmSpectrum, waveform) {
        if (pcmSpectrum.any { it > 0.005f }) pcmSpectrum else waveform
    }
    val bassEnergy = remember(activeSpectrum, isPlaying) {
        if (!isPlaying) 0f
        else (activeSpectrum.take(6).sum() / 6f).coerceIn(0f, 1f)
    }
    val pulseScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f + bassEnergy * 0.08f else 0.98f,
        animationSpec = tween(140),
        label = "audio-bass-pulse"
    )
    val vinylRotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, vinylMode) {
        if (isPlaying && vinylMode) {
            while (isActive) {
                val target = vinylRotation.value + 360f
                vinylRotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 9_500, easing = LinearEasing)
                )
            }
        }
    }

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
                        accent.copy(alpha = 0.42f),
                        secondaryGlow.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .size(320.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.34f + bassEnergy * 0.22f),
                            secondaryGlow.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
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
                Surface(
                    onClick = { vinylMode = !vinylMode },
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.18f),
                    modifier = Modifier.border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (vinylMode) Icons.Filled.Album else Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (vinylMode) "قرص فينيل" else "غلاف زجاجي",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.player_more))
                }
            }
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 248.dp),
                contentAlignment = Alignment.Center
            ) {
                if (vinylMode) {
                    Box(
                        modifier = Modifier
                            .size(236.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                rotationZ = vinylRotation.value
                            }
                            .shadow(
                                elevation = 28.dp,
                                shape = CircleShape,
                                ambientColor = accent.copy(alpha = 0.45f),
                                spotColor = secondaryGlow.copy(alpha = 0.35f)
                            )
                            .clip(CircleShape)
                            .background(Color(0xFF100E16))
                            .clickable { vinylMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            for (ring in 1..6) {
                                drawCircle(
                                    color = Color.White.copy(alpha = if (ring % 2 == 0) 0.06f else 0.03f),
                                    radius = r * (0.48f + ring * 0.075f),
                                    style = Stroke(width = 1.5f)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(154.dp)
                                .clip(CircleShape)
                                .border(2.dp, accent.copy(alpha = 0.72f), CircleShape)
                        ) {
                            if (item != null) {
                                MediaThumbnail(
                                    media = item,
                                    modifier = Modifier.fillMaxSize(),
                                    targetSize = Size(512, 512),
                                    cornerRadius = 77.dp,
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(accent.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A0810))
                                .border(2.dp, accent.copy(alpha = 0.85f), CircleShape)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .size(236.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .shadow(
                                elevation = 26.dp,
                                shape = RoundedCornerShape(28.dp),
                                ambientColor = accent.copy(alpha = 0.38f),
                                spotColor = secondaryGlow.copy(alpha = 0.28f)
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.52f),
                                        accent.copy(alpha = 0.72f),
                                        secondaryGlow.copy(alpha = 0.32f)
                                    )
                                ),
                                shape = RoundedCornerShape(28.dp)
                            )
                            .clickable { vinylMode = true },
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        tonalElevation = 4.dp
                    ) {
                        if (item != null) {
                            MediaThumbnail(
                                media = item,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(28.dp)),
                                targetSize = Size(720, 720),
                                cornerRadius = 28.dp,
                                contentScale = ContentScale.Crop
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
                }
            }

            if (visualizerOn) {
                Spacer(Modifier.height(14.dp))
                VisualizerBars(
                    waveform = activeSpectrum,
                    primaryColor = accent,
                    secondaryColor = secondaryGlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
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
                mediaTitle = item?.title ?: displayTitle,
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


            Spacer(Modifier.height(8.dp))

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
                            tint = contrastingOnColor(accent),
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

/** Mirrored live spectrum powered directly by the PCM equalizer tap (with platform Visualizer fallback). */
@Composable
private fun VisualizerBars(
    waveform: FloatArray,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val count = waveform.size
        if (count == 0) return@Canvas
        val slot = size.width / count
        val barWidth = (slot * 0.58f).coerceAtLeast(2f)
        val offset = (slot - barWidth) / 2f
        val midY = size.height / 2f
        val gradient = Brush.verticalGradient(listOf(primaryColor, secondaryColor, primaryColor))
        waveform.forEachIndexed { index, value ->
            val clamped = value.coerceIn(0.04f, 1f)
            val halfHeight = (clamped * midY).coerceAtLeast(3f)
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(
                    x = index * slot + offset,
                    y = midY - halfHeight
                ),
                size = CanvasSize(barWidth, halfHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = 0.35f + clamped * 0.65f
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
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    itemsIndexed(items, key = { idx, it -> "${it.id}_$idx" }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (index == currentIndex) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(index) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 12.sp,
                                color = if (index == currentIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.displayTitle,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
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
