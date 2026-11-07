package com.malekplay.backup

import android.content.Context
import android.net.Uri
import com.malekplay.core.ui.theme.AppThemeMode
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.PlaylistRepository
import com.malekplay.data.repository.PlaylistRestoreEntry
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.GestureAction
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.io.InputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Validate the complete user-data payload before restore mutates any store.
 * Missing or malformed sections must never be interpreted as empty collections,
 * because that could silently erase the user's existing favourites and playlists.
 */
/**
 * Reads a document-provider stream with a hard byte limit and cooperative cancellation.
 * A zero-length bulk read is handled with a single-byte read to guarantee progress.
 */
internal suspend fun readBoundedBackupBytes(
    input: InputStream,
    maxBytes: Int
): ByteArray {
    require(maxBytes > 0) { "Backup size limit must be positive" }
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            total++
            require(total <= maxBytes) { "Backup file exceeds the size limit" }
            output.write(single)
            continue
        }
        total += count
        require(total <= maxBytes) { "Backup file exceeds the size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal data class ValidatedBackupCollections(
    val favourites: JSONArray,
    val playlists: JSONArray,
    val settings: JSONObject
)

internal fun validateBackupCollections(root: JSONObject): ValidatedBackupCollections {
    val favourites = root.optJSONArray("favourites")
        ?: throw IllegalArgumentException("Backup favourites section is missing or invalid")
    val playlists = root.optJSONArray("playlists")
        ?: throw IllegalArgumentException("Backup playlists section is missing or invalid")
    val settings = root.optJSONObject("settings")
        ?: throw IllegalArgumentException("Backup settings section is missing or invalid")

    require(favourites.length() <= 20_000) { "Too many favourites" }
    require(playlists.length() <= 500) { "Too many playlists" }

    for (i in 0 until favourites.length()) {
        val value = favourites.get(i)
        require(value is String && value.length in 1..4096) {
            "Favourite entry at index $i is invalid"
        }
    }

    val seenPlaylistIds = HashSet<String>()
    var totalItems = 0
    for (i in 0 until playlists.length()) {
        val item = playlists.get(i) as? JSONObject
            ?: throw IllegalArgumentException("Playlist entry at index $i is invalid")
        val name = item.opt("name") as? String
        require(!name.isNullOrBlank() && name.length <= 256) {
            "Playlist name at index $i is invalid"
        }
        if (item.has("id") && !item.isNull("id")) {
            val id = item.opt("id") as? String
            require(id != null && id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "Playlist id at index $i is invalid"
            }
            require(seenPlaylistIds.add(id)) { "Duplicate playlist identifier in backup" }
        }
        val mediaIds = item.optJSONArray("mediaIds")
            ?: throw IllegalArgumentException("Playlist media list at index $i is invalid")
        require(mediaIds.length() <= 5_000) { "Playlist at index $i has too many entries" }
        totalItems += mediaIds.length()
        require(totalItems <= 50_000) { "Too many playlist entries" }
        for (j in 0 until mediaIds.length()) {
            val mediaId = mediaIds.get(j)
            require(mediaId is String && mediaId.length in 1..4096) {
                "Playlist media entry at index $i, item $j is invalid"
            }
        }
    }
    return ValidatedBackupCollections(favourites, playlists, settings)
}


/**
 * Exports and restores the parts of the app that are pure user data.
 *
 * Media files and the cached MediaStore rows are deliberately excluded: they are
 * re-derived from the device, and copying the open SQLite/WAL pair is unsafe.
 */
