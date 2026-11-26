@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.ui.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.malekplay.R
import com.malekplay.vault.PrivateVaultArchiveBrowser
import com.malekplay.vault.PrivateVaultEntry
import com.malekplay.vault.PrivateVaultRepository
import com.malekplay.vault.PrivateVaultSession
import com.malekplay.vault.VaultArchiveListingItem
import com.malekplay.vault.VaultMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun VaultMediaViewerDialog(
    entry: PrivateVaultEntry,
    repository: PrivateVaultRepository,
    session: PrivateVaultSession,
    entries: List<PrivateVaultEntry>,
    archiveBrowser: PrivateVaultArchiveBrowser? = null,
    archiveItem: VaultArchiveListingItem? = null,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val activeMime = archiveItem?.mimeType ?: entry.mimeType
    val activeTitle = archiveItem?.name ?: entry.displayName

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF09090E)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    activeMime.startsWith("video/") || activeMime.startsWith("audio/") -> {
                        VaultIsolatedMediaPlayer(
                            entry = entry,
                            repository = repository,
                            session = session,
                            entries = entries,
                            archiveItem = archiveItem,
                            onPlaybackActiveChanged = onPlaybackActiveChanged
                        )
                    }
                    activeMime.startsWith("image/") -> {
                        VaultInMemoryImageViewer(
                            entry = entry,
                            repository = repository,
                            session = session,
                            archiveBrowser = archiveBrowser,
                            archiveItem = archiveItem
                        )
                    }
                    else -> {
                        VaultInMemoryTextViewer(
                            entry = entry,
                            repository = repository,
                            session = session,
                            archiveBrowser = archiveBrowser,
                            archiveItem = archiveItem
                        )
                    }
                }

                // Top header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color(0x88000000))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = activeTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultIsolatedMediaPlayer(
    entry: PrivateVaultEntry,
    repository: PrivateVaultRepository,
    session: PrivateVaultSession,
    entries: List<PrivateVaultEntry>,
    archiveItem: VaultArchiveListingItem?,
    onPlaybackActiveChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activeMime = archiveItem?.mimeType ?: entry.mimeType
    val activeTitle = archiveItem?.name ?: entry.displayName
    val isAudio = activeMime.startsWith("audio/")
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    val mediaUri = remember(entry.id, archiveItem?.path) {
        if (archiveItem != null) {
            VaultMediaDataSource.archiveEntryUri(entry.id, archiveItem.path)
        } else {
            VaultMediaDataSource.entryUri(entry.id)
        }
    }

    val exoPlayer = remember(entry.id, archiveItem?.path, session) {
        val dataSourceFactory = VaultMediaDataSource.Factory(repository, session) { entries }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(mediaUri)
                        .setMimeType(activeMime)
                        .build()
                )
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPlaybackActiveChanged(playing)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = context.getString(R.string.vault_media_playback_error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            onPlaybackActiveChanged(false)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            if (dur > 0L) durationMs = dur
            delay(400L)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isAudio) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                }
                Text(
                    text = activeTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                },
                onRelease = { view ->
                    view.player = null
                }
            )
        }

        playbackError?.let { error ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Bottom isolated controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val total = durationMs.coerceAtLeast(1L)
            Slider(
                value = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    if (durationMs > 0L) {
                        exoPlayer.seekTo((fraction * durationMs).toLong())
                    }
                },
                enabled = durationMs > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatVaultDuration(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(formatVaultDuration(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = null, tint = Color.White)
                }
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L) }) {
                    Icon(Icons.Filled.Forward10, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VaultInMemoryImageViewer(
    entry: PrivateVaultEntry,
    repository: PrivateVaultRepository,
    session: PrivateVaultSession,
    archiveBrowser: PrivateVaultArchiveBrowser?,
    archiveItem: VaultArchiveListingItem?
) {
    val context = LocalContext.current
    val itemKey = "${entry.id}:${archiveItem?.path.orEmpty()}"
    var bitmap by remember(itemKey) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(itemKey) { mutableStateOf(true) }
    var error by remember(itemKey) { mutableStateOf<String?>(null) }
    var scale by remember(itemKey) { mutableFloatStateOf(1f) }
    var offsetX by remember(itemKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(itemKey) { mutableFloatStateOf(0f) }

    LaunchedEffect(itemKey, session) {
        loading = true
        error = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (archiveItem != null && archiveBrowser != null) {
                    archiveBrowser.readArchiveEntryBytes(entry, archiveItem.path, session, maxBytes = 32 * 1024 * 1024)
                } else {
                    repository.readEntryBytes(entry, session, maxBytes = 32 * 1024 * 1024)
                }
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error(context.getString(R.string.vault_viewer_image_decode_failed))
                } finally {
                    bytes.fill(0)
                }
            }
        }
        loading = false
        loaded.onSuccess { bitmap = it }.onFailure { error = it.localizedMessage }
    }

    DisposableEffect(itemKey) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(itemKey) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                )
            }
            .pointerInput(itemKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    if (nextScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(color = Color.White)
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = archiveItem?.name ?: entry.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                )
            }
        }
    }
}

@Composable
private fun VaultInMemoryTextViewer(
    entry: PrivateVaultEntry,
    repository: PrivateVaultRepository,
    session: PrivateVaultSession,
    archiveBrowser: PrivateVaultArchiveBrowser?,
    archiveItem: VaultArchiveListingItem?
) {
    val context = LocalContext.current
    val itemKey = "${entry.id}:${archiveItem?.path.orEmpty()}"
    val activeMime = archiveItem?.mimeType ?: entry.mimeType
    val activeName = archiveItem?.name ?: entry.displayName
    var content by remember(itemKey) { mutableStateOf<String?>(null) }
    var loading by remember(itemKey) { mutableStateOf(true) }
    var error by remember(itemKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(itemKey, session) {
        loading = true
        error = null
        val isTextLike = activeMime.startsWith("text/") ||
            activeName.substringAfterLast('.', "").lowercase() in
            setOf("txt", "srt", "ass", "vtt", "json", "xml", "log", "md", "csv")
        if (!isTextLike) {
            loading = false
            error = context.getString(R.string.vault_viewer_unsupported_preview, activeMime)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (archiveItem != null && archiveBrowser != null) {
                    archiveBrowser.readArchiveEntryBytes(entry, archiveItem.path, session, maxBytes = 512 * 1024)
                } else {
                    repository.readEntryBytes(entry, session, maxBytes = 512 * 1024)
                }
                try {
                    bytes.toString(Charsets.UTF_8)
                } finally {
                    bytes.fill(0)
                }
            }
        }
        loading = false
        result.onSuccess { content = it }.onFailure { error = it.localizedMessage }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(color = Color.White)
            error != null -> Text(error.orEmpty(), color = Color.White.copy(alpha = 0.85f))
            else -> Text(
                text = content.orEmpty(),
                color = Color(0xFFEAEAF2),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

private fun formatVaultDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
