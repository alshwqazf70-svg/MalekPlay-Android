package com.malekplay.player.audio

import android.media.audiofx.Visualizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.malekplay.domain.model.EqualizerPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AudioEffects"

/** Owns software DSP and the optional platform virtualizer for one Media3 session. */
class AudioEffectsController {

    private var virtualizer: Virtualizer? = null
    private var visualizer: Visualizer? = null
    private var visualizerRequested = false
    private var currentSessionId = 0
    private var bands = List(TenBandEqualizerAudioProcessor.BAND_COUNT) { 0 }
    private var pendingPreset: EqualizerPreset? = null
    private var bassStrength = 0
    private var virtualizerStrength = 0
    private var loudnessGain = 0

    /** This processor is installed in the service's DefaultAudioSink before playback starts. */
    val softwareEqualizerProcessor = TenBandEqualizerAudioProcessor()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _bandCount = MutableStateFlow(TenBandEqualizerAudioProcessor.BAND_COUNT)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _levelRange = MutableStateFlow(
        TenBandEqualizerAudioProcessor.MIN_BAND_MB to TenBandEqualizerAudioProcessor.MAX_BAND_MB
    )
    val levelRange: StateFlow<Pair<Int, Int>> = _levelRange.asStateFlow()

    private val _virtualizerSupported = MutableStateFlow(false)
    val virtualizerSupported: StateFlow<Boolean> = _virtualizerSupported.asStateFlow()

    private val _outputRouteLabel = MutableStateFlow("جارٍ اكتشاف مخرج الصوت")
    val outputRouteLabel: StateFlow<String> = _outputRouteLabel.asStateFlow()

    val processingMetrics: StateFlow<TenBandEqualizerAudioProcessor.Metrics> =
        softwareEqualizerProcessor.metrics

    /** 0..1 per band, refreshed at ~10 Hz for the visualizer bars. */
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

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            if (currentSessionId != 0) releaseSessionEffects()
            return
        }
        if (audioSessionId == currentSessionId) return
        val restartVisualizer = visualizerRequested
        releaseSessionEffects()
        visualizerRequested = restartVisualizer
        currentSessionId = audioSessionId

        virtualizer = runCatching {
            Virtualizer(0, audioSessionId).apply {
                _virtualizerSupported.value = strengthSupported
                if (strengthSupported) {
                    setStrength(virtualizerStrength.coerceIn(0, 1000).toShort())
                    enabled = virtualizerStrength > 0
                } else {
                    enabled = false
                }
            }
        }.onFailure {
            _virtualizerSupported.value = false
            Log.w(TAG, "Virtualizer unavailable for audio session", it)
        }.getOrNull()

        _available.value = true // The software DSP is available for decoded PCM.
        softwareEqualizerProcessor.setBandsMillibels(bands)
        softwareEqualizerProcessor.setBassBoost(bassStrength)
        softwareEqualizerProcessor.setLoudnessGain(loudnessGain)
        if (restartVisualizer) startVisualizerIfReady()
    }

    @Synchronized
    fun setBands(values: List<Int>) {
        pendingPreset = null
        bands = List(TenBandEqualizerAudioProcessor.BAND_COUNT) { index ->
            values.getOrNull(index)?.coerceIn(
                TenBandEqualizerAudioProcessor.MIN_BAND_MB,
                TenBandEqualizerAudioProcessor.MAX_BAND_MB
            ) ?: 0
        }
        softwareEqualizerProcessor.setBandsMillibels(bands)
    }

    @Synchronized
    fun applyPreset(preset: EqualizerPreset) {
        pendingPreset = preset
        bands = preset.bands.take(TenBandEqualizerAudioProcessor.BAND_COUNT)
            .map { it.coerceIn(
                TenBandEqualizerAudioProcessor.MIN_BAND_MB,
                TenBandEqualizerAudioProcessor.MAX_BAND_MB
            ) }
            .let { values ->
                List(TenBandEqualizerAudioProcessor.BAND_COUNT) { values.getOrElse(it) { 0 } }
            }
        softwareEqualizerProcessor.setBandsMillibels(bands)
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        softwareEqualizerProcessor.setBassBoost(
            (bassStrength / 1000f * TenBandEqualizerAudioProcessor.MAX_BASS_BOOST_DB).toInt()
        )
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength.coerceIn(0, 1000)
        val effect = virtualizer
        if (effect == null || !_virtualizerSupported.value) {
            Log.i(TAG, "Virtualizer not applied: effect unsupported for current session")
            return
        }
        runCatching {
            effect.setStrength(virtualizerStrength.toShort())
            effect.enabled = virtualizerStrength > 0
        }.onFailure { Log.w(TAG, "Virtualizer update failed", it) }
    }

    @Synchronized
    fun setLoudness(gainDb: Int) {
        loudnessGain = gainDb.coerceIn(0, TenBandEqualizerAudioProcessor.MAX_LOUDNESS_DB)
        softwareEqualizerProcessor.setLoudnessGain(loudnessGain)
    }

    /** Called by PlaybackService when Android reports a change in connected outputs. */
    fun updateOutputRoute(label: String) {
        _outputRouteLabel.value = label.ifBlank { "مخرج صوت غير معروف" }
    }

    @Synchronized
    fun startVisualizer() {
        visualizerRequested = true
        startVisualizerIfReady()
    }

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
        releaseSessionEffects()
        _available.value = false
    }

    private fun releaseSessionEffects() {
        runCatching { virtualizer?.release() }
        stopVisualizer()
        virtualizer = null
        currentSessionId = 0
        _virtualizerSupported.value = false
        _available.value = false
    }

    companion object {
        const val VISUALIZER_CAPTURE_RATE_HZ = 10
    }
}
