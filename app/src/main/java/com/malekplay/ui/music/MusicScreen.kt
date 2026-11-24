@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.malekplay.ui.music

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.data.repository.MediaRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.AlbumSummary
import com.malekplay.domain.model.ArtistSummary
import com.malekplay.domain.model.GenreSummary
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.MediaBulkDeleteBar
import com.malekplay.ui.components.MediaDeleteButton
import com.malekplay.ui.components.PlaylistPickerDialog
import com.malekplay.ui.components.loadMediaArtwork
import com.malekplay.ui.library.LibraryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

private enum class MusicSortMode {
    DEFAULT, NAME, NEWEST, DURATION, ARTIST
}

private sealed interface MusicDrillDown {
    data class Album(val summary: AlbumSummary) : MusicDrillDown
    data class Artist(val summary: ArtistSummary) : MusicDrillDown
    data class Genre(val summary: GenreSummary) : MusicDrillDown
}

/**
 * Albums / artists / genres are aggregates; they are read straight from the
 * repository because [LibraryViewModel] only exposes flat media lists.
 */
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    userDataRepository: UserDataRepository
) : ViewModel() {

    val lastPlayedMediaId = userDataRepository.observeLastPlayedMediaId()

    fun albums(unknownLabel: String): Flow<List<AlbumSummary>> =
        mediaRepository.observeAlbums(unknownLabel)

    fun artists(unknownLabel: String): Flow<List<ArtistSummary>> =
        mediaRepository.observeArtists(unknownLabel)

    fun genres(unknownLabel: String): Flow<List<GenreSummary>> =
        mediaRepository.observeGenres(unknownLabel)
}

