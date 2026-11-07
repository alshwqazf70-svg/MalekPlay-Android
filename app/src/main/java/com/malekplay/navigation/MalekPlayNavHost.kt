package com.malekplay.navigation

import androidx.media3.common.util.UnstableApi

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.malekplay.core.ui.util.stringResourceByName
import com.malekplay.domain.model.MediaItem
import com.malekplay.R
import com.malekplay.ui.home.HomeScreen
import com.malekplay.ui.videos.VideoCategoryScreen
import com.malekplay.ui.local.LocalScreen
import com.malekplay.ui.music.MusicScreen
import com.malekplay.ui.network.NetworkScreen
import com.malekplay.ui.network.UltraShareScreen
import com.malekplay.ui.player.audio.AudioPlayerScreen
import com.malekplay.ui.player.video.VideoPlayerScreen
import com.malekplay.ui.playlists.PlaylistScreen
import com.malekplay.ui.profile.ProfileScreen
import com.malekplay.ui.settings.SettingsScreen
import com.malekplay.ui.library.LocalFolderScreen
import com.malekplay.ui.favorites.FavouritesScreen
import com.malekplay.ui.playlists.PlaylistLibraryScreen

private const val ANIM_MS = 260

// Five primary destinations keep touch targets and labels comfortable on phones.
private val BOTTOM_DESTINATIONS = listOf(
    TopLevelDestination.HOME,
    TopLevelDestination.MOVIES,
    TopLevelDestination.SERIES,
    TopLevelDestination.MUSIC,
    TopLevelDestination.LOCAL,
    TopLevelDestination.ULTRA_SHARE
)

