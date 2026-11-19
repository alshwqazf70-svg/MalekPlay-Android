package io.github.peerless2012.ass.media.render

import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.executor.AssExecutor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class AssTexOverlay(private val handler: AssHandler, private val render: AssRender) : TextureOverlay() {

    private val vertexShaderCode = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord.xy;
            }
        """.trimIndent()

    // alpha
    private val fragmentShaderCode = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            void main() {
                float alpha = texture2D(u_Texture, v_TexCoord).a;
                gl_FragColor = u_Color * alpha;

            }
        """.trimIndent()

    private val rectangleCoords = floatArrayOf(
        -1f,  1f,  // Top left
        1f,  1f,  // Top right
        -1f, -1f,  // Bottom left
        1f, -1f   // Bottom right
    )

    private val textureCoords = floatArrayOf(
        0f, 0f,  // Top left
        1f, 0f,  // Top right
        0f, 1f,  // Bottom left
        1f, 1f   // Bottom right
    )

    private val preFbo = IntArray(1)

    private val preViewPort = IntArray(4)

    private val preTex = IntArray(1)

    private val preAlign = IntArray(1)

    private val preBoundTexture = IntArray(1)

    private val preProgram = IntArray(1)

    private val preArrayBuffer = IntArray(1)

    private val preBlendFunc = IntArray(4)

    private var texId = 0

    /** One upload texture reused for every libass bitmap on this GL context. */
    private var scratchTextureId = 0
    private var scratchTextureWidth = 0
    private var scratchTextureHeight = 0

    private var texDirty = true

    private var texSize = Size.ZERO

    private var fboId = 0

    private lateinit var glProgram: GlProgram

    private var vertexBufferId = 0

    private var texCoordBufferId = 0

    private lateinit var executor: AssExecutor

    override fun getTextureId(presentationTimeUs: Long): Int {
        if (!::executor.isInitialized || !::glProgram.isInitialized || texId == 0 || fboId == 0) {
            return texId
        }
        val timeUs = if (handler.videoTime >= 0) handler.videoTime else presentationTimeUs
        val assFrame = executor.renderFrame(timeUs, AssTexType.BITMAP_ALPHA)

        if (assFrame != null && assFrame.changed == 0) return texId
        if (assFrame == null && !texDirty) return texId

        val blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, preFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, preViewPort, 0)
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, preTex, 0)
        GLES20.glGetIntegerv(GLES20.GL_UNPACK_ALIGNMENT, preAlign, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, preProgram, 0)
        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, preArrayBuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_RGB, preBlendFunc, 0)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_RGB, preBlendFunc, 1)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_SRC_ALPHA, preBlendFunc, 2)
        GLES20.glGetIntegerv(GLES20.GL_BLEND_DST_ALPHA, preBlendFunc, 3)

        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, preBoundTexture, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texId,
                0
            )
            GlUtil.checkGlError()
            GlUtil.clearFocusedBuffers()
            texDirty = false

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            assFrame?.images?.let { frames ->
                texDirty = true
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                glProgram.use()
                GLES20.glUniform1i(glProgram.getUniformLocation("u_Texture"), 0)
                val aPosition = glProgram.getAttributeArrayLocationAndEnable("a_Position")
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, 0)
                val aTexCoord = glProgram.getAttributeArrayLocationAndEnable("a_TexCoord")
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordBufferId)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                GlUtil.checkGlError()

                frames.forEach { frame ->
                    frame.bitmap?.let { bitmap ->
                        if (scratchTextureId == 0) {
                            val texture = IntArray(1)
                            GLES20.glGenTextures(1, texture, 0)
                            scratchTextureId = texture[0]
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratchTextureId)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        } else {
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratchTextureId)
                        }

                        if (scratchTextureWidth != bitmap.width || scratchTextureHeight != bitmap.height) {
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                            scratchTextureWidth = bitmap.width
                            scratchTextureHeight = bitmap.height
                        } else {
                            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                        }
                        GlUtil.checkGlError()

                        val r = frame.color shr 24 and 0xFF
                        val g = frame.color shr 16 and 0xFF
                        val b = frame.color shr 8 and 0xFF
                        val a = 0xFF - frame.color and 0xFF
                        GLES20.glViewport(
                            frame.x,
                            texSize.height - bitmap.height - frame.y,
                            bitmap.width,
                            bitmap.height
                        )
                        GLES20.glUniform4f(
                            glProgram.getUniformLocation("u_Color"),
                            r / 255f,
                            g / 255f,
                            b / 255f,
                            a / 255f
                        )
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GlUtil.checkGlError()
                    }
                }
            }
        } finally {
            GLES20.glUseProgram(preProgram[0])
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, preArrayBuffer[0])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, preBoundTexture[0])
            GLES20.glViewport(preViewPort[0], preViewPort[1], preViewPort[2], preViewPort[3])
            GLES20.glActiveTexture(preTex[0])
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, preFbo[0])
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, preAlign[0])
            GLES20.glBlendFuncSeparate(
                preBlendFunc[0],
                preBlendFunc[1],
                preBlendFunc[2],
                preBlendFunc[3]
            )
            if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND) else GLES20.glDisable(GLES20.GL_BLEND)
        }
        return texId
    }

    override fun getTextureSize(presentationTimeUs: Long): Size {
        return texSize
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        this.texSize = videoSize
        executor = AssExecutor(render)
        render.setFrameSize(videoSize.width, videoSize.height)
        texId = GlUtil.createTexture(videoSize.width, videoSize.height, false)
        fboId = GlUtil.createFboForTexture(texId)
        glProgram = GlProgram(vertexShaderCode, fragmentShaderCode)
        GlUtil.checkGlError()

        val vertexBuffer = ByteBuffer.allocateDirect(rectangleCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(rectangleCoords)
        vertexBuffer.position(0)

        val texCordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(textureCoords)
        texCordBuffer.position(0)

        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        vertexBufferId = buffers[0]
        texCoordBufferId = buffers[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, rectangleCoords.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
        GlUtil.checkGlError()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordBufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, textureCoords.size * 4, texCordBuffer, GLES20.GL_STATIC_DRAW)
        GlUtil.checkGlError()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun release() {
        if (fboId != 0) GlUtil.deleteFbo(fboId)
        if (texId != 0) GlUtil.deleteTexture(texId)
        if (scratchTextureId != 0) GlUtil.deleteTexture(scratchTextureId)
        if (vertexBufferId != 0) GlUtil.deleteBuffer(vertexBufferId)
        if (texCoordBufferId != 0) GlUtil.deleteBuffer(texCoordBufferId)
        if (::glProgram.isInitialized) glProgram.delete()
        if (::executor.isInitialized) executor.shutdownNow()
        fboId = 0
        texId = 0
        scratchTextureId = 0
        vertexBufferId = 0
        texCoordBufferId = 0
        super.release()
    }
}