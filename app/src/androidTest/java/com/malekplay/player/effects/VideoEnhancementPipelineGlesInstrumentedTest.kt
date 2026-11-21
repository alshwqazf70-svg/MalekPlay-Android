package com.malekplay.player.effects

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiles and links the production enhancement shaders against the device's
 * actual GLES 2 driver, then releases the Media3 program while its EGL context
 * is current. This catches driver-specific GLSL and GL-lifecycle failures that
 * local JVM tests cannot observe. It does not claim to validate pixel output.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class VideoEnhancementPipelineGlesInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun productionShaderCompilesLinksAndReleasesOnDeviceGles2Driver() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue("No EGL display available", display != EGL14.EGL_NO_DISPLAY)

        val eglVersions = IntArray(2)
        assertTrue(
            "EGL initialization failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
            EGL14.eglInitialize(display, eglVersions, 0, eglVersions, 1)
        )
        assertTrue("Could not bind OpenGL ES API", EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))

        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var eglContext = EGL14.EGL_NO_CONTEXT
        try {
            val config = choosePbufferGles2Config(display)
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE),
                0
            )
            assertTrue("Could not create EGL pbuffer", surface != EGL14.EGL_NO_SURFACE)

            eglContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            assertTrue("Could not create GLES 2 context", eglContext != EGL14.EGL_NO_CONTEXT)
            assertTrue(
                "Could not make GLES context current",
                EGL14.eglMakeCurrent(display, surface, surface, eglContext)
            )

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val version = GLES20.glGetString(GLES20.GL_VERSION)
            assertNotNull("GLES renderer string is unavailable", renderer)
            assertNotNull("GLES version string is unavailable", version)

            val shaderProgram = VideoEnhancementPipelineEffect()
                .toGlShaderProgram(context, /* useHdr = */ false)
            shaderProgram.release()

            assertEquals(
                "GL error after shader creation/release on $renderer ($version)",
                GLES20.GL_NO_ERROR,
                GLES20.glGetError()
            )
        } finally {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroyContext(display, eglContext)
            }
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
            }
            EGL14.eglTerminate(display)
        }
    }

    private fun choosePbufferGles2Config(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            "No EGL config supports GLES2 + pbuffer",
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)
        )
        assertTrue("No matching EGL config returned", count[0] > 0)
        return requireNotNull(configs[0])
    }
}
