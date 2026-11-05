package com.malekplay.player

import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkPlaybackUriBuilderTest {
    @Test
    fun buildsSmbUriWithProfileIdAndEncodedPathSegments() {
        val uri = NetworkPlaybackUriBuilder.build(
            profile(NetworkProtocol.SMB),
            NetworkEntry("episode one.mp4", "media/Movies/episode one.mp4", false)
        )
        assertEquals("maleksmb", uri.scheme)
        assertEquals("net_test", uri.host)
        assertEquals(listOf("media", "Movies", "episode one.mp4"), uri.pathSegments)
    }

    @Test
    fun buildsSftpUriWithoutEmbeddingCredentialsOrHost() {
        val uri = NetworkPlaybackUriBuilder.build(
            profile(NetworkProtocol.SFTP),
            NetworkEntry("clip.mp4", "/library/clip.mp4", false)
        )
        assertEquals("maleksftp", uri.scheme)
        assertEquals("net_test", uri.host)
        assertEquals(listOf("library", "clip.mp4"), uri.pathSegments)
        assertEquals(null, uri.userInfo)
    }

    @Test
    fun rejectsDirectoriesWebDavAndTraversalSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.SMB),
                NetworkEntry("Movies", "media/Movies", true)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.WEBDAV),
                NetworkEntry("clip.mp4", "/clip.mp4", false)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.SFTP),
                NetworkEntry("clip.mp4", "/library/../private/clip.mp4", false)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkPlaybackUriBuilder.build(
                profile(NetworkProtocol.SMB),
                NetworkEntry("clip.mp4", "media", false)
            )
        }
    }

    private fun profile(protocol: NetworkProtocol) = NetworkProfile(
        id = "net_test",
        protocol = protocol,
        host = "nas.example.com",
        port = when (protocol) {
            NetworkProtocol.WEBDAV -> 443
            NetworkProtocol.SMB -> 445
            NetworkProtocol.SFTP -> 22
        },
        username = "test",
        rootPath = "/"
    )
}
