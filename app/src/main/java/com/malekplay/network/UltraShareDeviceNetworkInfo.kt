package com.malekplay.network

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Device-local network discovery and provider metadata access for Ultra Share.
 * Callers should invoke these blocking operations on Dispatchers.IO.
 */
internal object UltraShareDeviceNetworkInfo {
    fun readDocumentMetadata(contentResolver: ContentResolver, uri: Uri): Triple<String, String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        var size = -1L
        if (uri.scheme == "file") {
            size = uri.path?.let(::File)?.takeIf { it.isFile }?.length() ?: -1L
        } else {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
            if (size <= 0L) {
                size = runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length.takeIf { it >= 0L } ?: -1L }
                }.getOrDefault(-1L)
            }
        }
        name = UltraShareCore.sanitizeFileName(name)
        require(size > 0L) { "الملف فارغ ولا يحتوي على وسائط صالحة للمشاركة." }
        val mimeType = UltraShareCore.sanitizeMimeType(
            contentResolver.getType(uri) ?: "application/octet-stream"
        )
        require(UltraShareCore.isAllowedShareFile(name, mimeType)) {
            "لا يمكن مشاركة ملفات APK أو PDF أو SVG."
        }
        return Triple(name, mimeType, size)
    }

    fun findLocalIpv4Addresses(context: Context): List<String> = runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // The active Wi-Fi network is the reliable default for QR pairing.
        // A stale p2p interface may still report 192.168.49.1 after its group
        // disappeared, even while the phone is reachable through a 10.x WLAN.
        val activeWifiAddresses = connectivity?.activeNetwork?.let { active ->
            if (connectivity.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) {
                val properties = connectivity.getLinkProperties(active)
                // A Wi-Fi Direct group may be reported as TRANSPORT_WIFI. Its
                // 192.168.49.x address is not a LAN fallback unless P2P is joined.
                if (UltraShareNetworkRouting.isWifiDirectInterfaceName(properties?.interfaceName.orEmpty())) {
                    emptyList()
                } else {
                    properties?.linkAddresses.orEmpty()
                        .mapNotNull { it.address as? Inet4Address }
                        .filter {
                            !it.isLoopbackAddress &&
                                (it.isSiteLocalAddress || it.isLinkLocalAddress) &&
                                !UltraShareNetworkRouting.isWifiDirectGroupHost(it.hostAddress.orEmpty())
                        }
                        .mapNotNull { it.hostAddress }
                }
            } else emptyList()
        }.orEmpty()

        val interfaceAddresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || UltraShareNetworkRouting.isHotspotInterfaceName(name) ||
                        name.startsWith("eth"))
            }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter {
                        !it.isLoopbackAddress &&
                            (it.isSiteLocalAddress || it.isLinkLocalAddress) &&
                            !UltraShareNetworkRouting.isWifiDirectGroupHost(it.hostAddress.orEmpty())
                    }
                    .map { network.name.lowercase() to it }
            }
            .sortedWith(compareBy<Pair<String, Inet4Address>>(
                { (name, _) ->
                    when {
                        name.startsWith("ap") || name.startsWith("softap") -> 0
                        name.startsWith("swlan") -> 1
                        name.startsWith("wlan") -> 2
                        else -> 3
                    }
                },
                { (_, address) -> address.hostAddress.orEmpty() }
            ))
            .mapNotNull { (_, address) -> address.hostAddress }

        // For QR LocalOnlyHotspot sessions, prefer SoftAP-specific interfaces
        // over the device's pre-existing infrastructure Wi-Fi route. The latter
        // can be a different subnet and is not necessarily reachable by the joining peer.
        (interfaceAddresses + activeWifiAddresses).distinct()
    }.getOrDefault(emptyList())

}