package com.malekplay.player.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.DlnaRendererClient
import com.malekplay.player.cast.LocalMediaBridgeRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

/**
 * Application-facing adapter for DLNA playback.
 *
 * It resolves the phone-owned media bridge URL and delegates UPnP commands to
 * the existing renderer client. Session state and polling are owned by
 * PlayerViewModel; this class deliberately contains no UI or coroutine scope.
 */
class DlnaPlaybackController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val rendererClient = DlnaRendererClient()

    suspend fun play(
        device: DiscoveredNetworkDevice,
        mediaItem: MediaItem,
        title: String
    ) {
        val configuration = mediaItem.localConfiguration
            ?: throw IllegalStateException("لا يوجد مصدر وسائط صالح للفيديو الحالي.")
        val sourceUri = configuration.uri
        val mimeType = configuration.mimeType
            ?: appContext.contentResolver.getType(sourceUri)
            ?: "video/mp4"

        val receiverUri = when (sourceUri.scheme?.lowercase()) {
            // DlnaRendererClient independently restricts plain HTTP to private
            // LAN hosts; HTTPS and private app URIs are proxied by the service.
            "http" -> sourceUri
            "content", "file", "https", "maleksmb", "maleksftp",
            "malekdlna-http", "malekdlna-https" ->
                LocalMediaBridgeRegistry.urlFor(sourceUri, mimeType)
            else -> null
        } ?: throw IOException(
            "مصدر هذا الفيديو لا يمكن تقديمه للتلفزيون. تحقق من أن المصدر مدعوم ومتاح على الهاتف."
        )

        rendererClient.play(
            device = device,
            mediaUrl = receiverUri.toString(),
            title = title,
            mimeType = mimeType
        )
    }

    suspend fun pause(device: DiscoveredNetworkDevice) = rendererClient.pause(device)

    suspend fun resume(device: DiscoveredNetworkDevice) = rendererClient.resume(device)

    suspend fun stop(device: DiscoveredNetworkDevice) = rendererClient.stop(device)

    suspend fun seek(device: DiscoveredNetworkDevice, positionMs: Long) =
        rendererClient.seek(device, positionMs)

    suspend fun getPosition(device: DiscoveredNetworkDevice): DlnaRendererClient.PlaybackPosition? =
        rendererClient.getPosition(device)
}
