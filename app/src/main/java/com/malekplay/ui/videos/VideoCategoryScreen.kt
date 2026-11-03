package com.malekplay.ui.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
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
    val classified = videos.map { item ->
        item to VideoKindClassifier.classify(item.media.displayTitle, item.media.folderName, item.media.folderPath)
    }
    val unclassifiedCount = classified.count { it.second == VideoKind.UNCLASSIFIED }
    val filtered = classified.filter { (_, kind) ->
        if (series) kind == VideoKind.SERIES else kind != VideoKind.SERIES
    }.map { it.first }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
        })
    }) { padding ->
        if (filtered.isEmpty()) {
            EmptyState(title = stringResource(R.string.video_category_empty_title, title), body = stringResource(R.string.video_category_empty_body), icon = Icons.Filled.Movie, modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!series && unclassifiedCount > 0) {
                    item {
                        Text(
                            stringResource(R.string.video_category_unclassified_notice, unclassifiedCount),
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
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
