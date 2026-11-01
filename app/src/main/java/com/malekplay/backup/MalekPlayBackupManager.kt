package com.malekplay.backup

import android.content.Context
import android.net.Uri
import com.malekplay.data.preferences.PlayerSettings
import com.malekplay.data.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Backs up user preferences and library organization only.
 * The active Room database is deliberately not copied while SQLite/WAL may be open.
 */
class MalekPlayBackupManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    suspend fun exportTo(destination: Uri) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("malekplay_library", Context.MODE_PRIVATE)
        val settings = settingsRepository.currentSettings()
        val favorites = JSONArray().apply {
            prefs.getStringSet("favorites", emptySet()).orEmpty().sorted().forEach(::put)
        }
        val recent = JSONArray().apply {
            prefs.getString("recent", "").orEmpty().split("|")
                .filter { it.isNotBlank() }.take(50).forEach(::put)
        }
        val playlistsText = prefs.getString("playlists_json", "[]") ?: "[]"
        val playlists = JSONArray(playlistsText)
        val root = JSONObject()
            .put("format", "malekplay-backup")
            .put("version", BACKUP_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("favorites", favorites)
            .put("recent", recent)
            .put("playlists", playlists)
            .put("settings", JSONObject()
                .put("resumePlayback", settings.resumePlayback)
                .put("rememberBrightness", settings.rememberBrightness)
                .put("defaultPlaybackSpeed", settings.defaultPlaybackSpeed.toDouble())
                .put("subtitleSizeSp", settings.subtitleSizeSp)
                .put("theme", settings.theme))
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "ملف النسخة الاحتياطية أكبر من الحد المسموح" }
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("تعذر إنشاء ملف النسخة الاحتياطية")
        output.use { it.write(bytes) }
    }

    suspend fun restoreFrom(source: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source)
            ?: error("تعذر فتح ملف النسخة الاحتياطية")
        val bytes = input.use { stream ->
            val data = stream.readNBytes(MAX_BACKUP_BYTES + 1)
            require(data.size <= MAX_BACKUP_BYTES) { "ملف النسخة الاحتياطية يتجاوز الحد المسموح" }
            data
        }
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.optString("format") == "malekplay-backup") { "صيغة الملف ليست نسخة MalekPlay" }
        require(root.optInt("version", -1) == BACKUP_VERSION) { "إصدار النسخة الاحتياطية غير مدعوم" }
        val favoritesArray = root.optJSONArray("favorites") ?: JSONArray()
        val recentArray = root.optJSONArray("recent") ?: JSONArray()
        val playlistsArray = root.optJSONArray("playlists") ?: JSONArray()
        require(favoritesArray.length() <= 20_000 && recentArray.length() <= 50 && playlistsArray.length() <= 500) {
            "تحتوي النسخة على عدد عناصر غير مقبول"
        }
        val favorites = buildSet {
            for (i in 0 until favoritesArray.length()) {
                val value = favoritesArray.optString(i)
                require(value.length <= 4096)
                if (value.isNotBlank()) add(value)
            }
        }
        val recent = buildList {
            for (i in 0 until recentArray.length()) {
                val value = recentArray.optString(i)
                require(value.length <= 4096)
                if (value.isNotBlank()) add(value)
            }
        }
        val playlistsText = playlistsArray.toString()
        require(playlistsText.length <= 1_000_000)
        val settingsJson = root.optJSONObject("settings") ?: JSONObject()
        val restoredSettings = PlayerSettings(
            resumePlayback = settingsJson.optBoolean("resumePlayback", true),
            rememberBrightness = settingsJson.optBoolean("rememberBrightness", true),
            defaultPlaybackSpeed = settingsJson.optDouble("defaultPlaybackSpeed", 1.0).toFloat().coerceIn(0.5f, 2f),
            subtitleSizeSp = settingsJson.optInt("subtitleSizeSp", 20).coerceIn(14, 36),
            theme = settingsJson.optString("theme", "light").takeIf { it in setOf("light", "dark", "system") } ?: "light"
        )
        val prefs = context.getSharedPreferences("malekplay_library", Context.MODE_PRIVATE)
        check(prefs.edit()
            .putStringSet("favorites", favorites)
            .putString("recent", recent.joinToString("|"))
            .putString("playlists_json", playlistsText)
            .commit()) { "تعذر حفظ بيانات المكتبة المستعادة" }
        settingsRepository.restoreSettings(restoredSettings)
    }

    private companion object {
        const val BACKUP_VERSION = 1
        const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
    }
}
