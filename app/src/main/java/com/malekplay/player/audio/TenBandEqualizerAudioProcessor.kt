package com.malekplay.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Device-independent ten-band PCM equalizer used by Media3's AudioSink.
 *
 * Processing is deliberately limited to decoded 16-bit PCM. Encoded passthrough
 * and unsupported PCM formats are not modified, and the UI can inspect metrics
 * to avoid claiming that an effect was applied when no PCM frames were processed.
 */
class TenBandEqualizerAudioProcessor : BaseAudioProcessor() {

    data class Metrics(
        val configured: Boolean = false,
        val sampleRateHz: Int = 0,
        val processedBuffers: Long = 0,
        val processedFrames: Long = 0,
        val effectFrames: Long = 0,
        val lastBufferFrameCount: Int = 0
    )

    private data class Coefficients(
        val b0: Float = 1f,
        val b1: Float = 0f,
        val b2: Float = 0f,
        val a1: Float = 0f,
        val a2: Float = 0f
    )

    private class FilterState {
        var z1 = 0f
        var z2 = 0f
        fun clear() { z1 = 0f; z2 = 0f }
    }

    @Volatile private var requestedBandsMb = IntArray(BAND_COUNT)
    @Volatile private var bassBoostDb = 0
    @Volatile private var loudnessGainDb = 0
    @Volatile private var coefficients = Array(BAND_COUNT) { Coefficients() }
    @Volatile private var linearGain = 1f

    private var sampleRate = 0
    private var channelCount = 0
    private var states = emptyArray<Array<FilterState>>()
    private var buffersProcessed = 0L
    private var framesProcessed = 0L
    private var framesWithEffects = 0L

    private val _metrics = MutableStateFlow(Metrics())
    val metrics: StateFlow<Metrics> = _metrics.asStateFlow()

    fun setBandsMillibels(values: List<Int>) {
        requestedBandsMb = IntArray(BAND_COUNT) { index ->
            values.getOrNull(index)?.coerceIn(MIN_BAND_MB, MAX_BAND_MB) ?: 0
        }
        rebuildCoefficients()
    }

    fun setBassBoost(value: Int) {
        bassBoostDb = value.coerceIn(0, MAX_BASS_BOOST_DB)
        rebuildCoefficients()
    }

    fun setLoudnessGain(value: Int) {
        loudnessGainDb = value.coerceIn(0, MAX_LOUDNESS_DB)
        linearGain = 10f.pow(loudnessGainDb / 20f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        states = Array(channelCount) { Array(BAND_COUNT) { FilterState() } }
        rebuildCoefficients()
        _metrics.value = _metrics.value.copy(
            configured = true,
            sampleRateHz = sampleRate,
            lastBufferFrameCount = 0
        )
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        clearFilterState()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        states = emptyArray()
        _metrics.value = Metrics()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount <= 0) return

        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        val channels = channelCount
        if (channels <= 0 || byteCount % (2 * channels) != 0) {
            // Preserve unusual/truncated buffers exactly rather than corrupting PCM.
            output.put(input)
            output.flip()
            return
        }

        val filterCoefficients = coefficients
        val gain = linearGain
        val effectEnabled = hasAudibleEffect()
        val frameCount = byteCount / (2 * channels)
        var frame = 0
        while (input.remaining() >= 2) {
            val channel = frame % channels
            val source = input.short.toFloat() / 32768f
            var value = source
            val channelStates = states[channel]
            for (band in 0 until BAND_COUNT) {
                val c = filterCoefficients[band]
                val state = channelStates[band]
                val filtered = c.b0 * value + state.z1
                state.z1 = c.b1 * value - c.a1 * filtered + state.z2
                state.z2 = c.b2 * value - c.a2 * filtered
                value = filtered
            }
            value *= gain
            // Soft limiting protects against clipping when several bands and gain
            // are boosted together, without hard-clipping ordinary programme peaks.
            val magnitude = abs(value)
            if (magnitude > LIMITER_THRESHOLD) {
                value = (if (value < 0f) -1f else 1f) *
                    (LIMITER_THRESHOLD + LIMITER_HEADROOM *
                        tanh((magnitude - LIMITER_THRESHOLD) / LIMITER_HEADROOM))
            }
            output.putShort((value.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            if (channel == channels - 1) frame++
        }
        input.position(input.limit())
        output.flip()

        buffersProcessed++
        framesProcessed += frameCount
        if (effectEnabled) framesWithEffects += frameCount
        if (buffersProcessed == 1L || buffersProcessed % METRICS_UPDATE_INTERVAL == 0L) {
            _metrics.value = Metrics(
                configured = true,
                sampleRateHz = sampleRate,
                processedBuffers = buffersProcessed,
                processedFrames = framesProcessed,
                effectFrames = framesWithEffects,
                lastBufferFrameCount = frameCount
            )
        }
    }

    @Synchronized
    private fun rebuildCoefficients() {
        val rate = sampleRate
        if (rate <= 0) return
        val gains = requestedBandsMb
        coefficients = Array(BAND_COUNT) { index ->
            val frequency = BAND_FREQUENCIES_HZ[index]
            val bassContribution = when (index) {
                0 -> bassBoostDb
                1 -> bassBoostDb
                2 -> bassBoostDb / 2
                else -> 0
            }
            peakingFilter(
                sampleRate = rate,
                frequencyHz = frequency,
                gainDb = (gains[index] / 100f) + bassContribution
            )
        }
    }

    private fun hasAudibleEffect(): Boolean =
        loudnessGainDb > 0 || bassBoostDb > 0 || requestedBandsMb.any { it != 0 }

    private fun clearFilterState() {
        states.forEach { channel -> channel.forEach(FilterState::clear) }
    }

    private fun peakingFilter(sampleRate: Int, frequencyHz: Int, gainDb: Float): Coefficients {
        if (abs(gainDb) < 0.001f || frequencyHz >= sampleRate * NYQUIST_GUARD) {
            return Coefficients()
        }
        val frequency = frequencyHz.toFloat().coerceAtMost(sampleRate * NYQUIST_GUARD)
        val a = 10.0.pow((gainDb / 40.0).toDouble()).toFloat()
        val omega = (2.0 * PI * frequency / sampleRate).toFloat()
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2f * FILTER_Q)
        val a0 = 1f + alpha / a
        if (!a0.isFinite() || a0 == 0f) return Coefficients()
        return Coefficients(
            b0 = (1f + alpha * a) / a0,
            b1 = (-2f * cosOmega) / a0,
            b2 = (1f - alpha * a) / a0,
            a1 = (-2f * cosOmega) / a0,
            a2 = (1f - alpha / a) / a0
        )
    }

    companion object {
        const val BAND_COUNT = 10
        const val MIN_BAND_MB = -1_200
        const val MAX_BAND_MB = 1_200
        const val MAX_BASS_BOOST_DB = 8
        const val MAX_LOUDNESS_DB = 12
        const val METRICS_UPDATE_INTERVAL = 16L
        const val LIMITER_THRESHOLD = 0.94f
        const val LIMITER_HEADROOM = 0.06f
        const val FILTER_Q = 1.0f
        const val NYQUIST_GUARD = 0.45f
        val BAND_FREQUENCIES_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    }
}
