package com.malekplay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.library.LibraryViewModel

private val Night = Color(0xFF0D0D12)
private val Panel = Color(0xFF17131F)
private val PanelRaised = Color(0xFF21182D)
private val Neon = Color(0xFFA855F7)
private val CardShape = RoundedCornerShape(20.dp)

private data class HomeAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(
    onOpenMedia: (MediaItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    contentPadding: PaddingValues,
    onOpenMovies: (() -> Unit)? = null,
    onOpenSeries: (() -> Unit)? = null,
    onOpenMusic: (() -> Unit)? = null,
    onOpenFavourites: (() -> Unit)? = null,
    onOpenPlaylists: (() -> Unit)? = null,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recent by viewModel.continueWatching.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val featured = recent.firstOrNull() ?: videos.firstOrNull()

    val moviesLabel = stringResource(R.string.nav_movies)
    val seriesLabel = stringResource(R.string.nav_series)
    val libraryLabel = stringResource(R.string.nav_local)
    val musicLabel = stringResource(R.string.nav_music)
    val favouritesLabel = stringResource(R.string.nav_favourites)
    val playlistsLabel = stringResource(R.string.nav_playlists)
    val actions = buildList {
        onOpenMovies?.let { add(HomeAction(moviesLabel, Icons.Filled.Movie, it)) }
        onOpenSeries?.let { add(HomeAction(seriesLabel, Icons.Filled.Tv, it)) }
        add(HomeAction(libraryLabel, Icons.Filled.Folder, onOpenLibrary))
        onOpenMusic?.let { add(HomeAction(musicLabel, Icons.Filled.QueueMusic, it)) }
        onOpenFavourites?.let { add(HomeAction(favouritesLabel, Icons.Filled.Favorite, it)) }
        onOpenPlaylists?.let { add(HomeAction(playlistsLabel, Icons.Filled.PlaylistPlay, it)) }
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        Box(
            Modifier.align(Alignment.TopEnd).size(340.dp).background(
                Brush.radialGradient(listOf(Neon.copy(alpha = 0.14f), Color.Transparent))
            )
        )
        Column(
            Modifier.fillMaxSize().padding(contentPadding)
                .verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Header(onOpenSettings, onOpenLibrary)

            HeroCard(
                item = featured,
                videoCount = stats.videoCount,
                audioCount = stats.audioCount,
                onOpenMedia = onOpenMedia,
                onOpenLibrary = onOpenLibrary
            )

            SectionHeading(
                stringResource(R.string.app_name),
                stringResource(R.string.app_tagline),
                Modifier.padding(horizontal = 18.dp)
            )
            ActionRail(actions)

            if (recent.isNotEmpty()) {
                SectionHeading(
                    stringResource(R.string.home_continue_watching),
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
                MediaRail(recent, onOpenMedia, showProgress = true)
            }

            SectionHeading(
                stringResource(R.string.home_recent_videos),
                stringResource(R.string.home_stat_videos, stats.videoCount),
                Modifier.padding(horizontal = 18.dp)
            )
            if (videos.isEmpty()) {
                EmptyLibrary(onOpenLibrary)
            } else {
                MediaRail(videos.take(12), onOpenMedia, showProgress = false, portrait = true)
            }

            if (audio.isNotEmpty()) {
                SectionHeading(
                    stringResource(R.string.nav_music),
                    stringResource(R.string.home_stat_audio, stats.audioCount),
                    Modifier.padding(horizontal = 18.dp)
                )
                AudioPanel(stats.audioCount, onOpenMusic ?: onOpenLibrary)
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit, onLibrary: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).shadow(14.dp, CircleShape, ambientColor = Neon.copy(alpha = 0.35f))
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_malekplay_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("Malek", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                Text("Play", color = Neon, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.app_tagline), color = Color(0xFFB9B1C8), style = MaterialTheme.typography.bodySmall)
        }
        HeaderButton(Icons.Filled.Folder, stringResource(R.string.nav_local), onLibrary)
        Spacer(Modifier.width(8.dp))
        HeaderButton(Icons.Filled.Settings, stringResource(R.string.settings_title), onSettings)
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun HeroCard(
    item: LibraryItem?,
    videoCount: Int,
    audioCount: Int,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenLibrary: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(278.dp)
            .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = Neon.copy(alpha = 0.22f))
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF28103E), Color(0xFF130D20), Night)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Neon.copy(alpha = 0.9f), Color(0xFF5C2A83), Color.White.copy(alpha = 0.1f))),
                RoundedCornerShape(24.dp)
            )
    ) {
        if (item != null) {
            MediaThumbnail(item.media, Modifier.fillMaxSize(), cornerRadius = 0.dp)
        } else {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_malekplay_logo),
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp).size(160.dp)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF5080710), Color(0xC9080710), Color(0x28080710)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x25000000), Color(0xF5000000)))
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(19.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Neon)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    if (item != null) stringResource(R.string.home_continue_watching) else "MALEKPLAY  •  CINEMATIC PLAYER",
                    color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                )
            }
            Text(
                item?.media?.displayTitle ?: stringResource(R.string.home_hero_title),
                color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item != null) stringResource(R.string.home_continue_watching) else stringResource(R.string.home_hero_subtitle),
                color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF9333EA), Neon, Color(0xFF7E22CE))))
                        .clickable { if (item != null) onOpenMedia(item.media) else onOpenLibrary() }
                        .padding(horizontal = 15.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(19.dp))
                        Text(
                            if (item != null) stringResource(R.string.home_continue_watching) else stringResource(R.string.home_open_library),
                            color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Text(stringResource(R.string.home_stat_videos, videoCount), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.home_stat_audio, audioCount), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            if (item != null && item.fraction > 0f) {
                NeonProgress(item.fraction, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Color(0xFF81788F), style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.width(38.dp).height(3.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Neon, Color.Transparent)))
        )
    }
}

