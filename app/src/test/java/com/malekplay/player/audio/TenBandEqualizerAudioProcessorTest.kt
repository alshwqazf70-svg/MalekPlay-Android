package com.malekplay.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TenBandEqualizerAudioProcessorTest {

    @Test
    fun neutralSettingsPassPcmThroughBitExactly() {
        val processor = TenBandEqualizerAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        val samples = shortArrayOf(1_234, -2_345, 32_000, -32_000)
        samples.forEach(input::putShort)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        samples.forEach { expected -> assertEquals(expected, output.short) }
        assertEquals(0L, processor.metrics.value.effectFrames)
        assertEquals(2L, processor.metrics.value.processedFrames)
    }

    @Test
    fun tenBandGainChangesPcmAndCountsEffectFrames() {
        val processor = TenBandEqualizerAudioProcessor()
        processor.setBandsMillibels(List(10) { index -> if (index == 5) 900 else 0 })
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(4_800 * 2).order(ByteOrder.nativeOrder())
        repeat(4_800) { index ->
            val sample = (sin(2.0 * PI * 1_000.0 * index / 48_000.0) * 8_000.0).toInt()
            input.putShort(sample.toShort())
        }
        input.flip()
        val originalSample = input.getShort(24)

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertTrue(originalSample != output.getShort(24))
        assertEquals(4_800L, processor.metrics.value.processedFrames)
        assertEquals(4_800L, processor.metrics.value.effectFrames)
        assertTrue(processor.metrics.value.processedBuffers > 0L)
    }

    @Test
    fun gainAndBassControlsAreBounded() {
        val processor = TenBandEqualizerAudioProcessor()
        processor.setBassBoost(Int.MAX_VALUE)
        processor.setLoudnessGain(Int.MAX_VALUE)
        processor.setBandsMillibels(List(10) { Int.MAX_VALUE })

        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(4_096).order(ByteOrder.nativeOrder())
        repeat(2_048) { input.putShort(Short.MAX_VALUE) }
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder())
        while (output.remaining() >= 2) {
            val value = output.short.toInt()
            assertTrue(value in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        }
        assertEquals(2_048L, processor.metrics.value.effectFrames)
    }
}
