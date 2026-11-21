package com.malekplay.player.effects

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-driver pixel tests for the production GLSL stage.
 *
 * The first pass verifies that a disabled stage is an identity transform,
 * including all four image quadrants. The second verifies that enabling detail
 * changes pixel values. This is intentionally an EGL/GLES instrumentation test,
 * not a JVM test or a counter-only assertion.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class VideoEnhancementPipelinePixelInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun passthroughPreservesTextureOrientationAndDetailStageChangesPixels() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue("No EGL display available", display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1))
        assertTrue("Could not bind OpenGL ES API", EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))

        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var eglContext = EGL14.EGL_NO_CONTEXT
        var inputTexture = 0
        var outputTexture = 0
        var framebuffer = 0
        var passthrough: BaseGlShaderProgram? = null
        try {
            val config = chooseConfig(display)
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0
            )
            assertTrue("Could not create pbuffer", surface != EGL14.EGL_NO_SURFACE)
            eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            assertTrue("Could not create GLES2 context", eglContext != EGL14.EGL_NO_CONTEXT)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, eglContext))

            val source = quadrantPattern()
            inputTexture = createTexture(source)
            outputTexture = createEmptyTexture()
            framebuffer = createFramebuffer(outputTexture)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, SIZE, SIZE)

            val mutableEffect = VideoEnhancementPipelineEffect()
            passthrough = mutableEffect
                .toGlShaderProgram(context, /* useHdr = */ false) as BaseGlShaderProgram
            listOf(8 to 8, 16 to 9, 9 to 16, 640 to 360, 1920 to 1080).forEach { (width, height) ->
                val configured = passthrough.configure(width, height)
                assertEquals(width, configured.width)
                assertEquals(height, configured.height)
            }
            // Restore the dimensions of the actual test texture before drawing.
            passthrough.configure(SIZE, SIZE)
            passthrough.drawFrame(inputTexture, 1L)
            GLES20.glFinish()
            val identityOutput = readPixels()
            assertTrue(
                "Disabled shader changed pixel data or flipped/rotated the texture",
                maxChannelDelta(source, identityOutput) <= 1
            )

            // Change the AtomicReference after program creation. The very same
            // GL program must begin changing output pixels without graph rebuild.
            mutableEffect.update(
                VideoEnhancementPipelineConfig(
                    detailEnabled = true,
                    detailStrength = 1.20f,
                    contrastBoost = 0.22f,
                    clarityStrength = 0.30f
                )
            )
            passthrough.drawFrame(inputTexture, 2L)
            GLES20.glFinish()
            val enhancedOutput = readPixels()
            assertTrue(
                "Runtime-enabled detail stage produced byte-identical pixels",
                maxChannelDelta(source, enhancedOutput) >= MIN_VISIBLE_CHANNEL_DELTA
            )

            // Turning the stages off on the same program must restore exact
            // pass-through output; stale preset strengths may not keep filtering.
            mutableEffect.update(VideoEnhancementPipelineConfig())
            passthrough.drawFrame(inputTexture, 3L)
            GLES20.glFinish()
            val disabledAgainOutput = readPixels()
            assertTrue(
                "Runtime disable did not restore identity output",
                maxChannelDelta(source, disabledAgainOutput) <= 1
            )

            // Exercise real texture allocation and rendering after input-size
            // changes, not just configure() return values. Portrait and
            // landscape transitions must preserve all four image quadrants.
            listOf(16 to 9, 9 to 16, 64 to 36).forEachIndexed { index, (width, height) ->
                val resizedSource = quadrantPattern(width, height)
                val resizedInput = createTexture(resizedSource, width, height)
                val resizedOutput = createEmptyTexture(width, height)
                val resizedFramebuffer = createFramebuffer(resizedOutput)
                try {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resizedFramebuffer)
                    GLES20.glViewport(0, 0, width, height)
                    val outputSize = passthrough.configure(width, height)
                    assertEquals(width, outputSize.width)
                    assertEquals(height, outputSize.height)
                    passthrough.drawFrame(resizedInput, 4L + index)
                    GLES20.glFinish()
                    assertTrue(
                        "Resized $width x $height frame changed orientation or pixel values",
                        maxChannelDelta(resizedSource, readPixels(width, height)) <= 1
                    )
                } finally {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glDeleteFramebuffers(1, intArrayOf(resizedFramebuffer), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(resizedInput), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(resizedOutput), 0)
                }
            }
            assertEquals("Unexpected GL error after pixel readback", GLES20.GL_NO_ERROR, GLES20.glGetError())
        } finally {
            runCatching { passthrough?.release() }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                if (inputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
                if (outputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroyContext(display, eglContext)
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglTerminate(display)
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0))
        assertTrue("No GLES2 pbuffer config", count[0] > 0)
        return requireNotNull(configs[0])
    }

    private fun quadrantPattern(width: Int = SIZE, height: Int = SIZE): ByteArray {
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = when {
                    x < width / 2 && y < height / 2 -> intArrayOf(230, 35, 25, 255)
                    x >= width / 2 && y < height / 2 -> intArrayOf(20, 210, 45, 255)
                    x < width / 2 -> intArrayOf(30, 55, 225, 255)
                    else -> intArrayOf(225, 210, 35, 255)
                }
                val offset = (y * width + x) * 4
                for (channel in 0..3) pixels[offset + channel] = color[channel].toByte()
            }
        }
        return pixels
    }

    private fun createTexture(pixels: ByteArray, width: Int = SIZE, height: Int = SIZE): Int {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        setTextureParameters()
        val pixelBuffer = ByteBuffer.allocateDirect(pixels.size)
        pixelBuffer.put(pixels).position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer
        )
        return texture[0]
    }

    private fun createEmptyTexture(width: Int = SIZE, height: Int = SIZE): Int {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        setTextureParameters()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        return texture[0]
    }

    private fun setTextureParameters() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun createFramebuffer(texture: Int): Int {
        val framebuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        assertEquals(
            "Offscreen framebuffer is incomplete",
            GLES20.GL_FRAMEBUFFER_COMPLETE,
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        )
        return framebuffer[0]
    }

    private fun readPixels(width: Int = SIZE, height: Int = SIZE): ByteArray {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(
            0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        val pixels = ByteArray(width * height * 4)
        buffer.position(0)
        buffer.get(pixels)
        return pixels
    }

    private fun maxChannelDelta(first: ByteArray, second: ByteArray): Int =
        first.indices.maxOf { index ->
            kotlin.math.abs((first[index].toInt() and 0xff) - (second[index].toInt() and 0xff))
        }

    private companion object {
        const val SIZE = 8
        const val MIN_VISIBLE_CHANNEL_DELTA = 2
    }
}
