@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.player.cast

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.android.gms.cast.MediaQueueItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
 * Short-lived, token-protected LAN bridge for media and subtitles sent to Cast,
 * DLNA, Roku, and Web Cast receivers.
 *
 * The bridge reads through the same Media3 DataSource factory as local playback,
 * so SMB/SFTP/WebDAV credentials stay on the phone. It supports HEAD, byte ranges,
 * DLNA contentFeatures/transferMode headers, filename extensions for strict TVs,
 * and automatic SRT/ASS -> WebVTT subtitle conversion.
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
    private val fileNameById = ConcurrentHashMap<String, String>()
    private val subtitleUrlByMediaKey = ConcurrentHashMap<String, String>()
    private val subtitleSourceById = ConcurrentHashMap<String, SubtitleSource>()
    private val idBySubtitleKey = ConcurrentHashMap<String, String>()
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(16)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var baseUrl: String? = null
    @Volatile private var boundPort: Int = 0

    private data class SubtitleSource(
        val uri: Uri?,
        val inlineContent: String?,
        val rawHint: String
    )

    @Synchronized
    private fun ensureStarted() {
        if (running.get()) return
        val address = findPrivateIpv4()
            ?: throw IOException("لا يوجد عنوان شبكة محلي (Wi-Fi أو نقطة اتصال) متاح للبث.")
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Bind to wildcard 0.0.0.0 on an ephemeral port only if multiple private LAN interfaces
        // exist, or bind directly to the private interface; we advertise only private LAN IPs.
        socket.bind(InetSocketAddress(0), 32)
        serverSocket = socket
        boundPort = socket.localPort
        baseUrl = "http://${address.hostAddress}:$boundPort"
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

    /** Returns the active LAN base URL, or null if the server has not started yet. */
    fun activeBaseUrl(): String? {
        if (!running.get()) return null
        val address = findPrivateIpv4() ?: return baseUrl
        return "http://${address.hostAddress}:$boundPort"
    }

    /** Returns all private LAN IPv4 addresses currently active on this phone. */
    fun allLanBaseUrls(): List<String> {
        if (!running.get()) return emptyList()
        return findAllPrivateIpv4s().map { "http://${it.hostAddress}:$boundPort" }
    }

    /** Returns a receiver-reachable URL with a safe filename extension for strict TVs. */
    @Synchronized
    fun urlFor(uri: Uri, mimeType: String? = null, fileNameHint: String? = null): Uri {
        val scheme = uri.scheme?.lowercase()
        require(
            scheme == ContentResolver.SCHEME_CONTENT ||
                scheme == ContentResolver.SCHEME_FILE ||
                scheme == "https" ||
                scheme == "http" ||
                scheme == "maleksmb" ||
                scheme == "maleksftp" ||
                scheme == "malekdlna-http" ||
                scheme == "malekdlna-https"
        ) { "Unsupported upstream media scheme: $scheme" }
        ensureStarted()
        val key = uri.toString()
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() }
            ?: mimeByUri[key]
            ?: inferMimeFromUriOrName(uri, fileNameHint)
        val id = idByUri.getOrPut(key) {
            val newId = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            uriById[newId] = uri
            newId
        }
        if (effectiveMime.isNotBlank()) mimeByUri[key] = effectiveMime
        val safeName = buildSafeStreamFileName(uri, effectiveMime, fileNameHint)
        fileNameById[id] = safeName
        val currentHostBase = activeBaseUrl() ?: baseUrl
        return Uri.parse("$currentHostBase/media/$token/$id/$safeName")
    }

    /** Registers a companion subtitle URI and returns both SRT and VTT bridge URLs. */
    @Synchronized
    fun subtitleUrlFor(subtitleUri: Uri, formatVtt: Boolean = false): Uri {
        ensureStarted()
        val key = "uri:$subtitleUri"
        val id = idBySubtitleKey.getOrPut(key) {
            val newId = ByteArray(10).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val hint = subtitleUri.lastPathSegment ?: "subtitle.srt"
            subtitleSourceById[newId] = SubtitleSource(uri = subtitleUri, inlineContent = null, rawHint = hint)
            newId
        }
        val currentHostBase = activeBaseUrl() ?: baseUrl
        val ext = if (formatVtt) "sub.vtt" else "sub.srt"
        return Uri.parse("$currentHostBase/sub/$token/$id/$ext")
    }

    /** Registers inline subtitle text (e.g. translated subtitles) and returns a bridge URL. */
    @Synchronized
    fun registerInlineSubtitle(content: String, rawHint: String = "subtitle.srt", formatVtt: Boolean = false): Uri {
        ensureStarted()
        val key = "inline:${MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }}"
        val id = idBySubtitleKey.getOrPut(key) {
            val newId = ByteArray(10).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            subtitleSourceById[newId] = SubtitleSource(uri = null, inlineContent = content, rawHint = rawHint)
            newId
        }
        val currentHostBase = activeBaseUrl() ?: baseUrl
        val ext = if (formatVtt) "sub.vtt" else "sub.srt"
        return Uri.parse("$currentHostBase/sub/$token/$id/$ext")
    }

    fun associateSubtitle(mediaUri: Uri, subtitleUrl: Uri) {
        subtitleUrlByMediaKey[mediaUri.toString()] = subtitleUrl.toString()
    }

    fun clearSubtitleAssociation(mediaUri: Uri) {
        subtitleUrlByMediaKey.remove(mediaUri.toString())
    }

    /** Maps our own bridge URL back to the original URI when playback returns locally. */
    fun originalUriFor(uri: Uri): Uri? {
        val parts = uri.pathSegments
        if (parts.size !in 3..4 || parts[0] != "media" || !constantTimeEquals(parts[1], token)) return null
        return uriById[parts[2]]
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                // Ensure only LAN / loopback peers can consume the local media bridge.
                val remoteAddr = client.inetAddress
                if (remoteAddr is Inet4Address && !remoteAddr.isLoopbackAddress &&
                    !remoteAddr.isSiteLocalAddress && !(remoteAddr.hostAddress?.startsWith("169.254.") == true)
                ) {
                    return
                }

                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readLine(input, 4096) ?: return
                val request = requestLine.split(' ')
                if (request.size < 2) {
                    respond(output, 400, "Bad Request")
                    return
                }
                val method = request[0].uppercase()
                if (method != "GET" && method != "HEAD" && method != "OPTIONS") {
                    respond(output, 405, "Method Not Allowed", "Allow" to "GET, HEAD, OPTIONS")
                    return
                }
                val parsedRequestUri = Uri.parse(request[1])
                val path = parsedRequestUri.pathSegments
                var rangeHeader: String? = null
                var getContentFeatures = false
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
                    if (colon > 0) {
                        val headerName = line.substring(0, colon).trim()
                        val headerVal = line.substring(colon + 1).trim()
                        if (headerName.equals("Range", true)) {
                            rangeHeader = headerVal
                        } else if (headerName.equals("getcontentFeatures.dlna.org", true)) {
                            getContentFeatures = headerVal == "1"
                        }
                    }
                }
                if (method == "OPTIONS") {
                    respond(
                        output,
                        200,
                        "OK",
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                        "Access-Control-Allow-Headers" to "Range, getcontentFeatures.dlna.org, transferMode.dlna.org",
                        "Allow" to "GET, HEAD, OPTIONS"
                    )
                    return
                }
                if (path.size !in 3..4 || !constantTimeEquals(path[1], token)) {
                    respond(output, 404, "Not Found")
                    return
                }
                when (path[0]) {
                    "media" -> {
                        val uri = uriById[path[2]]
                        if (uri == null) {
                            respond(output, 404, "Not Found")
                            return
                        }
                        serveMedia(output, method, uri, rangeHeader, getContentFeatures)
                    }
                    "sub" -> {
                        val sub = subtitleSourceById[path[2]]
                        if (sub == null) {
                            respond(output, 404, "Not Found")
                            return
                        }
                        val wantVtt = path.getOrNull(3)?.endsWith(".vtt", ignoreCase = true) == true ||
                            parsedRequestUri.getQueryParameter("format").equals("vtt", true)
                        serveSubtitle(output, method, sub, wantVtt)
                    }
                    else -> respond(output, 404, "Not Found")
                }
            } catch (_: Exception) {
                // The peer may disconnect during a seek or while closing playback.
            }
        }
    }

    private fun serveSubtitle(
        output: BufferedOutputStream,
        method: String,
        sub: SubtitleSource,
        wantVtt: Boolean
    ) {
        val rawText = sub.inlineContent ?: sub.uri?.let { uri ->
            readSubtitleTextFromUri(uri)
        }
        if (rawText == null) {
            respond(output, 404, "Not Found")
            return
        }
        val rendered = if (wantVtt) convertSubtitleToWebVtt(rawText, sub.rawHint) else rawText
        val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
        val mime = if (wantVtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8"
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*",
            "Connection" to "close"
        )
        writeHeaders(output, 200, "OK", headers)
        if (method != "HEAD") {
            output.write(bytes)
        }
        output.flush()
    }

    private fun readSubtitleTextFromUri(uri: Uri): String? {
        return runCatching {
            val ds = upstreamFactory.createDataSource()
            try {
                ds.open(DataSpec(uri))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < 4 * 1024 * 1024) {
                    val n = ds.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT || n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    total += n
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            } finally {
                runCatching { ds.close() }
            }
        }.getOrNull()
    }

    private fun serveMedia(
        output: BufferedOutputStream,
        method: String,
        uri: Uri,
        range: String?,
        includeContentFeatures: Boolean
    ) {
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
            val mime = mimeByUri[key] ?: resolver.getType(uri) ?: inferMimeFromUriOrName(uri, null)
            val isImage = mime.startsWith("image/", ignoreCase = true)
            val dlnaFlags = if (totalLength != null) {
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            } else {
                "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }
            val headers = linkedMapOf(
                "Content-Type" to mime,
                "Accept-Ranges" to if (totalLength != null) "bytes" else "none",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
                "transferMode.dlna.org" to if (isImage) "Interactive" else "Streaming",
                "realTimeInfo.dlna.org" to "DLNA.ORG_TLAG=*",
                "Connection" to "close"
            )
            if (includeContentFeatures || !isImage) {
                headers["contentFeatures.dlna.org"] = dlnaFlags
            }
            subtitleUrlByMediaKey[key]?.let { subUrl ->
                headers["CaptionInfo.sec"] = subUrl
            }
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
            var zeroReads = 0
            if (knownCount == null) {
                while (true) {
                    val read = mediaSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read == 0) {
                        if (++zeroReads > 3) throw IOException("Upstream media made no read progress")
                        continue
                    }
                    zeroReads = 0
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
                    if (read == 0) {
                        if (++zeroReads > 3) throw IOException("Upstream media made no read progress")
                        continue
                    }
                    zeroReads = 0
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
        boundPort = 0
        uriById.clear()
        idByUri.clear()
        lengthByUri.clear()
        mimeByUri.clear()
        fileNameById.clear()
        subtitleUrlByMediaKey.clear()
        subtitleSourceById.clear()
        idBySubtitleKey.clear()
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

    private fun findPrivateIpv4(): Inet4Address? = findAllPrivateIpv4s().firstOrNull()

    private fun findAllPrivateIpv4s(): List<Inet4Address> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        return interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                val name = it.name.lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                    name.startsWith("p2p") || name.startsWith("eth") || name.startsWith("rndis") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { address ->
                !address.isLoopbackAddress &&
                    (address.isSiteLocalAddress || address.hostAddress?.startsWith("169.254.") == true)
            }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private data class ByteRange(val first: Long, val last: Long)

    @UnstableApi
    class BridgeMediaItemConverter(private val server: CastMediaHttpServer) : MediaItemConverter {
        private val delegate = DefaultMediaItemConverter()

        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val local = mediaItem.localConfiguration ?: return delegate.toMediaQueueItem(mediaItem)
            val scheme = local.uri.scheme?.lowercase()
            if (scheme == "http") return delegate.toMediaQueueItem(mediaItem)
            val bridged = MediaItem.Builder()
                .setMediaId(mediaItem.mediaId)
                .setUri(server.urlFor(local.uri, local.mimeType, mediaItem.mediaMetadata.title?.toString()))
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

    companion object {
        internal fun inferMimeFromUriOrName(uri: Uri, hint: String?): String {
            val candidate = (hint ?: uri.lastPathSegment ?: "").lowercase()
            return when {
                candidate.endsWith(".mp4") || candidate.endsWith(".m4v") -> "video/mp4"
                candidate.endsWith(".mkv") -> "video/x-matroska"
                candidate.endsWith(".webm") -> "video/webm"
                candidate.endsWith(".avi") -> "video/x-msvideo"
                candidate.endsWith(".mov") -> "video/quicktime"
                candidate.endsWith(".ts") || candidate.endsWith(".m2ts") -> "video/mp2t"
                candidate.endsWith(".3gp") -> "video/3gpp"
                candidate.endsWith(".flv") -> "video/x-flv"
                candidate.endsWith(".wmv") -> "video/x-ms-wmv"
                candidate.endsWith(".mp3") -> "audio/mpeg"
                candidate.endsWith(".m4a") || candidate.endsWith(".aac") -> "audio/mp4"
                candidate.endsWith(".flac") -> "audio/flac"
                candidate.endsWith(".wav") -> "audio/wav"
                candidate.endsWith(".ogg") || candidate.endsWith(".opus") -> "audio/ogg"
                candidate.endsWith(".jpg") || candidate.endsWith(".jpeg") -> "image/jpeg"
                candidate.endsWith(".png") -> "image/png"
                candidate.endsWith(".webp") -> "image/webp"
                candidate.endsWith(".gif") -> "image/gif"
                else -> "video/mp4"
            }
        }

        internal fun buildSafeStreamFileName(uri: Uri, mimeType: String, hint: String?): String {
            val rawExt = (hint ?: uri.lastPathSegment ?: "")
                .substringAfterLast('.', "")
                .lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' }
                .take(6)
            val ext = rawExt.ifBlank {
                when (mimeType.substringBefore(';').trim().lowercase()) {
                    "video/mp4" -> "mp4"
                    "video/x-matroska" -> "mkv"
                    "video/webm" -> "webm"
                    "video/x-msvideo" -> "avi"
                    "video/quicktime" -> "mov"
                    "video/mp2t" -> "ts"
                    "video/3gpp" -> "3gp"
                    "audio/mpeg" -> "mp3"
                    "audio/mp4" -> "m4a"
                    "audio/flac" -> "flac"
                    "audio/wav" -> "wav"
                    "audio/ogg" -> "ogg"
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "mp4"
                }
            }
            return "stream.$ext"
        }

        /** Converts SRT or ASS/SSA subtitle text into valid WebVTT for browsers and smart TVs. */
        internal fun convertSubtitleToWebVtt(raw: String, hint: String = ""): String {
            val cleaned = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
            if (cleaned.startsWith("WEBVTT", ignoreCase = true)) return cleaned
            val isAss = hint.endsWith(".ass", true) || hint.endsWith(".ssa", true) ||
                cleaned.contains("[Script Info]", ignoreCase = true) ||
                cleaned.lineSequence().any { it.startsWith("Dialogue:", ignoreCase = true) }
            if (isAss) {
                val sb = StringBuilder("WEBVTT\n\n")
                var index = 1
                cleaned.lineSequence().forEach { line ->
                    if (line.startsWith("Dialogue:", ignoreCase = true)) {
                        val payload = line.substringAfter(':')
                        val parts = payload.split(',', limit = 10)
                        if (parts.size >= 10) {
                            val start = formatAssTimeToVtt(parts[1].trim())
                            val end = formatAssTimeToVtt(parts[2].trim())
                            val text = parts[9]
                                .replace(Regex("\\{[^}]*}"), "")
                                .replace("\\N", "\n")
                                .replace("\\n", "\n")
                                .replace("\\h", " ")
                                .trim()
                            if (start != null && end != null && text.isNotEmpty()) {
                                sb.append(index++).append('\n')
                                sb.append(start).append(" --> ").append(end).append('\n')
                                sb.append(text).append("\n\n")
                            }
                        }
                    }
                }
                return sb.toString()
            }
            // Standard SRT -> VTT: convert comma milliseconds (00:01:02,345) to dot (00:01:02.345)
            val vttBody = cleaned.replace(
                Regex("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{3})"),
                "$1.$2"
            )
            return "WEBVTT\n\n$vttBody\n"
        }

        private fun formatAssTimeToVtt(raw: String): String? {
            // ASS uses H:MM:SS.cc (centiseconds)
            val match = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$").matchEntire(raw) ?: return null
            val h = match.groupValues[1].toIntOrNull()?.let { "%02d".format(it) } ?: return null
            val m = match.groupValues[2]
            val s = match.groupValues[3]
            val cs = match.groupValues[4]
            return "$h:$m:$s.${cs}0"
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

    fun server(): CastMediaHttpServer? = activeServer

    fun urlFor(uri: Uri, mimeType: String? = null, fileNameHint: String? = null): Uri? {
        val server = activeServer ?: return null
        return runCatching { server.urlFor(uri, mimeType, fileNameHint) }.getOrNull()
    }

    fun subtitleUrlFor(subtitleUri: Uri, formatVtt: Boolean = false): Uri? {
        val server = activeServer ?: return null
        return runCatching { server.subtitleUrlFor(subtitleUri, formatVtt) }.getOrNull()
    }

    fun associateSubtitle(mediaUri: Uri, subtitleUrl: Uri) {
        activeServer?.associateSubtitle(mediaUri, subtitleUrl)
    }

    fun clearSubtitleAssociation(mediaUri: Uri) {
        activeServer?.clearSubtitleAssociation(mediaUri)
    }
}
