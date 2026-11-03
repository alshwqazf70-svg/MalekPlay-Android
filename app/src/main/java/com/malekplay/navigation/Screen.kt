package com.malekplay.navigation

import android.net.Uri

/** Route definitions. Args are URL-encoded so Uri values survive the back stack. */
sealed class Screen(val route: String) {

    data object Local : Screen("local")
    data object Music : Screen("music")
    data object Network : Screen("network")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")\n    data object Favourites : Screen("favourites")\n    data object Playlists : Screen("playlists")

    data object VideoPlayer : Screen("video/{mediaId}") {
        fun create(mediaId: String) = "video/${Uri.encode(mediaId)}"
        const val ARG_MEDIA_ID = "mediaId"
    }

    data object AudioPlayer : Screen("audio/{mediaId}") {
        fun create(mediaId: String) = "audio/${Uri.encode(mediaId)}"
        const val ARG_MEDIA_ID = "mediaId"
    }

    data object Folder : Screen("folder/{path}") {
        fun create(path: String) = "folder/${Uri.encode(path)}"
        const val ARG_PATH = "path"
    }

    data object Playlist : Screen("playlist/{playlistId}") {
        fun create(playlistId: String) = "playlist/${Uri.encode(playlistId)}"
        const val ARG_PLAYLIST_ID = "playlistId"
    }
}

/** Bottom navigation tabs, in display order (RTL: the list is laid out right-to-left). */
enum class TopLevelDestination(
    val screen: Screen,
    val labelRes: String
) {
    LOCAL(Screen.Local, "nav_local"),
    MUSIC(Screen.Music, "nav_music"),
    NETWORK(Screen.Network, "nav_network"),
    PROFILE(Screen.Profile, "nav_profile")
}