@Composable
fun MusicScreen(
    onOpenMedia: (MediaItem) -> Unit,
    contentPadding: PaddingValues,
    onSendMedia: (List<MediaItem>) -> Unit = {}
) {
    val viewModel: MusicViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val songs by libraryViewModel.audio.collectAsStateWithLifecycle()
    val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
    val lastPlayedMediaId by viewModel.lastPlayedMediaId.collectAsStateWithLifecycle(initialValue = null)

    val unknownAlbum = stringResource(R.string.music_unknown_album)
    val unknownArtist = stringResource(R.string.music_unknown_artist)

    val albums by remember(unknownAlbum) { viewModel.albums(unknownAlbum) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by remember(unknownArtist) { viewModel.artists(unknownArtist) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val genres by remember(unknownArtist) { viewModel.genres(unknownArtist) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var tabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var localSortMode by remember { mutableStateOf(MusicSortMode.DEFAULT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var drillDown by remember { mutableStateOf<MusicDrillDown?>(null) }
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }

    BackHandler(enabled = drillDown != null) {
        drillDown = null
    }

    // Sorting and filtering thousands of songs must not run during Compose recomposition.
    val sortedSongs by produceState(
        initialValue = emptyList<LibraryItem>(),
        songs, settings.sortMode, localSortMode
    ) {
        value = withContext(Dispatchers.Default) {
            when (localSortMode) {
                MusicSortMode.DEFAULT -> libraryViewModel.applySort(songs, settings.sortMode)
                MusicSortMode.NAME -> songs.sortedBy { it.media.displayTitle.lowercase() }
                MusicSortMode.NEWEST -> songs.sortedByDescending { it.media.addedAtMs }
                MusicSortMode.DURATION -> songs.sortedByDescending { it.media.durationMs }
                MusicSortMode.ARTIST -> songs.sortedWith(
                    compareBy<LibraryItem>(
                        { it.media.artist?.lowercase() ?: "\uffff" },
                        { it.media.displayTitle.lowercase() }
                    )
                )
            }
        }
    }

    val visibleSongs by produceState(
        initialValue = emptyList<LibraryItem>(),
        sortedSongs, searchQuery, favouritesOnly
    ) {
        value = withContext(Dispatchers.Default) {
            val q = searchQuery.trim()
            sortedSongs.filter { item ->
                val matchesFav = !favouritesOnly || item.isFavourite
                val matchesSearch = q.isBlank() ||
                    item.media.displayTitle.contains(q, ignoreCase = true) ||
                    item.media.artist.orEmpty().contains(q, ignoreCase = true) ||
                    item.media.album.orEmpty().contains(q, ignoreCase = true) ||
                    item.media.genre.orEmpty().contains(q, ignoreCase = true) ||
                    item.media.folderName.contains(q, ignoreCase = true)
                matchesFav && matchesSearch
            }
        }
    }

    val drillDownSongs by produceState(
        initialValue = emptyList<LibraryItem>(),
        sortedSongs, drillDown, unknownAlbum, unknownArtist
    ) {
        val current = drillDown
        value = if (current == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                when (current) {
                    is MusicDrillDown.Album -> sortedSongs.filter { item ->
                        val albumName = item.media.album?.takeIf { it.isNotBlank() } ?: unknownAlbum
                        val artistName = item.media.artist?.takeIf { it.isNotBlank() } ?: unknownArtist
                        if (current.summary.albumId != null && item.media.albumId != null) {
                            item.media.albumId == current.summary.albumId
                        } else {
                            albumName.equals(current.summary.name, ignoreCase = true) &&
                                artistName.equals(current.summary.artist, ignoreCase = true)
                        }
                    }
                    is MusicDrillDown.Artist -> sortedSongs.filter { item ->
                        val artistName = item.media.artist?.takeIf { it.isNotBlank() } ?: unknownArtist
                        artistName.equals(current.summary.name, ignoreCase = true)
                    }
                    is MusicDrillDown.Genre -> sortedSongs.filter { item ->
                        val genreName = item.media.genre?.takeIf { it.isNotBlank() } ?: unknownArtist
                        genreName.equals(current.summary.name, ignoreCase = true)
                    }
                }
            }
        }
    }

    val visibleAlbums by produceState(
        initialValue = emptyList<AlbumSummary>(), albums, searchQuery
    ) {
        value = withContext(Dispatchers.Default) {
            val q = searchQuery.trim()
            if (q.isBlank()) albums else albums.filter {
                it.name.contains(q, true) || it.artist.contains(q, true)
            }
        }
    }
    val visibleArtists by produceState(
        initialValue = emptyList<ArtistSummary>(), artists, searchQuery
    ) {
        value = withContext(Dispatchers.Default) {
            val q = searchQuery.trim()
            if (q.isBlank()) artists else artists.filter { it.name.contains(q, true) }
        }
    }
    val visibleGenres by produceState(
        initialValue = emptyList<GenreSummary>(), genres, searchQuery
    ) {
        value = withContext(Dispatchers.Default) {
            val q = searchQuery.trim()
            if (q.isBlank()) genres else genres.filter { it.name.contains(q, true) }
        }
    }

    val lastPlayedItem = remember(songs, lastPlayedMediaId) {
        lastPlayedMediaId?.let { id -> songs.firstOrNull { it.media.id == id } }
    }

    val selectedSongs = remember { mutableStateListOf<Uri>() }
    val songSelectionMode = selectedSongs.isNotEmpty()
    fun toggleSong(uri: Uri) {
        if (uri in selectedSongs) selectedSongs.remove(uri) else selectedSongs.add(uri)
    }

    val tabCounts = listOf(visibleSongs.size, visibleAlbums.size, visibleArtists.size, visibleGenres.size)
    val tabLabels = listOf(
        R.string.music_songs,
        R.string.music_albums,
        R.string.music_artists,
        R.string.music_genres
    )

    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.surfaceContainerHigh, colors.background, colors.surface)
                )
            )
            .padding(contentPadding)
    ) {
        val currentDrill = drillDown
        if (currentDrill != null) {
            DrillDownHeader(
                drillDown = currentDrill,
                tracks = drillDownSongs,
                onBack = { drillDown = null },
                onPlayAll = { drillDownSongs.firstOrNull()?.let { onOpenMedia(it.media) } },
                onShuffle = { drillDownSongs.randomOrNull()?.let { onOpenMedia(it.media) } }
            )
            if (songSelectionMode) {
                SelectionActionBar(
                    selectedCount = selectedSongs.size,
                    onSelectAll = {
                        drillDownSongs.forEach { if (it.media.uri !in selectedSongs) selectedSongs.add(it.media.uri) }
                    },
                    onClear = { selectedSongs.clear() },
                    onSend = {
                        onSendMedia(songs.filter { it.media.uri in selectedSongs }.map { it.media })
                    }
                )
                MediaBulkDeleteBar(
                    mediaItems = songs.filter { it.media.uri in selectedSongs }.map { it.media },
                    onDeleted = libraryViewModel::refresh,
                    onClearSelection = { selectedSongs.clear() }
                )
            }
            SongsTab(
                songs = drillDownSongs,
                unknownArtist = unknownArtist,
                lastPlayedMediaId = lastPlayedMediaId,
                onOpenMedia = onOpenMedia,
                selectionMode = songSelectionMode,
                selectedUris = selectedSongs.toSet(),
                onToggleSelection = ::toggleSong,
                onToggleFavourite = { libraryViewModel.toggleFavourite(it) },
                onAddToPlaylist = { playlistTarget = it },
                onDeleted = libraryViewModel::refresh
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.nav_music),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.music_summary_stats,
                            songs.size,
                            albums.size,
                            artists.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.primaryContainer.copy(alpha = 0.45f))
                        .border(
                            1.dp,
                            colors.primary.copy(alpha = 0.42f),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(stringResource(R.string.music_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.music_clear_search)
                            )
                        }
                    }
                }
            )

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = Color.Transparent,
                contentColor = colors.primary
            ) {
                tabLabels.forEachIndexed { index, labelRes ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(
                                text = "${stringResource(labelRes)} (${tabCounts[index]})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (tabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (tabIndex == 0 && songSelectionMode) {
                SelectionActionBar(
                    selectedCount = selectedSongs.size,
                    onSelectAll = {
                        visibleSongs.forEach { if (it.media.uri !in selectedSongs) selectedSongs.add(it.media.uri) }
                    },
                    onClear = { selectedSongs.clear() },
                    onSend = {
                        onSendMedia(songs.filter { it.media.uri in selectedSongs }.map { it.media })
                    }
                )
                MediaBulkDeleteBar(
                    mediaItems = songs.filter { it.media.uri in selectedSongs }.map { it.media },
                    onDeleted = libraryViewModel::refresh,
                    onClearSelection = { selectedSongs.clear() }
                )
            }

            when (tabIndex) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    if (lastPlayedItem != null && searchQuery.isBlank() && !favouritesOnly) {
                        ResumeListeningCard(
                            item = lastPlayedItem,
                            unknownArtist = unknownArtist,
                            onClick = { onOpenMedia(lastPlayedItem.media) }
                        )
                    }
                    if (songs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { visibleSongs.firstOrNull()?.let { onOpenMedia(it.media) } },
                                enabled = visibleSongs.isNotEmpty(),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = contrastingOnColor(colors.primary)
                                )
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.music_play_all), style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = { visibleSongs.randomOrNull()?.let { onOpenMedia(it.media) } },
                                enabled = visibleSongs.isNotEmpty(),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.music_shuffle), style = MaterialTheme.typography.labelMedium)
                            }
                            FilterChip(
                                selected = !favouritesOnly,
                                onClick = { favouritesOnly = false },
                                label = { Text(stringResource(R.string.music_filter_all)) }
                            )
                            FilterChip(
                                selected = favouritesOnly,
                                onClick = { favouritesOnly = !favouritesOnly },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (favouritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(stringResource(R.string.music_filter_favourites)) }
                            )
                            Box {
                                FilterChip(
                                    selected = localSortMode != MusicSortMode.DEFAULT,
                                    onClick = { showSortMenu = true },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    label = { Text(musicSortLabel(localSortMode)) }
                                )
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    MusicSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(musicSortLabel(mode)) },
                                            onClick = {
                                                localSortMode = mode
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    SongsTab(
                        songs = visibleSongs,
                        unknownArtist = unknownArtist,
                        lastPlayedMediaId = lastPlayedMediaId,
                        onOpenMedia = onOpenMedia,
                        selectionMode = songSelectionMode,
                        selectedUris = selectedSongs.toSet(),
                        onToggleSelection = ::toggleSong,
                        onToggleFavourite = { libraryViewModel.toggleFavourite(it) },
                        onAddToPlaylist = { playlistTarget = it },
                        onDeleted = libraryViewModel::refresh
                    )
                }

                1 -> AlbumsTab(
                    albums = visibleAlbums,
                    onOpenAlbum = { drillDown = MusicDrillDown.Album(it) }
                )

                2 -> ArtistsTab(
                    artists = visibleArtists,
                    onOpenArtist = { drillDown = MusicDrillDown.Artist(it) }
                )

                else -> GenresTab(
                    genres = visibleGenres,
                    onOpenGenre = { drillDown = MusicDrillDown.Genre(it) }
                )
            }
        }
    }

    playlistTarget?.let { media ->
        PlaylistPickerDialog(mediaId = media.id, onDismiss = { playlistTarget = null })
    }
}

