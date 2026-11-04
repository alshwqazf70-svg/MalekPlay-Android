package com.malekplay.backup

import android.content.Context
import android.net.Uri
import com.malekplay.core.ui.theme.AppThemeMode
import com.malekplay.data.preferences.SettingsRepository
import com.malekplay.data.repository.PlaylistRepository
import com.malekplay.data.repository.UserDataRepository
import com.malekplay.domain.model.GestureAction
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

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

        val playlistsArray = JSONArray()
        for (playlist in playlistRepository.snapshotPlaylists()) {
            playlistsArray.put(
                JSONObject()
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
            val buffer = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BACKUP_BYTES) { "Backup file exceeds the size limit" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }

        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.optString("format") == FORMAT) { "Not a MalekPlay backup file" }
        require(root.optInt("version", -1) == BACKUP_VERSION) { "Unsupported backup version" }

        val favouritesArray = root.optJSONArray("favourites") ?: JSONArray()
        val playlistsArray = root.optJSONArray("playlists") ?: JSONArray()
        require(favouritesArray.length() <= MAX_FAVOURITES) { "Too many favourites" }
        require(playlistsArray.length() <= MAX_PLAYLISTS) { "Too many playlists" }

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
                val mediaIds = buildList {
                    for (j in 0 until mediaArray.length()) {
                        val value = mediaArray.optString(j)
                        if (value.length in 1..MAX_URI_LENGTH) add(value)
                    }
                }.distinct().take(MAX_PLAYLIST_ITEMS)
                totalItems += mediaIds.size
                require(totalItems <= MAX_TOTAL_PLAYLIST_ITEMS) { "Too many playlist entries" }
                add(name to mediaIds)
            }
        }
        val settingsJson = root.optJSONObject("settings")
        val restoredSettings = settingsJson?.let(::settingsFromJson)

        userDataRepository.replaceFavourites(favourites)
        playlistRepository.replaceAll(restoredPlaylists)
        if (restoredSettings != null) settingsRepository.restore(restoredSettings)
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
        themeMode = runCatching { AppThemeMode.valueOf(json.optString("themeMode", "SYSTEM")) }
            .getOrDefault(AppThemeMode.SYSTEM),
        accent = json.optString("accent").takeIf { it.isNotBlank() } ?: "emerald",
        dynamicColor = json.optBoolean("dynamicColor", true),
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
