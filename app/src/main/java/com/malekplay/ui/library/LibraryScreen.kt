package com.malekplay.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import com.malekplay.domain.model.MediaItem
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.components.MediaDeleteButton
import com.malekplay.ui.components.PlaylistPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

private enum class LibraryFilter { ALL, WATCHING, FAVOURITES }

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onVideoClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val smartRecommendations by viewModel.smartRecommendations.collectAsStateWithLifecycle()
    val smartCollections by viewModel.smartCollections.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<MediaFolder?>(null) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sortOpen by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
    var assistantQuery by remember { mutableStateOf("") }
    var assistantSubmittedQuery by remember { mutableStateOf("") }
    val assistantFlow: Flow<LibraryAssistantResponse?> = remember(assistantSubmittedQuery) {
        if (assistantSubmittedQuery.isBlank()) flowOf(null)
        else viewModel.assist(assistantSubmittedQuery)
    }
    val assistantResponse by assistantFlow.collectAsStateWithLifecycle(initialValue = null)

    val mediaFlow: Flow<List<LibraryItem>> = remember(query, searching, folder) {
        when {
            searching && query.isNotBlank() -> viewModel.search(query.trim())
            folder != null -> viewModel.folderContents(folder!!.path)
            else -> viewModel.videos
        }
    }
    val rawItems by mediaFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Large-list filtering and sorting can take long enough to cause dropped frames
    // on mid-range phones. Keep this work cancellable and off the main thread.
    val visibleItems by produceState(
        initialValue = emptyList<LibraryItem>(),
        rawItems, filter, settings.sortMode
    ) {
        value = withContext(Dispatchers.Default) {
            val chosen = when (filter) {
                LibraryFilter.ALL -> rawItems
                LibraryFilter.WATCHING -> rawItems.filter { it.isInProgress }
                LibraryFilter.FAVOURITES -> rawItems.filter { it.isFavourite }
            }
            viewModel.applySort(chosen, settings.sortMode)
        }
    }

    BackHandler(folder != null || searching || filter != LibraryFilter.ALL) {
        when {
            folder != null -> folder = null
            searching -> { searching = false; query = "" }
            else -> filter = LibraryFilter.ALL
        }
    }

    val colors = MaterialTheme.colorScheme
    val glass = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))
    val glassStroke = Brush.linearGradient(listOf(colors.primary.copy(alpha = .45f), colors.outlineVariant, colors.secondary.copy(alpha = .25f)))
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.background, colors.surface))
        )
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).size(240.dp)
                .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(.16f), Color.Transparent)), CircleShape)
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(folder?.name ?: "مكتبتي", color = colors.onBackground, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${videos.size} فيديو  •  ${folders.size} مجلد", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (folder != null || searching) {
                    HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") {
                        if (folder != null) folder = null else { searching = false; query = "" }
                    }
                }
                HeaderAction(Icons.Filled.Search, "بحث") { searching = !searching; if (!searching) query = "" }
                HeaderAction(if (settings.viewMode == ViewMode.GRID) Icons.Filled.List else Icons.Filled.GridView, "العرض") {
                    viewModel.setViewMode(if (settings.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
                }
                HeaderAction(Icons.Filled.Sort, "ترتيب") { sortOpen = true }
                HeaderAction(Icons.Filled.Refresh, "تحديث", viewModel::refresh)
            }

            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(when (mode) {
                            SortMode.NEWEST -> "الأحدث أولاً"
                            SortMode.OLDEST -> "الأقدم أولاً"
                            SortMode.NAME -> "الاسم"
                            SortMode.DURATION -> "المدة"
                            SortMode.SIZE -> "حجم الملف"
                        }) },
                        onClick = { viewModel.setSortMode(mode); sortOpen = false }
                    )
                }
            }

            if (searching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp)).background(glass)
                        .border(1.dp, glassStroke, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text("ابحث عن فيلم أو ملف فيديو...", color = colors.onSurfaceVariant)
                                inner()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        Icon(Icons.Filled.Close, "مسح", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { query = "" })
                    }
                }
            }

            if (refreshing) LinearProgressIndicator(
                Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent
            )
            if (error != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer)
                        .border(1.dp, MalekPlayColors.Ruby.copy(.4f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(error ?: "تعذر تحديث المكتبة", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("إعادة المحاولة", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { viewModel.refresh() }.padding(6.dp))
                }
            }

            if (folder == null && !searching) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.padding(top = 5.dp, bottom = 12.dp)
                ) {
                    item { FilterPill("الكل", filter == LibraryFilter.ALL) { filter = LibraryFilter.ALL } }
                    item { FilterPill("متابعة المشاهدة", filter == LibraryFilter.WATCHING) { filter = LibraryFilter.WATCHING } }
                    item { FilterPill("المفضلة", filter == LibraryFilter.FAVOURITES) { filter = LibraryFilter.FAVOURITES } }
                }
                if (filter == LibraryFilter.ALL) {
                    LibraryAssistantPanel(
                        query = assistantQuery,
                        onQueryChange = { assistantQuery = it },
                        onAsk = { assistantSubmittedQuery = assistantQuery.trim() },
                        response = assistantResponse,
                        onResultClick = onVideoClick
                    )
                }
                if (filter == LibraryFilter.ALL && smartRecommendations.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ذكاء مالك المحلي", color = colors.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("يتعلم من مكتبتك • دون إنترنت", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(smartRecommendations, key = { "smart-" + it.media.id }) { recommendation ->
                            SmartRecommendationTile(recommendation) { onVideoClick(recommendation.item) }
                        }
                    }
                }
                if (filter == LibraryFilter.ALL && smartCollections.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("مجموعات أنشأها الذكاء المحلي", color = colors.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("تجميع تلقائي حسب النوع", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(smartCollections, key = { "smart-collection-" + it.query }) { collection ->
                            SmartCollectionTile(collection) {
                                folder = null
                                filter = LibraryFilter.ALL
                                searching = true
                                query = collection.title
                            }
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("تصفح المجلدات", color = colors.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("${folders.size} مجلد", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(viewModel.applyFolderSort(folders, settings.sortMode), key = { it.path }) { entry ->
                            FolderTile(entry, onOpen = { folder = entry })
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = settings.viewMode,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn() + slideInHorizontally { width -> width / 16 }) togetherWith
                        (fadeOut() + slideOutHorizontally { width -> -width / 20 })
                },
                label = "library-view"
            ) { mode ->
                if (visibleItems.isEmpty()) {
                    EmptyLibrary(
                        searching = searching && query.isNotBlank(),
                        filter = filter,
                        onRefresh = viewModel::refresh
                    )
                } else if (mode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        gridItems(visibleItems, key = { it.media.id }) { item ->
                            MediaGridCard(
                                item = item,
                                onPlay = { onVideoClick(item) },
                                onFavourite = { viewModel.toggleFavourite(item.media.id) },
                                onAddToPlaylist = { playlistTarget = item.media },
                                onDeleted = viewModel::refresh
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(visibleItems, key = { it.media.id }) { item ->
                            PremiumVideoRow(
                                item = item,
                                onClick = { onVideoClick(item) },
                                onFavourite = { viewModel.toggleFavourite(item.media.id) },
                                onAddToPlaylist = { playlistTarget = item.media },
                                onDeleted = viewModel::refresh
                            )
                        }
                    }
                }
            }
        }
    }
    playlistTarget?.let { PlaylistPickerDialog(mediaId = it.id, onDismiss = { playlistTarget = null }) }
}


@Composable
private fun LibraryAssistantPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onAsk: () -> Unit,
    response: LibraryAssistantResponse?,
    onResultClick: (LibraryItem) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)))
            .border(1.dp, colors.primary.copy(alpha = .38f), RoundedCornerShape(18.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مساعد مالك للمكتبة", color = colors.onSurface, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("محلي • دون إنترنت", color = colors.primary, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                decorationBox = { inner ->
                    Box {
                        if (query.isBlank()) Text(
                            "مثال: اعرض أفلام الخيال العلمي التي لم أشاهدها",
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "اسأل",
                color = colors.onPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(11.dp))
                    .background(colors.primary).clickable(onClick = onAsk)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        response?.let { result ->
            Text(result.message, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (result.items.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(result.items, key = { "assistant-" + it.media.id }) { item ->
                        Column(
                            Modifier.width(150.dp).clip(RoundedCornerShape(13.dp))
                                .background(colors.surface.copy(alpha = .72f))
                                .clickable { onResultClick(item) }.padding(10.dp)
                        ) {
                            Text(item.media.displayTitle, color = colors.onSurface,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (item.isInProgress) "متابعة المشاهدة" else item.media.genre.orEmpty().ifBlank { "من مكتبتك" },
                                color = colors.primary, maxLines = 1,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartCollectionTile(collection: SmartMediaCollection, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(158.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)))
            .border(1.dp, colors.secondary.copy(alpha = .35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Text(
            collection.title,
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${collection.itemCount} عناصر • ${collection.kind} ذكي",
            color = colors.secondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SmartRecommendationTile(recommendation: SmartMediaRecommendation, onClick: () -> Unit) {
    val item = recommendation.item
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(172.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface)))
            .border(1.dp, colors.primary.copy(alpha = .28f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Text(
            item.media.displayTitle,
            color = colors.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            recommendation.reason,
            color = colors.primary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))
    val glassStroke = Brush.linearGradient(listOf(colors.primary.copy(alpha = .45f), colors.outlineVariant, colors.secondary.copy(alpha = .25f)))
    Box(
        Modifier.padding(start = 5.dp).size(37.dp).clip(CircleShape).background(glass)
            .border(1.dp, glassStroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))
    Row(
        Modifier.clip(CircleShape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(.20f)) else Modifier.background(glass))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(.75f) else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun FolderTile(folder: MediaFolder, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))
    val glassStroke = Brush.linearGradient(listOf(colors.primary.copy(alpha = .45f), colors.outlineVariant, colors.secondary.copy(alpha = .25f)))
    Row(
        Modifier.width(190.dp).animateContentSize().clip(RoundedCornerShape(17.dp)).background(glass)
            .border(1.dp, glassStroke, RoundedCornerShape(17.dp))
            .clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(colors.primaryContainer.copy(.25f))
                    .border(1.dp, colors.primary.copy(.35f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Folder, null, tint = colors.primary)
            }
            if (folder.newItemCount > 0) {
                Box(
                    Modifier.align(Alignment.TopStart).offset(x = (-5).dp, y = (-5).dp)
                        .clip(CircleShape).background(colors.primary)
                        .border(1.dp, colors.surface, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(folder.newItemCount.toString(), color = colors.onPrimary,
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(folder.name, color = colors.onSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${folder.itemCount} ملف • ${folder.videoCount} فيديو • ${folder.audioCount} موسيقى",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 2
            )
            Text(formatFileSize(folder.totalBytes), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PremiumVideoRow(
    item: LibraryItem,
    onClick: () -> Unit,
    onFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDeleted: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val glass = Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.surface))
    val glassStroke = Brush.linearGradient(listOf(colors.primary.copy(alpha = .45f), colors.outlineVariant, colors.secondary.copy(alpha = .25f)))
    Row(
        Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(19.dp)).background(glass)
            .border(1.dp, glassStroke, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(116.dp, 74.dp).clip(RoundedCornerShape(13.dp))) {
            MediaThumbnail(item.media, Modifier.fillMaxSize(), cornerRadius = 13.dp)
            Box(Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape)
                .background(Color.Black.copy(.58f)).border(1.dp, Color.White.copy(.2f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(.9f), modifier = Modifier.size(18.dp))
            }
            if (item.isInProgress) LinearProgressIndicator(
                progress = { item.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(.2f)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.media.displayTitle, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${formatDuration(item.media.durationMs)}  •  ${formatFileSize(item.media.sizeBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            if (item.isInProgress) Text("متابعة المشاهدة • ${(item.fraction * 100).toInt()}%",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            else if (item.isCompleted) Text("تمت المشاهدة", color = MalekPlayColors.Emerald, style = MaterialTheme.typography.labelSmall)
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable(
                onClick = onAddToPlaylist,
                role = androidx.compose.ui.semantics.Role.Button
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlaylistAdd,
                contentDescription = "إضافة إلى قائمة التشغيل",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable(
                onClick = onFavourite,
                role = androidx.compose.ui.semantics.Role.Button
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = if (item.isFavourite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                tint = if (item.isFavourite) Color(0xFFFF6B9D) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
        MediaDeleteButton(media = item.media, compact = true, onDeleted = onDeleted)
    }
}

@Composable
private fun EmptyLibrary(searching: Boolean, filter: LibraryFilter, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(.17f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(.4f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (filter == LibraryFilter.FAVOURITES) Icons.Filled.Favorite else Icons.Filled.Folder,
                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(when {
            searching -> "لا توجد نتائج مطابقة"
            filter == LibraryFilter.WATCHING -> "لا توجد مشاهدة قيد المتابعة"
            filter == LibraryFilter.FAVOURITES -> "قائمة المفضلة فارغة"
            else -> "مكتبتك جاهزة لاستقبال الوسائط"
        }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(if (searching) "جرّب كلمات بحث مختلفة." else "حدّث المكتبة للبحث عن الملفات الجديدة.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(15.dp))
        Text("تحديث المكتبة", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onRefresh).padding(10.dp))
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
    return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
