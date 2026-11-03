package com.malekplay.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.library.LibraryViewModel

private val Night = MalekPlayColors.Background
private val Panel = MalekPlayColors.Surface
private val PanelRaised = MalekPlayColors.SurfaceElevated
private val Neon = MalekPlayColors.NeonPurple
private val CardShape = RoundedCornerShape(24.dp)
private val GlassBrush = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.105f), Color.White.copy(alpha = 0.035f))
)
private val GlassBorder = Brush.linearGradient(
    colors = listOf(
        MalekPlayColors.PurpleBright.copy(alpha = 0.58f),
        Color.White.copy(alpha = 0.12f),
        MalekPlayColors.PurpleDeep.copy(alpha = 0.30f)
    )
)

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
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val featured = continueWatching.firstOrNull() ?: videos.firstOrNull()
    val featuredIsResume = continueWatching.isNotEmpty()

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
        onOpenMusic?.let { add(HomeAction(musicLabel, Icons.Filled.MusicNote, it)) }
        onOpenFavourites?.let { add(HomeAction(favouritesLabel, Icons.Filled.Favorite, it)) }
        onOpenPlaylists?.let { add(HomeAction(playlistsLabel, Icons.Filled.PlaylistPlay, it)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
    ) {
        // Ambient violet light gives depth without relying on a flat purple fill.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 104.dp, y = (-72).dp)
                .size(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Neon.copy(alpha = 0.20f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-120).dp, y = 100.dp)
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MalekPlayColors.PurpleDeep.copy(alpha = 0.13f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item(key = "header") {
                HomeHeader(
                    onOpenSettings = onOpenSettings,
                    onOpenLibrary = onOpenLibrary
                )
            }

            item(key = "hero") {
                HeroCard(
                    item = featured,
                    isResume = featuredIsResume,
                    videoCount = stats.videoCount,
                    audioCount = stats.audioCount,
                    onOpenMedia = onOpenMedia,
                    onOpenLibrary = onOpenLibrary
                )
            }

            item(key = "quick-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading(
                        title = stringResource(R.string.nav_local),
                        subtitle = stringResource(R.string.home_library_tagline),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    ActionRail(actions)
                }
            }

            if (continueWatching.isNotEmpty()) {
                item(key = "continue-heading") {
                    SectionHeading(
                        title = stringResource(R.string.home_continue_watching),
                        subtitle = stringResource(R.string.home_stat_videos, continueWatching.size),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
                item(key = "continue-rail") {
                    MediaRail(
                        items = continueWatching,
                        onOpenMedia = onOpenMedia,
                        showProgress = true,
                        portrait = false
                    )
                }
            }

            item(key = "recent-heading") {
                SectionHeading(
                    title = stringResource(R.string.home_recent_videos),
                    subtitle = stringResource(R.string.home_stat_videos, stats.videoCount),
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
            if (videos.isEmpty()) {
                item(key = "empty-library") { EmptyLibrary(onOpenLibrary) }
            } else {
                item(key = "recent-rail") {
                    MediaRail(
                        items = videos.take(12),
                        onOpenMedia = onOpenMedia,
                        showProgress = false,
                        portrait = true
                    )
                }
            }

            if (audio.isNotEmpty()) {
                item(key = "audio-heading") {
                    SectionHeading(
                        title = stringResource(R.string.nav_music),
                        subtitle = stringResource(R.string.home_stat_audio, stats.audioCount),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
                item(key = "audio-panel") {
                    AudioPanel(
                        count = stats.audioCount,
                        onClick = onOpenMusic ?: onOpenLibrary
                    )
                }
            }

            item(key = "footer") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(26.dp).height(2.dp).background(Neon.copy(alpha = 0.55f), CircleShape))
                    Text(
                        text = stringResource(R.string.home_library_tagline),
                        modifier = Modifier.padding(horizontal = 10.dp),
                        color = MalekPlayColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Box(Modifier.width(26.dp).height(2.dp).background(Neon.copy(alpha = 0.55f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = Neon.copy(alpha = 0.34f),
                    spotColor = Neon.copy(alpha = 0.20f)
                )
                .clip(CircleShape)
                .background(PanelRaised)
                .border(1.dp, GlassBorder, CircleShape)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_malekplay_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Malek",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Play",
                    color = Neon,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Text(
                text = stringResource(R.string.home_library_tagline),
                color = MalekPlayColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.nav_local),
            onClick = onOpenLibrary
        )
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.settings_title),
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(8.dp, CircleShape, ambientColor = Neon.copy(alpha = 0.12f))
            .clip(CircleShape)
            .background(GlassBrush)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MalekPlayColors.TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HeroCard(
    item: LibraryItem?,
    isResume: Boolean,
    videoCount: Int,
    audioCount: Int,
    onOpenMedia: (MediaItem) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(330.dp)
            .shadow(
                elevation = 26.dp,
                shape = shape,
                ambientColor = Neon.copy(alpha = 0.26f),
                spotColor = Neon.copy(alpha = 0.16f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF35134F), Color(0xFF191025), Night)
                )
            )
            .border(1.dp, GlassBorder, shape)
    ) {
        if (item != null) {
            MediaThumbnail(
                media = item.media,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_malekplay_logo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(190.dp),
                contentScale = ContentScale.Fit
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF50A0710),
                            Color(0xD90A0710),
                            Color(0x550A0710),
                            Color(0x180A0710)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x30000000),
                            Color(0x28000000),
                            Color(0xEB08070D)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .border(1.dp, Color.White.copy(alpha = 0.17f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(Neon, CircleShape)
            )
            Text(
                text = if (isResume) {
                    stringResource(R.string.home_continue_watching)
                } else {
                    stringResource(R.string.home_hero_title)
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 19.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = item?.media?.displayTitle ?: stringResource(R.string.home_hero_title),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isResume) {
                    stringResource(R.string.home_continue_watching)
                } else {
                    stringResource(R.string.home_hero_subtitle)
                },
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeonButton(
                    text = if (item != null) {
                        stringResource(R.string.home_continue_watching)
                    } else {
                        stringResource(R.string.home_open_library)
                    },
                    icon = Icons.Filled.PlayArrow,
                    onClick = {
                        if (item != null) onOpenMedia(item.media) else onOpenLibrary()
                    }
                )
                StatPill(
                    icon = Icons.Filled.Movie,
                    value = videoCount.toString()
                )
                StatPill(
                    icon = Icons.Filled.MusicNote,
                    value = audioCount.toString()
                )
            }

            if (item != null && isResume && item.fraction > 0f) {
                NeonProgress(
                    progress = item.fraction,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun NeonButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF7E22CE), Neon, Color(0xFFC084FC))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.26f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun StatPill(
    icon: ImageVector,
    value: String
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.36f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MalekPlayColors.PurpleSoft, modifier = Modifier.size(14.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = MalekPlayColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MalekPlayColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Neon, MalekPlayColors.PurpleDeep.copy(alpha = 0.2f))
                    )
                )
        )
    }
}

@Composable
private fun ActionRail(actions: List<HomeAction>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(actions, key = { it.title }) { action ->
            ActionTile(action = action)
        }
    }
}

@Composable
private fun ActionTile(action: HomeAction) {
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier = Modifier
            .width(102.dp)
            .height(108.dp)
            .shadow(10.dp, shape, ambientColor = Neon.copy(alpha = 0.10f))
            .clip(shape)
            .background(GlassBrush)
            .border(1.dp, GlassBorder, shape)
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 9.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Neon.copy(alpha = 0.32f), MalekPlayColors.PurpleDeep.copy(alpha = 0.12f))
                    )
                )
                .border(1.dp, Neon.copy(alpha = 0.42f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = MalekPlayColors.PurpleSoft,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = action.title,
            color = MalekPlayColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MediaRail(
    items: List<LibraryItem>,
    onOpenMedia: (MediaItem) -> Unit,
    showProgress: Boolean,
    portrait: Boolean
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.media.id }) { item ->
            MediaPosterCard(
                item = item,
                portrait = portrait,
                showProgress = showProgress,
                onClick = { onOpenMedia(item.media) }
            )
        }
    }
}

