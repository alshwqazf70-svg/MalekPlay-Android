package com.malekplay.network

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
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
object UltraShareTransport {
    const val DEFAULT_PORT = 38741
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 100L * 1024 * 1024 * 1024
    private const val MAGIC = 0x4D505531 // MPU1
    private const val VERSION = 1
    private const val SECRET_BYTES = 32
    private const val CHALLENGE_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4
    private const val GCM_TAG_BYTES = 16
    private const val MAX_FRAME_BYTES = CHUNK_BYTES + GCM_TAG_BYTES
    private const val SOCKET_TIMEOUT_MS = 20_000

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
        val mimeType: String
    )

    fun createPairingSecret(): String {
        val bytes = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidPairingSecret(value: String): Boolean =
        value.length == SECRET_BYTES * 2 && value.all { it in "0123456789abcdefABCDEF" }

    private fun decodeSecret(value: String): ByteArray {
        require(isValidPairingSecret(value)) { "رمز الاقتران غير صالح." }
        return ByteArray(SECRET_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .filter { it >= ' ' && it != '\u007f' && it != ':' && it != '*' &&
                it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
            .trim()
            .trim('.')
            .take(180)
        require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            "اسم الملف غير صالح."
        }
        return cleaned
    }

    suspend fun send(
        resolver: ContentResolver,
        uri: Uri,
        host: String,
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        fileName: String,
        mimeType: String = "application/octet-stream",
        sizeBytes: Long,
        onProgress: (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "أدخل عنوان الجهاز المستقبل." }
        require(port in 1..65535) { "منفذ الاتصال غير صالح." }
        require(sizeBytes in 1..MAX_FILE_BYTES) { "حجم الملف غير مدعوم أو الملف فارغ." }
        val safeName = sanitizeFileName(fileName)
        val secret = decodeSecret(pairingSecret)
        val digest = resolver.openInputStream(uri)?.use { stream ->
            digestStream(stream, sizeBytes, safeName, onProgress, receiving = false, reportProgress = false)
        } ?: error("تعذر فتح الملف المحدد.")

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            check(input.readInt() == MAGIC) { "الجهاز الآخر لا يشغّل MalekPlay Ultra Share." }
            val challenge = ByteArray(CHALLENGE_BYTES).also(input::readFully)
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
                    writeUtf8(data, safeName, 512)
                    data.writeLong(sizeBytes)
                    writeUtf8(data, mimeType.take(160), 192)
                    data.write(digest)
                }
                bytes.toByteArray()
            }
            writeEncryptedFrame(output, sendKey, noncePrefix, 0L, metadata)

            val offsetPayload = readEncryptedFrame(input, receiveKey, noncePrefix, 0L, 64)
            val offset = DataInputStream(ByteArrayInputStream(offsetPayload)).use { it.readLong() }
            require(offset in 0..sizeBytes) { "موضع الاستئناف الذي أرسله المستقبل غير صالح." }

            val startedAt = System.nanoTime()
            var transferred = offset
            resolver.openInputStream(uri)?.use { source ->
                skipFully(source, offset)
                val buffer = ByteArray(CHUNK_BYTES)
                var read: Int
                var sequence = 1L
                while (transferred < sizeBytes) {
                    currentCoroutineContext().ensureActive()
                    val wanted = minOf(buffer.size.toLong(), sizeBytes - transferred).toInt()
                    read = readAtMost(source, buffer, wanted)
                    check(read > 0) { "انتهى الملف قبل اكتمال نقله." }
                    writeEncryptedFrame(output, sendKey, noncePrefix, sequence++, buffer.copyOf(read))
                    transferred += read
                    onProgress(progress(safeName, transferred, sizeBytes, startedAt, false))
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
            onProgress(progress(safeName, sizeBytes, sizeBytes, startedAt, false))
        }
    }

    suspend fun receiveOnce(
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        destinationDirectory: File,
        onProgress: (Progress) -> Unit = {}
    ): ReceivedFile = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "منفذ الاستقبال غير صالح." }
        val secret = decodeSecret(pairingSecret)
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
                    accepted = server.accept()
                } catch (_: SocketTimeoutException) {
                    // Poll cancellation while keeping the receiver available for the user.
                }
            }
            accepted.use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                output.writeInt(MAGIC)
                val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
                output.write(challenge)
                output.flush()

                val suppliedProof = ByteArray(32).also(input::readFully)
                val authenticated = MessageDigest.isEqual(suppliedProof, hmac(secret, challenge))
                output.writeBoolean(authenticated)
                output.flush()
                check(authenticated) { "فشل التحقق من رمز الاقتران." }

                val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
                output.write(noncePrefix)
                output.flush()
                val receiveKey = deriveSessionKey(secret, challenge, "client-to-server")
                val sendKey = deriveSessionKey(secret, challenge, "server-to-client")

                val metadataBytes = readEncryptedFrame(input, receiveKey, noncePrefix, 0L, 1024)
                val metadata = DataInputStream(ByteArrayInputStream(metadataBytes))
                require(metadata.readUnsignedShort() == VERSION) { "إصدار بروتوكول النقل غير مدعوم." }
                val fileName = sanitizeFileName(readUtf8(metadata, 512))
                val size = metadata.readLong()
                require(size in 1..MAX_FILE_BYTES) { "حجم الملف المرسل غير مسموح." }
                val mime = readUtf8(metadata, 192).take(160)
                val expectedDigest = ByteArray(32).also(metadata::readFully)
                require(metadata.available() == 0) { "بيانات الملف المرسل غير مكتملة." }

                val partial = File(destinationDirectory, ".$fileName.ultrashare.part")
                val resumeMarker = File(destinationDirectory, ".$fileName.ultrashare.meta")
                val expectedMarker = "$size:" + expectedDigest.joinToString("") { "%02x".format(it) }
                val existingMarker = resumeMarker.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                if (existingMarker != expectedMarker) {
                    if (partial.exists()) check(partial.delete()) { "تعذر تنظيف ملف استئناف غير مطابق." }
                    if (resumeMarker.exists()) check(resumeMarker.delete()) { "تعذر تنظيف بيانات الاستئناف القديمة." }
                    val markerTemp = File(destinationDirectory, ".$fileName.ultrashare.meta.tmp")
                    markerTemp.writeText(expectedMarker, Charsets.UTF_8)
                    check(markerTemp.renameTo(resumeMarker)) { "تعذر حفظ بيانات استئناف النقل." }
                }
                if (partial.exists() && partial.length() > size) {
                    check(partial.delete()) { "تعذر تنظيف ملف استئناف قديم." }
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
                        onProgress(progress(fileName, transferred, size, startedAt, true))
                    }
                    fileOutput.fd.sync()
                    val end = readEncryptedFrame(input, receiveKey, noncePrefix, sequence, 64)
                    require(end.isEmpty()) { "إشارة نهاية النقل غير صالحة." }
                }

                val actualDigest = FileInputStream(partial).use { digestStreamRaw(it) }
                val verified = MessageDigest.isEqual(expectedDigest, actualDigest) &&
                    partial.length() == size
                val resultMessage = if (verified) "تم التحقق من سلامة الملف." else "فشل التحقق من سلامة الملف؛ احتفظنا بالجزء لاستكمال المحاولة."
                val finalFile = if (verified) uniqueDestination(destinationDirectory, fileName) else null
                if (verified) {
                    check(partial.renameTo(finalFile!!)) { "تم استلام الملف لكن تعذر نقله إلى مجلد الملفات المكتملة." }
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
                onProgress(progress(fileName, size, size, startedAt, true))
                ReceivedFile(requireNotNull(finalFile), fileName, size, mime)
            }
        }
    }

    fun defaultReceiveDirectory(context: android.content.Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        return File(base, "MalekPlay/Received")
    }

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

    private fun digestStream(
        stream: InputStream,
        expectedSize: Long,
        fileName: String,
        onProgress: (Progress) -> Unit,
        receiving: Boolean,
        reportProgress: Boolean
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedSize) { "تغير حجم الملف أثناء قراءة المصدر." }
            digest.update(buffer, 0, count)
            if (reportProgress) onProgress(Progress(fileName, total, expectedSize, 0L, receiving))
        }
        require(total == expectedSize) { "حجم الملف لا يطابق الحجم المعلن." }
        return digest.digest()
    }

    private fun digestStreamRaw(stream: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun hmac(secret: ByteArray, message: ByteArray): ByteArray =
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
        plain: ByteArray
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(noncePrefix, sequence)))
        val encrypted = cipher.doFinal(plain)
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

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                require(input.read() >= 0) { "تعذر استئناف قراءة الملف من الموضع المطلوب." }
                remaining--
            }
        }
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val count = input.read(buffer, total, wanted - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun progress(
        fileName: String,
        transferred: Long,
        total: Long,
        startedAtNanos: Long,
        receiving: Boolean
    ): Progress {
        val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(1L)
        val rate = ((transferred * 1_000_000_000L) / elapsed).coerceAtLeast(0L)
        return Progress(fileName, transferred, total, rate, receiving)
    }
}
