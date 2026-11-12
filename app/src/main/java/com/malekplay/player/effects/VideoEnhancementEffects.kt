package com.malekplay.player.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Low-strength edge-aware spatial denoising. It smooths small pixel variations while
 * reducing the blend around strong edges to avoid turning the whole picture into a blur.
 */
@UnstableApi
class SpatialNoiseReductionEffect(
    private val strength: Float = 0.22f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SpatialNoiseReductionProgram(context, useHdr, strength.coerceIn(0.05f, 0.35f))
}

@UnstableApi
private class SpatialNoiseReductionProgram(
    context: Context,
    useHdr: Boolean,
    strength: Float
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX_SHADER, DENOISE_FRAGMENT_SHADER)

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
        program.setFloatUniform("uStrength", strength)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexelSize", floatArrayOf(
                1f / inputWidthForShader.coerceAtLeast(1),
                1f / inputHeightForShader.coerceAtLeast(1)
            ))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    private var inputWidthForShader = 1
    private var inputHeightForShader = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputWidthForShader = inputWidth
        inputHeightForShader = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun release() {
        super.release()
        program.delete()
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = uTransformationMatrix * aFramePosition;
                vec4 p = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0);
                vTexSamplingCoord = (uTexTransformationMatrix * p).xy;
            }
        """
        const val DENOISE_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 n1 = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 n2 = texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb;
                vec3 n3 = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 n4 = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
                vec3 averageColor = (n1 + n2 + n3 + n4) * 0.25;
                float edge = length(averageColor - c.rgb);
                float edgeProtection = 1.0 - smoothstep(0.035, 0.18, edge);
                float amount = uStrength * edgeProtection;
                gl_FragColor = vec4(mix(c.rgb, averageColor, amount), c.a);
            }
        """
    }
}

/**
 * A deliberately subtle temporal frame blend for older GLES devices.
 *
 * This is not optical-flow frame interpolation: it blends a small amount of the
 * preceding frame into the current frame to soften cadence judder. The low blend
 * weight limits, but cannot eliminate, motion ghosting.
 */
@UnstableApi
class TemporalMotionSmoothingEffect(
    private val previousFrameWeight: Float = 0.07f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TemporalMotionSmoothingProgram(
            context, useHdr, previousFrameWeight.coerceIn(0.03f, 0.10f), useHdr
        )
}

@UnstableApi
private class TemporalMotionSmoothingProgram(
    context: Context,
    useHdr: Boolean,
    private val previousFrameWeight: Float,
    private val useHighPrecisionColorComponents: Boolean
) : BaseGlShaderProgram(useHdr, 1) {
    private val blendProgram = GlProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)
    private val copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
    private var historyTextureId = 0
    private var historyFramebufferId = 0
    private var historyWidth = 0
    private var historyHeight = 0
    private var hasPreviousFrame = false
    private var lastPresentationTimeUs = Long.MIN_VALUE

    init {
        listOf(blendProgram, copyProgram).forEach { shader ->
            shader.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            shader.setFloatsUniform("uTransformationMatrix", identity)
            shader.setFloatsUniform("uTexTransformationMatrix", identity)
        }
        blendProgram.setFloatUniform("uPreviousWeight", previousFrameWeight)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (historyWidth != inputWidth || historyHeight != inputHeight || historyTextureId == 0) {
            releaseHistory()
            historyTextureId = GlUtil.createTexture(
                inputWidth, inputHeight, useHighPrecisionColorComponents
            )
            historyFramebufferId = GlUtil.createFboForTexture(historyTextureId)
            historyWidth = inputWidth
            historyHeight = inputHeight
            hasPreviousFrame = false
            lastPresentationTimeUs = Long.MIN_VALUE
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // Do not blend across a seek, timestamp reset, or a long decoder stall.
            if (lastPresentationTimeUs == Long.MIN_VALUE ||
                presentationTimeUs <= lastPresentationTimeUs ||
                presentationTimeUs - lastPresentationTimeUs > 150_000L
            ) {
                hasPreviousFrame = false
            }
            blendProgram.use()
            blendProgram.setSamplerTexIdUniform("uCurrentFrame", inputTexId, 0)
            blendProgram.setSamplerTexIdUniform("uPreviousFrame", historyTextureId, 1)
            blendProgram.setIntUniform("uHasPreviousFrame", if (hasPreviousFrame) 1 else 0)
            blendProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()

            // Preserve the output framebuffer/viewport selected by BaseGlShaderProgram.
            val previousFramebuffer = IntArray(1)
            val previousViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

            GlUtil.focusFramebufferUsingCurrentContext(historyFramebufferId, historyWidth, historyHeight)
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
            GLES20.glViewport(
                previousViewport[0], previousViewport[1],
                previousViewport[2], previousViewport[3]
            )
            GlUtil.checkGlError()
            hasPreviousFrame = true
            lastPresentationTimeUs = presentationTimeUs
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun flush() {
        super.flush()
        hasPreviousFrame = false
        lastPresentationTimeUs = Long.MIN_VALUE
    }

    override fun release() {
        super.release()
        releaseHistory()
        blendProgram.delete()
        copyProgram.delete()
    }

    private fun releaseHistory() {
        if (historyFramebufferId != 0) {
            GlUtil.deleteFbo(historyFramebufferId)
            historyFramebufferId = 0
        }
        if (historyTextureId != 0) {
            GlUtil.deleteTexture(historyTextureId)
            historyTextureId = 0
        }
        historyWidth = 0
        historyHeight = 0
        hasPreviousFrame = false
        lastPresentationTimeUs = Long.MIN_VALUE
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = uTransformationMatrix * aFramePosition;
                vec4 p = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0);
                vTexSamplingCoord = (uTexTransformationMatrix * p).xy;
            }
        """
        const val COPY_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }
        """
        const val BLEND_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uCurrentFrame;
            uniform sampler2D uPreviousFrame;
            uniform int uHasPreviousFrame;
            uniform float uPreviousWeight;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 current = texture2D(uCurrentFrame, vTexSamplingCoord);
                if (uHasPreviousFrame == 0) {
                    gl_FragColor = current;
                } else {
                    vec4 previous = texture2D(uPreviousFrame, vTexSamplingCoord);
                    gl_FragColor = mix(current, previous, uPreviousWeight);
                }
            }
        """
    }
}
