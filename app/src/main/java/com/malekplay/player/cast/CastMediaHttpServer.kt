package com.malekplay.player.cast

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
class CastMediaHttpServer(
    context: Context,
    private val upstreamFactory: DataSource.Factory
) : AutoCloseable {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val random = SecureRandom()
    private val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val uriById = ConcurrentHashMap<String, Uri>()
    private val idByUri = ConcurrentHashMap<String, String>()
    private val lengthByUri = ConcurrentHashMap<String, Long>()
    private val mimeByUri = ConcurrentHashMap<String, String>()
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
    fun urlFor(uri: Uri, mimeType: String? = null): Uri {
        val scheme = uri.scheme?.lowercase()
        require(
            scheme == ContentResolver.SCHEME_CONTENT ||
                scheme == ContentResolver.SCHEME_FILE ||
                scheme == "https" ||
                scheme == "maleksmb" ||
                scheme == "maleksftp" ||
                scheme == "malekdlna-http" ||
                scheme == "malekdlna-https"
        ) { "Unsupported upstream media scheme: $scheme" }
        ensureStarted()
        val key = uri.toString()
        val id = idByUri.getOrPut(key) {
            val newId = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            uriById[newId] = uri
            newId
        }
        mimeType?.takeIf { it.isNotBlank() }?.let { mimeByUri[key] = it }
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
        val key = uri.toString()
        var totalLength = lengthByUri[key]
        var source: DataSource? = null
        try {
            if (range == null && method == "GET") {
                source = upstreamFactory.createDataSource()
                val openedLength = source.open(DataSpec(uri))
                if (openedLength >= 0L) {
                    totalLength = openedLength
                    lengthByUri[key] = openedLength
                }
            } else if (totalLength == null) {
                val probe = upstreamFactory.createDataSource()
                try {
                    val measured = probe.open(DataSpec(uri))
                    if (measured >= 0L) {
                        totalLength = measured
                        lengthByUri[key] = measured
                    }
                } finally {
                    probe.close()
                }
            }

            if (range != null && totalLength == null) {
                respond(output, 416, "Range Not Satisfiable", "Accept-Ranges" to "none")
                return
            }

            val parsed = if (range != null) parseRange(range, totalLength ?: 0L) else null
            if (range != null && parsed == null) {
                respond(
                    output, 416, "Range Not Satisfiable",
                    "Content-Range" to "bytes */${totalLength ?: 0L}",
                    "Accept-Ranges" to "bytes"
                )
                return
            }

            val start = parsed?.first ?: 0L
            val knownCount = if (parsed != null) parsed.last - parsed.first + 1L else totalLength
            val status = if (parsed != null) 206 else 200
            val mime = mimeByUri[key] ?: resolver.getType(uri) ?: "application/octet-stream"
            val headers = linkedMapOf(
                "Content-Type" to mime,
                "Accept-Ranges" to if (totalLength != null) "bytes" else "none",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "close"
            )
            if (knownCount != null) headers["Content-Length"] = knownCount.toString()
            else headers["Transfer-Encoding"] = "chunked"
            if (status == 206 && parsed != null) {
                headers["Content-Range"] = "bytes ${parsed.first}-${parsed.last}/${totalLength}"
            }
            writeHeaders(output, status, if (status == 206) "Partial Content" else "OK", headers)
            if (method == "HEAD") {
                output.flush()
                return
            }

            val mediaSource = source ?: upstreamFactory.createDataSource().also { created ->
                val spec = if (parsed != null) {
                    DataSpec.Builder()
                        .setUri(uri)
                        .setPosition(start)
                        .setLength(knownCount!!)
                        .build()
                } else DataSpec(uri)
                created.open(spec)
                source = created
            }

            val buffer = ByteArray(64 * 1024)
            if (knownCount == null) {
                while (true) {
                    val read = mediaSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read <= 0) continue
                    output.write(Integer.toHexString(read).toByteArray(StandardCharsets.US_ASCII))
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(buffer, 0, read)
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
                output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            } else {
                var remaining = knownCount
                while (remaining > 0L) {
                    val read = mediaSource.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == C.RESULT_END_OF_INPUT) throw IOException("Upstream media ended before its advertised length")
                    if (read <= 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        } finally {
            runCatching { source?.close() }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        baseUrl = null
        uriById.clear()
        idByUri.clear()
        lengthByUri.clear()
        mimeByUri.clear()
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
            if (scheme == "http") return delegate.toMediaQueueItem(mediaItem)
            val bridged = MediaItem.Builder()
                .setMediaId(mediaItem.mediaId)
                .setUri(server.urlFor(local.uri, local.mimeType))
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

/**
 * Narrow in-process access to the playback service's LAN bridge.
 * The service owns its lifetime; UI callers never create a second listener.
 */
object LocalMediaBridgeRegistry {
    @Volatile
    private var activeServer: CastMediaHttpServer? = null

    fun attach(server: CastMediaHttpServer) {
        activeServer = server
    }

    fun detach(server: CastMediaHttpServer) {
        if (activeServer === server) activeServer = null
    }

    fun urlFor(uri: Uri, mimeType: String? = null): Uri? {
        val server = activeServer ?: return null
        return runCatching { server.urlFor(uri, mimeType) }.getOrNull()
    }
}
