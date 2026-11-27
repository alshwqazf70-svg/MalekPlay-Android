@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.malekplay.ui.videos

import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaBulkDeleteBar
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.MediaListRow
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.components.PlaylistPickerDialog
import com.malekplay.ui.library.LibraryViewModel

private enum class VideoFilter {
    ALL, UNWATCHED, IN_PROGRESS, COMPLETED, FAVOURITES
}

private enum class VideoSortOption {
    DEFAULT, NAME, NEWEST, DURATION, SIZE
}

private data class SeriesGroupSummary(
    val key: String,
    val displayName: String,
    val entries: List<Pair<LibraryItem, EpisodeSortKey?>>,
    val seasons: List<Int>,
    val watchedCount: Int,
    val nextEpisode: LibraryItem
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCategoryScreen(
    series: Boolean,
    onBack: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onSendMedia: (List<MediaItem>) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val lastPlayedMediaId by viewModel.lastPlayedMediaId.collectAsStateWithLifecycle(initialValue = null)

    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(VideoFilter.ALL) }
    var sortOption by remember { mutableStateOf(VideoSortOption.DEFAULT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }
    var groupedSeriesView by remember { mutableStateOf(true) }
    var selectedSeriesKey by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    val selectedMedia = remember { mutableStateListOf<Uri>() }
    val selectionMode = selectedMedia.isNotEmpty()
    fun toggleSelection(uri: Uri) {
        if (uri in selectedMedia) selectedMedia.remove(uri) else selectedMedia.add(uri)
    }

    BackHandler(enabled = series && selectedSeriesKey != null) {
        selectedSeriesKey = null
        selectedSeason = null
    }

    val title = stringResource(if (series) R.string.nav_series else R.string.nav_movies)

    // Classification depends only on the media snapshot.
    val classified = remember(videos) {
        val classificationById = VideoKindClassifier.classifyAll(
            videos.map { item ->
                VideoFileDescriptor(
                    id = item.media.id,
                    title = item.media.displayTitle,
                    folderName = item.media.folderName,
                    folderPath = item.media.folderPath
                )
            }
        )
        videos.mapNotNull { item ->
            classificationById[item.media.id]?.let { classification -> item to classification }
        }
    }
    val classificationById = remember(classified) {
        classified.associate { it.first.media.id to it.second }
    }

    val categoryBase = remember(classified, series) {
        classified.filter { (_, kind) ->
            if (series) kind.kind == VideoKind.SERIES else kind.kind != VideoKind.SERIES
        }
    }
    val unclassifiedCount = remember(categoryBase, series) {
        if (series) 0 else categoryBase.count { it.second.kind == VideoKind.UNCLASSIFIED }
    }

    val filtered = remember(categoryBase, searchQuery, activeFilter) {
        val q = searchQuery.trim()
        categoryBase.filter { (item, _) ->
            val matchesFilter = when (activeFilter) {
                VideoFilter.ALL -> true
                VideoFilter.UNWATCHED -> !item.isCompleted && !item.isInProgress
                VideoFilter.IN_PROGRESS -> item.isInProgress
                VideoFilter.COMPLETED -> item.isCompleted
                VideoFilter.FAVOURITES -> item.isFavourite
            }
            val matchesSearch = q.isBlank() ||
                item.media.displayTitle.contains(q, ignoreCase = true) ||
                item.media.folderName.contains(q, ignoreCase = true) ||
                item.media.folderPath.contains(q, ignoreCase = true)
            matchesFilter && matchesSearch
        }.map { it.first }
    }

    val orderedWithEpisode = remember(filtered, sortOption, classificationById) {
        val withEp = filtered.map { item ->
            item to (classificationById[item.media.id] ?: ClassifiedVideo(VideoKind.UNCLASSIFIED))
        }
        when (sortOption) {
            VideoSortOption.DEFAULT -> withEp.sortedWith(
                compareBy<Pair<LibraryItem, ClassifiedVideo>>(
                    { it.second.episode?.seriesKey ?: it.first.media.displayTitle.lowercase() },
                    { it.second.episode?.season ?: Int.MAX_VALUE },
                    { it.second.episode?.episode ?: Int.MAX_VALUE },
                    { it.first.media.displayTitle.lowercase() }
                )
            )
            VideoSortOption.NAME -> withEp.sortedBy { it.first.media.displayTitle.lowercase() }
            VideoSortOption.NEWEST -> withEp.sortedByDescending { it.first.media.addedAtMs }
            VideoSortOption.DURATION -> withEp.sortedByDescending { it.first.media.durationMs }
            VideoSortOption.SIZE -> withEp.sortedByDescending { it.first.media.sizeBytes }
        }
    }
    val orderedFiltered = remember(orderedWithEpisode) {
        orderedWithEpisode.map { it.first }
    }

    val seriesGroups = remember(orderedWithEpisode, series) {
        if (!series) emptyList()
        else {
            orderedWithEpisode.groupBy { (item, classifiedVideo) ->
                classifiedVideo.episode?.seriesKey?.takeIf { it.isNotBlank() }
                    ?: item.media.folderName.takeIf { it.isNotBlank() }
                    ?: item.media.displayTitle.substringBeforeLast('.').ifBlank { item.media.displayTitle }
            }.map { (key, entries) ->
                val sortedEntries = entries.sortedWith(
                    compareBy<Pair<LibraryItem, ClassifiedVideo>>(
                        { it.second.episode?.season ?: Int.MAX_VALUE },
                        { it.second.episode?.episode ?: Int.MAX_VALUE },
                        { it.first.media.displayTitle.lowercase() }
                    )
                )
                val seasons = sortedEntries.mapNotNull { it.second.episode?.season }.distinct().sorted()
                val watched = sortedEntries.count { it.first.isCompleted }
                val nextEp = sortedEntries.firstOrNull { it.first.isInProgress }?.first
                    ?: sortedEntries.firstOrNull { !it.first.isCompleted }?.first
                    ?: sortedEntries.first().first
                SeriesGroupSummary(
                    key = key,
                    displayName = VideoKindClassifier.formatSeriesDisplayName(key),
                    entries = sortedEntries.map { it.first to it.second.episode },
                    seasons = seasons,
                    watchedCount = watched,
                    nextEpisode = nextEp
                )
            }
        }
    }

    val activeSeriesGroup = remember(seriesGroups, selectedSeriesKey) {
        selectedSeriesKey?.let { k -> seriesGroups.firstOrNull { it.key == k } }
    }

    val continueWatchingItems = remember(categoryBase) {
        categoryBase.map { it.first }.filter { it.isInProgress }
    }
    val totalCount = categoryBase.size
    val inProgressCount = remember(categoryBase) { categoryBase.count { it.first.isInProgress } }
    val completedCount = remember(categoryBase) { categoryBase.count { it.first.isCompleted } }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = activeSeriesGroup?.displayName ?: title,
                            color = colors.onSurface,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                activeSeriesGroup != null -> stringResource(
                                    R.string.video_series_watched_progress,
                                    activeSeriesGroup.watchedCount,
                                    activeSeriesGroup.entries.size
                                )
                                series -> "عالم الحلقات والمواسم"
                                else -> "تجربتك السينمائية"
                            },
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (activeSeriesGroup != null) {
                                selectedSeriesKey = null
                                selectedSeason = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = colors.primary
                        )
                    }
                },
                actions = {
                    if (!series || !groupedSeriesView || activeSeriesGroup != null) {
                        IconButton(onClick = { gridView = !gridView }) {
                            Icon(
                                imageVector = if (gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                contentDescription = null,
                                tint = colors.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (categoryBase.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = stringResource(R.string.video_category_empty_title, title),
                    body = stringResource(R.string.video_category_empty_body),
                    icon = if (series) Icons.Filled.LiveTv else Icons.Filled.Movie,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.surfaceContainerHigh, colors.background, colors.surface)
                        )
                    )
                    .padding(padding)
            ) {
                if (activeSeriesGroup != null) {
                    // Series detail view with seasons and sorted episodes
                    val seasonEpisodes = remember(activeSeriesGroup, selectedSeason) {
                        val s = selectedSeason
                        if (s == null) activeSeriesGroup.entries
                        else activeSeriesGroup.entries.filter { it.second?.season == s }
                    }
                    ElevatedCard(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .border(1.dp, colors.primary.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                MediaThumbnail(
                                    media = activeSeriesGroup.nextEpisode.media,
                                    modifier = Modifier.size(86.dp, 56.dp),
                                    targetSize = Size(180, 120),
                                    cornerRadius = 12.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeSeriesGroup.seasons.size > 1) {
                                            stringResource(
                                                R.string.video_series_seasons_episodes,
                                                activeSeriesGroup.seasons.size,
                                                activeSeriesGroup.entries.size
                                            )
                                        } else {
                                            stringResource(
                                                R.string.video_series_episodes_only,
                                                activeSeriesGroup.entries.size
                                            )
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.video_series_watched_progress,
                                            activeSeriesGroup.watchedCount,
                                            activeSeriesGroup.entries.size
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.primary
                                    )
                                }
                                Button(
                                    onClick = { onOpenMedia(activeSeriesGroup.nextEpisode.media) },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary,
                                        contentColor = contrastingOnColor(colors.primary)
                                    )
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.video_series_play_next), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (activeSeriesGroup.seasons.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = selectedSeason == null,
                                        onClick = { selectedSeason = null },
                                        label = { Text(stringResource(R.string.video_series_season_all)) }
                                    )
                                    activeSeriesGroup.seasons.forEach { seasonNum ->
                                        FilterChip(
                                            selected = selectedSeason == seasonNum,
                                            onClick = { selectedSeason = seasonNum },
                                            label = { Text(stringResource(R.string.video_series_season_number, seasonNum)) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectionMode) {
                        VideoSelectionBar(
                            selectedCount = selectedMedia.size,
                            onSelectAll = {
                                seasonEpisodes.forEach { (item, _) ->
                                    if (item.media.uri !in selectedMedia) selectedMedia.add(item.media.uri)
                                }
                            },
                            onClear = { selectedMedia.clear() },
                            onSend = {
                                onSendMedia(categoryBase.map { it.first }.filter { it.media.uri in selectedMedia }.map { it.media })
                            }
                        )
                        MediaBulkDeleteBar(
                            mediaItems = categoryBase.map { it.first }.filter { it.media.uri in selectedMedia }.map { it.media },
                            onDeleted = viewModel::refresh,
                            onClearSelection = { selectedMedia.clear() }
                        )
                    }

                    MediaItemsCollection(
                        items = seasonEpisodes.map { it.first },
                        gridView = gridView,
                        lastPlayedMediaId = lastPlayedMediaId,
                        selectionMode = selectionMode,
                        selectedUris = selectedMedia.toSet(),
                        onPlay = onOpenMedia,
                        onToggleSelection = ::toggleSelection,
                        onFavourite = { viewModel.toggleFavourite(it) },
                        onAddToPlaylist = { playlistTarget = it },
                        onDeleted = viewModel::refresh
                    )
                } else {
                    // Main Series or Movies overview
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainerLow)
                                )
                            )
                            .border(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        colors.primary.copy(alpha = 0.45f),
                                        colors.outlineVariant.copy(alpha = 0.50f),
                                        colors.secondary.copy(alpha = 0.30f)
                                    )
                                ),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (series) "مكتبة المسلسلات" else "مكتبة الأفلام",
                                    color = colors.onSurface,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (series) {
                                        stringResource(R.string.video_series_stats, seriesGroups.size, totalCount)
                                    } else {
                                        stringResource(R.string.video_movies_stats, totalCount, inProgressCount, completedCount)
                                    },
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = colors.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!series && orderedFiltered.size >= 2) {
                                FilledTonalButton(
                                    onClick = {
                                        val pick = orderedFiltered.filterNot { it.isCompleted }.randomOrNull()
                                            ?: orderedFiltered.random()
                                        onOpenMedia(pick.media)
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.video_pick_random_movie),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    // Search box with quick clear
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = {
                            Text(if (series) "ابحث عن مسلسل أو حلقة..." else "ابحث عن فيلم...")
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.music_clear_search))
                                }
                            }
                        }
                    )

                    // Series view mode toggle + status filter chips + sort menu
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (series) {
                            FilterChip(
                                selected = groupedSeriesView,
                                onClick = { groupedSeriesView = true },
                                leadingIcon = {
                                    Icon(Icons.Filled.LiveTv, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(stringResource(R.string.video_series_grouped)) }
                            )
                            FilterChip(
                                selected = !groupedSeriesView,
                                onClick = { groupedSeriesView = false },
                                label = { Text(stringResource(R.string.video_series_all_episodes)) }
                            )
                        }
                        listOf(
                            VideoFilter.ALL to R.string.music_filter_all,
                            VideoFilter.IN_PROGRESS to R.string.video_filter_in_progress,
                            VideoFilter.UNWATCHED to R.string.video_filter_unwatched,
                            VideoFilter.COMPLETED to R.string.video_filter_completed,
                            VideoFilter.FAVOURITES to R.string.music_filter_favourites
                        ).forEach { (filter, labelRes) ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                        Box {
                            FilterChip(
                                selected = sortOption != VideoSortOption.DEFAULT,
                                onClick = { showSortMenu = true },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(videoSortLabel(sortOption)) }
                            )
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                VideoSortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(videoSortLabel(option)) },
                                        onClick = {
                                            sortOption = option
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (selectionMode) {
                        VideoSelectionBar(
                            selectedCount = selectedMedia.size,
                            onSelectAll = {
                                orderedFiltered.forEach {
                                    if (it.media.uri !in selectedMedia) selectedMedia.add(it.media.uri)
                                }
                            },
                            onClear = { selectedMedia.clear() },
                            onSend = {
                                onSendMedia(orderedFiltered.filter { it.media.uri in selectedMedia }.map { it.media })
                            }
                        )
                        MediaBulkDeleteBar(
                            mediaItems = orderedFiltered.filter { it.media.uri in selectedMedia }.map { it.media },
                            onDeleted = viewModel::refresh,
                            onClearSelection = { selectedMedia.clear() }
                        )
                    }

                    if (!series && unclassifiedCount > 0 && activeFilter == VideoFilter.ALL && searchQuery.isBlank()) {
                        Text(
                            text = stringResource(R.string.video_category_unclassified_notice, unclassifiedCount),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (orderedFiltered.isEmpty()) {
                        EmptyState(
                            title = "لا توجد نتائج مطابقة",
                            body = "جرّب عبارة بحث أخرى أو غيّر عامل التصفية.",
                            icon = if (series) Icons.Filled.LiveTv else Icons.Filled.Movie,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    } else if (series && groupedSeriesView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 172.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (continueWatchingItems.isNotEmpty() && searchQuery.isBlank() && activeFilter == VideoFilter.ALL) {
                                item(key = "series-continue-strip", span = { GridItemSpan(maxLineSpan) }) {
                                    ContinueWatchingStrip(
                                        items = continueWatchingItems,
                                        onPlay = onOpenMedia
                                    )
                                }
                            }
                            items(seriesGroups, key = { it.key }) { group ->
                                SeriesGroupCard(
                                    group = group,
                                    onOpenSeries = {
                                        selectedSeriesKey = group.key
                                        selectedSeason = null
                                    },
                                    onPlayNext = { onOpenMedia(group.nextEpisode.media) }
                                )
                            }
                        }
                    } else {
                        MediaItemsCollection(
                            items = orderedFiltered,
                            gridView = gridView,
                            lastPlayedMediaId = lastPlayedMediaId,
                            selectionMode = selectionMode,
                            selectedUris = selectedMedia.toSet(),
                            onPlay = onOpenMedia,
                            onToggleSelection = ::toggleSelection,
                            onFavourite = { viewModel.toggleFavourite(it) },
                            onAddToPlaylist = { playlistTarget = it },
                            onDeleted = viewModel::refresh,
                            headerContent = if (!series && continueWatchingItems.isNotEmpty() && searchQuery.isBlank() && activeFilter == VideoFilter.ALL) {
                                {
                                    ContinueWatchingStrip(
                                        items = continueWatchingItems,
                                        onPlay = onOpenMedia
                                    )
                                }
                            } else null
                        )
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
private fun videoSortLabel(option: VideoSortOption): String = when (option) {
    VideoSortOption.DEFAULT -> stringResource(R.string.music_sort_default)
    VideoSortOption.NAME -> stringResource(R.string.music_sort_name)
    VideoSortOption.NEWEST -> stringResource(R.string.music_sort_newest)
    VideoSortOption.DURATION -> stringResource(R.string.music_sort_duration)
    VideoSortOption.SIZE -> stringResource(R.string.video_sort_size)
}

@Composable
private fun VideoSelectionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_selected_count, selectedCount),
                color = colors.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.music_select_all)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = onSend) { Text(stringResource(R.string.music_send_selected)) }
        }
    }
}

