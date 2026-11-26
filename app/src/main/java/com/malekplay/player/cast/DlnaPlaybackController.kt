package com.malekplay.player.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.DlnaRendererClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

/**
 * Application-facing adapter for DLNA / Roku TV playback.
 *
 * It resolves the phone-owned media & subtitle bridge URLs and delegates
 * UPnP / ECP commands to the renderer client. Session state and polling are
 * owned by PlayerViewModel; this class deliberately contains no UI or coroutine scope.
 */
class DlnaPlaybackController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val rendererClient = DlnaRendererClient()

    suspend fun play(
        device: DiscoveredNetworkDevice,
        mediaItem: MediaItem,
        title: String,
        subtitleOverrideUri: Uri? = null
    ) {
        val configuration = mediaItem.localConfiguration
            ?: throw IllegalStateException("لا يوجد مصدر وسائط صالح للفيديو الحالي.")
        val sourceUri = configuration.uri
        val rawMime = configuration.mimeType
            ?: appContext.contentResolver.getType(sourceUri)
        val fileNameHint = mediaItem.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: sourceUri.lastPathSegment
        val mimeType = rawMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: CastMediaHttpServer.inferMimeFromUriOrName(sourceUri, fileNameHint)

        val subtitleUri = subtitleOverrideUri
            ?: configuration.subtitleConfigurations.lastOrNull()?.uri

        val bridgedSubtitleUrl = subtitleUri?.let { sub ->
            LocalMediaBridgeRegistry.subtitleUrlFor(sub, formatVtt = false)
        }
        if (bridgedSubtitleUrl != null) {
            LocalMediaBridgeRegistry.associateSubtitle(sourceUri, bridgedSubtitleUrl)
        } else {
            LocalMediaBridgeRegistry.clearSubtitleAssociation(sourceUri)
        }

        val receiverUri = when (sourceUri.scheme?.lowercase()) {
            // DlnaRendererClient independently restricts plain HTTP to private
            // LAN hosts; HTTPS and private app URIs are proxied by the service.
            "http" -> sourceUri
            "content", "file", "https", "maleksmb", "maleksftp",
            "malekdlna-http", "malekdlna-https" ->
                LocalMediaBridgeRegistry.urlFor(sourceUri, mimeType, fileNameHint)
            else -> null
        } ?: throw IOException(
            "مصدر هذا الفيديو لا يمكن تقديمه للتلفزيون. تحقق من أن المصدر مدعوم ومتاح على الهاتف."
        )

        rendererClient.play(
            device = device,
            mediaUrl = receiverUri.toString(),
            title = title,
            mimeType = mimeType,
            subtitleUrl = bridgedSubtitleUrl?.toString()
        )
    }

    suspend fun pause(device: DiscoveredNetworkDevice) = rendererClient.pause(device)

    suspend fun resume(device: DiscoveredNetworkDevice) = rendererClient.resume(device)

    suspend fun stop(device: DiscoveredNetworkDevice) = rendererClient.stop(device)

    suspend fun seek(device: DiscoveredNetworkDevice, positionMs: Long) =
        rendererClient.seek(device, positionMs)

    suspend fun getVolume(device: DiscoveredNetworkDevice): Int? =
        rendererClient.getVolume(device)

    suspend fun setVolume(device: DiscoveredNetworkDevice, percent: Int) =
        rendererClient.setVolume(device, percent)

    suspend fun stepVolume(device: DiscoveredNetworkDevice, up: Boolean) =
        rendererClient.stepVolume(device, up)

    suspend fun setMute(device: DiscoveredNetworkDevice, muted: Boolean) =
        rendererClient.setMute(device, muted)

    suspend fun getPosition(device: DiscoveredNetworkDevice): DlnaRendererClient.PlaybackPosition? =
        rendererClient.getPosition(device)
}