@Composable
private fun musicSortLabel(mode: MusicSortMode): String = when (mode) {
    MusicSortMode.DEFAULT -> stringResource(R.string.music_sort_default)
    MusicSortMode.NAME -> stringResource(R.string.music_sort_name)
    MusicSortMode.NEWEST -> stringResource(R.string.music_sort_newest)
    MusicSortMode.DURATION -> stringResource(R.string.music_sort_duration)
    MusicSortMode.ARTIST -> stringResource(R.string.music_sort_artist)
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.primaryContainer.copy(alpha = 0.7f),
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
                modifier = Modifier.weight(1f),
                color = colors.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.music_select_all)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = onSend) { Text(stringResource(R.string.music_send_selected)) }
        }
    }
}

@Composable
private fun ResumeListeningCard(
    item: LibraryItem,
    unknownArtist: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(18.dp)
    ElevatedCard(
        onClick = onClick,
        shape = cardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = colors.primaryContainer.copy(alpha = 0.42f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .border(1.dp, colors.primary.copy(alpha = 0.35f), cardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Artwork(
                uri = item.media.artworkUri,
                mediaUri = item.media.uri,
                modifier = Modifier.size(50.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.music_resume_listening),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = item.media.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.media.artist?.takeIf { it.isNotBlank() } ?: unknownArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = CircleShape,
                color = colors.primary,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = contrastingOnColor(colors.primary),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrillDownHeader(
    drillDown: MusicDrillDown,
    tracks: List<LibraryItem>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.media.durationMs } }
    val title = when (drillDown) {
        is MusicDrillDown.Album -> drillDown.summary.name
        is MusicDrillDown.Artist -> drillDown.summary.name
        is MusicDrillDown.Genre -> drillDown.summary.name
    }
    val subtitle = when (drillDown) {
        is MusicDrillDown.Album -> "${drillDown.summary.artist} • ${formatDuration(totalDurationMs)}"
        is MusicDrillDown.Artist, is MusicDrillDown.Genre -> formatDuration(totalDurationMs)
    }

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = colors.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .border(1.dp, colors.primary.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = colors.primary
                    )
                }
                when (drillDown) {
                    is MusicDrillDown.Album -> Artwork(
                        uri = albumArtUri(drillDown.summary.albumId),
                        mediaUri = drillDown.summary.sampleUri,
                        modifier = Modifier.size(56.dp)
                    )
                    is MusicDrillDown.Artist -> Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.primaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
                        }
                    }
                    is MusicDrillDown.Genre -> Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.primaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${stringResource(R.string.music_track_count, tracks.size)} • $subtitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPlayAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = contrastingOnColor(colors.primary)
                        )
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.music_play_all), fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = onShuffle,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.music_shuffle), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- songs

