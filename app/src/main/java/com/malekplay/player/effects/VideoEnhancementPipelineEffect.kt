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
    // Toggle flags are authoritative: stale preset strengths must never keep
    // enhancement work running after every stage has been switched off.
    val hasActiveStage: Boolean
        get() = denoiseEnabled || detailEnabled || aiBoostEnabled
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
            VideoEnhancementMetrics.recordProgramCreated()
        } catch (error: Exception) {
            runCatching { program.delete() }
            throw error
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth.coerceAtLeast(1)
        this.inputHeight = inputHeight.coerceAtLeast(1)
        VideoEnhancementMetrics.recordConfigured(this.inputWidth, this.inputHeight)
        return Size(this.inputWidth, this.inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        VideoEnhancementMetrics.recordDrawFrameEntered()
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
            VideoEnhancementMetrics.recordDrawFrameSucceeded()
            if (settings.hasActiveStage) {
                VideoEnhancementMetrics.recordPipelineProcessed(
                    denoise = settings.denoiseEnabled || settings.aiBoostEnabled,
                    detail = settings.detailEnabled || settings.aiBoostEnabled,
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

            // Compress luminance only for edge masks and filter weights. The
            // actual RGB signal is never clamped to 0..1, preserving HDR headroom.
            float perceptualLuma(float value) {
                float positive = max(value, 0.0);
                return positive / (1.0 + positive);
            }

            float bilateralWeight(vec3 sampleColor, vec3 centerColor, float rangeScale) {
                float sampleY = max(luma(sampleColor), 0.0);
                float centerY = max(luma(centerColor), 0.0);
                float yDelta = abs(perceptualLuma(sampleY) - perceptualLuma(centerY));
                float hdrScale = 1.0 / (1.0 + max(sampleY, centerY));
                float colorDelta = length((sampleColor - centerColor) * hdrScale);
                return exp(-(yDelta * 0.72 + colorDelta * 0.28) * rangeScale);
            }

            void main() {
                vec4 center = texture2D(uTexSampler, vTexSamplingCoord);

                // One texture fetch only while all user-controlled stages are off.
                if (uDenoiseEnabled < 0.5 && uDetailEnabled < 0.5) {
                    gl_FragColor = center;
                    return;
                }

                vec2 d = uTexelSize;
                vec3 source = center.rgb;
                // Four axial samples serve both the bilateral filter and the
                // local-contrast/sharpening stage; diagonals are fetched only for denoise.
                vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, d.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, d.y)).rgb;
                vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, vTexSamplingCoord - vec2(d.x, 0.0)).rgb;
                vec3 base = source;

                if (uDenoiseEnabled > 0.5) {
                    vec3 ne = texture2D(uTexSampler, vTexSamplingCoord + d).rgb;
                    vec3 nw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-d.x, d.y)).rgb;
                    vec3 se = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, -d.y)).rgb;
                    vec3 sw = texture2D(uTexSampler, vTexSamplingCoord - d).rgb;

                    float rangeScale = mix(12.0, 26.0,
                        clamp(uDenoiseStrength + uAiBoost * 0.12, 0.0, 1.0));
                    float wn = bilateralWeight(n, source, rangeScale);
                    float ws = bilateralWeight(s, source, rangeScale);
                    float we = bilateralWeight(e, source, rangeScale);
                    float ww = bilateralWeight(w, source, rangeScale);
                    float wne = bilateralWeight(ne, source, rangeScale) * 0.7071;
                    float wnw = bilateralWeight(nw, source, rangeScale) * 0.7071;
                    float wse = bilateralWeight(se, source, rangeScale) * 0.7071;
                    float wsw = bilateralWeight(sw, source, rangeScale) * 0.7071;

                    vec3 sum = source + n*wn + s*ws + e*we + w*ww +
                        ne*wne + nw*wnw + se*wse + sw*wsw;
                    float totalWeight = 1.0 + wn + ws + we + ww + wne + wnw + wse + wsw;
                    vec3 bilateral = sum / max(totalWeight, 0.0001);

                    // Detect low-frequency block boundaries in otherwise flat regions.
                    // This lightly de-blocks 8x8/16x16 compression structure without
                    // averaging across real edges or erasing fine image texture.
                    float gx = abs(perceptualLuma(luma(e)) - perceptualLuma(luma(w)));
                    float gy = abs(perceptualLuma(luma(n)) - perceptualLuma(luma(s)));
                    float flatArea = 1.0 - smoothstep(0.012, 0.085, max(gx, gy));
                    float directionalBlock = smoothstep(0.006, 0.040, abs(gx - gy)) * flatArea;
                    vec3 crossAverage = (n + s + e + w) * 0.25;
                    bilateral = mix(bilateral, (bilateral + crossAverage) * 0.5,
                        directionalBlock * clamp(uDenoiseStrength * 0.22, 0.0, 0.12));

                    float relativeDenoiseDelta = length(source - bilateral) /
                        (1.0 + max(luma(source), 0.0));
                    float edgeProtection = smoothstep(0.018, 0.12, relativeDenoiseDelta);
                    float amount = clamp(uDenoiseStrength + uAiBoost * 0.10, 0.0, 0.58);
                    base = mix(source, bilateral, amount * (1.0 - edgeProtection * 0.58));
                }

                if (uDetailEnabled > 0.5) {
                    vec3 localAverage = (n + s + e + w) * 0.25;
                    float y = max(luma(base), 0.0);
                    float localY = max(luma(localAverage), 0.0);
                    float perceptualY = perceptualLuma(y);
                    float perceptualLocalY = perceptualLuma(localY);

                    // Relative-luminance masks remain stable for SDR and HDR values
                    // above 1.0; no hard SDR white point is assumed.
                    float midtoneMask = smoothstep(0.018, 0.24, perceptualY) *
                        (1.0 - smoothstep(0.70, 0.94, perceptualY));
                    float shadowMask = 1.0 - smoothstep(0.025, 0.31, perceptualY);
                    float localContrast = clamp(
                        (y - localY) / max(y + localY, 0.06),
                        -0.55,
                        0.55
                    );
                    float contrastFactor = 1.0 +
                        localContrast * uContrastBoost * midtoneMask * 1.65;

                    vec3 detail = base - localAverage;
                    float relativeEdge = length(detail) / (1.0 + y);
                    float edgeGate = smoothstep(0.0025, 0.038, relativeEdge);
                    float ai = uAiBoost;

                    // CAS-style adaptive gain rises on low-contrast texture but
                    // tapers at strong edges to prevent bright/dark ringing.
                    float localRange = max(
                        abs(perceptualY - perceptualLocalY),
                        0.001
                    );
                    float adaptiveGain = 1.0 + 0.32 *
                        (1.0 - smoothstep(0.025, 0.22, localRange));
                    float edgeCeiling = 1.0 - 0.38 *
                        smoothstep(0.12, 0.42, localRange);
                    float chroma = max(base.r, max(base.g, base.b)) -
                        min(base.r, min(base.g, base.b));
                    float skinLike = smoothstep(0.025, 0.16, base.r - base.g) *
                        smoothstep(0.008, 0.11, base.g - base.b) *
                        (1.0 - smoothstep(0.20, 0.48, chroma / (1.0 + y)));
                    float skinProtection = clamp(skinLike * 0.42, 0.0, 0.42);

                    float sharpness = uDetailStrength * (1.0 + ai * 0.12) *
                        adaptiveGain * edgeCeiling * (1.0 - skinProtection);
                    float clarity = uClarityStrength * (1.0 + ai * 0.24) *
                        (1.0 - skinProtection * 0.55);
                    float shadowRecovery = shadowMask * 0.006 *
                        max(1.0, min(y, 3.0)) * (1.0 + ai * 0.25);
                    vec3 recovered = base * contrastFactor + vec3(shadowRecovery);
                    vec3 sharpened = recovered +
                        detail * (sharpness + clarity) * edgeGate;

                    // Anti-ringing envelope: constrain overshoot to a small,
                    // signal-relative margin around the 4-neighbour colour range.
                    vec3 neighborhoodMin = min(base, min(min(n, s), min(e, w)));
                    vec3 neighborhoodMax = max(base, max(max(n, s), max(e, w)));
                    vec3 neighborhoodRange = max(
                        neighborhoodMax - neighborhoodMin,
                        max(abs(base) * 0.008, vec3(0.0005))
                    );
                    vec3 safeLow = neighborhoodMin - neighborhoodRange * 0.045;
                    vec3 safeHigh = neighborhoodMax + neighborhoodRange * 0.045;
                    vec3 bounded = clamp(sharpened, safeLow, safeHigh);
                    vec3 haloLimit = max(abs(base) * 0.105, vec3(0.004));
                    vec3 delta = clamp(bounded - base, -haloLimit, haloLimit);
                    base = max(base + delta, vec3(0.0));
                }

                // Do not upper-clamp RGB: HDR highlights and wide-gamut headroom
                // must survive the enhancement pass unchanged when not sharpened.
                gl_FragColor = vec4(max(base, vec3(0.0)), center.a);
            }
        """
    }
}
