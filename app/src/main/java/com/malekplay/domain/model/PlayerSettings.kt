package com.malekplay.domain.model

import com.malekplay.core.ui.theme.AppThemeMode

/** Actions the user can bind to a player gesture. */
enum class GestureAction {
    NONE, SEEK, VOLUME, BRIGHTNESS, PLAY_PAUSE, SPEED_BOOST;

    companion object {
        fun fromName(name: String?, fallback: GestureAction = NONE): GestureAction =
            entries.firstOrNull { it.name == name } ?: fallback
    }
}

/** Library sort order. */
enum class SortMode { NEWEST, OLDEST, NAME, DURATION, SIZE }

/** List rendering style for the library grid. */
enum class ViewMode { LIST, GRID }

/** Built-in equalizer presets, index-aligned with [EQUALIZER_PRESETS]. */
enum class EqualizerPreset(val label: String, val bands: IntArray) {
    CUSTOM("Custom", intArrayOf(0, 0, 0, 0, 0)),
    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bass boost", intArrayOf(900, 600, 200, 0, 0)),
    TREBLE_BOOST("Treble boost", intArrayOf(0, 0, 0, 500, 800)),
    VOCAL("Vocal", intArrayOf(-300, 200, 600, 200, -300)),
    CINEMA("Cinema", intArrayOf(400, 300, 0, 300, 500)),
    PODCAST("Podcast", intArrayOf(-400, -100, 300, 200, -200)),
    LOUDNESS("Loudness", intArrayOf(700, 400, -200, 300, 600));

    companion object {
        fun fromIndex(index: Int): EqualizerPreset =
            entries.getOrNull(index) ?: FLAT
    }
}

val EQUALIZER_PRESETS: List<EqualizerPreset> = EqualizerPreset.entries.toList()

/** Everything the user can tune. Persisted in DataStore as individual keys. */
data class PlayerSettings(
    // Appearance
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val accent: String = "emerald",
    val dynamicColor: Boolean = true,

    // Playback
    val resumePlayback: Boolean = true,
    val defaultSpeed: Float = 1f,
    val skipSeconds: Int = 10,
    val autoplayNext: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val rememberBrightness: Boolean = true,

    // Subtitles
    val subtitleSizeSp: Int = 20,
    val subtitleColor: Long = 0xFFFFFFFFL,
    val subtitleBackground: Boolean = true,
    val subtitleBottomPadding: Int = 8,
    val subtitleBold: Boolean = false,

    // Gestures
    val gestureSwipeHorizontal: GestureAction = GestureAction.SEEK,
    val gestureDoubleTap: GestureAction = GestureAction.SEEK,
    val gestureLongPress: GestureAction = GestureAction.SPEED_BOOST,
    val gestureVerticalLeft: GestureAction = GestureAction.BRIGHTNESS,
    val gestureVerticalRight: GestureAction = GestureAction.VOLUME,

    // Audio effects
    val equalizerPresetIndex: Int = EqualizerPreset.FLAT.ordinal,
    val equalizerBands: List<Int> = List(5) { 0 },
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val loudnessGainDb: Int = 0,

    // Library
    val sortMode: SortMode = SortMode.NEWEST,
    val viewMode: ViewMode = ViewMode.LIST
) {
    val subtitleColorInt: Int get() = (subtitleColor and 0xFFFFFFFFL).toInt()
}
