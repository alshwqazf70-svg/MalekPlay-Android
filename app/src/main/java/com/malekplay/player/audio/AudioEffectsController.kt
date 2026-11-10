package com.malekplay.player.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.malekplay.domain.model.EqualizerPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AudioEffects"

/**
 * Owns the platform AudioEffect chain for one audio session.
 *
 * Effects bind to an audio session id and must be rebuilt whenever ExoPlayer
 * reports a new one, so [attach] is safe to call repeatedly.
 */
class AudioEffectsController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var visualizer: Visualizer? = null
    // Retain the user's opt-in if the player exposes its audio session later.
    private var visualizerRequested = false

    private var currentSessionId = 0
    private var bands: List<Int> = EqualizerPreset.FLAT.bands.toList()
    private var pendingPreset: EqualizerPreset? = null
    private var bassStrength = 0
    private var virtualizerStrength = 0
    private var loudnessGain = 0

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _bandCount = MutableStateFlow(0)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _levelRange = MutableStateFlow(-1500 to 1500)
    val levelRange: StateFlow<Pair<Int, Int>> = _levelRange.asStateFlow()

    /** 0..1 per band, refreshed at ~20 Hz for the visualizer bars. */
    private val _waveform = MutableStateFlow(FloatArray(32))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    private val visualizerListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
            viz: Visualizer?, waveform: ByteArray?, samplingRate: Int
        ) {
            val data = waveform ?: return
            val bars = FloatArray(32)
            for (i in bars.indices) {
                var peak = 0
                val start = i * data.size / bars.size
                val end = (i + 1) * data.size / bars.size
                for (j in start until end) {
                    // PCM waveform samples are unsigned bytes centred around 128.
                    val sample = data[j].toInt() and 0xFF
                    val magnitude = kotlin.math.abs(sample - 128)
                    if (magnitude > peak) peak = magnitude
                }
                bars[i] = (peak / 128f).coerceIn(0f, 1f)
            }
            _waveform.value = bars
        }

        override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
    }

    /** (Re)binds the effect chain to an audio session. */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        // Preserve an explicit user request across audio-session replacement.
        val shouldRestartVisualizer = visualizerRequested
        release()
        visualizerRequested = shouldRestartVisualizer
        currentSessionId = audioSessionId

        equalizer = runCatching {
            Equalizer(0, audioSessionId).apply { enabled = true }
        }.onFailure { Log.w(TAG, "Equalizer unavailable", it) }.getOrNull()

        val eq = equalizer
        if (eq != null) {
            _bandCount.value = eq.numberOfBands.toInt().coerceAtMost(MAX_BANDS)
            val range = eq.bandLevelRange
            _levelRange.value = range[0].toInt() to range[1].toInt()
            pendingPreset?.let { bands = mapPreset(it, _bandCount.value) }
            applyBands(bands)
        } else {
            _bandCount.value = 0
        }

        bassBoost = runCatching {
            BassBoost(0, audioSessionId).apply {
                enabled = bassStrength > 0
                setStrength(bassStrength.toShort())
            }
        }.onFailure { Log.w(TAG, "BassBoost unavailable", it) }.getOrNull()

        virtualizer = runCatching {
            Virtualizer(0, audioSessionId).apply {
                enabled = virtualizerStrength > 0
                setStrength(virtualizerStrength.toShort())
            }
        }.onFailure { Log.w(TAG, "Virtualizer unavailable", it) }.getOrNull()

        loudness = runCatching {
            // LoudnessEnhancer throws if the target gain is set before a valid session.
            LoudnessEnhancer(audioSessionId).apply {
                enabled = loudnessGain > 0
                setTargetGain(loudnessGain * 100)
            }
        }.onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }.getOrNull()

        _available.value = equalizer != null || bassBoost != null ||
            virtualizer != null || loudness != null
    }

    @Synchronized
    fun setBands(values: List<Int>) {
        pendingPreset = null
        bands = values.toList()
        applyBands(bands)
    }

    @Synchronized
    fun applyPreset(preset: EqualizerPreset) {
        pendingPreset = preset
        val count = _bandCount.value
        if (count <= 0) {
            // Keep the full preset until the player exposes its actual band count.
            bands = preset.bands.toList()
            return
        }
        bands = mapPreset(preset, count)
        applyBands(bands)
    }

    private fun mapPreset(preset: EqualizerPreset, count: Int): List<Int> =
        List(count.coerceAtLeast(1)) { index ->
            val source = (index * preset.bands.size / count.coerceAtLeast(1))
                .coerceIn(0, preset.bands.lastIndex)
            preset.bands[source]
        }

    private fun applyBands(values: List<Int>) {
        val eq = equalizer ?: return
        val count = _bandCount.value
        if (count <= 0) return
        repeat(count) { index ->
            runCatching {
                val (minimum, maximum) = _levelRange.value
                val level = values.getOrElse(index) { 0 }.coerceIn(minimum, maximum)
                eq.setBandLevel(index.toShort(), level.toShort())
            }.onFailure { Log.w(TAG, "Band $index rejected", it) }
        }
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        runCatching {
            bassBoost?.apply {
                enabled = bassStrength > 0
                setStrength(bassStrength.toShort())
            }
        }.onFailure { Log.w(TAG, "BassBoost set failed", it) }
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength.coerceIn(0, 1000)
        runCatching {
            virtualizer?.apply {
                enabled = virtualizerStrength > 0
                setStrength(virtualizerStrength.toShort())
            }
        }.onFailure { Log.w(TAG, "Virtualizer set failed", it) }
    }

    @Synchronized
    fun setLoudness(gainDb: Int) {
        loudnessGain = gainDb.coerceIn(0, 30)
        runCatching {
            loudness?.apply {
                enabled = loudnessGain > 0
                setTargetGain(loudnessGain * 100)
            }
        }.onFailure { Log.w(TAG, "Loudness set failed", it) }
    }

    /**
     * Starts waveform capture for the visualizer.
     * Requires RECORD_AUDIO; the caller must hold it before calling.
     */
    @Synchronized
    fun startVisualizer() {
        visualizerRequested = true
        startVisualizerIfReady()
    }

    /** Starts capture only after ExoPlayer has supplied a valid audio session. */
    private fun startVisualizerIfReady() {
        if (!visualizerRequested || visualizer != null) return
        val session = currentSessionId
        if (session <= 0) return
        visualizer = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(
                    visualizerListener,
                    minOf(Visualizer.getMaxCaptureRate(), VISUALIZER_CAPTURE_RATE_HZ * 1_000),
                    true,
                    false
                )
                enabled = true
            }
        }.onFailure { Log.w(TAG, "Visualizer unavailable", it) }.getOrNull()
    }

    @Synchronized
    fun stopVisualizer() {
        visualizerRequested = false
        runCatching {
            visualizer?.apply {
                enabled = false
                setDataCaptureListener(null, 0, false, false)
                release()
            }
        }
        visualizer = null
        _waveform.value = FloatArray(32)
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        stopVisualizer()
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudness = null
        currentSessionId = 0
        _available.value = false
        _bandCount.value = 0
    }

    companion object {
        /** Guard against devices reporting an unreasonable band count. */
        const val MAX_BANDS = 12
        /** A 10 Hz display is sufficient for reactive bars with lower DSP wakeups. */
        const val VISUALIZER_CAPTURE_RATE_HZ = 10
    }
}
