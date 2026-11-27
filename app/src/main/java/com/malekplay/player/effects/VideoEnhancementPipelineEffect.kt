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
    val clarityStrength: Float = 0f,
    val vibranceStrength: Float = 0f,
    val bypassed: Boolean = false
) {
    // Toggle flags are authoritative: stale preset strengths must never keep
    // enhancement work running after every stage has been switched off.
    val hasActiveStage: Boolean
        get() = !bypassed && (denoiseEnabled || detailEnabled || aiBoostEnabled || vibranceStrength > 0f)
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
            clarityStrength = next.clarityStrength.coerceIn(0f, 0.30f),
            vibranceStrength = next.vibranceStrength.coerceIn(0f, 0.35f)
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
        // When both denoise and detail flags are false and only aiBoostEnabled is set
        // (standalone single-pass config), run both stages with AI boost.
        // When a specific stage flag is set (two-pass pipeline: denoise -> Lanczos -> detail),
        // strictly isolate each pass so detail never runs pre-upscale or denoise post-upscale.
        val standaloneAiOnly = settings.aiBoostEnabled && !settings.denoiseEnabled && !settings.detailEnabled
        val runDenoise = !settings.bypassed && (settings.denoiseEnabled || standaloneAiOnly)
        val runDetail = !settings.bypassed && (settings.detailEnabled || standaloneAiOnly)
        val vibrance = if (settings.bypassed) 0f else settings.vibranceStrength
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth, 1f / inputHeight)
            )
            program.setFloatUniform("uDenoiseEnabled", if (runDenoise) 1f else 0f)
            program.setFloatUniform("uDetailEnabled", if (runDetail) 1f else 0f)
            program.setFloatUniform("uDenoiseStrength", if (settings.bypassed) 0f else settings.denoiseStrength)
            program.setFloatUniform("uDetailStrength", if (settings.bypassed) 0f else settings.detailStrength)
            program.setFloatUniform("uContrastBoost", if (settings.bypassed) 0f else settings.contrastBoost)
            program.setFloatUniform("uClarityStrength", if (settings.bypassed) 0f else settings.clarityStrength)
            program.setFloatUniform("uVibranceStrength", vibrance)
            program.setFloatUniform("uAiBoost", if (!settings.bypassed && settings.aiBoostEnabled) 1f else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            VideoEnhancementMetrics.recordDrawFrameSucceeded()
            if (runDenoise || runDetail) {
                VideoEnhancementMetrics.recordPipelineProcessed(
                    denoise = runDenoise,
                    detail = runDetail,
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
            uniform float uVibranceStrength;
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
                if (uDenoiseEnabled < 0.5 && uDetailEnabled < 0.5 && uVibranceStrength <= 0.001) {
                    gl_FragColor = center;
                    return;
                }

                vec2 d = uTexelSize;
                vec3 source = center.rgb;
                // 8-neighbor samples serve both isotropic bilateral denoise and
                // isotropic detail/anti-ringing without directional aliasing.
                vec3 n  = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, d.y)).rgb;
                vec3 s  = texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, d.y)).rgb;
                vec3 e  = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, 0.0)).rgb;
                vec3 w  = texture2D(uTexSampler, vTexSamplingCoord - vec2(d.x, 0.0)).rgb;
                vec3 ne = texture2D(uTexSampler, vTexSamplingCoord + d).rgb;
                vec3 nw = texture2D(uTexSampler, vTexSamplingCoord + vec2(-d.x, d.y)).rgb;
                vec3 se = texture2D(uTexSampler, vTexSamplingCoord + vec2(d.x, -d.y)).rgb;
                vec3 sw = texture2D(uTexSampler, vTexSamplingCoord - d).rgb;
                vec3 base = source;

                if (uDenoiseEnabled > 0.5) {
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
                    // This lightly de-blocks 8x8/16x16 compression structure and 4:2:0
                    // chroma blotches without averaging across real edges.
                    float gx = abs(perceptualLuma(luma(e)) - perceptualLuma(luma(w)));
                    float gy = abs(perceptualLuma(luma(n)) - perceptualLuma(luma(s)));
                    float flatArea = 1.0 - smoothstep(0.012, 0.085, max(gx, gy));
                    float directionalBlock = smoothstep(0.006, 0.040, abs(gx - gy)) * flatArea;
                    vec3 crossAverage = (n + s + e + w) * 0.25;
                    bilateral = mix(bilateral, (bilateral + crossAverage) * 0.5,
                        directionalBlock * clamp(uDenoiseStrength * 0.22, 0.0, 0.12));

                    // Chroma-only flat-region smoothing to clean 4:2:0 color noise
                    // while preserving full luma sharpness.
                    float bilY = luma(bilateral);
                    float avgY = luma(crossAverage);
                    vec3 chromaSmooth = crossAverage + vec3(bilY - avgY);
                    bilateral = mix(bilateral, chromaSmooth,
                        flatArea * clamp(uDenoiseStrength * 0.28, 0.0, 0.15));

                    float relativeDenoiseDelta = length(source - bilateral) /
                        (1.0 + max(luma(source), 0.0));
                    float edgeProtection = smoothstep(0.018, 0.12, relativeDenoiseDelta);
                    float amount = clamp(uDenoiseStrength + uAiBoost * 0.10, 0.0, 0.58);
                    base = mix(source, bilateral, amount * (1.0 - edgeProtection * 0.58));
                }

                if (uDetailEnabled > 0.5) {
                    vec3 localAverage = (n + s + e + w) * 0.18 + (ne + nw + se + sw) * 0.07;
                    float y = max(luma(base), 0.0);
                    float localY = max(luma(localAverage), 0.0);
                    float perceptualY = perceptualLuma(y);
                    float perceptualLocalY = perceptualLuma(localY);

                    // Relative-luminance masks remain stable for SDR and HDR values
                    // above 1.0; no hard SDR white point is assumed.
                    float midtoneMask = smoothstep(0.018, 0.24, perceptualY) *
                        (1.0 - smoothstep(0.70, 0.94, perceptualY));
                    float shadowMask = 1.0 - smoothstep(0.025, 0.31, perceptualY);
                    // Preserve pure OLED blacks (0,0,0) while lifting shadowed detail.
                    float deepBlackGuard = smoothstep(0.004, 0.045, perceptualY);
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
                    float shadowRecovery = shadowMask * deepBlackGuard * 0.008 *
                        max(1.0, min(y, 3.0)) * (1.0 + ai * 0.25);
                    vec3 recovered = base * contrastFactor + vec3(shadowRecovery);
                    vec3 sharpened = recovered +
                        detail * (sharpness + clarity) * edgeGate;

                    // Anti-ringing envelope across the full 8-neighbor footprint.
                    vec3 axialMin = min(min(n, s), min(e, w));
                    vec3 diagMin = min(min(ne, nw), min(se, sw));
                    vec3 neighborhoodMin = min(base, min(axialMin, diagMin));
                    vec3 axialMax = max(max(n, s), max(e, w));
                    vec3 diagMax = max(max(ne, nw), max(se, sw));
                    vec3 neighborhoodMax = max(base, max(axialMax, diagMax));
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

                if (uVibranceStrength > 0.001) {
                    float maxC = max(base.r, max(base.g, base.b));
                    float minC = min(base.r, min(base.g, base.b));
                    float baseY = max(luma(base), 0.0);
                    float sat = (maxC - minC) / max(maxC, 0.0001);
                    float skinMask = smoothstep(0.025, 0.16, base.r - base.g) *
                        smoothstep(0.008, 0.11, base.g - base.b) *
                        (1.0 - smoothstep(0.20, 0.48, (maxC - minC) / (1.0 + baseY)));
                    float vibranceBoost = uVibranceStrength * (1.0 - sat) *
                        smoothstep(0.02, 0.15, sat) * (1.0 - clamp(skinMask * 0.75, 0.0, 0.75));
                    base = max(mix(vec3(baseY), base, 1.0 + vibranceBoost), vec3(0.0));
                }

                // Do not upper-clamp RGB: HDR highlights and wide-gamut headroom
                // must survive the enhancement pass unchanged when not sharpened.
                gl_FragColor = vec4(max(base, vec3(0.0)), center.a);
            }
        """
    }
}