@Composable
private fun ActionRail(actions: List<HomeAction>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEachIndexed { index, action ->
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.width(92.dp).height(94.dp).shadow(if (index == 0) 12.dp else 2.dp, shape)
                    .clip(shape)
                    .background(
                        if (index == 0) Brush.verticalGradient(listOf(Color(0xFF8B2CEB), Color(0xFF5B16B5)))
                        else Brush.verticalGradient(listOf(Color(0xFF21182D), Panel))
                    )
                    .border(1.dp, if (index == 0) Color(0xFFD8B4FE) else Color.White.copy(alpha = 0.06f), shape)
                    .clickable(onClick = action.onClick).padding(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(action.icon, contentDescription = action.title, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text(action.title, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MediaRail(
    items: List<LibraryItem>,
    onOpenMedia: (MediaItem) -> Unit,
    showProgress: Boolean,
    portrait: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { item ->
            Column(
                Modifier.width(if (portrait) 138.dp else 218.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Panel)
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                    .clickable { onOpenMedia(item.media) }
            ) {
                Box {
                    MediaThumbnail(
                        item.media,
                        Modifier.fillMaxWidth().aspectRatio(if (portrait) 0.78f else 16f / 9f),
                        cornerRadius = 0.dp
                    )
                    Box(
                        Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.58f))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        item.media.displayTitle, color = Color.White, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    if (showProgress && item.fraction > 0f) NeonProgress(item.fraction, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NeonProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize()
                .shadow(6.dp, CircleShape, ambientColor = Neon, spotColor = Neon)
                .background(Brush.horizontalGradient(listOf(Color(0xFF7E22CE), Neon, Color(0xFFD8B4FE))))
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenLibrary: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape)
            .background(Panel).border(1.dp, Color(0xFF332642), CardShape)
            .clickable(onClick = onOpenLibrary).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Folder, null, tint = Color(0xFFC084FC), modifier = Modifier.size(36.dp))
        Text(stringResource(R.string.lib_empty_title), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.lib_empty_body), color = Color(0xFFB9B1C8), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.home_open_library), color = Color(0xFFC084FC), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AudioPanel(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(17.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF241431), Panel)))
            .border(1.dp, Neon.copy(alpha = 0.22f), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.QueueMusic, null, tint = Color(0xFFC084FC), modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(stringResource(R.string.nav_music), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_stat_audio, count), color = Color(0xFFB9B1C8), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFFC084FC))
    }
}