@UnstableApi
@Composable
fun MalekPlayNavHost(
    externalUri: Uri? = null,
    navController: NavHostController = rememberNavController()
) {
    var pendingTransferUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val openTransferForUris: (List<Uri>) -> Unit = { uris ->
        pendingTransferUris = uris.distinct()
        if (pendingTransferUris.isNotEmpty()) navController.navigate(Screen.UltraShare.route)
    }
    val openTransferForMedia: (MediaItem) -> Unit = { media ->
        openTransferForUris(listOf(media.uri))
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = BOTTOM_DESTINATIONS.any { it.screen.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    MalekPlayBottomBar(
                        navController = navController,
                        currentRoute = currentRoute
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = externalUri?.let { Screen.VideoPlayer.create(it.toString()) }
                ?: Screen.Home.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(tween(ANIM_MS)) + scaleIn(
                    initialScale = 0.985f,
                    animationSpec = tween(ANIM_MS)
                )
            },
            exitTransition = {
                fadeOut(tween(ANIM_MS)) + scaleOut(
                    targetScale = 0.985f,
                    animationSpec = tween(ANIM_MS)
                )
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onOpenMedia = { navController.openMedia(it) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    onOpenLibrary = { navController.navigate(Screen.Local.route) },
                    onOpenMovies = { navController.navigate(Screen.Movies.route) },
                    onOpenSeries = { navController.navigate(Screen.Series.route) },
                    onOpenMusic = { navController.navigate(Screen.Music.route) },
                    onOpenFavourites = { navController.navigate(Screen.Favourites.route) },
                    onOpenPlaylists = { navController.navigate(Screen.Playlists.route) },
                    onOpenNetwork = { navController.navigate(Screen.Network.route) },
                    onOpenUltraShare = { navController.navigate(Screen.UltraShare.route) },
                    contentPadding = padding
                )
            }

            composable(Screen.Movies.route) {
                VideoCategoryScreen(series = false, onBack = { navController.popBackStack() }, onOpenMedia = { navController.openMedia(it) }, onSendMedia = { media -> openTransferForUris(media.map { it.uri }) })
            }

            composable(Screen.Series.route) {
                VideoCategoryScreen(series = true, onBack = { navController.popBackStack() }, onOpenMedia = { navController.openMedia(it) }, onSendMedia = { media -> openTransferForUris(media.map { it.uri }) })
            }

            composable(Screen.Local.route) {
                LocalScreen(
                    onOpenMedia = { item -> navController.openMedia(item) },
                    onOpenFolder = { path -> navController.navigate(Screen.Folder.create(path)) },
                    onSendMedia = { media -> openTransferForUris(media.map { it.uri }) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    contentPadding = padding
                )
            }

            composable(Screen.Music.route) {
                MusicScreen(
                    onOpenMedia = { item -> navController.openMedia(item) },
                    contentPadding = padding,
                    onSendMedia = { media -> openTransferForUris(media.map { it.uri }) }
                )
            }

            composable(Screen.Network.route) {
                NetworkScreen(
                    onOpenMedia = navController::openNetworkMedia,
                    contentPadding = padding,
                    onOpenUltraShare = { navController.navigate(Screen.UltraShare.route) }
                )
            }

            composable(Screen.UltraShare.route) {
                UltraShareScreen(
                    contentPadding = padding,
                    onBack = { pendingTransferUris = emptyList(); navController.popBackStack() },
                    initialUris = pendingTransferUris
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onOpenMedia = { item -> navController.openMedia(item) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    contentPadding = padding
                )
            }

            composable(
                route = Screen.VideoPlayer.route,
                arguments = listOf(navArgument(Screen.VideoPlayer.ARG_MEDIA_ID) {
                    type = androidx.navigation.NavType.StringType
                }),
                enterTransition = {
                    slideInVertically(tween(ANIM_MS)) { it } + fadeIn(tween(ANIM_MS))
                },
                exitTransition = {
                    slideOutVertically(tween(ANIM_MS)) { it } + fadeOut(tween(ANIM_MS))
                }
            ) { entry ->
                val mediaId = entry.arguments?.getString(Screen.VideoPlayer.ARG_MEDIA_ID)
                    ?.let { Uri.decode(it) }.orEmpty()
                VideoPlayerScreen(
                    mediaId = mediaId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.AudioPlayer.route,
                arguments = listOf(navArgument(Screen.AudioPlayer.ARG_MEDIA_ID) {
                    type = androidx.navigation.NavType.StringType
                }),
                enterTransition = {
                    slideInVertically(tween(ANIM_MS)) { it } + fadeIn(tween(ANIM_MS))
                },
                exitTransition = {
                    slideOutVertically(tween(ANIM_MS)) { it } + fadeOut(tween(ANIM_MS))
                }
            ) { entry ->
                val mediaId = entry.arguments?.getString(Screen.AudioPlayer.ARG_MEDIA_ID)
                    ?.let { Uri.decode(it) }.orEmpty()
                AudioPlayerScreen(
                    mediaId = mediaId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Folder.route,
                arguments = listOf(navArgument(Screen.Folder.ARG_PATH) {
                    type = androidx.navigation.NavType.StringType
                })
            ) { entry ->
                val path = entry.arguments?.getString(Screen.Folder.ARG_PATH)
                    ?.let { Uri.decode(it) }.orEmpty()
                LocalFolderScreen(
                    folderPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { item -> navController.openMedia(item) },
                    contentPadding = padding,
                    onSendMedia = { selectedMedia: List<MediaItem> ->
                        openTransferForUris(selectedMedia.map { selectedItem: MediaItem -> selectedItem.uri })
                    }
                )
            }

            composable(
                route = Screen.Playlist.route,
                arguments = listOf(navArgument(Screen.Playlist.ARG_PLAYLIST_ID) {
                    type = androidx.navigation.NavType.StringType
                })
            ) { entry ->
                val id = entry.arguments?.getString(Screen.Playlist.ARG_PLAYLIST_ID)
                    ?.let { Uri.decode(it) }.orEmpty()
                PlaylistScreen(
                    playlistId = id,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { item -> navController.openMedia(item) },
                    contentPadding = padding
                )
            }

            composable(Screen.Favourites.route) {
                FavouritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { item -> navController.openMedia(item) }
                )
            }

            composable(Screen.Playlists.route) {
                PlaylistLibraryScreen(
                    onOpenPlaylist = { id -> navController.navigate(Screen.Playlist.create(id)) }
                )
            }

            composable(
                route = Screen.Settings.route,
                enterTransition = { slideIntoSlideUp() },
                exitTransition = { slideOutOfSlideDown() }
            ) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Switch primary tabs from one stable root.
 *
 * Always normalize the stack to Home before opening the requested tab. This
 * avoids relying on a possibly stale back-stack lookup while a destination
 * transition is still settling, and launchSingleTop makes rapid repeated taps
 * idempotent.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    val route = destination.screen.route
    if (currentDestination?.route == route) return

    navigate(route) {
        popUpTo(Screen.Home.route) {
            inclusive = false
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}

/** Routes network media by its remote file extension instead of opening every item as video. */
private fun NavHostController.openNetworkMedia(rawUri: String) {
    val extension = Uri.parse(rawUri).lastPathSegment
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()
    val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus",
        "wma", "aiff", "aif", "alac", "amr", "mid", "midi", "3ga"
    )
    navigate(
        if (extension in audioExtensions) Screen.AudioPlayer.create(rawUri)
        else Screen.VideoPlayer.create(rawUri)
    )
}

/** Routes audio to the audio player and video to the video player. */
private fun NavHostController.openMedia(item: MediaItem) {
    val route = if (item.isVideo) {
        Screen.VideoPlayer.create(item.id)
    } else {
        Screen.AudioPlayer.create(item.id)
    }
    navigate(route)
}

private fun AnimatedContentTransitionScope<*>.slideIntoSlideUp() =
    slideInVertically(tween(ANIM_MS)) { it } + fadeIn(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<*>.slideOutOfSlideDown() =
    slideOutVertically(tween(ANIM_MS)) { it } + fadeOut(tween(ANIM_MS))

@Composable
private fun MalekPlayBottomBar(
    navController: NavHostController,
    currentRoute: String?
) {
    val barShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val barBorder = Brush.horizontalGradient(
        listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
            Color.White.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f),
            Color.Transparent
        )
    )
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.99f),
                        MaterialTheme.colorScheme.background
                    )
                ),
                barShape
            )
            .border(BorderStroke(1.dp, barBorder), barShape),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        BOTTOM_DESTINATIONS.forEach { destination ->
            val selected = currentRoute == destination.screen.route
            val label = stringResourceByName(destination.labelRes)
            val iconScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "bottom-nav-icon-scale"
            )
            NavigationBarItem(
                modifier = Modifier.testTag("bottom_nav_${destination.name.lowercase()}"),
                selected = selected,
                onClick = {
                    navController.navigateToTopLevel(destination)
                },
                icon = {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = label,
                        modifier = Modifier
                            .size(23.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                )
            )
        }
    }
}

@Composable
private fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.HOME -> Icons.Filled.Home
    TopLevelDestination.MOVIES -> Icons.Filled.Movie
    TopLevelDestination.SERIES -> Icons.Filled.Tv
    TopLevelDestination.MUSIC -> Icons.Filled.LibraryMusic
    TopLevelDestination.LOCAL -> Icons.Filled.Folder
    TopLevelDestination.FAVOURITES -> Icons.Filled.Favorite
    TopLevelDestination.PLAYLISTS -> Icons.Filled.QueueMusic
    TopLevelDestination.ULTRA_SHARE -> Icons.Filled.SwapHoriz
}
