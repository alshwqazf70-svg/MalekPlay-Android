@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.malekplay.ui.components

import android.annotation.SuppressLint

import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.content.pm.PackageManager
import android.app.RecoverableSecurityException
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --------------------------------------------------------------- thumbnails

// A small, byte-bounded cache prevents scrolling back through the library from
// decoding the same artwork repeatedly while keeping memory use predictable.
private val thumbnailCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 8L)
        .coerceAtMost(32L * 1024L * 1024L)
        .toInt()
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Releases decoded artwork promptly when Android reports memory pressure. */
internal fun trimMediaThumbnailCache(level: Int) {
    when {
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> thumbnailCache.evictAll()
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
            thumbnailCache.trimToSize(thumbnailCache.maxSize() / 4)
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
            thumbnailCache.trimToSize(thumbnailCache.maxSize() / 2)
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
            thumbnailCache.trimToSize(thumbnailCache.maxSize() / 2)
    }
}

/**
 * Uses MediaStore first, then MediaMetadataRetriever as a provider-independent
 * fallback. This keeps thumbnails available in every library surface, including
 * rows backed by cached database entries.
 */
private fun loadVideoThumbnail(
    context: android.content.Context,
    uri: android.net.Uri,
    targetSize: Size
): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, targetSize, null)
        }.getOrNull()?.let { return it }
    } else {
        val mediaId = uri.lastPathSegment?.toLongOrNull()
        if (uri.scheme == "content" && uri.authority == "media" && mediaId != null) {
            @Suppress("DEPRECATION")
            runCatching {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    mediaId,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }.getOrNull()?.let { return it }
        }
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val width = targetSize.width.coerceAtLeast(1)
        val height = targetSize.height.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                width,
                height
            )
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { frame ->
                    if (frame.width <= width && frame.height <= height) frame
                    else Bitmap.createScaledBitmap(frame, width, height, true)
                }
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

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
    val colors = MaterialTheme.colorScheme
    val source = if (media.isVideo) media.uri else (media.artworkUri ?: media.uri)
    // Include inexpensive MediaStore metadata so a replaced file at the same
    // content URI does not keep showing the previous decoded frame.
    val cacheKey = "${source}:${media.sizeBytes}:${media.addedAtMs}:${media.durationMs}:" +
        "${targetSize.width}x${targetSize.height}"
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = cacheKey) {
        value = thumbnailCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            try {
                val loaded = if (!media.isVideo) {
                    loadMediaArtwork(
                        context = context,
                        artworkUri = media.artworkUri,
                        mediaUri = media.uri,
                        targetSize = maxOf(targetSize.width, targetSize.height)
                    )
                } else {
                    loadVideoThumbnail(context, source, targetSize)
                }
                loaded?.also { thumbnailCache.put(cacheKey, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainerLow)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = media.title,
                modifier = Modifier.fillMaxSize(),
                // Album covers stay fully visible; video frames may fill/crop.
                contentScale = if (media.isVideo) contentScale else ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .shadow(10.dp, CircleShape, ambientColor = Color(0x55A855F7))
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x667E22CE), Color(0x2215101F))
                        )
                    )
                    .border(1.dp, Color(0x66C084FC), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (media.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFE9D5FF),
                    modifier = Modifier.size(29.dp)
                )
            }
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
    showFolder: Boolean = false,
    onDeleted: () -> Unit = {},
    onSend: () -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = onSend
) {
    val media = item.media
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (selectionMode) onSelect() else onPlay() }, onLongClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when { isSelected -> MaterialTheme.colorScheme.primaryContainer; isCurrent -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.surface }
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

            MediaDeleteButton(media = media, onDeleted = onDeleted)
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
    onAddToPlaylist: () -> Unit,
    onDeleted: () -> Unit = {},
    onSend: () -> Unit = {},
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = onSend
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .shadow(12.dp, shape, ambientColor = Color(0x55A855F7), spotColor = Color(0x22A855F7))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainerLow)))
            .border(2.dp, Brush.linearGradient(if (isSelected) listOf(Color(0xFFE9D5FF), Color(0xFFA855F7)) else listOf(Color(0x88C084FC), Color(0x22FFFFFF), Color(0x667E22CE))), shape)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(0.92f).combinedClickable(onClick = { if (selectionMode) onSelect() else onPlay() }, onLongClick = onSelect)
        ) {
            MediaThumbnail(
                media = item.media,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                contentScale = ContentScale.Crop
            )
            if (selectionMode || isSelected) Surface(modifier = Modifier.align(Alignment.TopEnd).padding(9.dp), shape = CircleShape, color = if (isSelected) Color(0xFFA855F7) else Color.Black.copy(alpha = 0.68f)) { Icon(Icons.Filled.CheckCircle, contentDescription = if (isSelected) "محدد" else "تحديد", tint = Color.White, modifier = Modifier.padding(5.dp).size(22.dp)) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0x18000000), Color.Transparent, Color(0xAA08060D))
            )))
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f))
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape).clickable(onClick = onAddToPlaylist),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "إضافة إلى قائمة التشغيل",
                        tint = Color.White, modifier = Modifier.size(18.dp))
                }
                MediaDeleteButton(media = item.media, compact = true, onDeleted = onDeleted)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f))
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape).clickable(onClick = onFavourite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (item.isFavourite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                        tint = if (item.isFavourite) Color(0xFFFFD166) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Box(
                modifier = Modifier.align(Alignment.Center).size(48.dp)
                    .shadow(12.dp, CircleShape, ambientColor = Color(0x99A855F7), spotColor = Color(0x66A855F7))
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF7E22CE), Color(0xFFA855F7))))
                    .border(1.dp, Color.White.copy(alpha = 0.42f), CircleShape)
                    .clickable(onClick = { if (selectionMode) onSelect() else onPlay() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "تشغيل", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (item.isInProgress) {
                LinearProgressIndicator(
                    progress = { item.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Color(0xFFA855F7),
                    trackColor = Color.White.copy(alpha = 0.22f)
                )
            }
            if (item.isCompleted) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = CircleShape,
                    color = Color(0xE6000000)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "تمت المشاهدة",
                        tint = Color(0xFF34D399), modifier = Modifier.padding(5.dp).size(17.dp))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = { if (selectionMode) onSelect() else onPlay() }).padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.media.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    formatDuration(item.media.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text("•", color = Color(0xFF81788F), style = MaterialTheme.typography.labelSmall)
                Text(
                    formatBytes(item.media.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                if (item.isInProgress) {
                    Text(
                        (item.fraction * 100).toInt().toString() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC084FC),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
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
    val folderShape = RoundedCornerShape(18.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color(0x77C084FC), Color(0x22FFFFFF), Color(0x557E22CE))
                ),
                folderShape
            )
            .clickable(onClick = onClick),
        shape = folderShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
    val emptyShape = RoundedCornerShape(26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
            .clip(emptyShape)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainerLow)
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color(0x88C084FC), Color(0x22FFFFFF), Color(0x667E22CE))
                ),
                emptyShape
            )
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .shadow(14.dp, CircleShape, ambientColor = Color(0x55A855F7))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x667E22CE), Color(0x2215101F))
                    )
                )
                .border(1.dp, Color(0x66C084FC), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actions != null) {
            Spacer(Modifier.height(16.dp))
            actions()
        }
    }
}

