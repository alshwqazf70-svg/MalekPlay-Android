@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.malekplay.ui.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaBulkDeleteBar
import com.malekplay.ui.components.PlaylistPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalFolderScreen(
    folderPath: String,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    contentPadding: PaddingValues,
    onSendMedia: (List<MediaItem>) -> Unit = {}
) {
    val viewModel: LibraryViewModel = hiltViewModel()
    val colors = MaterialTheme.colorScheme

    val items by remember(folderPath) { viewModel.folderContents(folderPath) }
        .collectAsStateWithLifecycle(initialValue = emptyList<LibraryItem>())
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lastPlayedMediaId by viewModel.lastPlayedMediaId.collectAsStateWithLifecycle(initialValue = null)
    val seenNewMediaIds by viewModel.seenNewMediaIds.collectAsStateWithLifecycle()
    val newMediaBaselineMs by viewModel.newMediaBaselineMs.collectAsStateWithLifecycle()
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
    val selectedUris = remember { mutableStateListOf<Uri>() }
    val selectionMode = selectedUris.isNotEmpty()
    fun toggleSelection(uri: Uri) {
        if (uri in selectedUris) selectedUris.remove(uri) else selectedUris.add(uri)
    }

    LaunchedEffect(settings.sortMode) {
        if (settings.sortMode == SortMode.MANUAL) selectedUris.clear()
    }

    val sortedItems by produceState(
        initialValue = emptyList<LibraryItem>(),
        items,
        settings.sortMode,
        settings.sortAscending,
        lastPlayedMediaId,
        settings.manualSortOrder
    ) {
        value = withContext(Dispatchers.Default) {
            viewModel.applySort(
                items = items,
                mode = settings.sortMode,
                ascending = settings.sortAscending,
                lastPlayedMediaId = lastPlayedMediaId,
                manualOrder = settings.manualSortOrder
            )
        }
    }

    val folderName = remember(folderPath) {
        folderPath.trimEnd('/').substringAfterLast('/').ifBlank { folderPath }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    Column {
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val newCount = items.count {
                            newMediaBaselineMs > 0L &&
                                it.media.addedAtMs >= newMediaBaselineMs &&
                                it.media.id !in seenNewMediaIds
                        }
                        Text(
                            text = "${items.size} ملف  •  ${items.count { it.media.isVideo }} فيديو  •  ${items.count { it.media.isAudio }} موسيقى" +
                                if (newCount > 0) "  •  $newCount جديد" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
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
                        onClick = {
                            viewModel.setViewMode(
                                if (settings.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                            )
                        }
                    ) {
                        Icon(
                            imageVector = if (settings.viewMode == ViewMode.LIST) {
                                Icons.Filled.GridView
                            } else {
                                Icons.Filled.ViewList
                            },
                            contentDescription = stringResource(R.string.lib_view_grid)
                        )
                    }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = 0.22f),
                            colors.background,
                            colors.surface
                        )
                    )
                )
                .padding(padding)
                .padding(contentPadding)
        ) {
            if (items.isNotEmpty()) {
                LibrarySortBar(
                    sortMode = settings.sortMode,
                    sortAscending = settings.sortAscending,
                    foldersFirst = settings.foldersFirst,
                    showFoldersFirstOption = false,
                    onSelectMode = viewModel::setSortMode,
                    onToggleAscending = { viewModel.setSortAscending(!settings.sortAscending) },
                    onToggleFoldersFirst = { viewModel.setFoldersFirst(!settings.foldersFirst) }
                )
                ManualSortBanner(
                    visible = settings.sortMode == SortMode.MANUAL,
                    hasCustomOrder = settings.manualSortOrder.isNotEmpty(),
                    onReset = viewModel::resetManualSortOrder,
                    onDone = { viewModel.setSortMode(SortMode.NEWEST) }
                )
                Spacer(Modifier.height(6.dp))
            }

            if (selectionMode) {
                Surface(
                    color = colors.primaryContainer.copy(alpha = 0.95f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedUris.size} ملف محدد",
                            modifier = Modifier.weight(1f),
                            color = colors.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { selectedUris.clear() }) { Text("إلغاء") }
                        TextButton(onClick = {
                            onSendMedia(items.filter { it.media.uri in selectedUris }.map { it.media })
                        }) { Text("إرسال المحدد") }
                    }
                }
                MediaBulkDeleteBar(
                    mediaItems = items.filter { it.media.uri in selectedUris }.map { it.media },
                    onDeleted = viewModel::refresh,
                    onClearSelection = { selectedUris.clear() }
                )
            }

            if (items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.lib_empty_title),
                    body = stringResource(R.string.lib_empty_body),
                    icon = Icons.Filled.Movie,
                    modifier = Modifier.fillMaxSize()
                )
                return@Column
            }

            ReorderableMediaCollection(
                items = sortedItems,
                viewMode = settings.viewMode,
                emptyTitle = stringResource(R.string.lib_empty_title),
                emptyBody = stringResource(R.string.lib_empty_body),
                lastPlayedMediaId = lastPlayedMediaId,
                onPlay = onOpenMedia,
                onSend = { toggleSelection(it.uri) },
                selectionMode = selectionMode,
                selectedUris = selectedUris.toSet(),
                onToggleSelection = ::toggleSelection,
                onFavourite = { viewModel.toggleFavourite(it.media.id) },
                onAddToPlaylist = { playlistTarget = it.media },
                onDeleted = viewModel::refresh,
                manualReorderEnabled = settings.sortMode == SortMode.MANUAL,
                onMoveManualItem = viewModel::moveManualItem,
                onStepManualItem = viewModel::stepManualItem
            )
        }
    }

    playlistTarget?.let { media ->
        PlaylistPickerDialog(
            mediaId = media.id,
            onDismiss = { playlistTarget = null }
        )
    }
}