@Composable
private fun MediaPosterCard(
    item: LibraryItem,
    portrait: Boolean,
    showProgress: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier = Modifier
            .width(if (portrait) 148.dp else 224.dp)
            .clip(shape)
            .background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.075f), shape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Box {
            MediaThumbnail(
                media = item.media,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (portrait) 0.76f else 16f / 9f),
                cornerRadius = 0.dp,
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .shadow(8.dp, CircleShape, ambientColor = Neon.copy(alpha = 0.22f))
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.50f))
                    .border(1.dp, Color.White.copy(alpha = 0.27f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(25.dp)
                )
            }
            if (showProgress && item.fraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.White.copy(alpha = 0.24f))
                ) {
                    NeonProgress(item.fraction, Modifier.fillMaxSize())
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.media.displayTitle,
                color = MalekPlayColors.TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatDuration(item.media.durationMs),
                color = MalekPlayColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            if (showProgress && item.fraction > 0f) {
                Text(
                    text = stringResource(R.string.home_continue_watching),
                    color = MalekPlayColors.PurpleBright,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NeonProgress(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "media-progress"
    )
    Box(
        modifier = modifier
            .height(5.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .fillMaxHeight()
                .shadow(7.dp, CircleShape, ambientColor = Neon, spotColor = Neon)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(MalekPlayColors.PurpleDeep, Neon, MalekPlayColors.PurpleSoft)
                    )
                )
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenLibrary: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(GlassBrush)
            .border(1.dp, GlassBorder, shape)
            .clickable(role = Role.Button, onClick = onOpenLibrary)
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MalekPlayColors.PurpleDeep.copy(alpha = 0.24f))
                .border(1.dp, Neon.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MalekPlayColors.PurpleBright,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = stringResource(R.string.lib_empty_title),
            color = MalekPlayColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.lib_empty_body),
            color = MalekPlayColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.home_open_library),
            color = MalekPlayColors.PurpleBright,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AudioPanel(
    count: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(12.dp, shape, ambientColor = Neon.copy(alpha = 0.12f))
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF2A153A), Panel, MalekPlayColors.BackgroundElevated)
                )
            )
            .border(1.dp, Neon.copy(alpha = 0.27f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MalekPlayColors.PurpleDeep.copy(alpha = 0.28f))
                .border(1.dp, Neon.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = MalekPlayColors.PurpleSoft,
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 13.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(R.string.nav_music),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.home_stat_audio, count),
                color = MalekPlayColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(12.dp, 24.dp, 17.dp, 31.dp, 20.dp, 27.dp, 13.dp).forEachIndexed { index, barHeight ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight)
                        .clip(CircleShape)
                        .background(
                            if (index % 2 == 0) MalekPlayColors.PurpleBright
                            else Neon.copy(alpha = 0.64f)
                        )
                )
            }
        }
    }
}