@Singleton
class MalekPlayBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val userDataRepository: UserDataRepository
) {

    suspend fun exportTo(destination: Uri) = withContext(Dispatchers.IO) {
        val settings = settingsRepository.currentSettings()
        val favourites = userDataRepository.observeFavouriteIdsSnapshot()
        require(favourites.size <= MAX_FAVOURITES) { "Too many favourites to export" }
        val playlistSnapshot = playlistRepository.snapshotPlaylists()
        require(playlistSnapshot.size <= MAX_PLAYLISTS) { "Too many playlists to export" }
        val totalPlaylistItems = playlistSnapshot.sumOf { it.items.size }
        require(totalPlaylistItems <= MAX_TOTAL_PLAYLIST_ITEMS) {
            "Too many playlist entries to export"
        }

        val playlistsArray = JSONArray()
        for (playlist in playlistSnapshot) {
            playlistsArray.put(
                JSONObject()
                    .put("id", playlist.playlist.playlistId)
                    .put("name", playlist.playlist.name)
                    .put("mediaIds", JSONArray().apply {
                        playlist.items.sortedBy { it.position }
                            .forEach { put(it.mediaId) }
                    })
            )
        }

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", BACKUP_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("favourites", JSONArray().apply {
                favourites.sorted().forEach { put(it) }
            })
            .put("playlists", playlistsArray)
            .put("settings", settingsToJson(settings))

        val bytes = root.toString(2).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "Backup file exceeds the size limit" }

        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("Could not create the backup file")
        output.use { it.write(bytes) }
    }

    @Suppress("NestedBlockDepth")
    suspend fun restoreFrom(source: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not open the backup file")
        val bytes = input.use { stream ->
            readBoundedBackupBytes(stream, MAX_BACKUP_BYTES)
        }

        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.optString("format") == FORMAT) { "Not a MalekPlay backup file" }
        require(root.optInt("version", -1) == BACKUP_VERSION) { "Unsupported backup version" }

        val validated = validateBackupCollections(root)
        val favouritesArray = validated.favourites
        val playlistsArray = validated.playlists

        val favourites = buildSet {
            for (i in 0 until favouritesArray.length()) {
                val value = favouritesArray.optString(i)
                if (value.length in 1..MAX_URI_LENGTH) add(value)
            }
        }

        // Parse and validate every section before changing any existing user data.
        val restoredPlaylists = buildList {
            var totalItems = 0
            for (i in 0 until playlistsArray.length()) {
                val item = playlistsArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                val mediaArray = item.optJSONArray("mediaIds") ?: JSONArray()
                val playlistId = item.optString("id")
                    .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                val mediaIds = buildList {
                    for (j in 0 until mediaArray.length()) {
                        val value = mediaArray.optString(j)
                        if (value.length in 1..MAX_URI_LENGTH) add(value)
                    }
                }.distinct().take(MAX_PLAYLIST_ITEMS)
                totalItems += mediaIds.size
                require(totalItems <= MAX_TOTAL_PLAYLIST_ITEMS) { "Too many playlist entries" }
                add(PlaylistRestoreEntry(playlistId, name, mediaIds))
            }
        }
        val restoredSettings = settingsFromJson(validated.settings)

        // Snapshot all stores before writing. Each store commits atomically locally;
        // if a later store fails, restore the prior snapshots and retain the original error.
        val previousFavourites = userDataRepository.observeFavouriteIdsSnapshot()
        val previousPlaylists = playlistRepository.snapshotPlaylists().map { current ->
            PlaylistRestoreEntry(
                playlistId = current.playlist.playlistId,
                name = current.playlist.name,
                mediaIds = current.items.sortedBy { it.position }.map { it.mediaId }
            )
        }
        val previousSettings = settingsRepository.currentSettings()

        try {
            userDataRepository.replaceFavourites(favourites)
            playlistRepository.restoreAll(restoredPlaylists)
            if (restoredSettings != null) settingsRepository.restore(restoredSettings)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { settingsRepository.restore(previousSettings) }
                    .onFailure(failure::addSuppressed)
                runCatching { playlistRepository.restoreAll(previousPlaylists) }
                    .onFailure(failure::addSuppressed)
                runCatching { userDataRepository.replaceFavourites(previousFavourites) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    // ------------------------------------------------------------- settings IO

    private fun settingsToJson(s: PlayerSettings): JSONObject = JSONObject()
        .put("themeMode", s.themeMode.name)
        .put("accent", s.accent)
        .put("dynamicColor", s.dynamicColor)
        .put("resumePlayback", s.resumePlayback)
        .put("defaultSpeed", s.defaultSpeed.toDouble())
        .put("skipSeconds", s.skipSeconds)
        .put("autoplayNext", s.autoplayNext)
        .put("backgroundPlayback", s.backgroundPlayback)
        .put("rememberBrightness", s.rememberBrightness)
        .put("subtitleSizeSp", s.subtitleSizeSp)
        .put("subtitleColor", s.subtitleColor)
        .put("subtitleBackground", s.subtitleBackground)
        .put("subtitleBottomPadding", s.subtitleBottomPadding)
        .put("subtitleBold", s.subtitleBold)
        .put("gestureSwipeHorizontal", s.gestureSwipeHorizontal.name)
        .put("gestureDoubleTap", s.gestureDoubleTap.name)
        .put("gestureLongPress", s.gestureLongPress.name)
        .put("gestureVerticalLeft", s.gestureVerticalLeft.name)
        .put("gestureVerticalRight", s.gestureVerticalRight.name)
        .put("equalizerPresetIndex", s.equalizerPresetIndex)
        .put("equalizerBands", JSONArray().apply { s.equalizerBands.forEach { put(it) } })
        .put("bassBoostStrength", s.bassBoostStrength)
        .put("virtualizerStrength", s.virtualizerStrength)
        .put("loudnessGainDb", s.loudnessGainDb)
        .put("sortMode", s.sortMode.name)
        .put("viewMode", s.viewMode.name)

    private fun settingsFromJson(json: JSONObject): PlayerSettings = PlayerSettings(
        themeMode = runCatching { AppThemeMode.valueOf(json.optString("themeMode", "DARK")) }
            .getOrDefault(AppThemeMode.DARK),
        accent = json.optString("accent").takeIf { it.isNotBlank() } ?: "amethyst",
        dynamicColor = json.optBoolean("dynamicColor", false),
        resumePlayback = json.optBoolean("resumePlayback", true),
        defaultSpeed = json.optDouble("defaultSpeed", 1.0).toFloat().coerceIn(0.25f, 4f),
        skipSeconds = json.optInt("skipSeconds", 10).coerceIn(5, 60),
        autoplayNext = json.optBoolean("autoplayNext", true),
        backgroundPlayback = json.optBoolean("backgroundPlayback", true),
        rememberBrightness = json.optBoolean("rememberBrightness", true),
        subtitleSizeSp = json.optInt("subtitleSizeSp", 20).coerceIn(12, 48),
        subtitleColor = json.optLong("subtitleColor", 0xFFFFFFFFL),
        subtitleBackground = json.optBoolean("subtitleBackground", true),
        subtitleBottomPadding = json.optInt("subtitleBottomPadding", 8).coerceIn(0, 40),
        subtitleBold = json.optBoolean("subtitleBold", false),
        gestureSwipeHorizontal = GestureAction.fromName(
            json.optString("gestureSwipeHorizontal"), GestureAction.SEEK
        ),
        gestureDoubleTap = GestureAction.fromName(
            json.optString("gestureDoubleTap"), GestureAction.SEEK
        ),
        gestureLongPress = GestureAction.fromName(
            json.optString("gestureLongPress"), GestureAction.SPEED_BOOST
        ),
        gestureVerticalLeft = GestureAction.fromName(
            json.optString("gestureVerticalLeft"), GestureAction.BRIGHTNESS
        ),
        gestureVerticalRight = GestureAction.fromName(
            json.optString("gestureVerticalRight"), GestureAction.VOLUME
        ),
        equalizerPresetIndex = json.optInt("equalizerPresetIndex", 1).coerceIn(0, 7),
        equalizerBands = runCatching {
            val array = json.optJSONArray("equalizerBands") ?: JSONArray()
            List(5) { index -> array.optInt(index, 0).coerceIn(-1500, 1500) }
        }.getOrDefault(List(5) { 0 }),
        bassBoostStrength = json.optInt("bassBoostStrength", 0).coerceIn(0, 1000),
        virtualizerStrength = json.optInt("virtualizerStrength", 0).coerceIn(0, 1000),
        loudnessGainDb = json.optInt("loudnessGainDb", 0).coerceIn(0, 30),
        sortMode = runCatching { SortMode.valueOf(json.optString("sortMode", "NEWEST")) }
            .getOrDefault(SortMode.NEWEST),
        viewMode = runCatching { ViewMode.valueOf(json.optString("viewMode", "GRID")) }
            .getOrDefault(ViewMode.GRID)
    )

    private companion object {
        const val FORMAT = "malekplay-backup"
        const val BACKUP_VERSION = 2
        const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
        const val MAX_FAVOURITES = 20_000
        const val MAX_PLAYLISTS = 500
        const val MAX_PLAYLIST_ITEMS = 5_000
        const val MAX_TOTAL_PLAYLIST_ITEMS = 50_000
        const val MAX_URI_LENGTH = 4096
    }
}
