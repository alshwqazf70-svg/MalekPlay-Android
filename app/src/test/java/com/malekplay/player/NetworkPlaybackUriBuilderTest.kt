package com.malekplay.player

import android.net.Uri
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkPlaybackUriBuilderTest {

    private fun profile(protocol: NetworkProtocol, rootPath: String) = NetworkProfile(
        id = "net_test_123",
        protocol = protocol,
        host = "192.0.2.10",
        port = if (protocol == NetworkProtocol.SMB) 445 else 22,
        username = "media-user",
        rootPath = rootPath,
        displayName = "Media server"
    )

    @Test
    fun smbUriContainsProfileAndSharePathButNoCredentialsOrHost() {
        val uri = NetworkPlaybackUriBuilder.build(
            profile(NetworkProtocol.SMB, "media/videos"),
            NetworkEntry("clip one.mp4", "media/videos/clip one.mp4", false)
        )

        assertEquals("maleksmb", uri.scheme)
        assertEquals("net_test_123", uri.authority)
        assertEquals(listOf("media", "videos", "clip one.mp4"), uri.pathSegments)
        assertFalse(uri.toString().contains("media-user"))
        assertFalse(uri.toString().contains("192.0.2.10"))
        assertEquals(null, uri.query)
        assertEquals(null, uri.userInfo)
    }

    @Test
    fun sftpUriPreservesNestedAbsolutePath() {
        val uri = NetworkPlaybackUriBuilder.build(
            profile(NetworkProtocol.SFTP, "/srv/media"),
            NetworkEntry("movie.mkv", "/srv/media/films/movie.mkv", false)
        )

        assertEquals("maleksftp", uri.scheme)
        assertEquals(listOf("srv", "media", "films", "movie.mkv"), uri.pathSegments)
    }

    @Test
    fun rejectsDirectoriesAndTraversalSegments() {
        val smb = profile(NetworkProtocol.SMB, "media")
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(smb, NetworkEntry("folder", "media/folder", true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(smb, NetworkEntry("escape.mp4", "media/../escape.mp4", false))
        }
    }

    @Test
    fun rejectsWrongShareAndUnsupportedProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.SMB, "media"),
                NetworkEntry("clip.mp4", "other/clip.mp4", false)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.WEBDAV, "/"),
                NetworkEntry("clip.mp4", "/clip.mp4", false)
            )
        }
    }

    @Test
    fun encodesReservedCharactersAsSinglePathSegments() {
        val uri = NetworkPlaybackUriBuilder.build(
            profile(NetworkProtocol.SFTP, "/"),
            NetworkEntry("clip #1.mp4", "/media/clip #1.mp4", false)
        )
        assertEquals(listOf("media", "clip #1.mp4"), uri.pathSegments)
        assertFalse(uri.toString().contains("#1.mp4"))
    }
}
