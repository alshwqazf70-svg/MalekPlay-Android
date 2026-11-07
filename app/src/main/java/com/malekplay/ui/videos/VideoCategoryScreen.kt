package com.malekplay.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
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
    onSendMedia: (MediaItem) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    var playlistTarget by remember { mutableStateOf<MediaItem?>(null) }
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
    val filtered = remember(classified, series) {
        classified.filter { (_, kind) ->
            if (series) kind == VideoKind.SERIES else kind != VideoKind.SERIES
        }.map { it.first }
    }

    Scaffold(
        containerColor = MalekPlayColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = if (series) "عالم الحلقات والمواسم" else "تجربتك السينمائية",
                            color = MalekPlayColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MalekPlayColors.PurpleBright
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (filtered.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.video_category_empty_title, title),
                body = stringResource(R.string.video_category_empty_body),
                icon = Icons.Filled.Movie,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(MalekPlayColors.BackgroundElevated, MalekPlayColors.Background, Color(0xFF08070C))))
                    .padding(padding)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF292039), Color(0xFF17131F), Color(0xFF111019))))
                        .border(1.dp, Brush.linearGradient(listOf(MalekPlayColors.PurpleBright.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f), MalekPlayColors.PurpleDeep.copy(alpha = 0.35f))), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Text(
                        text = if (series) "مكتبة المسلسلات" else "مكتبة الأفلام",
                        color = MalekPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.video_category_count, filtered.size),
                        modifier = Modifier.padding(top = 3.dp),
                        color = MalekPlayColors.PurpleBright,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
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
                    items(filtered, key = { it.media.id }) { item ->
                        MediaGridCard(
                            item = item,
                            onPlay = { onOpenMedia(item.media) },
                            onFavourite = { viewModel.toggleFavourite(item.media.id) },
                            onAddToPlaylist = { playlistTarget = item.media },
                            onDeleted = viewModel::refresh,
                            onSend = { onSendMedia(item.media) }
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
