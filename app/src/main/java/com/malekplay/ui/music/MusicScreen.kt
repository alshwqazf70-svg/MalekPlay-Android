package com.malekplay.ui.music

import android.graphics.Bitmap
import android.util.LruCache
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.data.repository.MediaRepository
import com.malekplay.domain.model.AlbumSummary
import com.malekplay.domain.model.ArtistSummary
import com.malekplay.domain.model.GenreSummary
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.components.MediaDeleteButton
import com.malekplay.ui.components.loadMediaArtwork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Albums / artists / genres are aggregates; they are read straight from the
 * repository because [LibraryViewModel] only exposes flat media lists.
 */
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

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
    contentPadding: PaddingValues
) {
    val viewModel: MusicViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val songs by libraryViewModel.audio.collectAsStateWithLifecycle()
    val settings by libraryViewModel.settings.collectAsStateWithLifecycle()

    val unknownAlbum = stringResource(R.string.music_unknown_album)
    val unknownArtist = stringResource(R.string.music_unknown_artist)

    val albums by remember(unknownAlbum) { viewModel.albums(unknownAlbum) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by remember(unknownArtist) { viewModel.artists(unknownArtist) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val genres by remember(unknownArtist) { viewModel.genres(unknownArtist) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val sortedSongs = remember(songs, settings.sortMode) {
        libraryViewModel.applySort(songs, settings.sortMode)
    }
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabLabels = listOf(
        R.string.music_songs,
        R.string.music_albums,
        R.string.music_artists,
        R.string.music_genres
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MalekPlayColors.BackgroundElevated,
                        MalekPlayColors.Background,
                        Color(0xFF07060C)
                    )
                )
            )
            .padding(contentPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nav_music),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MalekPlayColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.music_track_count, songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MalekPlayColors.TextMuted
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MalekPlayColors.PurpleDeep.copy(alpha = 0.30f))
                    .border(
                        1.dp,
                        MalekPlayColors.NeonPurple.copy(alpha = 0.42f),
                        RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleSoft,
                    modifier = Modifier.size(27.dp)
                )
            }
        }

        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = Color.Transparent,
            contentColor = MalekPlayColors.NeonPurple
        ) {
            tabLabels.forEachIndexed { index, labelRes ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        when (tabIndex) {
            0 -> SongsTab(
                songs = sortedSongs,
                unknownArtist = unknownArtist,
                onOpenMedia = onOpenMedia,
                onToggleFavourite = { libraryViewModel.toggleFavourite(it) },
                onDeleted = libraryViewModel::refresh
            )

            1 -> AlbumsTab(albums = albums)
            2 -> ArtistsTab(artists = artists)
            else -> GenresTab(genres = genres)
        }
    }
}

// ------------------------------------------------------------------- songs

@Composable
private fun SongsTab(
    songs: List<LibraryItem>,
    unknownArtist: String,
    onOpenMedia: (MediaItem) -> Unit,
    onToggleFavourite: (String) -> Unit,
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
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = songs, key = { it.media.id }) { item ->
            SongRow(
                item = item,
                unknownArtist = unknownArtist,
                onClick = { onOpenMedia(item.media) },
                onToggleFavourite = { onToggleFavourite(item.media.id) },
                onDeleted = onDeleted
            )
        }
    }
}

@Composable
private fun SongRow(
    item: LibraryItem,
    unknownArtist: String,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDeleted: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(7.dp, RoundedCornerShape(16.dp), ambientColor = MalekPlayColors.NeonGlow)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF21192D), Color(0xFF15121C), Color(0xFF111019))))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        MalekPlayColors.PurpleBright.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.07f),
                        MalekPlayColors.PurpleDeep.copy(alpha = 0.30f)
                    )
                ),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(uri = item.media.artworkUri, mediaUri = item.media.uri, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.media.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MalekPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = item.media.artist?.takeIf { it.isNotBlank() }
                ?: item.media.album?.takeIf { it.isNotBlank() }
                ?: unknownArtist
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MalekPlayColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.media.album?.takeIf { it.isNotBlank() } ?: formatDuration(item.media.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MalekPlayColors.PurpleBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(item.media.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MalekPlayColors.TextSecondary
        )
        MediaDeleteButton(media = item.media, onDeleted = onDeleted)
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

// ------------------------------------------------------------------ albums

@Composable
private fun AlbumsTab(albums: List<AlbumSummary>) {
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
            AlbumCard(album = album)
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumSummary) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = MalekPlayColors.NeonGlow)
            .clip(shape)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        MalekPlayColors.PurpleBright.copy(alpha = 0.48f),
                        Color.White.copy(alpha = 0.08f),
                        MalekPlayColors.PurpleDeep.copy(alpha = 0.34f)
                    )
                ),
                shape
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF17131F)
        )
    ) {
        Column(modifier = Modifier.padding(11.dp)) {
            Box {
                Artwork(
                    uri = albumArtUri(album.albumId),
                    mediaUri = album.sampleUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xD9000000))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = stringResource(R.string.music_track_count, album.trackCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MalekPlayColors.PurpleSoft,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                color = MalekPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MalekPlayColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ----------------------------------------------------------------- artists

@Composable
private fun ArtistsTab(artists: List<ArtistSummary>) {
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
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = artists, key = { it.name }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleSoft,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.music_track_count, artist.trackCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ genres

@Composable
private fun GenresTab(genres: List<GenreSummary>) {
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
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = genres, key = { it.name }) { genre ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.music_track_count, genre.trackCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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

/**
 * Album art is decoded off the main thread; any failure falls back to a
 * tinted placeholder so a missing thumbnail never breaks the list.
 */
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
                } else {
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    val shape = RoundedCornerShape(8.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
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

/** Mirror of [MediaItem.artworkUri] for aggregate rows that only carry an id. */
private fun albumArtUri(albumId: Long?): Uri? {
    val id = albumId ?: return null
    if (id <= 0L) return null
    return Uri.parse("content://media/external/audio/albumart")
        .buildUpon()
        .appendPath(id.toString())
        .build()
}
