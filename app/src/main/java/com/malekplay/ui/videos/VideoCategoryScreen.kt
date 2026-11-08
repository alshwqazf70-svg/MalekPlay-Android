package com.malekplay.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.PlaylistPickerDialog
import com.malekplay.ui.library.LibraryViewModel

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
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedMedia = remember { mutableStateListOf<Uri>() }
    val selectionMode = selectedMedia.isNotEmpty()
    fun toggleSelection(uri: Uri) { if (uri in selectedMedia) selectedMedia.remove(uri) else selectedMedia.add(uri) }
    val title = stringResource(if (series) R.string.nav_series else R.string.nav_movies)
    // Classification depends only on the media snapshot. Reuse it across
    // unrelated recompositions (for example playlist dialog visibility).
    val classified = remember(videos) {
        videos.map { item ->
            item to VideoKindClassifier.classify(
                item.media.displayTitle,
                item.media.folderName,
                item.media.folderPath
            )
        }
    }
    val unclassifiedCount = remember(classified) {
        classified.count { it.second == VideoKind.UNCLASSIFIED }
    }
    val filtered = remember(classified, series, searchQuery) {
        val q = searchQuery.trim()
        classified.filter { (item, kind) ->
            val inCategory = if (series) kind == VideoKind.SERIES else kind != VideoKind.SERIES
            val matches = q.isBlank() ||
                item.media.displayTitle.contains(q, ignoreCase = true) ||
                item.media.folderName.contains(q, ignoreCase = true) ||
                item.media.folderPath.contains(q, ignoreCase = true)
            inCategory && matches
        }.map { it.first }
    }

    // Parse each filename once per library snapshot. Re-running several
    // regular expressions from a sort comparator is expensive on large libraries.
    val orderedWithEpisode = remember(filtered) {
        filtered.map { item ->
            val episode = VideoKindClassifier.episodeSortKey(
                item.media.displayTitle,
                item.media.folderName,
                item.media.folderPath
            )
            item to episode
        }.sortedWith(
            compareBy<Pair<LibraryItem, EpisodeSortKey?>>(
                { it.second?.seriesKey ?: it.first.media.displayTitle.lowercase() },
                { it.second?.season ?: Int.MAX_VALUE },
                { it.second?.episode ?: Int.MAX_VALUE },
                { it.first.media.displayTitle.lowercase() }
            )
        )
    }
    val orderedFiltered = remember(orderedWithEpisode) {
        orderedWithEpisode.map { it.first }
    }

    val seriesSections = remember(orderedWithEpisode, series) {
        if (!series) emptyList()
        else orderedWithEpisode.groupBy { (item, episode) ->
            episode?.seriesKey?.takeIf { it.isNotBlank() }
                ?: item.media.folderName.takeIf { it.isNotBlank() }
                ?: item.media.displayTitle.substringBeforeLast('.').ifBlank { item.media.displayTitle }
        }.mapValues { (_, entries) -> entries.map { it.first } }.toList()
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, color = colors.onSurface, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (series) "عالم الحلقات والمواسم" else "تجربتك السينمائية",
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = colors.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(if (series) "ابحث عن مسلسل أو حلقة..." else "ابحث عن فيلم...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "بحث") }
                )
                EmptyState(
                    title = if (searchQuery.isBlank()) stringResource(R.string.video_category_empty_title, title) else "لا توجد نتائج مطابقة",
                    body = if (searchQuery.isBlank()) stringResource(R.string.video_category_empty_body) else "جرّب اسماً آخر أو امسح عبارة البحث.",
                    icon = Icons.Filled.Movie,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh, colors.background, colors.surface)))
                    .padding(padding)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainerLow)))
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(
                                    colors.primary.copy(alpha = 0.48f),
                                    colors.outlineVariant.copy(alpha = 0.55f),
                                    colors.secondary.copy(alpha = 0.30f)
                                )
                            ),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Text(
                        text = if (series) "مكتبة المسلسلات" else "مكتبة الأفلام",
                        color = colors.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.video_category_count, filtered.size),
                        modifier = Modifier.padding(top = 3.dp),
                        color = colors.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(if (series) "ابحث عن مسلسل أو حلقة..." else "ابحث عن فيلم...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "بحث") }
                )
                if (selectionMode) {
                    androidx.compose.material3.Surface(color = colors.primaryContainer.copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${selectedMedia.size} ملف محدد", color = colors.onPrimaryContainer, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { selectedMedia.clear() }) { Text("إلغاء") }
                            TextButton(onClick = { onSendMedia(filtered.filter { it.media.uri in selectedMedia }.map { it.media }) }) { Text("إرسال المحدد") }
                        }
                    }
                }
                if (!series && unclassifiedCount > 0) {
                    Text(
                        text = stringResource(R.string.video_category_unclassified_notice, unclassifiedCount),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (series) {
                        seriesSections.forEach { (seriesName, episodes) ->
                            item(key = "series-header-$seriesName", span = { GridItemSpan(maxLineSpan) }) {
                                androidx.compose.material3.Surface(
                                    color = colors.surfaceContainerHigh,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(
                                            text = seriesName.replace('_', ' ').replace('.', ' '),
                                            color = colors.onSurface,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${episodes.size} حلقة",
                                            color = colors.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            items(episodes, key = { it.media.id }) { item ->
                                MediaGridCard(
                            item = item,
                            onPlay = { onOpenMedia(item.media) },
                            onFavourite = { viewModel.toggleFavourite(item.media.id) },
                            onAddToPlaylist = { playlistTarget = item.media },
                            onDeleted = viewModel::refresh,
                            onSend = { toggleSelection(item.media.uri) },
                            selectionMode = selectionMode,
                            isSelected = item.media.uri in selectedMedia,
                            onSelect = { toggleSelection(item.media.uri) }
                        )
                            }
                        }
                    } else {
                        items(orderedFiltered, key = { it.media.id }) { item ->
                            MediaGridCard(
                            item = item,
                            onPlay = { onOpenMedia(item.media) },
                            onFavourite = { viewModel.toggleFavourite(item.media.id) },
                            onAddToPlaylist = { playlistTarget = item.media },
                            onDeleted = viewModel::refresh,
                            onSend = { toggleSelection(item.media.uri) },
                            selectionMode = selectionMode,
                            isSelected = item.media.uri in selectedMedia,
                            onSelect = { toggleSelection(item.media.uri) }
                        )
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
