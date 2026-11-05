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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "MediaStoreScanner"

/** Literal column name; MediaColumns does not expose this constant on all API levels. */
private const val BUCKET_DISPLAY_NAME = "bucket_display_name"
private const val SCAN_BATCH_SIZE = 250

/** A scan explicitly records which media types were fully visible to the app. */
data class MediaScanResult(
    val videoIds: Set<String>,
    val audioIds: Set<String>,
    val videosComplete: Boolean,
    val audioComplete: Boolean
)

/**
 * Reads the device media library through MediaStore.
 *
 * Everything is read with a scoped query; there is no file-path walking, which
 * keeps the app compatible with scoped storage on Android 10+.
 */
class MediaStoreScanner(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /** Emits bounded batches so full media entities are not retained in memory. */
    suspend fun scan(
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): MediaScanResult = withContext(Dispatchers.IO) {
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

        // Partial visual access may update visible rows, but never authorizes deletion.
        val videoIds = if (videosComplete || videosPartial) scanVideos(onBatch) else emptySet()
        val audioIds = if (audioComplete) scanAudio(readGenreIndex(), onBatch) else emptySet()
        MediaScanResult(videoIds, audioIds, videosComplete, audioComplete)
    }

    // ------------------------------------------------------------------ video

    private suspend fun scanVideos(onBatch: suspend (List<MediaItemEntity>) -> Unit): Set<String> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.BUCKET_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.RESOLUTION)
        }.toTypedArray()
        return query(collection, projection, isVideo = true, onBatch = onBatch) { cursor, columns ->
            val rawResolution = columns.text(MediaStore.Video.Media.RESOLUTION)
            val (width, height) = parseResolution(rawResolution)
            val id = cursor.getLong(columns[MediaStore.MediaColumns._ID])
            val display = columns.text(MediaStore.MediaColumns.DISPLAY_NAME)
            val title = chooseMediaDisplayName(
                metadataTitle = columns.text(MediaStore.MediaColumns.TITLE),
                fileName = display
            )
            val durationMs = columns.long(MediaStore.MediaColumns.DURATION)
            val sizeBytes = columns.long(MediaStore.MediaColumns.SIZE)

            MediaItemEntity(
                mediaId = mediaId(collection, id),
                title = title,
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

    private suspend fun scanAudio(
        genres: Map<Long, String>,
        onBatch: suspend (List<MediaItemEntity>) -> Unit
    ): Set<String> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.BUCKET_ID)
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
        return query(collection, projection, isVideo = false, onBatch = onBatch) { cursor, columns ->
            val id = cursor.getLong(columns[MediaStore.MediaColumns._ID])
            val display = columns.text(MediaStore.MediaColumns.DISPLAY_NAME)
            // Some downloaders write ID3 text using a legacy/invalid charset.
            // Prefer a readable embedded tag, otherwise fall back to the filename.
            val title = chooseMediaDisplayName(
                metadataTitle = columns.text(MediaStore.Audio.Media.TITLE),
                fileName = display
            )
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
    private suspend fun readGenreIndex(): Map<Long, String> {
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
                    currentCoroutineContext().ensureActive()
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
                                currentCoroutineContext().ensureActive()
                                result[memberCursor.getLong(audioIdCol)] = genreName
                            }
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Genre members unavailable", it)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Genre index unavailable", it)
        }
        return result
    }

    // ----------------------------------------------------------------- shared

    private suspend fun <T> query(
        collection: android.net.Uri,
        projection: Array<String>,
        isVideo: Boolean,
        onBatch: suspend (List<MediaItemEntity>) -> Unit,
        row: (android.database.Cursor, ColumnIndex) -> MediaItemEntity
    ): Set<String> {
        // Membership checks only; insertion order is never consumed.
        val seenIds = HashSet<String>()
        val batch = ArrayList<MediaItemEntity>(SCAN_BATCH_SIZE)
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
            // Scan order is irrelevant: batches are upserted by stable media id.
            // Avoid asking MediaStore to sort the entire library on every refresh.
            null
        ) ?: throw IllegalStateException(
            "MediaStore returned a null cursor for ${if (isVideo) "video" else "audio"}"
        )

        cursor.use {
            val columns = ColumnIndex(cursor, projection)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                // Do not silently skip malformed rows: refresh must never treat a
                // partial scan as a complete library snapshot and delete valid rows.
                val item = row(cursor, columns)
                seenIds += item.mediaId
                batch += item
                if (batch.size >= SCAN_BATCH_SIZE) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return seenIds
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
            // Before Android 10, bucket names are not unique. Use the stable
            // bucket id for grouping while keeping the display name separate.
            val bucketId = text(MediaStore.MediaColumns.BUCKET_ID).trim()
            if (bucketId.isNotEmpty()) return "bucket:$bucketId"
            return text(BUCKET_DISPLAY_NAME).trim().ifEmpty { "/" }
        }

        fun folderName(): String {
            val relative = text(MediaStore.MediaColumns.RELATIVE_PATH).trim()
            if (relative.isNotEmpty()) {
                val name = relative.trimEnd('/').substringAfterLast('/')
                if (name.isNotEmpty()) return name
            }
            return text(BUCKET_DISPLAY_NAME).trim().ifEmpty { "/" }
        }
    }

    private fun mediaId(collection: android.net.Uri, id: Long): String =
        android.content.ContentUris.withAppendedId(collection, id).toString()

    private fun secondsToMillis(seconds: Long): Long = when {
        seconds <= 0L -> System.currentTimeMillis()
        seconds > Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
        else -> seconds * 1_000L
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        // MediaStore reports resolution as "1920x1080" (older) or "1920×1080".
        val parts = raw.split('x', '×', 'X').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size >= 2) parts[0] to parts[1] else 0 to 0
    }

    private fun estimateBitrate(sizeBytes: Long, durationMs: Long): Long {
        if (sizeBytes <= 0L || durationMs <= 0L) return 0L
        // Use floating-point intermediates to avoid overflow on very large media files.
        val bitsPerSecond = sizeBytes.toDouble() * 8_000.0 / durationMs.toDouble()
        return bitsPerSecond.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(Long.MAX_VALUE.toDouble())
            ?.toLong() ?: 0L
    }

    private fun String.nullIfBlank(): String? = takeIf { it.isNotBlank() }
}