@Composable
private fun SongsTab(
    songs: List<LibraryItem>,
    unknownArtist: String,
    lastPlayedMediaId: String?,
    onOpenMedia: (MediaItem) -> Unit,
    selectionMode: Boolean,
    selectedUris: Set<Uri>,
    onToggleSelection: (Uri) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onDeleted: () -> Unit
) {
    if (songs.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
            title = stringResource(R.string.lib_empty_title),
            body = stringResource(R.string.lib_empty_body)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
    ) {
        items(items = songs, key = { it.media.id }) { item ->
            SongRow(
                item = item,
                unknownArtist = unknownArtist,
                isLastPlayed = item.media.id == lastPlayedMediaId,
                onClick = { onOpenMedia(item.media) },
                selectionMode = selectionMode,
                isSelected = item.media.uri in selectedUris,
                onSelect = { onToggleSelection(item.media.uri) },
                onToggleFavourite = { onToggleFavourite(item.media.id) },
                onAddToPlaylist = { onAddToPlaylist(item.media) },
                onDeleted = onDeleted
            )
        }
    }
}

@Composable
private fun SongRow(
    item: LibraryItem,
    unknownArtist: String,
    isLastPlayed: Boolean,
    onClick: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDeleted: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(4.dp, shape, ambientColor = colors.primary.copy(alpha = 0.16f))
            .clip(shape)
            .background(
                when {
                    isSelected -> colors.primaryContainer.copy(alpha = 0.72f)
                    isLastPlayed -> colors.primaryContainer.copy(alpha = 0.25f)
                    else -> colors.surface
                }
            )
            .border(
                width = if (isSelected || isLastPlayed) 1.5.dp else 1.dp,
                color = if (isSelected || isLastPlayed) colors.primary.copy(alpha = 0.55f)
                else colors.outlineVariant.copy(alpha = 0.35f),
                shape = shape
            )
            .combinedClickable(
                onClick = { if (selectionMode) onSelect() else onClick() },
                onLongClick = onSelect
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(uri = item.media.artworkUri, mediaUri = item.media.uri, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.media.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isLastPlayed) colors.primary else colors.onSurface,
                fontWeight = if (isLastPlayed) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = item.media.artist?.takeIf { it.isNotBlank() }
                ?: item.media.album?.takeIf { it.isNotBlank() }
                ?: unknownArtist
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val distinctAlbum = item.media.album?.takeIf {
                it.isNotBlank() && !it.equals(subtitle, ignoreCase = true)
            }
            if (distinctAlbum != null) {
                Text(
                    text = distinctAlbum,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatDuration(item.media.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
        IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.PlaylistAdd,
                contentDescription = stringResource(R.string.music_add_to_playlist),
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        MediaDeleteButton(media = item.media, onDeleted = onDeleted)
        IconButton(onClick = onToggleFavourite, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (item.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (item.isFavourite) R.string.lib_remove_favourite else R.string.lib_add_favourite
                ),
                tint = if (item.isFavourite) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ albums

@Composable
private fun AlbumsTab(
    albums: List<AlbumSummary>,
    onOpenAlbum: (AlbumSummary) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Filled.Album, contentDescription = null) },
            title = stringResource(R.string.lib_empty_title),
            body = stringResource(R.string.lib_empty_body)
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = albums, key = { it.name + "/" + it.artist }) { album ->
            AlbumCard(album = album, onClick = { onOpenAlbum(album) })
        }
    }
}

@Composable
private fun AlbumCard(
    album: AlbumSummary,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = colors.primary.copy(alpha = 0.22f))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.45f),
                        colors.outlineVariant.copy(alpha = 0.35f),
                        colors.primaryContainer.copy(alpha = 0.35f)
                    )
                ),
                shape
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box {
                Artwork(
                    uri = albumArtUri(album.albumId),
                    mediaUri = album.sampleUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.primaryContainer.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.music_track_count, album.trackCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ----------------------------------------------------------------- artists

@Composable
private fun ArtistsTab(
    artists: List<ArtistSummary>,
    onOpenArtist: (ArtistSummary) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            title = stringResource(R.string.lib_empty_title),
            body = stringResource(R.string.lib_empty_body)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        items(items = artists, key = { it.name }) { artist ->
            ArtistGenreRow(
                name = artist.name,
                trackCount = artist.trackCount,
                icon = Icons.Filled.Person,
                onClick = { onOpenArtist(artist) }
            )
        }
    }
}

// ------------------------------------------------------------------ genres

@Composable
private fun GenresTab(
    genres: List<GenreSummary>,
    onOpenGenre: (GenreSummary) -> Unit
) {
    if (genres.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
            title = stringResource(R.string.lib_empty_title),
            body = stringResource(R.string.lib_empty_body)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        items(items = genres, key = { it.name }) { genre ->
            ArtistGenreRow(
                name = genre.name,
                trackCount = genre.trackCount,
                icon = Icons.Filled.GraphicEq,
                onClick = { onOpenGenre(genre) }
            )
        }
    }
}

@Composable
private fun ArtistGenreRow(
    name: String,
    trackCount: Int,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(colors.surfaceContainerHigh, colors.surface, colors.surfaceContainerLow)
                )
            )
            .border(
                1.dp,
                colors.outlineVariant.copy(alpha = 0.45f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.primaryContainer.copy(alpha = 0.65f))
                .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.music_track_count, trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = trackCount.toString(),
                color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ------------------------------------------------------------------ shared

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private val albumArtworkCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Keep aggregate album-art bitmaps bounded when Android reports memory pressure. */
internal fun trimMusicArtworkCache(level: Int) {
    when {
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
            albumArtworkCache.evictAll()
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE ->
            albumArtworkCache.trimToSize(albumArtworkCache.maxSize() / 4)
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
            albumArtworkCache.trimToSize(albumArtworkCache.maxSize() / 2)
    }
}

@Composable
private fun Artwork(uri: Uri?, modifier: Modifier = Modifier, mediaUri: Uri? = null) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = uri.toString() + "|" + mediaUri.toString()
    ) {
        value = null
        if (uri == null && mediaUri == null) return@produceState
        val cacheKey = (uri?.toString() ?: "no-album-id") + "|" + mediaUri.toString()
        value = withContext(Dispatchers.IO) {
            try {
                val cached = albumArtworkCache.get(cacheKey)
                val decoded = cached ?: loadMediaArtwork(
                    context = context,
                    artworkUri = uri,
                    mediaUri = mediaUri,
                    targetSize = 256
                )
                if (decoded != null) {
                    if (cached == null) albumArtworkCache.put(cacheKey, decoded)
                    decoded.asImageBitmap()
                } else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun albumArtUri(albumId: Long?): Uri? {
    val id = albumId ?: return null
    if (id <= 0L) return null
    return Uri.parse("content://media/external/audio/albumart")
        .buildUpon()
        .appendPath(id.toString())
        .build()
}