@Composable
private fun ContinueWatchingStrip(
    items: List<LibraryItem>,
    onPlay: (MediaItem) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.video_continue_watching),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(items, key = { "continue-${it.media.id}" }) { item ->
                val shape = RoundedCornerShape(16.dp)
                ElevatedCard(
                    onClick = { onPlay(item.media) },
                    shape = shape,
                    colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                    modifier = Modifier
                        .width(184.dp)
                        .border(1.dp, colors.primary.copy(alpha = 0.28f), shape)
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(102.dp)
                        ) {
                            MediaThumbnail(
                                media = item.media,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0.dp
                            )
                            Surface(
                                shape = CircleShape,
                                color = colors.primary,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = contrastingOnColor(colors.primary),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { item.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = colors.primary,
                            trackColor = colors.surfaceContainerHighest,
                            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
                        )
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = item.media.displayTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colors.onSurface
                            )
                            Text(
                                text = formatDuration(item.media.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesGroupCard(
    group: SeriesGroupSummary,
    onOpenSeries: () -> Unit,
    onPlayNext: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val total = group.entries.size.coerceAtLeast(1)
    val progress = group.watchedCount.toFloat() / total.toFloat()

    ElevatedCard(
        onClick = onOpenSeries,
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.primary.copy(alpha = 0.30f), shape)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
            ) {
                MediaThumbnail(
                    media = group.nextEpisode.media,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.primaryContainer.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (group.seasons.size > 1) {
                            stringResource(
                                R.string.video_series_seasons_episodes,
                                group.seasons.size,
                                group.entries.size
                            )
                        } else {
                            stringResource(R.string.video_series_episodes_only, group.entries.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (group.watchedCount >= group.entries.size) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF16A34A),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
            )
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = group.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.video_series_watched_progress,
                        group.watchedCount,
                        group.entries.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onPlayNext,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.video_series_play_next),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaItemsCollection(
    items: List<LibraryItem>,
    gridView: Boolean,
    lastPlayedMediaId: String?,
    selectionMode: Boolean,
    selectedUris: Set<Uri>,
    onPlay: (MediaItem) -> Unit,
    onToggleSelection: (Uri) -> Unit,
    onFavourite: (String) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onDeleted: () -> Unit,
    headerContent: (@Composable () -> Unit)? = null
) {
    if (gridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (headerContent != null) {
                item(key = "category-header", span = { GridItemSpan(maxLineSpan) }) {
                    headerContent()
                }
            }
            items(items, key = { it.media.id }) { item ->
                MediaGridCard(
                    item = item,
                    isLastPlayed = item.media.id == lastPlayedMediaId,
                    onPlay = { onPlay(item.media) },
                    onFavourite = { onFavourite(item.media.id) },
                    onAddToPlaylist = { onAddToPlaylist(item.media) },
                    onDeleted = onDeleted,
                    onSend = { onToggleSelection(item.media.uri) },
                    selectionMode = selectionMode,
                    isSelected = item.media.uri in selectedUris,
                    onSelect = { onToggleSelection(item.media.uri) }
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (headerContent != null) {
                item(key = "category-header") {
                    headerContent()
                }
            }
            items(items, key = { it.media.id }) { item ->
                MediaListRow(
                    item = item,
                    isCurrent = item.media.id == lastPlayedMediaId,
                    onPlay = { onPlay(item.media) },
                    onFavourite = { onFavourite(item.media.id) },
                    onAddToPlaylist = { onAddToPlaylist(item.media) },
                    showFolder = true,
                    onDeleted = onDeleted,
                    onSend = { onToggleSelection(item.media.uri) },
                    selectionMode = selectionMode,
                    isSelected = item.media.uri in selectedUris,
                    onSelect = { onToggleSelection(item.media.uri) }
                )
            }
        }
    }
}
