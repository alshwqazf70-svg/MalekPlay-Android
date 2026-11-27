package io.github.peerless2012.ass.media

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.TEXT_SSA
import androidx.media3.common.Player.Listener
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTrack
import io.github.peerless2012.ass.media.parser.AssHeaderParser
import io.github.peerless2012.ass.media.render.AssOverlayManager
import io.github.peerless2012.ass.media.render.AssVideoEffectRegistry
import io.github.peerless2012.ass.media.type.AssRenderType

/**
 * Handles ASS subtitle rendering and integration with ExoPlayer.
 *
 * This class listens to ExoPlayer events and manages the creation, selection, and rendering of ASS
 * subtitle tracks.
 * @param renderType The subtitle render type.
 */
@OptIn(UnstableApi::class)
class AssHandler(
    val renderType: AssRenderType,
    val config: AssHandlerConfig = AssHandlerConfig()
) : Listener {

    private var assInstance: Ass? = null
    private val ass: Ass
        get() = assInstance ?: Ass().also { assInstance = it }

    var render: AssRender? = null
        private set

    var renderCallback: ((AssRender?) -> Unit)? = null

    var track: AssTrack? = null
        private set

    /** Keep complete format IDs; only use suffix matching when the match is unambiguous. */
    private val availableTracks = mutableMapOf<String, AssTrack>()
    private val pendingFonts = mutableListOf<Pair<String, ByteArray>>()

    var videoSize = Size.ZERO
        private set

    var surfaceSize = Size.ZERO
        private set

    val videoFramePeriod = 3
    private var videoFrameIndex = 0

    var videoTime = -1L
        set(value) {
            if (field == value) return
            field = value
            if (videoFrameIndex == 0) {
                runCatching { videoTimeCallback?.invoke(value) }
                    .onFailure { Log.w("AssHandler", "ASS video-time callback failed", it) }
            }
            videoFrameIndex++
            if (videoFrameIndex >= videoFramePeriod) videoFrameIndex %= videoFramePeriod
        }

    var videoTimeCallback: ((Long) -> Unit)? = null

    private var overlayManager: AssOverlayManager? = null
    private var format: Format? = null
    private lateinit var handler: Handler
    private var attachedPlayer: ExoPlayer? = null

    @Synchronized
    fun init(player: ExoPlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.let { previousPlayer ->
            previousPlayer.removeListener(this)
            overlayManager?.release()
            AssVideoEffectRegistry.detach(previousPlayer)
        }
        overlayManager = null
        attachedPlayer = player
        player.addListener(this)
        handler = Handler(player.applicationLooper)
        if (renderType == AssRenderType.EFFECTS_CANVAS || renderType == AssRenderType.EFFECTS_OPEN_GL) {
            overlayManager = AssOverlayManager(this, player, renderType == AssRenderType.EFFECTS_OPEN_GL)
        }
    }

    @Synchronized
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        Log.i("AssHandler", "onMediaItemTransition: item = $mediaItem, reason = $reason")
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        overlayManager?.disable(allowRebind = false)
        releaseMediaResources(closeAssInstance = true)
        runCatching { renderCallback?.invoke(null) }
            .onFailure { Log.w("AssHandler", "ASS renderer detach callback failed", it) }
        videoSize = Size.ZERO
        videoTime = -1
        videoFrameIndex = 0
    }

    @Synchronized
    fun release() {
        attachedPlayer?.let { player ->
            player.removeListener(this)
            if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
            overlayManager?.release()
            AssVideoEffectRegistry.detach(player)
        }
        attachedPlayer = null
        overlayManager = null
        releaseMediaResources(closeAssInstance = true)
        runCatching { renderCallback?.invoke(null) }
            .onFailure { Log.w("AssHandler", "ASS renderer detach callback failed", it) }
        renderCallback = null
        videoTimeCallback = null
    }

    private fun releaseMediaResources(closeAssInstance: Boolean) {
        // ass-kt 0.5.1 exposes explicit release() methods for all three native handles.
        runCatching { render?.setTrack(null) }
            .onFailure { Log.w("AssHandler", "Could not detach native ASS track", it) }
        (availableTracks.values + listOfNotNull(track)).toSet().forEach { nativeTrack ->
            runCatching { nativeTrack.release() }
                .onFailure { Log.w("AssHandler", "Could not release native ASS track", it) }
        }
        runCatching { render?.release() }
            .onFailure { Log.w("AssHandler", "Could not release native ASS renderer", it) }
        availableTracks.clear()
        track = null
        render = null
        format = null
        pendingFonts.clear()
        if (closeAssInstance) {
            runCatching { assInstance?.release() }
                .onFailure { Log.w("AssHandler", "Could not release native ASS library", it) }
            assInstance = null
        }
    }

    @Synchronized
    override fun onTracksChanged(tracks: Tracks) {
        runCatching {
            Log.i("AssHandler", "onTracksChanged $tracks")
            getSelectedVideoTrack(tracks)?.let { setVideoSize(it.width, it.height) }
            format = getSelectedAssTrack(tracks)
            if (format == null) {
                Log.i("AssHandler", "subtitle track disabled")
                track = null
                render?.setTrack(null)
                overlayManager?.disable()
                return
            }
            updateTrack()
        }.onFailure { error ->
            Log.w("AssHandler", "Failed to update ASS track state", error)
        }
    }

    @Synchronized
    private fun updateTrack() {
        val selectedId = format?.id?.takeIf { it.isNotBlank() }
        val selectedTrack = if (selectedId == null) {
            availableTracks.values.singleOrNull()
        } else {
            availableTracks[selectedId] ?: availableTracks.entries
                .filter { it.key.substringAfterLast(':') == selectedId.substringAfterLast(':') }
                .singleOrNull()
                ?.value
        }
        if (selectedTrack == null || this.track === selectedTrack) return

        val currentRender = render ?: return
        Log.i("AssHandler", "subtitle track changed to $format")
        if (videoSize.isValid) {
            currentRender.setStorageSize(videoSize.width, videoSize.height)
        }
        val overlayMode = renderType == AssRenderType.OVERLAY_CANVAS ||
            renderType == AssRenderType.OVERLAY_OPEN_GL
        val frameSize = if (overlayMode && surfaceSize.isValid) surfaceSize else videoSize
        if (frameSize.isValid) {
            currentRender.setFrameSize(frameSize.width, frameSize.height)
        }
        currentRender.setTrack(selectedTrack)
        this.track = selectedTrack

        overlayManager?.let { manager ->
            if (::handler.isInitialized) handler.post { manager.enable(currentRender) }
        }
    }

    @Synchronized
    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        super.onSurfaceSizeChanged(width, height)
        Log.i("AssHandler", "onSurfaceSizeChanged: width = $width, height = $height")
        if (surfaceSize.width == width && surfaceSize.height == height) return
        surfaceSize = Size(width, height)
        if ((renderType == AssRenderType.OVERLAY_CANVAS || renderType == AssRenderType.OVERLAY_OPEN_GL) &&
            surfaceSize.isValid
        ) {
            render?.setFrameSize(surfaceSize.width, surfaceSize.height)
        }
    }

    @Synchronized
    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        setVideoSize(videoSize.width, videoSize.height)
        Log.i("AssHandler", "onVideoSizeChanged: width = ${videoSize.width}, height = ${videoSize.height}")
    }

    @Synchronized
    fun setVideoSize(width: Int, height: Int) {
        Log.i("AssHandler", "setVideoSize: width = $width, height = $height")
        videoSize = Size(width, height)
        if (!videoSize.isValid) return
        render?.setStorageSize(width, height)
        val overlayMode = renderType == AssRenderType.OVERLAY_CANVAS ||
            renderType == AssRenderType.OVERLAY_OPEN_GL
        val frameSize = if (overlayMode && surfaceSize.isValid) surfaceSize else videoSize
        render?.setFrameSize(frameSize.width, frameSize.height)
    }

    @Synchronized
    fun hasTracks(): Boolean = availableTracks.isNotEmpty()

    @Synchronized
    fun addFont(name: String, data: ByteArray) {
        if (availableTracks.isNotEmpty()) {
            ass.addFont(name, data)
        } else {
            pendingFonts.add(name to data)
        }
    }

    @Synchronized
    fun createTrack(format: Format): AssTrack {
        Log.i("AssHandler", "createTrack: format = $format")
        createRenderIfNeeded()

        if (pendingFonts.isNotEmpty()) {
            for ((name, data) in pendingFonts) ass.addFont(name, data)
            pendingFonts.clear()
        }

        val newTrack = ass.createTrack()
        if (format.initializationData.isNotEmpty()) {
            val header = AssHeaderParser.parse(format, renderType != AssRenderType.CUES)
            newTrack.readBuffer(header)
        }
        val declaredTrackId = format.id?.takeIf { it.isNotBlank() }
        val trackId = declaredTrackId ?: "ass-track-${availableTracks.size}"
        availableTracks[trackId] = newTrack
        updateTrack()
        return newTrack
    }

    private fun createRenderIfNeeded() {
        if (render != null) return
        Log.i("AssHandler", "createRender")
        render = ass.createRender().also { currentRender ->
            if (videoSize.isValid) currentRender.setStorageSize(videoSize.width, videoSize.height)
            val overlayMode = renderType == AssRenderType.OVERLAY_CANVAS ||
                renderType == AssRenderType.OVERLAY_OPEN_GL
            val frameSize = if (overlayMode && surfaceSize.isValid) surfaceSize else videoSize
            if (frameSize.isValid) currentRender.setFrameSize(frameSize.width, frameSize.height)
            Log.i("AssHandler", "Ass cacheSize: ${config.cacheSize}MB")
            Log.i("AssHandler", "Ass glyphSize: ${config.glyphSize}")
            currentRender.setCacheLimit(config.glyphSize, config.cacheSize)
        }
        runCatching { renderCallback?.invoke(render) }
            .onFailure { Log.w("AssHandler", "ASS renderer attach callback failed", it) }
    }

    @Synchronized
    fun readTrackDialogue(
        trackId: String?,
        start: Long,
        duration: Long,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size
    ) {
        val nativeTrack = trackId?.let(availableTracks::get) ?: return
        nativeTrack.readChunk(start, duration, data, offset, length)
    }

    private fun getSelectedVideoTrack(tracks: Tracks): Format? {
        return tracks.groups.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { index ->
                group.isTrackSelected(index) &&
                    MimeTypes.isVideo(group.getTrackFormat(index).sampleMimeType)
            }?.let { index -> group.getTrackFormat(index) }
        }
    }

    private fun getSelectedAssTrack(tracks: Tracks): Format? {
        return tracks.groups.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { index ->
                if (!group.isTrackSelected(index)) return@firstOrNull false
                val candidate = group.getTrackFormat(index)
                candidate.sampleMimeType == TEXT_SSA || candidate.codecs == TEXT_SSA
            }?.let { index -> group.getTrackFormat(index) }
        }
    }

    private val Size.isValid
        get() = width > 0 && height > 0
}
