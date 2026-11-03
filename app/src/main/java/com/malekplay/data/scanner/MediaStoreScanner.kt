package com.malekplay.data.scanner

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.malekplay.data.local.entity.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaStoreScanner"

/** Literal column name; MediaColumns does not expose this constant on all API levels. */
private const val BUCKET_DISPLAY_NAME = "bucket_display_name"

/** A scan explicitly records which media types were fully visible to the app. */
data class MediaScanResult(
    val items: List<MediaItemEntity>,
    val videosComplete: Boolean,
    val audioComplete: Boolean
) {
    val videoItems: List<MediaItemEntity> get() = items.filter { it.isVideo }
    val audioItems: List<MediaItemEntity> get() = items.filterNot { it.isVideo }
}

/**
 * Reads the device media library through MediaStore.
 *
 * Everything is read with a scoped query; there is no file-path walking, which
 * keeps the app compatible with scoped storage on Android 10+.
 */
class MediaStoreScanner(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    suspend fun scan(): MediaScanResult = withContext(Dispatchers.IO) {
        val videoPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val videosComplete = ContextCompat.checkSelfPermission(appContext, videoPermission) ==
            PackageManager.PERMISSION_GRANTED
        val audioComplete = ContextCompat.checkSelfPermission(appContext, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        val videosPartial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

        // A partial visual grant permits reading selected videos, but never
        // authorizes deleting rows that are simply outside the selected set.
        val videos = if (videosComplete || videosPartial) scanVideos() else emptyList()
        val audio = if (audioComplete) scanAudio(readGenreIndex()) else emptyList()

        MediaScanResult(
            items = videos + audio,
            videosComplete = videosComplete,
            audioComplete = audioComplete
        )
    }

    // ------------------------------------------------------------------ video

    private fun scanVideos(): List<MediaItemEntity> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.RESOLUTION)
        }.toTypedArray()
        return query(collection, projection, isVideo = true) { cursor, columns ->
            val rawResolution = columns.text(MediaStore.Video.Media.RESOLUTION)
            val (width, height) = parseResolution(rawResolution)
            val id = cursor.getLong(columns[MediaStore.MediaColumns._ID])
            val display = columns.text(MediaStore.MediaColumns.DISPLAY_NAME).ifBlank { "Untitled" }
            val durationMs = columns.long(MediaStore.MediaColumns.DURATION)
            val sizeBytes = columns.long(MediaStore.MediaColumns.SIZE)

            MediaItemEntity(
                mediaId = mediaId(collection, id),
                title = display,
                uri = mediaId(collection, id),
                mimeType = columns.text(MediaStore.MediaColumns.MIME_TYPE).ifBlank { "video/*" },
                sizeBytes = sizeBytes,
                durationMs = durationMs,
                addedAtMs = secondsToMillis(columns.long(MediaStore.MediaColumns.DATE_ADDED)),
                isVideo = true,
                folderPath = columns.folderPath(),
                folderName = columns.folderName(),
                artist = null,
                album = null,
                albumId = null,
                artistId = null,
                genre = null,
                width = width,
                height = height,
                bitrate = estimateBitrate(sizeBytes, durationMs)
            )
        }
    }

    // ------------------------------------------------------------------ audio

    private fun scanAudio(genres: Map<Long, String>): List<MediaItemEntity> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.ARTIST_ID)
        }.toTypedArray()
        return query(collection, projection, isVideo = false) { cursor, columns ->
            val id = cursor.getLong(columns[MediaStore.MediaColumns._ID])
            val display = columns.text(MediaStore.MediaColumns.DISPLAY_NAME).ifBlank { "Untitled" }
            // Prefer the embedded title tag; fall back to the file name.
            val title = columns.text(MediaStore.Audio.Media.TITLE)
                .takeIf { it.isNotBlank() } ?: display
            val durationMs = columns.long(MediaStore.MediaColumns.DURATION)
            val sizeBytes = columns.long(MediaStore.MediaColumns.SIZE)

            MediaItemEntity(
                mediaId = mediaId(collection, id),
                title = title,
                uri = mediaId(collection, id),
                mimeType = columns.text(MediaStore.MediaColumns.MIME_TYPE).ifBlank { "audio/*" },
                sizeBytes = sizeBytes,
                durationMs = durationMs,
                addedAtMs = secondsToMillis(columns.long(MediaStore.MediaColumns.DATE_ADDED)),
                isVideo = false,
                folderPath = columns.folderPath(),
                folderName = columns.folderName(),
                artist = columns.text(MediaStore.Audio.Media.ARTIST).nullIfBlank(),
                album = columns.text(MediaStore.Audio.Media.ALBUM).nullIfBlank(),
                albumId = columns.longOrNull(MediaStore.Audio.Media.ALBUM_ID),
                artistId = columns.longOrNull(MediaStore.Audio.Media.ARTIST_ID),
                genre = genres[id],
                width = 0,
                height = 0,
                bitrate = estimateBitrate(sizeBytes, durationMs)
            )
        }
    }

    /** Audio ids to genre name. Genre lives in a separate table, so it is indexed first. */
    private fun readGenreIndex(): Map<Long, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val result = HashMap<Long, String>()
        val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        val genreProjection = arrayOf(
            MediaStore.Audio.Genres._ID,
            MediaStore.Audio.Genres.NAME
        )
        runCatching {
            resolver.query(genresUri, genreProjection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (cursor.moveToNext()) {
                    val genreId = cursor.getLong(idCol)
                    val genreName = cursor.getString(nameCol) ?: continue
                    val membersUri =
                        MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    runCatching {
                        resolver.query(
                            membersUri,
                            arrayOf(MediaStore.Audio.Media._ID),
                            null,
                            null,
                            null
                        )?.use { memberCursor ->
                            val audioIdCol = memberCursor.getColumnIndex(MediaStore.Audio.Media._ID)
                            if (audioIdCol < 0) return@use
                            while (memberCursor.moveToNext()) {
                                result[memberCursor.getLong(audioIdCol)] = genreName
                            }
                        }
                    }.onFailure { Log.w(TAG, "Genre members unavailable", it) }
                }
            }
        }.onFailure { Log.w(TAG, "Genre index unavailable", it) }
        return result
    }

    // ----------------------------------------------------------------- shared

    private fun <T> query(
        collection: android.net.Uri,
        projection: Array<String>,
        isVideo: Boolean,
        row: (android.database.Cursor, ColumnIndex) -> T
    ): List<T> {
        val out = mutableListOf<T>()
        // Match MediaStore's visible-library semantics: ignore zero-byte,
        // pending writes and trashed rows so incomplete files never enter the cache.
        val selection = buildList {
            add("${MediaStore.MediaColumns.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            }
        }.joinToString(" AND ")
        val cursor = resolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        ) ?: throw IllegalStateException(
            "MediaStore returned a null cursor for ${if (isVideo) "video" else "audio"}"
        )

        cursor.use {
            val columns = ColumnIndex(cursor, projection)
            while (cursor.moveToNext()) {
                // Do not silently skip malformed rows: refresh must never treat a
                // partial scan as a complete library snapshot and delete valid rows.
                out += row(cursor, columns)
            }
        }
        return out
    }

    /** Safe column lookup wrapper: columns vary by OEM and API level. */
    private class ColumnIndex(cursor: android.database.Cursor, projection: Array<String>) {
        private val map = HashMap<String, Int>(projection.size * 2)
        private val cursor = cursor

        init {
            projection.forEach { name ->
                val index = cursor.getColumnIndex(name)
                if (index >= 0) map[name] = index
            }
        }

        operator fun get(name: String): Int = map[name] ?: -1

        fun text(name: String): String {
            val index = this[name]
            return if (index < 0) "" else cursor.getString(index) ?: ""
        }

        fun long(name: String): Long {
            val index = this[name]
            return if (index < 0) 0L else cursor.getLong(index)
        }

        fun longOrNull(name: String): Long? {
            val index = this[name]
            return if (index < 0) null else cursor.getLong(index)
        }

        fun folderPath(): String {
            val relative = text(MediaStore.MediaColumns.RELATIVE_PATH).trim()
            if (relative.isNotEmpty()) return relative.trimEnd('/')
            return text(BUCKET_DISPLAY_NAME).trim().ifEmpty { "/" }
        }

        fun folderName(): String {
            val path = folderPath()
            val name = path.substringAfterLast('/')
            return name.ifEmpty { path.ifEmpty { "/" } }
        }
    }

    private fun mediaId(collection: android.net.Uri, id: Long): String =
        android.content.ContentUris.withAppendedId(collection, id).toString()

    private fun secondsToMillis(seconds: Long): Long =
        if (seconds > 0L) seconds * 1000L else System.currentTimeMillis()

    private fun parseResolution(raw: String): Pair<Int, Int> {
        // MediaStore reports resolution as "1920x1080" (older) or "1920×1080".
        val parts = raw.split('x', '×', 'X').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size >= 2) parts[0] to parts[1] else 0 to 0
    }

    private fun estimateBitrate(sizeBytes: Long, durationMs: Long): Long {
        if (sizeBytes <= 0L || durationMs <= 0L) return 0L
        return (sizeBytes * 8L * 1000L / durationMs)
    }

    private fun String.nullIfBlank(): String? = takeIf { it.isNotBlank() }
}
