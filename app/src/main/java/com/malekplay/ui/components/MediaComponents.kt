package com.malekplay.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.util.Size
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.material3.ProgressIndicatorDefaults
import com.malekplay.core.ui.util.formatBytes
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --------------------------------------------------------------- thumbnails

/**
 * Loads a MediaStore thumbnail off the main thread.
 * Falls back to a genre icon when the frame cannot be read.
 */
@Composable
fun MediaThumbnail(
    media: MediaItem,
    modifier: Modifier = Modifier,
    targetSize: Size = Size(320, 180),
    cornerRadius: Dp = 10.dp,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = media.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
                val source = if (media.isVideo) media.uri else (media.artworkUri ?: media.uri)
                context.contentResolver.loadThumbnail(source, targetSize, null)
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = media.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Icon(
                imageVector = if (media.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// -------------------------------------------------------------- list rows

@Composable
fun MediaListRow(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    showFolder: Boolean = false
) {
    val media = item.media
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = media.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val subtitle = buildList {
                    if (showFolder && media.folderName.isNotBlank()) add(media.folderName)
                    if (!media.artist.isNullOrBlank()) add(media.artist!!)
                    add(formatDuration(media.durationMs))
                    add(formatBytes(media.sizeBytes))
                }.joinToString("  •  ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.isInProgress) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onFavourite) {
                Icon(
                    imageVector = if (item.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = if (item.isFavourite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))

            Box(contentAlignment = Alignment.Center) {
                MediaThumbnail(media = media, modifier = Modifier.size(84.dp, 58.dp))
                if (item.isCompleted) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

@Composable
fun MediaGridCard(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onPlay),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(contentAlignment = Alignment.Center) {
                MediaThumbnail(
                    media = item.media,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f),
                    cornerRadius = 0.dp
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp).size(22.dp)
                    )
                }
                IconButton(
                    onClick = onFavourite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp)
                ) {
                    Icon(
                        imageVector = if (item.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = if (item.isFavourite) MaterialTheme.colorScheme.tertiary else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (item.isInProgress) {
                    LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = item.media.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDuration(item.media.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------------ folder rows

/**
 * Folder row modelled on the classic media-player browse screen:
 * icon, name, a breakdown of what is inside, and a count badge.
 */
@Composable
fun FolderRow(
    folder: MediaFolder,
    modifier: Modifier = Modifier,
    videoLabel: String,
    audioLabel: String,
    fileLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                val detail = when {
                    folder.videoCount > 0 && folder.audioCount > 0 ->
                        "${fileLabel.format(folder.itemCount)}"
                    folder.videoCount > 0 -> videoLabel.format(folder.videoCount)
                    else -> audioLabel.format(folder.audioCount)
                }
                Text(
                    text = "$detail  •  ${formatBytes(folder.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                // Badge: how many files live in the folder.
                if (folder.itemCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = folder.itemCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ misc pieces

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        action?.invoke()
        Spacer(Modifier.weight(1f))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    icon: ImageVector = Icons.Filled.AudioFile,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actions != null) {
            Spacer(Modifier.height(12.dp))
            actions()
        }
    }
}
