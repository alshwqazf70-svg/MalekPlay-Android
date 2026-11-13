package com.malekplay.player

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
 * Lightweight edge-preserving spatial denoise. It samples the centre pixel and
 * four neighbours; it does not generate frames or retain temporal frame history.
 */
@UnstableApi
class BilateralDenoiseEffect(
    private val strength: Float = DEFAULT_STRENGTH
) : GlEffect {
    init {
        require(strength.isFinite() && strength in 0f..0.65f)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BilateralDenoiseShaderProgram(context, useHdr, strength)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        strength <= 0f || inputWidth <= 0 || inputHeight <= 0

    companion object {
        const val DEFAULT_STRENGTH = 0.32f
    }
}

@UnstableApi
private class BilateralDenoiseShaderProgram(
    context: Context,
    useHdr: Boolean,
    strength: Float
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = try {
        GlProgram(
            context,
            "shaders/vertex_shader_transformation_es2.glsl",
            "shaders/fragment_shader_bilateral_denoise_es2.glsl"
        )
    } catch (error: Exception) {
        throw VideoFrameProcessingException(error)
    }

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
        program.setFloatsUniform(
            "uTexelSize",
            floatArrayOf(1f / inputWidth.coerceAtLeast(1), 1f / inputHeight.coerceAtLeast(1))
        )
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        runCatching { program.delete() }
    }
}
