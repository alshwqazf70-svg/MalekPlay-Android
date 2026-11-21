package com.malekplay.player.effects

import android.graphics.Bitmap
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlTextureProducer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the production enhancement GlEffect through Media3's real
 * DefaultVideoFrameProcessor, rather than calling drawFrame() directly.
 *
 * Bitmap input avoids codec/device-specific fixtures while still exercising
 * Media3 stream registration, GL graph construction, shader configure/draw,
 * output texture delivery, stream-size reconfiguration and processor teardown.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class VideoEnhancementMedia3FrameProcessorInstrumentedTest {

    @Test
    fun media3ProcessesFramesThroughEnhancementEffectAcrossInputSizeChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputFrames = CountDownLatch(2)
        val firstOutputFrame = CountDownLatch(1)
        val firstStreamRegistered = CountDownLatch(1)
        val secondStreamRegistered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val outputTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
        var processor: VideoFrameProcessor? = null

        val textureListener = object : GlTextureProducer.Listener {
            override fun onTextureRendered(
                textureProducer: GlTextureProducer,
                outputTexture: androidx.media3.common.GlTextureInfo,
                presentationTimeUs: Long,
                syncObject: Long
            ) {
                outputTimes += presentationTimeUs
                textureProducer.releaseOutputTexture(presentationTimeUs)
                firstOutputFrame.countDown()
                outputFrames.countDown()
            }
        }
        val registrationCount = java.util.concurrent.atomic.AtomicInteger(0)
        val processorListener = object : VideoFrameProcessor.Listener {
            override fun onInputStreamRegistered(
                inputType: Int,
                format: Format,
                effects: List<androidx.media3.common.Effect>
            ) {
                if (registrationCount.incrementAndGet() == 1) {
                    firstStreamRegistered.countDown()
                } else {
                    secondStreamRegistered.countDown()
                }
            }

            override fun onError(exception: VideoFrameProcessingException) {
                failure.compareAndSet(null, exception)
                ended.countDown()
            }

            override fun onEnded() {
                ended.countDown()
            }
        }

        val effect = VideoEnhancementPipelineEffect(
            VideoEnhancementPipelineConfig(
                denoiseEnabled = true,
                detailEnabled = true,
                denoiseStrength = 0.42f,
                detailStrength = 0.72f,
                contrastBoost = 0.10f,
                clarityStrength = 0.16f
            )
        )

        val initialProgramCreations = VideoEnhancementMetrics.snapshot().programCreations
        VideoEnhancementMetrics.reset()
        try {
            val activeProcessor = DefaultVideoFrameProcessor.Factory.Builder()
                .setTextureOutput(textureListener, /* textureOutputCapacity = */ 2)
                .build()
                .create(
                    context,
                    DebugViewProvider.NONE,
                    ColorInfo.SDR_BT709_LIMITED,
                    /* renderFramesAutomatically = */ true,
                    Executor { command -> command.run() },
                    processorListener
                )
            processor = activeProcessor

            val firstFormat = videoFormat(32, 24)
            activeProcessor.registerInputStream(
                VideoFrameProcessor.INPUT_TYPE_BITMAP,
                firstFormat,
                listOf(effect),
                /* offsetToAddUs = */ 0L
            )
            assertTrue(
                "Media3 did not finish registering the first bitmap stream",
                firstStreamRegistered.await(10, TimeUnit.SECONDS)
            )
            assertTrue(
                "Media3 rejected first bitmap frame",
                activeProcessor.queueInputBitmap(
                    testBitmap(32, 24),
                    SingleTimestampIterator(10_000L)
                )
            )

            assertTrue(
                "Media3 did not deliver the first output before stream transition",
                firstOutputFrame.await(10, TimeUnit.SECONDS)
            )

            // A second stream on the same processor models a media-item/format
            // transition. The graph must reconfigure the same effect instance.
            val secondFormat = videoFormat(24, 32)
            activeProcessor.registerInputStream(
                VideoFrameProcessor.INPUT_TYPE_BITMAP,
                secondFormat,
                listOf(effect),
                /* offsetToAddUs = */ 20_000L
            )
            assertTrue(
                "Media3 did not finish registering the second bitmap stream",
                secondStreamRegistered.await(10, TimeUnit.SECONDS)
            )
            assertTrue(
                "Media3 rejected second bitmap frame after stream reconfiguration",
                activeProcessor.queueInputBitmap(
                    testBitmap(24, 32),
                    SingleTimestampIterator(10_000L)
                )
            )
            activeProcessor.signalEndOfInput()

            assertTrue(
                "Media3 did not signal end of processing",
                ended.await(15, TimeUnit.SECONDS)
            )
            // Surface the original asynchronous GL/Media3 exception immediately.
            // Waiting for output textures first would hide onError() behind a timeout.
            failure.get()?.let { throw AssertionError("Media3 frame processing failed", it) }
            assertTrue(
                "Media3 did not deliver both processed output textures",
                outputFrames.await(15, TimeUnit.SECONDS)
            )

            val metrics = VideoEnhancementMetrics.snapshot()
            assertTrue("Media3 never created the production GL shader program", metrics.programCreations > initialProgramCreations)
            assertTrue("Media3 never configured the production shader", metrics.configureCalls >= 2)
            assertTrue("Media3 never entered the production drawFrame()", metrics.drawFrameCalls >= 2)
            assertTrue("Media3 did not complete any production shader draws", metrics.drawFrameSuccesses >= 2)
            assertTrue("Enabled enhancement did not process any frames", metrics.processedDraws >= 2)
            assertEquals("The second stream did not reconfigure the shader width", 24, metrics.lastConfiguredWidth)
            assertEquals("The second stream did not reconfigure the shader height", 32, metrics.lastConfiguredHeight)
            val renderedTimes = synchronized(outputTimes) { outputTimes.toList() }
            assertEquals("Media3 did not preserve each stream's timestamp offset", listOf(10_000L, 30_000L), renderedTimes)
        } finally {
            processor?.let { activeProcessor ->
                runCatching { activeProcessor.release() }
            }
        }
    }

    private fun videoFormat(width: Int, height: Int): Format =
        Format.Builder()
            .setWidth(width)
            .setHeight(height)
            .setPixelWidthHeightRatio(1f)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()

    private fun testBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val red = (x * 255 / width).coerceIn(0, 255)
                    val green = (y * 255 / height).coerceIn(0, 255)
                    val blue = if ((x / 4 + y / 4) % 2 == 0) 220 else 32
                    setPixel(x, y, android.graphics.Color.rgb(red, green, blue))
                }
            }
        }

    private class SingleTimestampIterator(private val timestampUs: Long) : TimestampIterator {
        private var available = true

        override fun hasNext(): Boolean = available

        override fun next(): Long {
            check(available) { "Timestamp iterator exhausted" }
            available = false
            return timestampUs
        }

        override fun copyOf(): TimestampIterator = SingleTimestampIterator(timestampUs)
    }
}
