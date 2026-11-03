package com.malekplay.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.PlaylistPickerDialog
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onVideoClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val folders by viewModel.folders.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<com.malekplay.domain.model.MediaItem?>(null) }
    val settings by viewModel.settings.collectAsState()

    val displayedFlow: Flow<List<LibraryItem>> = remember(searchQuery, searchActive, selectedFolder) {
        when {
            searchActive && searchQuery.isNotBlank() -> viewModel.search(searchQuery.trim())
            selectedFolder != null -> viewModel.folderContents(selectedFolder!!.path)
            else -> viewModel.videos
        }
    }
    val displayedItems by displayedFlow.collectAsState(initial = emptyList())

    BackHandler(enabled = selectedFolder != null || searchActive) {
        if (selectedFolder != null) selectedFolder = null
        else {
            searchActive = false
            searchQuery = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("بحث في الفيديوهات والمجلدات...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = selectedFolder?.name ?: "مكتبة الفيديو",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                navigationIcon = {
                    if (selectedFolder != null || searchActive) {
                        IconButton(onClick = {
                            if (selectedFolder != null) selectedFolder = null
                            else { searchActive = false; searchQuery = "" }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                        }
                    } else {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث")
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "ترتيب")
                        }
                        IconButton(onClick = {
                            viewModel.setViewMode(if (settings.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
                        }) {
                            Icon(
                                if (settings.viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "تغيير طريقة العرض"
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(text = { Text("الأحدث") }, onClick = {
                                viewModel.setSortMode(SortMode.NEWEST); showSortMenu = false
                            })
                            DropdownMenuItem(text = { Text("الأقدم") }, onClick = {
                                viewModel.setSortMode(SortMode.OLDEST); showSortMenu = false
                            })
                            DropdownMenuItem(text = { Text("الاسم") }, onClick = {
                                viewModel.setSortMode(SortMode.NAME); showSortMenu = false
                            })
                            DropdownMenuItem(text = { Text("المدة") }, onClick = {
                                viewModel.setSortMode(SortMode.DURATION); showSortMenu = false
                            })
                            DropdownMenuItem(text = { Text("الحجم") }, onClick = {
                                viewModel.setSortMode(SortMode.SIZE); showSortMenu = false
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            if (isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null && folders.isEmpty() && !isRefreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "تعذر تحميل المكتبة", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("إعادة المحاولة") }
                    }
                }
            } else if (selectedFolder == null && !searchActive) {
                val sortedFolders = viewModel.applyFolderSort(folders, settings.sortMode)
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Text(
                            "المجلدات",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(sortedFolders, key = { it.path }) { folder ->
                        FolderItemRow(folder = folder, onClick = { selectedFolder = folder })
                    }
                }
            } else {
                if (displayedItems.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchActive && searchQuery.isNotBlank()) "لا توجد نتائج مطابقة"
                            else "لا توجد فيديوهات في هذا المجلد",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val sortedItems = viewModel.applySort(displayedItems, settings.sortMode)
                    if (settings.viewMode == ViewMode.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 148.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            gridItems(sortedItems, key = { it.media.id }) { item ->
                                MediaGridCard(
                                    item = item,
                                    onPlay = { onVideoClick(item) },
                                    onFavourite = { viewModel.toggleFavourite(item.media.id) },
                                    onAddToPlaylist = { playlistTarget = item.media }
                                )
                            }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(sortedItems, key = { it.media.id }) { item ->
                                VideoItemRow(item = item, onClick = { onVideoClick(item) })
                            }
                        }
                    }
                }
            }
        }
    }
    playlistTarget?.let { media ->
        PlaylistPickerDialog(mediaId = media.id, onDismiss = { playlistTarget = null })
    }
}

@Composable
fun FolderItemRow(folder: MediaFolder, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${folder.videoCount} فيديو • ${folder.audioCount} صوت",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun VideoItemRow(item: LibraryItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(104.dp, 66.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            MediaThumbnail(
                media = item.media,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 12.dp
            )
            Text(
                formatDuration(item.media.durationMs),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(topStart = 6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.media.displayTitle, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(formatFileSize(item.media.sizeBytes), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.isInProgress) {
                LinearProgressIndicator(progress = item.fraction, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            }
        }
    }
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0)
    else String.format("%.1f MB", mb)
}
