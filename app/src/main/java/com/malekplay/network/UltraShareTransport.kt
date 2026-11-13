package com.malekplay.network

import android.content.ContentResolver
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * First LAN transport for MalekPlay Ultra Share.
 *
 * The receiver accepts one authenticated transfer per invocation. The 256-bit
 * pairing secret is exchanged out-of-band (the QR/pairing UI is added separately).
 * File data and metadata use AES-GCM; the challenge-response prevents sending
 * the reusable secret over the LAN.
 */
/**
 * Guarantees forward progress for unusual InputStreams that return zero from bulk reads.
 * Returns -1 only at EOF and otherwise copies at least one byte into the destination.
 */
internal fun readStreamWithProgress(
    input: InputStream,
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int {
    require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
    if (length == 0) return 0
    val count = input.read(buffer, offset, length)
    if (count != 0) return count
    val single = input.read()
    if (single < 0) return -1
    buffer[offset] = single.toByte()
    return 1
}

object UltraShareTransport {
    const val DEFAULT_PORT = 38741
    const val CHUNK_BYTES = 256 * 1024
    private const val SOCKET_BUFFER_BYTES = 1024 * 1024
    private const val DIGEST_BUFFER_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = 100L * 1024 * 1024 * 1024
    private const val MAGIC = 0x4D505531 // MPU1
    private const val VERSION = 1
    private const val SECRET_BYTES = 32
    private const val PIN_LENGTH = 9
    private const val LEGACY_PIN_LENGTH = 12
    private const val PIN_KDF_ITERATIONS = 210_000
    private const val PIN_SALT_BYTES = 16
    private const val CHALLENGE_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4
    private const val GCM_TAG_BYTES = 16
    private const val SOCKET_TIMEOUT_MS = 20_000
    private const val HANDSHAKE_TIMEOUT_MS = 5_000

    data class Progress(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val receiving: Boolean
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f
            else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data class ReceivedFile(
        val file: File,
        val fileName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val publishedUri: Uri? = null
    )

    /** Generates a compact, human-friendly one-time numeric pairing PIN. */
    fun createPairingSecret(): String {
        // A 9-digit PIN matches the receiver UI and preserves leading zeroes.
        val value = SecureRandom().nextLong().ushr(1) % 1_000_000_000L
        return String.format(Locale.US, "%09d", value)
    }

    fun isValidPairingSecret(value: String): Boolean =
        ((value.length == PIN_LENGTH || value.length == LEGACY_PIN_LENGTH) &&
            value.all { it in '0'..'9' }) ||
            (value.length == SECRET_BYTES * 2 && value.all { it in "0123456789abcdefABCDEF" })

    /** Accepts only literal private/link-local IPv4 endpoints; never resolves hostnames. */
    fun isValidPrivateLanHost(host: String): Boolean = runCatching {
        resolvePrivateLanAddress(host)
        true
    }.getOrDefault(false)

    /** Derives a per-handshake key; the fresh receiver challenge supplies the PBKDF2 salt. */
    internal fun derivePairingKey(value: String, challenge: ByteArray): ByteArray {
        require(isValidPairingSecret(value)) { "رمز الاقتران غير صالح." }
        require(challenge.size == CHALLENGE_BYTES) { "تحدي الاقتران غير صالح." }
        if (value.length == PIN_LENGTH || value.length == LEGACY_PIN_LENGTH) {
            // Implement PBKDF2-HMAC-SHA256 with the platform Mac primitive.
            // Some Android 10 provider builds fail to expose the matching
            // SecretKeyFactory even though HmacSHA256 itself is available.
            // The manual RFC 8018 implementation keeps the wire key identical
            // across Android 6-15 and avoids provider-specific PBKDF2 failures.
            val password = value.toByteArray(Charsets.UTF_8)
            val salt = challenge.copyOfRange(0, PIN_SALT_BYTES)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val blockInput = ByteArray(salt.size + 4)
            salt.copyInto(blockInput)
            blockInput[blockInput.lastIndex - 3] = 0
            blockInput[blockInput.lastIndex - 2] = 0
            blockInput[blockInput.lastIndex - 1] = 0
            blockInput[blockInput.lastIndex] = 1
            var u = mac.doFinal(blockInput)
            val result = u.copyOf()
            repeat(PIN_KDF_ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (index in result.indices) result[index] = (result[index].toInt() xor u[index].toInt()).toByte()
            }
            return result.copyOf(SECRET_BYTES)
        }
        return ByteArray(SECRET_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun sanitizeFileName(raw: String): String {
        val leaf = raw.replace('\\', '/').substringAfterLast('/')
        var cleaned = buildString(leaf.length) {
            var index = 0
            while (index < leaf.length) {
                val codePoint = leaf.codePointAt(index)
                index += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                val forbidden = codePoint == ':'.code || codePoint == '*'.code ||
                    codePoint == '?'.code || codePoint == '"'.code ||
                    codePoint == '<'.code || codePoint == '>'.code ||
                    codePoint == '|'.code || Character.isISOControl(codePoint) ||
                    type == Character.FORMAT.toInt()
                if (!forbidden) appendCodePoint(codePoint)
            }
        }.trim().trim('.')

        // Bound the UTF-8 filename without splitting a Unicode code point.
        while (cleaned.isNotEmpty() && cleaned.toByteArray(Charsets.UTF_8).size > 180) {
            val lastCodePoint = cleaned.codePointBefore(cleaned.length)
            cleaned = cleaned.dropLast(Character.charCount(lastCodePoint))
        }
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            "اسم الملف غير صالح."
        }
        return cleaned
    }

    fun sanitizeMimeType(raw: String): String {
        val candidate = raw.trim()
        val token = "[A-Za-z0-9!#$&^_.+-]+"
        return if (candidate.length <= 160 && Regex("^$token/$token$").matches(candidate)) {
            candidate
        } else {
            "application/octet-stream"
        }
    }

    /**
     * A resume file belongs to the exact content, not just its visible name and size.
     * This prevents two different same-name/same-size files from sharing partial bytes.
     */
    internal fun buildTransferId(fileName: String, size: Long, contentDigest: ByteArray): String {
        require(size in 0..MAX_FILE_BYTES)
        require(contentDigest.size == 32) { "بصمة الملف غير صالحة." }
        val nameBytes = sanitizeFileName(fileName).toByteArray(Charsets.UTF_8)
        val identity = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(nameBytes.size)
                data.write(nameBytes)
                data.writeLong(size)
                data.write(contentDigest)
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity)
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    /** Rejects impossible resume positions before either side seeks or appends. */
    internal fun isValidResumeOffset(offset: Long, expectedSize: Long): Boolean =
        expectedSize in 0..MAX_FILE_BYTES && offset in 0..expectedSize

    /** A completed destination is reusable only when its length and SHA-256 match. */
    internal fun isCompletedFileValid(file: File, expectedSize: Long, expectedDigest: ByteArray): Boolean {
        if (!file.isFile || expectedSize !in 0..MAX_FILE_BYTES || expectedDigest.size != 32 || file.length() != expectedSize) return false
        return runCatching {
            val actual = FileInputStream(file).use { digestStreamRaw(it) }
            MessageDigest.isEqual(expectedDigest, actual)
        }.getOrDefault(false)
    }
    internal fun completedReceiptFileName(
        markerContent: String,
        expectedSize: Long,
        expectedDigestHex: String
    ): String? = runCatching {
        val fields = markerContent.split(':', limit = 3)
        if (fields.size != 3 || fields[0].toLongOrNull() != expectedSize ||
            fields[1] != expectedDigestHex
        ) return@runCatching null
        sanitizeFileName(fields[2])
    }.getOrNull()

    suspend fun send(
        resolver: ContentResolver,
        uri: Uri,
        host: String,
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        sizeBytes: Long,
        onProgress: (Progress) -> Unit = {},
        connectivityManager: ConnectivityManager? = null
    ) = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "أدخل عنوان الجهاز المستقبل." }
        val targetAddress = resolvePrivateLanAddress(host)
        require(port in 1..65535) { "منفذ الاتصال غير صالح." }
        require(sizeBytes in 0..MAX_FILE_BYTES) { "حجم الملف غير مدعوم أو الملف فارغ." }
        val safeName = sanitizeFileName(fileName)
        val digest = resolver.openInputStream(uri)?.use { stream ->
            digestStream(stream, sizeBytes)
        } ?: error("تعذر فتح الملف المحدد.")

        connectUsingAvailableNetworks(targetAddress, port, connectivityManager).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.sendBufferSize = SOCKET_BUFFER_BYTES
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_BUFFER_BYTES))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER_BYTES))

            check(input.readInt() == MAGIC) { "الجهاز الآخر لا يشغّل MalekPlay Ultra Share." }
            val challenge = ByteArray(CHALLENGE_BYTES).also(input::readFully)
            val secret = derivePairingKey(pairingSecret, challenge)
            val proof = hmac(secret, challenge)
            output.write(proof)
            output.flush()
            check(input.readBoolean()) { "رفض الجهاز المستقبل رمز الاقتران." }
            val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(input::readFully)
            val sendKey = deriveSessionKey(secret, challenge, "client-to-server")
            val receiveKey = deriveSessionKey(secret, challenge, "server-to-client")

            val metadata = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeShort(VERSION)
                    writeUtf8(data, safeName, 768)
                    data.writeLong(sizeBytes)
                    writeUtf8(data, sanitizeMimeType(mimeType), 192)
                    data.write(digest)
                }
                bytes.toByteArray()
            }
            writeEncryptedFrame(output, sendKey, noncePrefix, 0L, metadata)

            val offsetPayload = readEncryptedFrame(input, receiveKey, noncePrefix, 0L, 64)
            val offset = DataInputStream(ByteArrayInputStream(offsetPayload)).use { it.readLong() }
            require(isValidResumeOffset(offset, sizeBytes)) { "موضع الاستئناف الذي أرسله المستقبل غير صالح." }

            var startedAt = System.nanoTime()
            var transferred = offset
            resolver.openInputStream(uri)?.use { source ->
                skipFully(source, offset)
                startedAt = System.nanoTime()
                val buffer = ByteArray(CHUNK_BYTES)
                var read: Int
                var sequence = 1L
                while (transferred < sizeBytes) {
                    currentCoroutineContext().ensureActive()
                    val wanted = minOf(buffer.size.toLong(), sizeBytes - transferred).toInt()
                    read = readAtMost(source, buffer, wanted)
                    check(read > 0) { "انتهى الملف قبل اكتمال نقله." }
                    writeEncryptedFrame(output, sendKey, noncePrefix, sequence++, buffer, 0, read)
                    transferred += read
                    onProgress(progress(safeName, transferred, sizeBytes, startedAt, false, offset))
                }
                check(source.read() == -1) { "تغير حجم الملف أثناء الإرسال؛ أعد المحاولة." }
                writeEncryptedFrame(output, sendKey, noncePrefix, sequence, ByteArray(0))
            } ?: error("تعذر إعادة فتح الملف للإرسال.")

            val acknowledgement = readEncryptedFrame(input, receiveKey, noncePrefix, 1L, 1024)
            DataInputStream(ByteArrayInputStream(acknowledgement)).use { data ->
                val accepted = data.readBoolean()
                val message = readUtf8(data, 512)
                check(accepted) { message.ifBlank { "تعذر التحقق من الملف على الجهاز المستقبل." } }
            }
            onProgress(progress(safeName, sizeBytes, sizeBytes, startedAt, false, offset))
        }
    }

    suspend fun receiveOnce(
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        destinationDirectory: File,
        onProgress: (Progress) -> Unit = {}
    ): ReceivedFile = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "منفذ الاستقبال غير صالح." }
        check(destinationDirectory.exists() || destinationDirectory.mkdirs()) {
            "تعذر إنشاء مجلد الملفات المستلمة."
        }
        check(destinationDirectory.isDirectory) { "مسار الاستقبال ليس مجلداً." }

        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), 1)
            server.soTimeout = 1_000
            var accepted: Socket? = null
            while (accepted == null) {
                currentCoroutineContext().ensureActive()
                try {
                    val candidate = server.accept()
                    if (isPrivateLanAddress(candidate.inetAddress)) accepted = candidate
                    else candidate.close()
                } catch (_: SocketTimeoutException) {
                    // Poll cancellation while keeping the receiver available for the user.
                }
            }
            val clientSocket = requireNotNull(accepted)
            clientSocket.use { socket ->
                // Do not let an unauthenticated LAN peer hold the single receiver
                // socket indefinitely while slowly sending the pairing proof.
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.receiveBufferSize = SOCKET_BUFFER_BYTES
                socket.sendBufferSize = SOCKET_BUFFER_BYTES
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_BUFFER_BYTES))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER_BYTES))

                output.writeInt(MAGIC)
                val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
                val secret = derivePairingKey(pairingSecret, challenge)
                output.write(challenge)
                output.flush()

                val suppliedProof = ByteArray(32).also(input::readFully)
                val authenticated = MessageDigest.isEqual(suppliedProof, hmac(secret, challenge))
                output.writeBoolean(authenticated)
                output.flush()
                check(authenticated) { "فشل التحقق من رمز الاقتران." }
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
                output.write(noncePrefix)
                output.flush()
                val receiveKey = deriveSessionKey(secret, challenge, "client-to-server")
                val sendKey = deriveSessionKey(secret, challenge, "server-to-client")

                val metadataBytes = readEncryptedFrame(input, receiveKey, noncePrefix, 0L, 1024)
                val metadata = DataInputStream(ByteArrayInputStream(metadataBytes))
                require(metadata.readUnsignedShort() == VERSION) { "إصدار بروتوكول النقل غير مدعوم." }
                val fileName = sanitizeFileName(readUtf8(metadata, 768))
                val size = metadata.readLong()
                require(size in 0..MAX_FILE_BYTES) { "حجم الملف المرسل غير مسموح." }
                val mime = sanitizeMimeType(readUtf8(metadata, 192))
                val expectedDigest = ByteArray(32).also(metadata::readFully)
                require(metadata.available() == 0) { "بيانات الملف المرسل غير مكتملة." }

                val transferId = buildTransferId(fileName, size, expectedDigest)
                val receiptMarker = File(destinationDirectory, ".ultrashare-$transferId.received")
                val expectedDigestHex = expectedDigest.joinToString("") { "%02x".format(it) }
                // A damaged or temporarily unreadable receipt must not abort a new
                // transfer. Treat it as untrusted metadata and continue through the
                // normal resume path, which removes the stale marker below.
                val completedReceipt = receiptMarker.takeIf { it.isFile }?.let { marker ->
                    runCatching {
                        UltraShareTransport.completedReceiptFileName(
                            marker.readText(Charsets.UTF_8),
                            size,
                            expectedDigestHex
                        )?.let { safeName ->
                            val completedFile = File(destinationDirectory, safeName)
                            completedFile.takeIf { isCompletedFileValid(it, size, expectedDigest) }
                        }
                    }.getOrNull()
                }
                if (completedReceipt != null) {
                    val offsetPayload = ByteArrayOutputStream().use { bytes ->
                        DataOutputStream(bytes).use { it.writeLong(size) }
                        bytes.toByteArray()
                    }
                    writeEncryptedFrame(output, sendKey, noncePrefix, 0L, offsetPayload)
                    val end = readEncryptedFrame(input, receiveKey, noncePrefix, 1L, 64)
                    require(end.isEmpty()) { "إشارة نهاية النقل غير صالحة." }
                    val ack = ByteArrayOutputStream().use { bytes ->
                        DataOutputStream(bytes).use { data ->
                            data.writeBoolean(true)
                            writeUtf8(data, "الملف مستلم مسبقاً وتم التحقق منه.", 512)
                        }
                        bytes.toByteArray()
                    }
                    writeEncryptedFrame(output, sendKey, noncePrefix, 1L, ack)
                    return@withContext ReceivedFile(completedReceipt, fileName, size, mime)
                }
                if (receiptMarker.exists()) {
                    check(receiptMarker.delete()) { "تعذر تنظيف سجل استلام قديم." }
                }
                val partial = File(destinationDirectory, ".ultrashare-$transferId.part")
                val resumeMarker = File(destinationDirectory, ".ultrashare-$transferId.meta")
                val expectedMarker = "$size:" + expectedDigest.joinToString("") { "%02x".format(it) }
                // Resume metadata is untrusted local state. If it is unreadable,
                // discard the partial data and rebuild the marker instead of failing
                // the whole receive session before the sender can retry.
                val existingMarker = resumeMarker.takeIf { it.isFile }?.let { marker ->
                    runCatching { marker.readText(Charsets.UTF_8) }.getOrNull()
                }
                if (existingMarker != expectedMarker) {
                    if (partial.exists()) check(partial.delete()) { "تعذر تنظيف ملف استئناف غير مطابق." }
                    if (resumeMarker.exists()) check(resumeMarker.delete()) { "تعذر تنظيف بيانات الاستئناف القديمة." }
                    val markerTemp = File(destinationDirectory, ".ultrashare-$transferId.meta.tmp")
                    if (markerTemp.exists()) check(markerTemp.delete()) { "تعذر تنظيف بيانات الاستئناف المؤقتة." }
                    FileOutputStream(markerTemp).use { markerOutput ->
                        markerOutput.write(expectedMarker.toByteArray(Charsets.UTF_8))
                        markerOutput.fd.sync()
                    }
                    check(markerTemp.renameTo(resumeMarker)) { "تعذر حفظ بيانات استئناف النقل." }
                }
                if (partial.exists() && partial.length() > size) {
                    check(partial.delete()) { "تعذر تنظيف ملف استئناف قديم." }
                }
                // A disconnect can leave a full-sized partial before the final
                // receipt/acknowledgement. Reuse it only if its complete digest matches.
                if (partial.isFile && partial.length() == size) {
                    val partialDigest = FileInputStream(partial).use { digestStreamRaw(it) }
                    if (!MessageDigest.isEqual(expectedDigest, partialDigest)) {
                        check(partial.delete()) { "تعذر تنظيف ملف استئناف مكتمل لكنه تالف." }
                    }
                }
                val offset = if (partial.isFile) partial.length() else 0L
                val offsetPayload = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { it.writeLong(offset) }
                    bytes.toByteArray()
                }
                writeEncryptedFrame(output, sendKey, noncePrefix, 0L, offsetPayload)

                val startedAt = System.nanoTime()
                var transferred = offset
                FileOutputStream(partial, true).use { fileOutput ->
                    var sequence = 1L
                    while (transferred < size) {
                        currentCoroutineContext().ensureActive()
                        val chunk = readEncryptedFrame(input, receiveKey, noncePrefix, sequence++, CHUNK_BYTES)
                        require(chunk.isNotEmpty() && chunk.size.toLong() <= size - transferred) {
                            "وصلت كتلة بيانات بحجم غير صالح."
                        }
                        fileOutput.write(chunk)
                        transferred += chunk.size
                        onProgress(progress(fileName, transferred, size, startedAt, true, offset))
                    }
                    fileOutput.fd.sync()
                    val end = readEncryptedFrame(input, receiveKey, noncePrefix, sequence, 64)
                    require(end.isEmpty()) { "إشارة نهاية النقل غير صالحة." }
                }

                val actualDigest = FileInputStream(partial).use { digestStreamRaw(it) }
                val verified = MessageDigest.isEqual(expectedDigest, actualDigest) &&
                    partial.length() == size
                val resultMessage = if (verified) "تم التحقق من سلامة الملف." else "فشل التحقق من سلامة الملف؛ أُزيلت النسخة غير المطابقة ويمكن إعادة الإرسال."
                val finalFile = if (verified) uniqueDestination(destinationDirectory, fileName) else null
                if (verified) {
                    val markerTemp = File(destinationDirectory, ".ultrashare-$transferId.received.tmp")
                    val markerContent = "$size:$expectedDigestHex:" + finalFile!!.name
                    FileOutputStream(markerTemp).use { markerOutput ->
                        markerOutput.write(markerContent.toByteArray(Charsets.UTF_8))
                        markerOutput.fd.sync()
                    }
                    if (receiptMarker.exists()) check(receiptMarker.delete()) {
                        "تعذر تحديث سجل الاستلام."
                    }
                    check(markerTemp.renameTo(receiptMarker)) { "تعذر حفظ سجل الاستلام." }
                    if (!partial.renameTo(finalFile)) {
                        receiptMarker.delete()
                        error("تم استلام الملف لكن تعذر نقله إلى مجلد الملفات المكتملة.")
                    }
                    if (resumeMarker.exists()) resumeMarker.delete()
                } else {
                    partial.delete()
                    resumeMarker.delete()
                }
                val ack = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeBoolean(verified)
                        writeUtf8(data, resultMessage, 512)
                    }
                    bytes.toByteArray()
                }
                writeEncryptedFrame(output, sendKey, noncePrefix, 1L, ack)
                check(verified) { resultMessage }
                onProgress(progress(fileName, size, size, startedAt, true, offset))
                ReceivedFile(requireNotNull(finalFile), fileName, size, mime)
            }
        }
    }

    fun defaultReceiveDirectory(context: android.content.Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        return File(base, "MalekPlay/Received")
    }

    /**
     * Connects through the Android network that actually has a route to the receiver.
     * This matters when Wi-Fi, a phone hotspot and Wi-Fi Direct coexist: the process
     * default route may not be the interface that owns the entered private address.
     * Network.bindSocket is available from API 21 (Android 5), so this path covers
     * the project's Android 6+ support without changing the wire protocol.
     */
    internal fun connectUsingAvailableNetworks(
        address: InetAddress,
        port: Int,
        connectivityManager: ConnectivityManager?
    ): Socket {
        val endpoint = InetSocketAddress(address, port)
        val manager = connectivityManager
        if (manager == null) {
            return Socket().apply {
                try { connect(endpoint, CONNECT_TIMEOUT_MS) } catch (failure: Exception) {
                    runCatching { close() }
                    throw IOException("تعذر الوصول إلى جهاز الاستقبال. تحقق من الشبكة وعنوان IP وأن الاستقبال يعمل.", failure)
                }
            }
        }

        val active = if (android.os.Build.VERSION.SDK_INT >= 23) runCatching { manager.activeNetwork }.getOrNull() else null
        val networks = runCatching { manager.allNetworks.toList() }.getOrDefault(emptyList())
            .distinct()
            // Never spend a connection timeout on a network that cannot route
            // packets to this receiver. Keep the most-specific matching route first.
            .mapNotNull { network ->
                val prefix = runCatching {
                    manager.getLinkProperties(network)?.routes
                        ?.filter { it.matches(address) }
                        ?.maxOfOrNull { it.destination.prefixLength }
                }.getOrNull() ?: return@mapNotNull null
                network to prefix
            }
            .sortedWith(
                compareByDescending<Pair<Network, Int>> { it.second }
                    .thenBy { if (it.first == active) 0 else 1 }
                    .thenBy {
                        runCatching {
                            val caps = manager.getNetworkCapabilities(it.first)
                            if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) 0 else 1
                        }.getOrDefault(1)
                    }
            )
            .take(4)
            .map { it.first }

        var lastFailure: Exception? = null
        for (network in networks) {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.connect(endpoint, CONNECT_TIMEOUT_MS)
                return socket
            } catch (failure: Exception) {
                lastFailure = failure
                runCatching { socket.close() }
            }
        }

        // Some OEM Wi-Fi Direct implementations expose a route without publishing
        // a Network object. Keep a bounded default-route fallback for those devices.
        val fallback = Socket()
        try {
            fallback.connect(endpoint, CONNECT_TIMEOUT_MS)
            return fallback
        } catch (failure: Exception) {
            runCatching { fallback.close() }
            val cause = lastFailure ?: failure
            throw IOException(
                "تعذر الاتصال بعنوان $address:$port عبر شبكات Wi-Fi المتاحة. تأكد من اتصال الهاتفين بالشبكة نفسها، أو استخدم Wi-Fi Direct، وتحقق من أن جهاز الاستقبال يعرض رمز الاقتران.",
                cause
            )
        }
    }

    private const val CONNECT_TIMEOUT_MS = 3_500

    private fun resolvePrivateLanAddress(host: String): InetAddress {
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            "أدخل عنوان IPv4 المحلي الظاهر على جهاز الاستقبال."
        }
        val address = InetAddress.getByName(host)
        require(address is Inet4Address && isPrivateLanAddress(address)) {
            "عنوان الاستقبال يجب أن يكون عنواناً خاصاً أو محلياً على الشبكة."
        }
        return address
    }

    private fun isPrivateLanAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isMulticastAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress)

    private fun uniqueDestination(directory: File, fileName: String): File {
        val original = File(directory, fileName)
        if (!original.exists()) return original
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val stem = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        var index = 1
        while (index < 10_000) {
            val candidate = File(directory, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
        error("تعذر إنشاء اسم فريد للملف المستلم.")
    }

    private suspend fun digestStream(
        stream: InputStream,
        expectedSize: Long
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        // Hashing is local disk I/O, independent from the negotiated wire-frame size.
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = readStreamWithProgress(stream, buffer)
            if (count < 0) break
            total += count
            require(total <= expectedSize) { "تغير حجم الملف أثناء قراءة المصدر." }
            digest.update(buffer, 0, count)
        }
        require(total == expectedSize) { "حجم الملف لا يطابق الحجم المعلن." }
        return digest.digest()
    }

    private suspend fun digestStreamRaw(stream: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    internal fun hmac(secret: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(message)
        }

    private fun deriveSessionKey(secret: ByteArray, challenge: ByteArray, direction: String): ByteArray {
        val label = "MalekPlay Ultra Share v1/$direction".toByteArray(Charsets.UTF_8)
        return hmac(secret, challenge + label)
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray =
        ByteArray(NONCE_PREFIX_BYTES + 8).also { result ->
            prefix.copyInto(result, 0)
            for (index in 0 until 8) {
                result[NONCE_PREFIX_BYTES + index] = (sequence ushr (56 - index * 8)).toByte()
            }
        }

    private fun writeEncryptedFrame(
        output: DataOutputStream,
        key: ByteArray,
        noncePrefix: ByteArray,
        sequence: Long,
        plain: ByteArray,
        plainOffset: Int = 0,
        plainLength: Int = plain.size
    ) {
        require(plainOffset >= 0 && plainLength >= 0 && plainOffset <= plain.size - plainLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(noncePrefix, sequence)))
        val encrypted = cipher.doFinal(plain, plainOffset, plainLength)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    private fun readEncryptedFrame(
        input: DataInputStream,
        key: ByteArray,
        noncePrefix: ByteArray,
        sequence: Long,
        maximumPlainBytes: Int
    ): ByteArray {
        val encryptedSize = input.readInt()
        require(encryptedSize in GCM_TAG_BYTES..(maximumPlainBytes + GCM_TAG_BYTES)) {
            "حجم حزمة النقل غير صالح."
        }
        val encrypted = ByteArray(encryptedSize).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(noncePrefix, sequence)))
        return cipher.doFinal(encrypted).also {
            require(it.size <= maximumPlainBytes) { "تجاوزت حزمة النقل الحد المسموح." }
        }
    }

    private fun writeUtf8(output: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximumBytes) { "بيانات النص أطول من الحد المسموح." }
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readUtf8(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readUnsignedShort()
        require(length <= maximumBytes) { "بيانات النص الواردة أطول من الحد المسموح." }
        val bytes = ByteArray(length).also(input::readFully)
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(64 * 1024)
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val wanted = minOf(discard.size.toLong(), remaining).toInt()
                val read = input.read(discard, 0, wanted)
                require(read > 0) { "تعذر استئناف قراءة الملف من الموضع المطلوب." }
                remaining -= read
            }
        }
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val count = input.read(buffer, total, wanted - total)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[total++] = single.toByte()
            } else {
                total += count
            }
        }
        return total
    }

    private fun progress(
        fileName: String,
        transferred: Long,
        total: Long,
        startedAtNanos: Long,
        receiving: Boolean,
        initialBytes: Long = 0L
    ): Progress {
        val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(1L)
        val newlyTransferred = (transferred - initialBytes).coerceAtLeast(0L)
        val rate = ((newlyTransferred.toDouble() * 1_000_000_000.0) / elapsed.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong().coerceAtLeast(0L)
        return Progress(fileName, transferred, total, rate, receiving)
    }
}
