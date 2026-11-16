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
    private val strength: Float = 0.34f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            SpatialNoiseReductionProgram(context, useHdr, strength.coerceIn(0.12f, 0.52f))
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("spatial_denoise_init", error)
            throw error
        }
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
            VideoEnhancementMetrics.recordProcessed("spatial_denoise", presentationTimeUs)
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("spatial_denoise", error)
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
        // Always release the owned GL program, even if the base output-surface
        // cleanup throws while the renderer is being torn down.
        try {
            super.release()
        } finally {
            program.delete()
        }
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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            varying vec2 vTexSamplingCoord;

            float luma(vec3 color) {
                return dot(color, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
                vec2 d = uTexelSize;
                vec3 n  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, d.y)).rgb;
                vec3 s  = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, d.y)).rgb;
                vec3 e  = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, 0.0)).rgb;
                vec3 w  = texture2D(uTexSampler, vTexSamplingCoord - vec2(d.x, 0.0)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexSamplingCoord + d).rgb;
                vec3 nw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-d.x, d.y)).rgb;
                vec3 se = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, -d.y)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexSamplingCoord - d).rgb;

                // 9-tap bilateral neighborhood. Luma and chroma distances both
                // protect edges, while diagonals suppress block and mosquito noise.
                float centerLuma = luma(center.rgb);
                float rangeScale = mix(12.0, 24.0, uStrength);
                float wN  = exp(-(abs(luma(n)  - centerLuma) * 0.60 + length(n  - center.rgb) * 0.40) * rangeScale);
                float wS  = exp(-(abs(luma(s)  - centerLuma) * 0.60 + length(s  - center.rgb) * 0.40) * rangeScale);
                float wE  = exp(-(abs(luma(e)  - centerLuma) * 0.60 + length(e  - center.rgb) * 0.40) * rangeScale);
                float wW  = exp(-(abs(luma(w)  - centerLuma) * 0.60 + length(w  - center.rgb) * 0.40) * rangeScale);
                float wNE = exp(-(abs(luma(ne) - centerLuma) * 0.60 + length(ne - center.rgb) * 0.40) * rangeScale) * 0.7071;
                float wNW = exp(-(abs(luma(nw) - centerLuma) * 0.60 + length(nw - center.rgb) * 0.40) * rangeScale) * 0.7071;
                float wSE = exp(-(abs(luma(se) - centerLuma) * 0.60 + length(se - center.rgb) * 0.40) * rangeScale) * 0.7071;
                float wSW = exp(-(abs(luma(sw) - centerLuma) * 0.60 + length(sw - center.rgb) * 0.40) * rangeScale) * 0.7071;

                vec3 weighted = center.rgb + n*wN + s*wS + e*wE + w*wW +
                    ne*wNE + nw*wNW + se*wSE + sw*wSW;
                float weightSum = 1.0 + wN + wS + wE + wW + wNE + wNW + wSE + wSW;
                vec3 filtered = weighted / max(weightSum, 0.0001);

                // Adaptive blend: stronger on flat/noisy areas, reduced near edges.
                float localVariation = max(max(wN, wS), max(wE, wW));
                float edgeProtection = smoothstep(0.12, 0.72, length(center.rgb - filtered));
                float amount = clamp(uStrength, 0.10, 0.52) * (1.0 - edgeProtection * 0.55);
                gl_FragColor = vec4(mix(center.rgb, filtered, amount), center.a);
            }
        """
    }
}

/**
 * Edge-aware unsharp masking applied after resampling/denoising.
 * It restores local edge contrast lost during scaling and compression cleanup;
 * it does not invent detail that is absent from the source.
 */
@UnstableApi
class VideoDetailEnhancementEffect(
    private val strength: Float = 0.78f
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            VideoDetailEnhancementProgram(context, useHdr, strength.coerceIn(0.25f, 1.15f))
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("detail_sharpen_init", error)
            throw error
        }
}

@UnstableApi
private class VideoDetailEnhancementProgram(
    context: Context,
    useHdr: Boolean,
    strength: Float
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX_SHADER, SHARPEN_FRAGMENT_SHADER)
    private var inputWidthForShader = 1
    private var inputHeightForShader = 1

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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inputWidthForShader = inputWidth
        inputHeightForShader = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(
                    1f / inputWidthForShader.coerceAtLeast(1),
                    1f / inputHeightForShader.coerceAtLeast(1)
                )
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            VideoEnhancementMetrics.recordProcessed("detail_sharpen", presentationTimeUs)
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("detail_sharpen", error)
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        // Always release the owned GL program, even if the base output-surface
        // cleanup throws while the renderer is being torn down.
        try {
            super.release()
        } finally {
            program.delete()
        }
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
        const val SHARPEN_FRAGMENT_SHADER = """
            #version 100
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 north = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 south = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
                vec3 east = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 west = texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb;
                vec3 localAverage = (north + south + east + west) * 0.25;
                vec3 detail = center.rgb - localAverage;
                float edgeMagnitude = length(detail);
                float edgeGate = smoothstep(0.006, 0.055, edgeMagnitude);
                vec3 sharpened = center.rgb + detail * uStrength * edgeGate;
                // Constrain sharpening to the local 5-tap neighborhood. This limits
                // bright/dark halos around subtitles and high-contrast edges after
                // Lanczos upscaling without clipping legitimate HDR values.
                vec3 localMin = min(center.rgb, min(min(north, south), min(east, west)));
                vec3 localMax = max(center.rgb, max(max(north, south), max(east, west)));
                vec3 enhanced = clamp(sharpened, localMin, localMax);
                // Preserve HDR values above 1.0; only prevent invalid negative light.
                gl_FragColor = vec4(max(enhanced, vec3(0.0)), center.a);
            }
        """
    }
}
