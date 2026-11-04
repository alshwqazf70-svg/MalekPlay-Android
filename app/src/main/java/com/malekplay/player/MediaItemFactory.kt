package com.malekplay.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import com.malekplay.core.ui.util.titleWithoutExtension
import com.malekplay.domain.model.MediaItem as DomainMedia

/**
 * Translates the app's Room-backed model into the Media3 representation the
 * player consumes.
 *
 * Kept in one place so the audio and video players, and the "play folder"
 * action, always build items with the same metadata (title, artwork, mime).
 */
@UnstableApi
fun DomainMedia.toMedia3Item(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMimeType(mimeType.takeIf { it.isNotBlank() })
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUri)
            .build()
    )
    .build()

/**
 * Builds a playable item for a Uri that is not (yet) in the library — for
 * example a file opened from another app or a network stream.
 */
@UnstableApi
fun mediaItemFromUri(uri: Uri, title: String? = null, isVideoHint: Boolean = true): MediaItem {
    val name = title ?: uri.lastPathSegment?.let { titleWithoutExtension(it) } ?: uri.toString()
    return MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMimeType(guessMimeType(uri, isVideoHint))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .build()
}

/** Best-effort MIME guess so the right renderer is picked for external files. */
@UnstableApi
@Suppress("UNUSED_PARAMETER")
fun guessMimeType(uri: Uri, isVideoHint: Boolean): String? {
    val path = uri.lastPathSegment.orEmpty()
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
        "mkv" -> MimeTypes.VIDEO_MATROSKA
        "webm" -> MimeTypes.VIDEO_WEBM
        "avi" -> MimeTypes.VIDEO_AVI
        "mpg", "mpeg", "m2v" -> MimeTypes.VIDEO_MPEG
        "ts", "m2ts", "mts" -> "video/mp2t"
        "3gp", "3g2" -> "video/3gpp"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "f4v" -> "video/mp4"
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a" -> MimeTypes.AUDIO_AAC
        "aac" -> MimeTypes.AUDIO_AAC
        "flac" -> MimeTypes.AUDIO_FLAC
        "ogg", "oga" -> MimeTypes.AUDIO_OGG
        "opus" -> MimeTypes.AUDIO_OPUS
        "wav" -> MimeTypes.AUDIO_WAV
        "amr" -> "audio/amr"
        "aiff", "aif" -> "audio/aiff"
        "mid", "midi" -> "audio/midi"
        "mka" -> "audio/x-matroska"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        else -> null
    }
}