/**
 * Requests platform approval before deleting a MediaStore item.
 * Android 6-9 use WRITE_EXTERNAL_STORAGE; Android 10 uses recoverable consent;
 * Android 11+ uses the system delete confirmation UI.
 */
@SuppressLint("NewApi") // RecoverableSecurityException is handled only for Android 10; older versions use the legacy permission path.
@Composable
fun MediaDeleteButton(media: MediaItem, compact: Boolean = false, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    // Declare the retry callback before registering launchers that invoke it.
    var deleteLegacyFile: () -> Unit = {}
    val approvalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                deleteLegacyFile()
            } else {
                Toast.makeText(context, "تم حذف الملف", Toast.LENGTH_SHORT).show()
                onDeleted()
            }
        }
    }
    fun requestApproval(sender: android.content.IntentSender) {
        approvalLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }
    deleteLegacyFile = {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { resolver.delete(media.uri, null, null) }
                if (deleted > 0) {
                    Toast.makeText(context, "تم حذف الملف", Toast.LENGTH_SHORT).show()
                    onDeleted()
                } else Toast.makeText(context, "تعذر حذف الملف أو أنه غير موجود", Toast.LENGTH_LONG).show()
            } catch (security: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) requestApproval(security.userAction.actionIntent.intentSender)
                else Toast.makeText(context, "لا يملك التطبيق إذن حذف هذا الملف", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, error.localizedMessage ?: "تعذر حذف الملف", Toast.LENGTH_LONG).show()
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) deleteLegacyFile()
        else Toast.makeText(context, "يلزم السماح بإدارة الملفات لحذفها", Toast.LENGTH_LONG).show()
    }
    IconButton(onClick = { showConfirm = true }) {
        Icon(Icons.Filled.DeleteOutline, contentDescription = "حذف ${media.displayTitle}",
            tint = if (compact) Color.White else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(if (compact) 18.dp else 22.dp))
    }
    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false },
        title = { Text("تأكيد حذف الملف") },
        text = { Text("هل تريد حذف «${media.displayTitle}» من الهاتف نهائيًا؟ سيطلب Android موافقتك قبل تنفيذ الحذف.") },
        confirmButton = {
            TextButton(onClick = {
                showConfirm = false
                if (media.uri.scheme != "content" || media.uri.authority != "media") {
                    Toast.makeText(context, "الحذف المباشر متاح لملفات مكتبة الهاتف فقط", Toast.LENGTH_LONG).show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { requestApproval(MediaStore.createDeleteRequest(resolver, listOf(media.uri)).intentSender) }
                    catch (error: Exception) { Toast.makeText(context, error.localizedMessage ?: "تعذر طلب إذن الحذف", Toast.LENGTH_LONG).show() }
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) deleteLegacyFile()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) deleteLegacyFile()
                else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("إلغاء") } }
    )
}
