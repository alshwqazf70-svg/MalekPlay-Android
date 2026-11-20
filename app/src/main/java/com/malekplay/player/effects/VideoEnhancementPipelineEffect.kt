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
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable, single-pass enhancement stage kept in Media3's effect graph for the
 * complete player lifetime. Updating the AtomicReference changes the next frame
 * without replacing the GlEffect or rebuilding VideoFrameProcessor.
 */
data class VideoEnhancementPipelineConfig(
    val denoiseEnabled: Boolean = false,
    val detailEnabled: Boolean = false,
    val aiBoostEnabled: Boolean = false,
    val denoiseStrength: Float = 0f,
    val detailStrength: Float = 0f,
    val contrastBoost: Float = 0f,
    val clarityStrength: Float = 0f
) {
    val hasActiveStage: Boolean
        get() = denoiseEnabled || detailEnabled || aiBoostEnabled ||
            contrastBoost > 0f || clarityStrength > 0f
}

@UnstableApi
class VideoEnhancementPipelineEffect(
    initialConfig: VideoEnhancementPipelineConfig = VideoEnhancementPipelineConfig()
) : GlEffect {
    private val config = AtomicReference(initialConfig)

    fun update(next: VideoEnhancementPipelineConfig) {
        config.set(next.copy(
            denoiseStrength = next.denoiseStrength.coerceIn(0f, 0.60f),
            detailStrength = next.detailStrength.coerceIn(0f, 1.20f),
            contrastBoost = next.contrastBoost.coerceIn(0f, 0.22f),
            clarityStrength = next.clarityStrength.coerceIn(0f, 0.30f)
        ))
    }

    fun currentConfig(): VideoEnhancementPipelineConfig = config.get()

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            VideoEnhancementPipelineProgram(useHdr, config)
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("enhancement_pipeline_init", error)
            throw error
        }
}

