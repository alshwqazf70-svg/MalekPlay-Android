package com.malekplay.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.malekPlayDataStore by preferencesDataStore(name = "malekplay_settings")

data class PlayerSettings(
    val resumePlayback: Boolean = true,
    val rememberBrightness: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val subtitleSizeSp: Int = 20,
    val theme: String = "light"
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val rememberBrightness = booleanPreferencesKey("remember_brightness")
        val defaultPlaybackSpeed = floatPreferencesKey("default_playback_speed")
        val subtitleSizeSp = intPreferencesKey("subtitle_size_sp")
        val theme = stringPreferencesKey("theme")
    }

    val settings: Flow<PlayerSettings> = context.malekPlayDataStore.data.map { prefs ->
        PlayerSettings(
            resumePlayback = prefs[Keys.resumePlayback] ?: true,
            rememberBrightness = prefs[Keys.rememberBrightness] ?: true,
            defaultPlaybackSpeed = (prefs[Keys.defaultPlaybackSpeed] ?: 1.0f).coerceIn(0.25f, 4.0f),
            subtitleSizeSp = (prefs[Keys.subtitleSizeSp] ?: 20).coerceIn(12, 48),
            theme = prefs[Keys.theme]?.takeIf { it in setOf("light", "dark", "system") } ?: "light"
        )
    }

    suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        context.malekPlayDataStore.edit { prefs ->
            val current = PlayerSettings(
                resumePlayback = prefs[Keys.resumePlayback] ?: true,
                rememberBrightness = prefs[Keys.rememberBrightness] ?: true,
                defaultPlaybackSpeed = prefs[Keys.defaultPlaybackSpeed] ?: 1.0f,
                subtitleSizeSp = prefs[Keys.subtitleSizeSp] ?: 20,
                theme = prefs[Keys.theme] ?: "light"
            )
            val next = transform(current)
            prefs[Keys.resumePlayback] = next.resumePlayback
            prefs[Keys.rememberBrightness] = next.rememberBrightness
            prefs[Keys.defaultPlaybackSpeed] = next.defaultPlaybackSpeed.coerceIn(0.25f, 4.0f)
            prefs[Keys.subtitleSizeSp] = next.subtitleSizeSp.coerceIn(12, 48)
            prefs[Keys.theme] = next.theme.takeIf { it in setOf("light", "dark", "system") } ?: "light"
        }
    }
}
