package com.malekplay.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onVideoClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val folders by viewModel.folders.collectAsState()
    val allMedia by viewModel.allMedia.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var selectedFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val searchFlow: Flow<List<LibraryItem>> = remember(searchQuery) {
        if (searchQuery.isBlank()) flowOf(emptyList()) else viewModel.search(searchQuery)
    }
    val searchResults by searchFlow.collectAsState(initial = emptyList())

    val folderFlow: Flow<List<LibraryItem>> = remember(selectedFolder?.path) {
        selectedFolder?.let { viewModel.folderContents(it.path) } ?: flowOf(emptyList())
    }
    val folderItems by folderFlow.collectAsState(initial = emptyList())

    val showingSearch = isSearchActive || searchQuery.isNotBlank()
    val displayedItems = when {
        selectedFolder != null -> folderItems
        showingSearch -> searchResults
        else -> allMedia
    }
    val sortedItems = viewModel.applySort(displayedItems, settings.sortMode)
    val sortedFolders = viewModel.applyFolderSort(folders, settings.sortMode)

    BackHandler(enabled = selectedFolder != null || isSearchActive) {
        if (selectedFolder != null) {
            selectedFolder = null
        } else {
            isSearchActive = false
            searchQuery = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
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
                            text = selectedFolder?.name ?: if (showingSearch) "نتائج البحث" else "مكتبة الفيديو",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                navigationIcon = {
                    if (selectedFolder != null) {
                        IconButton(onClick = { selectedFolder = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                        }
                    } else if (isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "إلغاء البحث")
                        }
                    }
                },
                actions = {
                    if (!isSearchActive && selectedFolder == null) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث")
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "ترتيب")
                        }
                        IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("الاسم") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.NAME)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("الأحدث") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.NEWEST)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("الحجم") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.SIZE)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isRefreshing && allMedia.isEmpty() && folders.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null && allMedia.isEmpty() && folders.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error ?: "حدث خطأ غير متوقع", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("إعادة المحاولة") }
                    }
                }
                selectedFolder != null || showingSearch -> {
                    if (sortedItems.isEmpty()) {
                        Text(
                            text = if (showingSearch) "لا توجد نتائج مطابقة" else "هذا المجلد فارغ",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            items(sortedItems, key = { it.media.id }) { item ->
                                VideoItemRow(item) { onVideoClick(item) }
                            }
                        }
                    }
                }
                sortedFolders.isEmpty() -> {
                    Text(
                        text = "لا توجد ملفات وسائط في الجهاز",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(sortedFolders, key = { it.path }) { folder ->
                            FolderItemRow(folder) { selectedFolder = folder }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderItemRow(
    folder: MediaFolder,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${folder.videoCount} فيديو • ${folder.audioCount} صوتيات",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun VideoItemRow(
    mediaItem: LibraryItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 85.dp, height = 55.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (mediaItem.media.isVideo) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = formatDuration(mediaItem.media.durationMs),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mediaItem.media.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatFileSize(mediaItem.media.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (mediaItem.isInProgress) {
                Spacer(modifier = Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { mediaItem.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb > 1024.0) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}