@UnstableApi
private class VideoEnhancementPipelineProgram(
    useHdr: Boolean,
    private val config: AtomicReference<VideoEnhancementPipelineConfig>
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    private var inputWidth = 1
    private var inputHeight = 1

    init {
        try {
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            program.setFloatsUniform("uTransformationMatrix", identity)
            program.setFloatsUniform("uTexTransformationMatrix", identity)
        } catch (error: Exception) {
            runCatching { program.delete() }
            throw error
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth.coerceAtLeast(1)
        this.inputHeight = inputHeight.coerceAtLeast(1)
        return Size(this.inputWidth, this.inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val settings = config.get()
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth, 1f / inputHeight)
            )
            program.setFloatUniform("uDenoiseEnabled", if (settings.denoiseEnabled || settings.aiBoostEnabled) 1f else 0f)
            program.setFloatUniform("uDetailEnabled", if (settings.detailEnabled || settings.aiBoostEnabled) 1f else 0f)
            program.setFloatUniform("uDenoiseStrength", settings.denoiseStrength)
            program.setFloatUniform("uDetailStrength", settings.detailStrength)
            program.setFloatUniform("uContrastBoost", settings.contrastBoost)
            program.setFloatUniform("uClarityStrength", settings.clarityStrength)
            program.setFloatUniform("uAiBoost", if (settings.aiBoostEnabled) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            if (settings.hasActiveStage) {
                VideoEnhancementMetrics.recordPipelineProcessed(
                    denoise = settings.denoiseEnabled || settings.aiBoostEnabled,
                    detail = settings.detailEnabled || settings.aiBoostEnabled ||
                        settings.contrastBoost > 0f || settings.clarityStrength > 0f,
                    presentationTimeUs = presentationTimeUs
                )
            }
        } catch (error: Exception) {
            VideoEnhancementMetrics.recordFailure("enhancement_pipeline", error)
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
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

        const val FRAGMENT_SHADER = """
            #version 100
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uDenoiseEnabled;
            uniform float uDetailEnabled;
            uniform float uDenoiseStrength;
            uniform float uDetailStrength;
            uniform float uContrastBoost;
            uniform float uClarityStrength;
            uniform float uAiBoost;
            varying vec2 vTexSamplingCoord;

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
                // A true one-sample passthrough is used while all enhancement stages
                // are disabled, including while Google's surface bridge owns output.
                if (uDenoiseEnabled < 0.5 && uDetailEnabled < 0.5 &&
                    uContrastBoost <= 0.0 && uClarityStrength <= 0.0) {
                    gl_FragColor = center;
                    return;
                }

                vec2 d = uTexelSize;
                vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, d.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, d.y)).rgb;
                vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, vTexSamplingCoord - vec2(d.x, 0.0)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexSamplingCoord + d).rgb;
                vec3 nw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-d.x, d.y)).rgb;
                vec3 se = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, -d.y)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexSamplingCoord - d).rgb;

                vec3 source = center.rgb;
                float centerLuma = luma(source);
                float rangeScale = mix(14.0, 27.0, clamp(uDenoiseStrength + uAiBoost * 0.12, 0.0, 1.0));
                float wn = exp(-(abs(luma(n) - centerLuma) * 0.60 + length(n - source) * 0.40) * rangeScale);
                float ws = exp(-(abs(luma(s) - centerLuma) * 0.60 + length(s - source) * 0.40) * rangeScale);
                float we = exp(-(abs(luma(e) - centerLuma) * 0.60 + length(e - source) * 0.40) * rangeScale);
                float ww = exp(-(abs(luma(w) - centerLuma) * 0.60 + length(w - source) * 0.40) * rangeScale);
                float wne = exp(-(abs(luma(ne) - centerLuma) * 0.60 + length(ne - source) * 0.40) * rangeScale) * 0.7071;
                float wnw = exp(-(abs(luma(nw) - centerLuma) * 0.60 + length(nw - source) * 0.40) * rangeScale) * 0.7071;
                float wse = exp(-(abs(luma(se) - centerLuma) * 0.60 + length(se - source) * 0.40) * rangeScale) * 0.7071;
                float wsw = exp(-(abs(luma(sw) - centerLuma) * 0.60 + length(sw - source) * 0.40) * rangeScale) * 0.7071;
                vec3 sum = source + n*wn + s*ws + e*we + w*ww +
                    ne*wne + nw*wnw + se*wse + sw*wsw;
                float weight = 1.0 + wn + ws + we + ww + wne + wnw + wse + wsw;
                vec3 filtered = sum / max(weight, 0.0001);
                float edgeProtection = smoothstep(0.035, 0.18, length(source - filtered));
                float denoiseAmount = uDenoiseEnabled * clamp(uDenoiseStrength + uAiBoost * 0.10, 0.0, 0.58);
                vec3 base = mix(source, filtered, denoiseAmount * (1.0 - edgeProtection * 0.58));

                float localLuma = luma((n + s + e + w) * 0.25);
                float baseLuma = luma(base);
                float midtoneMask = smoothstep(0.025, 0.18, baseLuma) *
                    (1.0 - smoothstep(0.78, 0.98, baseLuma));
                float localContrast = clamp(baseLuma - localLuma, -0.16, 0.16);
                float contrastFactor = 1.0 + localContrast * uContrastBoost * midtoneMask;
                vec3 detailVector = base - (n + s + e + w) * 0.25;
                float edgeGate = smoothstep(0.006, 0.055, length(detailVector));
                float detailAmount = uDetailEnabled * (uDetailStrength + uAiBoost * 0.16);
                vec3 clarity = detailVector * uClarityStrength * edgeGate;
                vec3 sharpened = base * contrastFactor +
                    detailVector * detailAmount * edgeGate + clarity;
                vec3 haloLimit = max(abs(base) * 0.12, vec3(0.025));
                vec3 result = clamp(sharpened, base - haloLimit, base + haloLimit);
                gl_FragColor = vec4(max(result, vec3(0.0)), center.a);
            }
        """
    }
}
