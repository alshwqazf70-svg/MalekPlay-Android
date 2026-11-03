package com.malekplay.ui.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaListRow
import com.malekplay.ui.components.PlaylistPickerDialog
import com.malekplay.ui.library.LibraryViewModel

@Composable
fun VideoCategoryScreen(series: Boolean, onBack: () -> Unit, onOpenMedia: (MediaItem) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
    val title = stringResource(if (series) R.string.nav_series else R.string.nav_movies)
    val filtered = videos.filter { item ->
        val text = listOf(item.media.displayTitle, item.media.folderName, item.media.album.orEmpty()).joinToString(" ").lowercase()
        val episodic = Regex("""(s\d{1,2}e\d{1,2}|\bseason\s*\d+|\bepisode\s*\d+|الحلقة|الموسم|مسلسل)""").containsMatchIn(text)
        if (series) episodic else !episodic
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
        })
    }) { padding ->
        if (filtered.isEmpty()) {
            EmptyState(title = stringResource(R.string.video_category_empty_title, title), body = stringResource(R.string.video_category_empty_body), icon = Icons.Filled.Movie, modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.media.id }) { item ->
                    MediaListRow(item, onPlay = { onOpenMedia(item.media) }, onFavourite = { viewModel.toggleFavourite(item.media.id) }, onAddToPlaylist = { playlistTarget = item.media })
                }
            }
        }
    }
    playlistTarget?.let { media ->
        PlaylistPickerDialog(mediaId = media.id, onDismiss = { playlistTarget = null })
    }
}
