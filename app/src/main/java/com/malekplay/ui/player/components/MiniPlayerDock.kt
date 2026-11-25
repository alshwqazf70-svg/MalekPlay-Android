package com.malekplay.ui.player.components

import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem as Media3Item
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.domain.model.MediaItem as DomainMedia
import com.malekplay.ui.components.AmbientArtworkPalette
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.components.loadAmbientArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Floating glassmorphic mini-player dock anchored above the bottom bar when
 * audio or background video continues playing outside the full player screens.
 */
@Composable
fun MiniPlayerDock(
    visible: Boolean,
    media: DomainMedia?,
    media3Item: Media3Item?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    addNavigationBarPadding: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val title = media?.displayTitle
        ?: media3Item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.app_name)
    val subtitle = buildString {
        val artist = media?.artist?.takeIf { it.isNotBlank() }
            ?: media3Item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
        if (artist != null) {
            append(artist)
            if (durationMs > 0L) append("  •  ")
        }
        if (durationMs > 0L) {
            append(formatDuration(positionMs))
            append(" / ")
            append(formatDuration(durationMs))
        }
    }.ifBlank {
        stringResource(if (isPlaying) R.string.player_play else R.string.player_pause)
    }

    val activeId = media?.id ?: media3Item?.mediaId.orEmpty()
    var palette by remember(activeId) { mutableStateOf<AmbientArtworkPalette?>(null) }
    LaunchedEffect(activeId, media?.artworkUri, media?.uri) {
        if (activeId.isBlank() || (media?.artworkUri == null && media?.uri == null)) {
            palette = null
            return@LaunchedEffect
        }
        palette = withContext(Dispatchers.IO) {
            loadAmbientArtworkPalette(
                context = context,
                artworkUri = media.artworkUri,
                mediaUri = media.uri,
                cacheKey = activeId
            )
        }
    }

    val primaryAccent = palette?.let { Color(it.primaryArgb) } ?: MaterialTheme.colorScheme.primary
    val secondaryAccent = palette?.let { Color(it.secondaryArgb) } ?: MaterialTheme.colorScheme.secondary
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val artworkRotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, visible) {
        if (isPlaying && visible) {
            while (isActive) {
                val target = artworkRotation.value + 360f
                artworkRotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 12_000, easing = LinearEasing)
                )
            }
        }
    }

    AnimatedVisibility(
        visible = visible && (media != null || media3Item != null),
        enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(180)),
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(22.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (addNavigationBarPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    ambientColor = primaryAccent.copy(alpha = 0.35f),
                    spotColor = primaryAccent.copy(alpha = 0.25f)
                )
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                            primaryAccent.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            primaryAccent.copy(alpha = 0.65f),
                            secondaryAccent.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    ),
                    shape = shape
                )
                .clickable(onClick = onOpenPlayer)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = primaryAccent,
                    trackColor = primaryAccent.copy(alpha = 0.14f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer {
                                if (media == null || !media.isVideo) {
                                    rotationZ = artworkRotation.value
                                }
                            }
                            .clip(CircleShape)
                            .border(1.5.dp, primaryAccent.copy(alpha = 0.72f), CircleShape)
                            .background(primaryAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (media != null) {
                            MediaThumbnail(
                                media = media,
                                modifier = Modifier.size(44.dp),
                                targetSize = Size(160, 160),
                                cornerRadius = 22.dp,
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = primaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = onPrevious,
                                enabled = hasPrevious,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipPrevious,
                                    contentDescription = stringResource(R.string.player_previous),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Surface(
                                onClick = onTogglePlayPause,
                                shape = CircleShape,
                                color = primaryAccent,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(
                                            if (isPlaying) R.string.player_pause else R.string.player_play
                                        ),
                                        tint = contrastingOnColor(primaryAccent),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = onNext,
                                enabled = hasNext,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = stringResource(R.string.player_next),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
