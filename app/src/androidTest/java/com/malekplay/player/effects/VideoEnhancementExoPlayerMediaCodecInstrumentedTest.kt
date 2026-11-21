package com.malekplay.player.effects

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof for the production enhancement effect:
 * generated AVC -> Android MediaCodec decoder -> Media3 video renderer ->
 * production GlEffect -> a real SurfaceView output.
 *
 * The fixture is encoded locally during the test. No network, mocked decoder,
 * bitmap input, or prebuilt media fixture is involved.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class VideoEnhancementExoPlayerMediaCodecInstrumentedTest {

    @Test
    fun realMediaCodecPlaybackRendersFramesProcessedByProductionEnhancementEffect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "enhancement-e2e-${System.nanoTime()}.mp4")
        createAvcFixture(context, fixture)

        val decoderInitialized = CountDownLatch(1)
        val playbackFinishedSignal = CountDownLatch(1)
        val decoderName = AtomicReference<String?>(null)
        val playbackError = AtomicReference<Throwable?>(null)
        val firstFrameRendered = AtomicBoolean(false)

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

        val scenario = ActivityScenario.launch(VideoEnhancementTestActivity::class.java)
        var player: ExoPlayer? = null
        try {
            scenario.onActivity { activity ->
                val exoPlayer = ExoPlayer.Builder(activity).build()
                player = exoPlayer
                exoPlayer.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderNameValue: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        decoderName.set(decoderNameValue)
                        decoderInitialized.countDown()
                    }

                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        firstFrameRendered.set(true)
                        playbackFinishedSignal.countDown()
                    }

                    override fun onPlayerError(
                        eventTime: AnalyticsListener.EventTime,
                        error: androidx.media3.common.PlaybackException
                    ) {
                        playbackError.compareAndSet(null, error)
                        playbackFinishedSignal.countDown()
                    }
                })

                // Install the production effect before prepare(), matching the
                // required Media3 video-graph initialization order.
                exoPlayer.setVideoEffects(listOf(effect))
                exoPlayer.setVideoSurfaceView(activity.surfaceView)
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }

            assertTrue(
                "ExoPlayer did not render a first frame from the locally encoded AVC fixture",
                playbackFinishedSignal.await(25, TimeUnit.SECONDS)
            )
            playbackError.get()?.let {
                throw AssertionError("ExoPlayer/MediaCodec playback failed", it)
            }
            assertTrue(
                "The MediaCodec decoder was not initialized",
                decoderInitialized.await(5, TimeUnit.SECONDS)
            )
            assertTrue(
                "Analytics did not confirm a frame reached the SurfaceView output",
                firstFrameRendered.get()
            )
            assertTrue(
                "No actual video decoder name was reported",
                !decoderName.get().isNullOrBlank()
            )

            val metrics = VideoEnhancementMetrics.snapshot()
            assertTrue(
                "The production enhancement shader was never created",
                metrics.programCreations > initialProgramCreations
            )
            assertTrue(
                "The production shader never received a decoded frame",
                metrics.drawFrameCalls > 0
            )
            assertTrue(
                "The production shader did not complete a GL draw",
                metrics.drawFrameSuccesses > 0
            )
            assertTrue(
                "No decoded frame was counted as enhancement-processed",
                metrics.processedDraws > 0
            )
        } finally {
            runCatching {
                scenario.onActivity { player?.release() }
            }
            scenario.close()
            fixture.delete()
        }
    }

    private fun createAvcFixture(context: Context, outputFile: File) {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        val flexibleYuv = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                runCatching {
                    info.getCapabilitiesForType(mimeType).colorFormats.contains(flexibleYuv)
                }.getOrDefault(false)
        } ?: throw AssertionError("No AVC encoder advertising flexible YUV420 input is available")

        val width = 320
        val height = 240
        val frameRate = 15
        val frameCount = 30
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, flexibleYuv)
            setInteger(MediaFormat.KEY_BIT_RATE, 450_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createByCodecName(encoderInfo.name)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerTrack = -1
        var encoderStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true

            fun drainEncoderOutput(): Boolean {
                while (true) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder output format changed more than once" }
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val endOfStream =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                check(muxerStarted && muxerTrack >= 0) {
                                    "Encoder emitted media before its output format"
                                }
                                val encoded = requireNotNull(encoder.getOutputBuffer(outputIndex))
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerTrack, encoded, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (endOfStream) return true
                        }
                    }
                }
            }

            fun awaitInputBuffer(): Int {
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) return inputIndex
                    drainEncoderOutput()
                }
                throw AssertionError("AVC encoder did not provide an input buffer before timeout")
            }

            for (frameIndex in 0 until frameCount) {
                val inputIndex = awaitInputBuffer()
                val image = requireNotNull(encoder.getInputImage(inputIndex)) {
                    "AVC encoder did not expose its flexible YUV input image"
                }
                try {
                    fillYuvImage(image, width, height, frameIndex)
                } finally {
                    image.close()
                }
                val presentationTimeUs = frameIndex * 1_000_000L / frameRate
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    width * height * 3 / 2,
                    presentationTimeUs,
                    0
                )
                drainEncoderOutput()
            }

            val eosInputIndex = awaitInputBuffer()
            encoder.queueInputBuffer(
                eosInputIndex,
                0,
                0,
                frameCount * 1_000_000L / frameRate,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )

            val deadline = SystemClock.elapsedRealtime() + 15_000L
            var encoderEnded = false
            while (!encoderEnded && SystemClock.elapsedRealtime() < deadline) {
                encoderEnded = drainEncoderOutput()
            }
            check(encoderEnded) { "AVC encoder timed out before end-of-stream" }
            check(muxerStarted && outputFile.length() > 0L) {
                "AVC encoder produced no playable MP4 output"
            }
        } finally {
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun fillYuvImage(
        image: android.media.Image,
        width: Int,
        height: Int,
        frameIndex: Int
    ) {
        val planes = image.planes
        require(planes.size == 3) { "Expected three flexible YUV420 planes" }

        val y = planes[0]
        val yBuffer = y.buffer.duplicate()
        val yBase = yBuffer.position()
        for (row in 0 until height) {
            for (column in 0 until width) {
                val value = 48 + ((column * 2 + row * 3 + frameIndex * 5) % 160)
                yBuffer.put(yBase + row * y.rowStride + column * y.pixelStride, value.toByte())
            }
        }

        for (planeIndex in 1..2) {
            val plane = planes[planeIndex]
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val chromaValue = if (planeIndex == 1) 104 else 152
            for (row in 0 until height / 2) {
                for (column in 0 until width / 2) {
                    buffer.put(
                        base + row * plane.rowStride + column * plane.pixelStride,
                        (chromaValue + (frameIndex % 5)).toByte()
                    )
                }
            }
        }
    }
}
