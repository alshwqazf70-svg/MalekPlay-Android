package com.malekplay.player.cast

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived, token-protected LAN bridge for local media sent to a Cast receiver.
 *
 * The server binds only to a private IPv4 address and serves only explicitly
 * registered content/file URIs. It supports HEAD and single-byte-range GETs,
 * which are required for receiver probing and seeking.
 */
class CastMediaHttpServer(context: Context) : AutoCloseable {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val random = SecureRandom()
    private val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val uriById = ConcurrentHashMap<String, Uri>()
    private val idByUri = ConcurrentHashMap<String, String>()
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var baseUrl: String? = null

    @Synchronized
    private fun ensureStarted() {
        if (running.get()) return
        val address = findPrivateIpv4()
            ?: throw IOException("No private Wi-Fi/LAN IPv4 address is available")
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, 0), 16)
        serverSocket = socket
        baseUrl = "http://${address.hostAddress}:${socket.localPort}"
        running.set(true)
        workers.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                } catch (_: IOException) {
                    if (running.get()) close()
                }
            }
        }
    }

    /** Returns a receiver-reachable URL for a local content/file URI. */
    @Synchronized
    fun urlFor(uri: Uri): Uri {
        val scheme = uri.scheme?.lowercase()
        require(scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE) {
            "Only local content:// and file:// media can be bridged"
        }
        ensureStarted()
        val key = uri.toString()
        val id = idByUri.getOrPut(key) {
            val newId = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            uriById[newId] = uri
            newId
        }
        return Uri.parse("${baseUrl}/media/${token}/${id}")
    }

    /** Maps our own bridge URL back to the original URI when playback returns locally. */
    fun originalUriFor(uri: Uri): Uri? {
        val parts = uri.pathSegments
        if (parts.size != 3 || parts[0] != "media" || !constantTimeEquals(parts[1], token)) return null
        return uriById[parts[2]]
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 12_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readLine(input, 4096) ?: return
                val request = requestLine.split(' ')
                if (request.size < 2) {
                    respond(output, 400, "Bad Request")
                    return
                }
                val method = request[0]
                if (method != "GET" && method != "HEAD") {
                    respond(output, 405, "Method Not Allowed", "Allow" to "GET, HEAD")
                    return
                }
                val path = Uri.parse(request[1]).pathSegments
                var rangeHeader: String? = null
                var headerBytes = 0
                while (true) {
                    val line = readLine(input, 8192) ?: break
                    headerBytes += line.length
                    if (headerBytes > 32_768) {
                        respond(output, 431, "Request Header Fields Too Large")
                        return
                    }
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals("Range", true)) {
                        rangeHeader = line.substring(colon + 1).trim()
                    }
                }
                if (path.size != 3 || path[0] != "media" ||
                    !constantTimeEquals(path[1], token)) {
                    respond(output, 404, "Not Found")
                    return
                }
                val uri = uriById[path[2]]
                if (uri == null) {
                    respond(output, 404, "Not Found")
                    return
                }
                serveMedia(output, method, uri, rangeHeader)
            } catch (_: Exception) {
                // The peer may disconnect during a seek or while closing playback.
            }
        }
    }

    private fun serveMedia(output: BufferedOutputStream, method: String, uri: Uri, range: String?) {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            ?: run { respond(output, 404, "Media Not Found"); return }
        descriptor.use { afd ->
            val length = when {
                afd.length >= 0 -> afd.length
                afd.parcelFileDescriptor.statSize >= 0 -> afd.parcelFileDescriptor.statSize - afd.startOffset
                else -> -1L
            }
            if (length < 0) {
                respond(output, 416, "Range Not Satisfiable", "Accept-Ranges" to "none")
                return
            }
            val parsed = parseRange(range, length)
            if (range != null && parsed == null) {
                respond(
                    output, 416, "Range Not Satisfiable",
                    "Content-Range" to "bytes */$length",
                    "Accept-Ranges" to "bytes"
                )
                return
            }
            val start = parsed?.first ?: 0L
            val end = parsed?.last ?: (length - 1L)
            val count = if (length == 0L) 0L else end - start + 1L
            val status = if (parsed == null) 200 else 206
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val headers = linkedMapOf(
                "Content-Type" to mime,
                "Content-Length" to count.toString(),
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "close"
            )
            if (status == 206) headers["Content-Range"] = "bytes $start-$end/$length"
            writeHeaders(output, status, if (status == 206) "Partial Content" else "OK", headers)
            if (method == "HEAD" || count == 0L) {
                output.flush()
                return
            }
            FileInputStream(afd.parcelFileDescriptor.fileDescriptor).use { stream ->
                val channel = stream.channel
                channel.position(afd.startOffset + start)
                val buffer = ByteArray(64 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        baseUrl = null
        uriById.clear()
        idByUri.clear()
        workers.shutdownNow()
    }

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        vararg headers: Pair<String, String>
    ) {
        val all = linkedMapOf("Content-Length" to "0", "Connection" to "close")
        headers.forEach { all[it.first] = it.second }
        writeHeaders(output, status, reason, all)
        output.flush()
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>
    ) {
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        headers.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < limit) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (next == 10) break
            if (next != 13) bytes.add(next.toByte())
        }
        if (bytes.size >= limit) throw IOException("HTTP line too long")
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun parseRange(header: String?, length: Long): ByteRange? {
        if (header == null) return null
        if (!header.startsWith("bytes=") || header.contains(',')) return null
        val spec = header.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0 || length <= 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return try {
            if (left.isEmpty()) {
                val suffix = right.toLong()
                if (suffix <= 0) null else ByteRange((length - suffix.coerceAtMost(length)), length - 1)
            } else {
                val start = left.toLong()
                val end = if (right.isEmpty()) length - 1 else right.toLong()
                if (start < 0 || start >= length || end < start) null
                else ByteRange(start, end.coerceAtMost(length - 1))
            }
        } catch (_: NumberFormatException) { null }
    }

    private fun findPrivateIpv4(): Inet4Address? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val addresses = interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("p2p")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
        return addresses.firstOrNull { address ->
            address is Inet4Address && !address.isLoopbackAddress &&
                (address.isSiteLocalAddress || address.hostAddress.startsWith("169.254."))
        } as? Inet4Address
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private data class ByteRange(val first: Long, val last: Long)

    @UnstableApi
    class LocalMediaItemConverter(private val server: CastMediaHttpServer) : MediaItemConverter {
        private val delegate = DefaultMediaItemConverter()

        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val local = mediaItem.localConfiguration ?: return delegate.toMediaQueueItem(mediaItem)
            val scheme = local.uri.scheme?.lowercase()
            if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) {
                return delegate.toMediaQueueItem(mediaItem)
            }
            val bridged = MediaItem.Builder()
                .setMediaId(mediaItem.mediaId)
                .setUri(server.urlFor(local.uri))
                .setMimeType(local.mimeType)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .build()
            return delegate.toMediaQueueItem(bridged)
        }

        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
            val item = delegate.toMediaItem(mediaQueueItem)
            val original = item.localConfiguration?.uri?.let(server::originalUriFor) ?: return item
            return MediaItem.Builder()
                .setMediaId(item.mediaId)
                .setUri(original)
                .setMimeType(item.localConfiguration?.mimeType)
                .setMediaMetadata(item.mediaMetadata)
                .build()
        }
    }
}
