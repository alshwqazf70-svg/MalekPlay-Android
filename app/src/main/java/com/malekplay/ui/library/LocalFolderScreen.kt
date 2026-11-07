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
    val selectedUris = remember { mutableStateListOf<android.net.Uri>() }
    val selectionMode = selectedUris.isNotEmpty()
    fun toggleSelection(uri: android.net.Uri) { if (uri in selectedUris) selectedUris.remove(uri) else selectedUris.add(uri) }

    val folderName = remember(folderPath) {
        folderPath.trimEnd('/').substringAfterLast('/').ifBlank { folderPath }
    }

    Scaffold(
        containerColor = MalekPlayColors.Background,
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
                            text = stringResource(R.string.lib_file_count, items.size),
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
            androidx.compose.material3.Surface(color = MalekPlayColors.PurpleDeep.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 12.dp, vertical = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedUris.size} ملف محدد", modifier = Modifier.weight(1f), color = MalekPlayColors.TextPrimary)
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
            items(items = items, key = { it.media.id }) { item ->
                FolderItemRow(
                    item = item,
                    onClick = { onOpenMedia(item.media) },
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
            .background(if (isSelected) MalekPlayColors.PurpleDeep.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.media.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.media.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val details = buildList {
                add(formatDuration(item.media.durationMs))
                add(formatBytes(item.media.sizeBytes))
                formatResolution(item.media.width, item.media.height)
                    .takeIf { it.isNotBlank() }?.let { add(it) }
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
