@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.malekplay.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.util.formatBytes
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatResolution
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaThumbnail

@Composable
fun LocalFolderScreen(
    folderPath: String,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    contentPadding: PaddingValues,
    onSendMedia: (List<MediaItem>) -> Unit = {}
) {
    val viewModel: LibraryViewModel = hiltViewModel()

    val items by remember(folderPath) { viewModel.folderContents(folderPath) }
        .collectAsStateWithLifecycle(initialValue = emptyList<LibraryItem>())
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val seenNewMediaIds by viewModel.seenNewMediaIds.collectAsStateWithLifecycle()
    val selectedUris = remember { mutableStateListOf<android.net.Uri>() }
    val selectionMode = selectedUris.isNotEmpty()
    fun toggleSelection(uri: android.net.Uri) { if (uri in selectedUris) selectedUris.remove(uri) else selectedUris.add(uri) }

    val folderName = remember(folderPath) {
        folderPath.trimEnd('/').substringAfterLast('/').ifBlank { folderPath }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${items.size} ملف  •  ${items.count { it.media.isVideo }} فيديو  •  ${items.count { it.media.isAudio }} موسيقى  •  ${items.count { System.currentTimeMillis() - it.media.addedAtMs <= 604800000L && it.media.id !in seenNewMediaIds }} جديد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !isRefreshing
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.lib_refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (selectionMode) {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 12.dp, vertical = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedUris.size} ملف محدد", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    androidx.compose.material3.TextButton(onClick = { selectedUris.clear() }) { Text("إلغاء") }
                    androidx.compose.material3.TextButton(onClick = { onSendMedia(items.filter { it.media.uri in selectedUris }.map { it.media }) }) { Text("إرسال المحدد") }
                }
            }
        }
        if (items.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.lib_empty_title),
                body = stringResource(R.string.lib_empty_body),
                icon = Icons.Filled.Movie,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(contentPadding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(items = items.sortedByDescending { it.media.addedAtMs }, key = { it.media.id }) { item ->
                FolderItemRow(
                    item = item,
                    isNew = System.currentTimeMillis() - item.media.addedAtMs <= 604800000L && item.media.id !in seenNewMediaIds,
                    onClick = { viewModel.markNewMediaSeen(item.media.id); onOpenMedia(item.media) },
                    onSend = { toggleSelection(item.media.uri) },
                    selectionMode = selectionMode,
                    isSelected = item.media.uri in selectedUris,
                    onSelect = { toggleSelection(item.media.uri) },
                    onToggleFavourite = { viewModel.toggleFavourite(item.media.id) }
                )
            }
        }
    }
}

@Composable
private fun FolderItemRow(
    item: LibraryItem,
    isNew: Boolean,
    onClick: () -> Unit,
    onSend: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavourite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (selectionMode) onSelect() else onClick() }, onLongClick = onSelect)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show the real video frame or embedded album cover instead of a generic type icon.
        // MediaThumbnail decodes off the main thread and falls back safely when artwork is unavailable.
        MediaThumbnail(
            media = item.media,
            modifier = Modifier.size(76.dp, 56.dp),
            targetSize = android.util.Size(304, 224),
            cornerRadius = 9.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.media.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(7.dp))
                if (isNew) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.ui.graphics.Color(0xFFE84D75),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "جديد",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                androidx.compose.material3.Surface(
                    color = if (item.media.isVideo) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.media.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (item.media.isVideo) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (item.media.isVideo) "فيديو" else "موسيقى",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.media.isVideo) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            val details = buildList {
                if (item.media.isAudio) {
                    item.media.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }?.let { add(it) }
                    item.media.album?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }?.let(::add)
                } else {
                    formatResolution(item.media.width, item.media.height)
                        .takeIf { it.isNotBlank() }?.let(::add)
                }
                add(formatDuration(item.media.durationMs))
                add(formatBytes(item.media.sizeBytes))
            }.joinToString(" • ")
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.isInProgress) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = item.fraction.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (item.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (item.isFavourite) R.string.lib_remove_favourite else R.string.lib_add_favourite
                ),
                tint = if (item.isFavourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
