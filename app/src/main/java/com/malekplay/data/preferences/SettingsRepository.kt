package com.malekplay.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.malekplay.core.ui.theme.AppThemeMode
import com.malekplay.domain.model.EqualizerPreset
import com.malekplay.domain.model.GestureAction
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.malekPlayDataStore by preferencesDataStore(name = "malekplay_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        val RESUME = booleanPreferencesKey("resume_playback")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val SKIP_SECONDS = intPreferencesKey("skip_seconds")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
        val REMEMBER_BRIGHTNESS = booleanPreferencesKey("remember_brightness")

        val SUBTITLE_SIZE = intPreferencesKey("subtitle_size_sp")
        val SUBTITLE_COLOR = longPreferencesKey("subtitle_color")
        val SUBTITLE_BACKGROUND = booleanPreferencesKey("subtitle_background")
        val SUBTITLE_PADDING = intPreferencesKey("subtitle_bottom_padding")
        val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")

        val GESTURE_SWIPE_H = stringPreferencesKey("gesture_swipe_h")
        val GESTURE_DOUBLE_TAP = stringPreferencesKey("gesture_double_tap")
        val GESTURE_LONG_PRESS = stringPreferencesKey("gesture_long_press")
        val GESTURE_VERTICAL_L = stringPreferencesKey("gesture_vertical_left")
        val GESTURE_VERTICAL_R = stringPreferencesKey("gesture_vertical_right")

        val EQ_PRESET = intPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val VIRTUALIZER = intPreferencesKey("virtualizer")
        val LOUDNESS = intPreferencesKey("loudness_gain_db")

        val SORT_MODE = stringPreferencesKey("sort_mode")
        val VIEW_MODE = stringPreferencesKey("view_mode")
    }

    val settings: Flow<PlayerSettings> = context.malekPlayDataStore.data.map { p -> p.toSettings() }

    suspend fun currentSettings(): PlayerSettings = settings.first()

    suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        context.malekPlayDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.writeFrom(next)
        }
    }

    suspend fun restore(value: PlayerSettings) = update { value }

    // ------------------------------------------------------------ conversions

    private fun Preferences.toSettings(): PlayerSettings = PlayerSettings(
        themeMode = runCatching { AppThemeMode.valueOf(this[Keys.THEME_MODE] ?: "DARK") }
            .getOrDefault(AppThemeMode.SYSTEM),
        accent = this[Keys.ACCENT]?.takeIf { it.isNotBlank() } ?: "amethyst",
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: false,

        resumePlayback = this[Keys.RESUME] ?: true,
        defaultSpeed = (this[Keys.DEFAULT_SPEED] ?: 1f).coerceIn(0.25f, 4f),
        skipSeconds = (this[Keys.SKIP_SECONDS] ?: 10).coerceIn(5, 60),
        autoplayNext = this[Keys.AUTOPLAY_NEXT] ?: true,
        backgroundPlayback = this[Keys.BACKGROUND_PLAYBACK] ?: true,
        rememberBrightness = this[Keys.REMEMBER_BRIGHTNESS] ?: true,

        subtitleSizeSp = (this[Keys.SUBTITLE_SIZE] ?: 20).coerceIn(12, 48),
        subtitleColor = this[Keys.SUBTITLE_COLOR] ?: 0xFFFFFFFFL,
        subtitleBackground = this[Keys.SUBTITLE_BACKGROUND] ?: true,
        subtitleBottomPadding = (this[Keys.SUBTITLE_PADDING] ?: 8).coerceIn(0, 40),
        subtitleBold = this[Keys.SUBTITLE_BOLD] ?: false,

        gestureSwipeHorizontal = GestureAction.fromName(
            this[Keys.GESTURE_SWIPE_H], GestureAction.SEEK
        ),
        gestureDoubleTap = GestureAction.fromName(
            this[Keys.GESTURE_DOUBLE_TAP], GestureAction.SEEK
        ),
        gestureLongPress = GestureAction.fromName(
            this[Keys.GESTURE_LONG_PRESS], GestureAction.SPEED_BOOST
        ),
        gestureVerticalLeft = GestureAction.fromName(
            this[Keys.GESTURE_VERTICAL_L], GestureAction.BRIGHTNESS
        ),
        gestureVerticalRight = GestureAction.fromName(
            this[Keys.GESTURE_VERTICAL_R], GestureAction.VOLUME
        ),

        equalizerPresetIndex = (this[Keys.EQ_PRESET] ?: EqualizerPreset.FLAT.ordinal)
            .coerceIn(0, EqualizerPreset.entries.lastIndex),
        equalizerBands = parseBands(this[Keys.EQ_BANDS]),
        bassBoostStrength = (this[Keys.BASS_BOOST] ?: 0).coerceIn(0, 1000),
        virtualizerStrength = (this[Keys.VIRTUALIZER] ?: 0).coerceIn(0, 1000),
        loudnessGainDb = (this[Keys.LOUDNESS] ?: 0).coerceIn(0, 30),

        sortMode = runCatching { SortMode.valueOf(this[Keys.SORT_MODE] ?: "NEWEST") }
            .getOrDefault(SortMode.NEWEST),
        viewMode = runCatching { ViewMode.valueOf(this[Keys.VIEW_MODE] ?: "GRID") }
            .getOrDefault(ViewMode.LIST)
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeFrom(s: PlayerSettings) {
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.ACCENT] = s.accent
        this[Keys.DYNAMIC_COLOR] = s.dynamicColor

        this[Keys.RESUME] = s.resumePlayback
        this[Keys.DEFAULT_SPEED] = s.defaultSpeed.coerceIn(0.25f, 4f)
        this[Keys.SKIP_SECONDS] = s.skipSeconds.coerceIn(5, 60)
        this[Keys.AUTOPLAY_NEXT] = s.autoplayNext
        this[Keys.BACKGROUND_PLAYBACK] = s.backgroundPlayback
        this[Keys.REMEMBER_BRIGHTNESS] = s.rememberBrightness

        this[Keys.SUBTITLE_SIZE] = s.subtitleSizeSp.coerceIn(12, 48)
        this[Keys.SUBTITLE_COLOR] = s.subtitleColor
        this[Keys.SUBTITLE_BACKGROUND] = s.subtitleBackground
        this[Keys.SUBTITLE_PADDING] = s.subtitleBottomPadding.coerceIn(0, 40)
        this[Keys.SUBTITLE_BOLD] = s.subtitleBold

        this[Keys.GESTURE_SWIPE_H] = s.gestureSwipeHorizontal.name
        this[Keys.GESTURE_DOUBLE_TAP] = s.gestureDoubleTap.name
        this[Keys.GESTURE_LONG_PRESS] = s.gestureLongPress.name
        this[Keys.GESTURE_VERTICAL_L] = s.gestureVerticalLeft.name
        this[Keys.GESTURE_VERTICAL_R] = s.gestureVerticalRight.name

        this[Keys.EQ_PRESET] = s.equalizerPresetIndex.coerceIn(0, EqualizerPreset.entries.lastIndex)
        this[Keys.EQ_BANDS] = s.equalizerBands.joinToString(",")
        this[Keys.BASS_BOOST] = s.bassBoostStrength.coerceIn(0, 1000)
        this[Keys.VIRTUALIZER] = s.virtualizerStrength.coerceIn(0, 1000)
        this[Keys.LOUDNESS] = s.loudnessGainDb.coerceIn(0, 30)

        this[Keys.SORT_MODE] = s.sortMode.name
        this[Keys.VIEW_MODE] = s.viewMode.name
    }

    private fun parseBands(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return List(5) { 0 }
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        // Always expose exactly 5 bands; pad or truncate to stay in range.
        return List(5) { index -> parsed.getOrNull(index)?.coerceIn(-1500, 1500) ?: 0 }
    }
}
