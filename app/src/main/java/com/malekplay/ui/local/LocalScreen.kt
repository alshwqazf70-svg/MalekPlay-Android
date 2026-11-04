package com.malekplay.ui.local

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.util.MediaPermissions
import com.malekplay.domain.model.MediaItem
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.FolderRow
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.MediaListRow
import com.malekplay.ui.components.SectionHeader
import com.malekplay.ui.components.PlaylistPickerDialog
import com.malekplay.ui.library.LibraryViewModel

private enum class LocalTab { FOLDERS, VIDEOS, AUDIO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(
    onOpenMedia: (MediaItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(LocalTab.FOLDERS) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }

    val searchFlow = remember(query) { viewModel.search(query) }
    val searchResults by searchFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val sortedVideos = remember(videos, settings.sortMode) {
        viewModel.applySort(videos, settings.sortMode)
    }
    val sortedAudio = remember(audio, settings.sortMode) {
        viewModel.applySort(audio, settings.sortMode)
    }

    var hasPermission by remember { mutableStateOf(MediaPermissions.isGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permissions can be revoked or granted from Android Settings while the
    // app is backgrounded; refresh the UI and cache when the screen resumes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = MediaPermissions.isGranted(context)
                if (granted != hasPermission) {
                    hasPermission = granted
                    if (granted) viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = MediaPermissions.isGranted(context)
        if (granted != hasPermission) {
            hasPermission = granted
            if (granted) viewModel.refresh()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "File"
            onOpenMedia(
                MediaItem(
                    id = uri.toString(),
                    uri = uri,
                    title = name,
                    durationMs = 0L,
                    sizeBytes = 0L,
                    addedAtMs = System.currentTimeMillis(),
                    mimeType = mime.ifBlank { "video/*" },
                    isVideo = mime.startsWith("video"),
                    folderPath = "",
                    folderName = "",
                    artist = null,
                    album = null,
                    albumId = null,
                    artistId = null,
                    genre = null,
                    width = 0,
                    height = 0,
                    bitrate = 0L
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // ----------------------------------------------------- branded library header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lib_title_folders),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.home_library_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lib_refresh))
            }
            IconButton(onClick = {
                viewModel.setViewMode(
                    if (settings.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                )
            }) {
                Icon(
                    if (settings.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList,
                    contentDescription = stringResource(R.string.lib_view_grid)
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }

        LibraryHero(
            folderCount = folders.size,
            videoCount = folders.sumOf { it.videoCount },
            audioCount = folders.sumOf { it.audioCount }
        )

        // -------------------------------------------------------- search box
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(stringResource(R.string.lib_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
        )

        if (!hasPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.lib_grant_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.lib_grant_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(MediaPermissions.required()) }) {
                        Text(stringResource(R.string.lib_grant_action))
                    }
                }
            }
            return
        }

        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------- tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalTab.entries.forEach { choice ->
                val label = when (choice) {
                    LocalTab.FOLDERS -> stringResource(R.string.lib_title_folders)
                    LocalTab.VIDEOS -> stringResource(R.string.profile_videos)
                    LocalTab.AUDIO -> stringResource(R.string.profile_songs)
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .clickableLocal { tab = choice },
                    color = if (tab == choice) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (tab == choice) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------- sort selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sort_title))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(
                        SortMode.NEWEST to R.string.sort_newest,
                        SortMode.OLDEST to R.string.sort_oldest,
                        SortMode.NAME to R.string.sort_name,
                        SortMode.DURATION to R.string.sort_duration,
                        SortMode.SIZE to R.string.sort_size
                    ).forEach { (mode, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                viewModel.setSortMode(mode)
                                sortMenu = false
                            }
                        )
                    }
                }
            }
        }

        error?.let { message ->
            Text(
                message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (isRefreshing && folders.isEmpty() && videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        // --------------------------------------------------------- contents
        val searching = query.isNotBlank()
        if (searching) {
            MediaList(
                items = searchResults,
                viewMode = settings.viewMode,
                emptyTitle = stringResource(R.string.lib_no_results),
                emptyBody = stringResource(R.string.lib_no_results_body),
                showFolder = true,
                onPlay = onOpenMedia,
                onFavourite = { viewModel.toggleFavourite(it.media.id) },
                onAddToPlaylist = { playlistTarget = it.media }
            )
            return
        }

        when (tab) {
            LocalTab.FOLDERS -> {
                if (folders.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.lib_empty_title),
                        body = stringResource(R.string.lib_empty_body),
                        icon = Icons.Filled.VideoLibrary,
                        actions = {
                            Button(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                                Text(stringResource(R.string.lib_open_file))
                            }
                        }
                    )
                } else {
                    val sorted = viewModel.applyFolderSort(folders, settings.sortMode)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sorted, key = { it.path }) { folder ->
                            FolderRow(
                                folder = folder,
                                videoLabel = stringResource(R.string.lib_video_count),
                                audioLabel = stringResource(R.string.lib_audio_count),
                                fileLabel = stringResource(R.string.lib_file_count),
                                onClick = { onOpenFolder(folder.path) }
                            )
                        }
                    }
                }
            }
            LocalTab.VIDEOS -> MediaList(
                items = sortedVideos,
                viewMode = settings.viewMode,
                emptyTitle = stringResource(R.string.lib_empty_title),
                emptyBody = stringResource(R.string.lib_empty_body),
                onPlay = onOpenMedia,
                onFavourite = { viewModel.toggleFavourite(it.media.id) },
                onAddToPlaylist = { playlistTarget = it.media }
            )
            LocalTab.AUDIO -> MediaList(
                items = sortedAudio,
                viewMode = settings.viewMode,
                emptyTitle = stringResource(R.string.lib_empty_title),
                emptyBody = stringResource(R.string.lib_empty_body),
                onPlay = onOpenMedia,
                onFavourite = { viewModel.toggleFavourite(it.media.id) },
                onAddToPlaylist = { playlistTarget = it.media }
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


@Composable
private fun LibraryHero(
    folderCount: Int,
    videoCount: Int,
    audioCount: Int
) {
    val totalMedia = videoCount + audioCount
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                        MalekPlayColors.Amethyst.copy(alpha = 0.88f)
                    )
                )
            )
            .padding(horizontal = 18.dp, vertical = 17.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.home_hero_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.home_hero_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.84f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryMetric(totalMedia.toString(), stringResource(R.string.home_total_media), Modifier.weight(1f))
                LibraryMetric(folderCount.toString(), stringResource(R.string.lib_title_folders), Modifier.weight(1f))
                LibraryMetric(videoCount.toString(), stringResource(R.string.profile_videos), Modifier.weight(1f))
                LibraryMetric(audioCount.toString(), stringResource(R.string.profile_songs), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/** Small helper so the tab surfaces read cleanly. */
private fun Modifier.clickableLocal(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun MediaList(
    items: List<com.malekplay.domain.model.LibraryItem>,
    viewMode: ViewMode,
    emptyTitle: String,
    emptyBody: String,
    showFolder: Boolean = false,
    onPlay: (com.malekplay.domain.model.MediaItem) -> Unit,
    onFavourite: (com.malekplay.domain.model.LibraryItem) -> Unit,
    onAddToPlaylist: (com.malekplay.domain.model.LibraryItem) -> Unit
) {
    if (items.isEmpty()) {
        EmptyState(title = emptyTitle, body = emptyBody)
        return
    }
    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.media.id }) { item ->
                MediaGridCard(
                    item = item,
                    onPlay = { onPlay(item.media) },
                    onFavourite = { onFavourite(item) },
                    onAddToPlaylist = { onAddToPlaylist(item) }
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.media.id }) { item ->
                MediaListRow(
                    item = item,
                    showFolder = showFolder,
                    onPlay = { onPlay(item.media) },
                    onFavourite = { onFavourite(item) },
                    onAddToPlaylist = { onAddToPlaylist(item) }
                )
            }
        }
    }
}
