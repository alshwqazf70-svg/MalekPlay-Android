package com.malekplay.domain.model

import android.net.Uri
import com.malekplay.core.ui.util.titleWithoutExtension
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.model.MediaWithState

/** UI-facing media model. Kept free of Room annotations. */
data class MediaItem(
    val id: String,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtMs: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val folderPath: String,
    val folderName: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val artistId: Long?,
    val genre: String?,
    val width: Int,
    val height: Int,
    val bitrate: Long
) {
    /**
     * Human-friendly title for library rows. MediaStore titles are often copied
     * from filenames, so normalize separators and whitespace without discarding
     * meaningful words, numbers, or non-Latin characters.
     */
    val displayTitle: String
        get() = titleWithoutExtension(title)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "بدون عنوان" }

    /** Embedded album art when the file is audio with a known album id. */
    val artworkUri: Uri?
        get() {
            val id = albumId ?: return null
            if (id <= 0L) return null
            return Uri.parse("content://media/external/audio/albumart").buildUpon()
                .appendPath(id.toString())
                .build()
        }

    val isAudio: Boolean get() = !isVideo
}

/** A [MediaItem] bundled with everything the library lists need to render. */
data class LibraryItem(
    val media: MediaItem,
    val positionMs: Long = 0L,
    val isCompleted: Boolean = false,
    val isFavourite: Boolean = false,
    val playbackSpeed: Float = 1f
) {
    val fraction: Float
        get() {
            val total = media.durationMs.takeIf { it > 0L } ?: return 0f
            return (positionMs.coerceIn(0L, total).toFloat() / total.toFloat())
        }

    val isInProgress: Boolean
        get() = !isCompleted && positionMs > 1_000L
}

/** One folder row: path, name and the counts shown on the browse screen. */
data class MediaFolder(
    val path: String,
    val name: String,
    val itemCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val totalBytes: Long,
    val lastAddedMs: Long,
    val newItemCount: Int,
    val sampleUri: Uri?
)

data class AlbumSummary(
    val name: String,
    val artist: String,
    val albumId: Long?,
    val trackCount: Int,
    val sampleUri: Uri?
)

data class ArtistSummary(
    val name: String,
    val artistId: Long?,
    val trackCount: Int,
    val sampleUri: Uri?
)

data class GenreSummary(
    val name: String,
    val trackCount: Int,
    val sampleUri: Uri?
)

data class LibraryStats(
    val videoCount: Int = 0,
    val audioCount: Int = 0,
    val totalBytes: Long = 0L,
    val watchedMs: Long = 0L
)

// --------------------------------------------------------------- mapping

fun MediaItemEntity.toDomain(): MediaItem = MediaItem(
    id = mediaId,
    uri = Uri.parse(uri),
    title = title,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    addedAtMs = addedAtMs,
    mimeType = mimeType,
    isVideo = isVideo,
    folderPath = folderPath,
    folderName = folderName,
    artist = artist,
    album = album,
    albumId = albumId,
    artistId = artistId,
    genre = genre,
    width = width,
    height = height,
    bitrate = bitrate
)

fun MediaWithState.toLibraryItem(favourites: Set<String>): LibraryItem = LibraryItem(
    media = media.toDomain(),
    positionMs = progress?.positionMs ?: 0L,
    isCompleted = progress?.isCompleted == true,
    isFavourite = media.mediaId in favourites,
    playbackSpeed = progress?.playbackSpeed ?: 1f
)
