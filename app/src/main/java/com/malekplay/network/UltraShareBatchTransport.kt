package com.malekplay.network

import android.net.Network
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import java.io.IOException
import java.net.Inet4Address
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
 * Ultra Share v2 data plane.
 *
 * One TCP connection carries one complete manifest and every file in that batch.
 * The receiver listener owns one server socket and dispatches bounded, isolated
 * workers for concurrently connected peers.
 */
object UltraShareBatchTransport {
    const val DEFAULT_MAX_PEERS = 4
    const val DEFAULT_PORT = UltraShareTransport.DEFAULT_PORT + 1
    private const val BUFFER_BYTES = 1024 * 1024
    private const val FRAME_BYTES = 256 * 1024
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private const val SESSION_TIMEOUT_MS = 30_000
    private const val CHALLENGE_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4
    private const val TAG_BYTES = 16
    private const val TYPE_CHUNK = 1
    private const val TYPE_FILE_END = 2
    private const val TYPE_BATCH_END = 3
    private val peerMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    data class Source(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val fileId: String = java.util.UUID.randomUUID().toString()
    )

    data class FileProgress(
        val fileId: String,
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val receiving: Boolean
    )

    data class FileOutcome(
        val fileId: String,
        val fileName: String,
        val receivedFile: UltraShareTransport.ReceivedFile?,
        val status: UltraShareBatchProtocol.FileStatus,
        val transferredBytes: Long,
        val error: String? = null
    )

    data class BatchOutcome(val batchId: String, val files: List<FileOutcome>)

    /**
     * Starts one listener for all peers. Each accepted socket is handled in its own
     * coroutine, with a hard concurrency ceiling and independent socket cancellation.
     */
    suspend fun serveMany(
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        destinationDirectory: File,
        maxPeers: Int = DEFAULT_MAX_PEERS,
        onPeerStarted: (String) -> Unit = {},
        onPeerFinished: (String) -> Unit = {},
        onProgress: (String, FileProgress) -> Unit = { _, _ -> },
        onBatchFinished: suspend (String, BatchOutcome) -> Unit = { _, _ -> },
        onPeerError: (String, Throwable) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        require(port in 1..65535)
        require(maxPeers in 1..8) { "عدد الأجهزة المتزامنة يجب أن يكون بين 1 و8." }
        check(destinationDirectory.exists() || destinationDirectory.mkdirs())
        check(destinationDirectory.isDirectory)
        val permits = Semaphore(maxPeers)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), maxPeers * 2)
            server.soTimeout = 1_000
            coroutineScope {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val socket = server.accept()
                        if (socket.inetAddress !is Inet4Address ||
                            !(socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLinkLocalAddress)) {
                            socket.close()
                            continue
                        }
                        if (!permits.tryAcquire()) {
                            runCatching { socket.close() }
                            continue
                        }
                        launch {
                            val peer = socket.inetAddress.hostAddress ?: "unknown"
                            var authenticated = false
                            try {
                                val peerMutex = peerMutexes[peer] ?: Mutex().let { candidate ->
                                    peerMutexes.putIfAbsent(peer, candidate) ?: candidate
                                }
                                val result = peerMutex.withLock {
                                    val peerState = File(
                                        destinationDirectory,
                                        ".ultrashare-v2-state/" + MessageDigest.getInstance("SHA-256")
                                            .digest(peer.toByteArray(Charsets.UTF_8))
                                            .take(12).joinToString("") { "%02x".format(it) }
                                    )
                                    check(peerState.exists() || peerState.mkdirs())
                                    receiveSession(
                                        socket, pairingSecret, destinationDirectory, peerState,
                                        onAuthenticated = {
                                            authenticated = true
                                            onPeerStarted(peer)
                                        },
                                        onProgress = { progress -> onProgress(peer, progress) }
                                    )
                                }
                                onBatchFinished(peer, result)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: DiscoveryProbeConnection) {
                                // A deliberate reachability probe is not a failed transfer.
                            } catch (failure: Exception) {
                                onPeerError(peer, failure)
                            } finally {
                                runCatching { socket.close() }
                                if (authenticated) runCatching { onPeerFinished(peer) }
                                permits.release()
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Periodic cancellation check keeps stop-service responsive.
                    }
                }
            }
        }
    }

    suspend fun sendBatch(
        resolver: ContentResolver,
        sources: List<Source>,
        host: String,
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        network: Network? = null,
        onProgress: (FileProgress) -> Unit = {}
    ): BatchOutcome = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty() && sources.size <= UltraShareBatchProtocol.MAX_FILES)
        require(UltraShareTransport.isValidPrivateLanHost(host))
        require(UltraShareTransport.isValidPairingSecret(pairingSecret))
        if (!UltraShareControlChannel.isSessionActive(host)) {
            throw IOException("انتهت قناة الموافقة؛ يجب إعادة الاتصال قبل بدء نقل V2.")
        }
        val prepared = sources.map { source ->
            require(source.sizeBytes in 0..UltraShareTransport.MAX_FILE_BYTES)
            val name = UltraShareTransport.sanitizeFileName(source.name)
            val mime = UltraShareTransport.sanitizeMimeType(source.mimeType)
            val digest = resolver.openInputStream(source.uri)?.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_BYTES)
                var countTotal = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    countTotal += count
                    require(countTotal <= source.sizeBytes) { "تغير حجم الملف أثناء التجهيز: $name" }
                    md.update(buffer, 0, count)
                }
                require(countTotal == source.sizeBytes) { "حجم الملف لا يطابق بياناته: $name" }
                md.digest()
            } ?: error("تعذر فتح الملف: $name")
            Triple(source, UltraShareBatchProtocol.newEntry(name, mime, source.sizeBytes, digest, source.fileId), digest)
        }
        val manifest = UltraShareBatchProtocol.Manifest(
            java.util.UUID.randomUUID().toString(), prepared.map { it.second }
        )
        val target = java.net.InetAddress.getByName(host)
        Socket().use { socket ->
            // Use the same Android network selected for this peer's control channel.
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(target, port), HANDSHAKE_TIMEOUT_MS)
            configure(socket)
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
            check(input.readInt() == HANDSHAKE_MAGIC) { "الجهاز الآخر لا يدعم جلسة بيانات V2." }
            val challenge = ByteArray(CHALLENGE_BYTES).also(input::readFully)
            val secret = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
            output.write(hmac(secret, challenge))
            output.flush()
            check(input.readBoolean()) { "رفض الجهاز المستقبل رمز الاقتران." }
            val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(input::readFully)
            val sendKey = deriveKey(secret, challenge, "client-to-server")
            val receiveKey = deriveKey(secret, challenge, "server-to-client")
            var sendSequence = 0L
            var receiveSequence = 0L

            writeFrame(output, sendKey, noncePrefix, sendSequence++, UltraShareBatchProtocol.encode(manifest))
            val offsetsPayload = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 32 * 1024)
            val offsets = decodeOffsets(offsetsPayload, manifest)
            val outcomes = ArrayList<UltraShareBatchProtocol.FileResult>(manifest.entries.size)

            prepared.forEachIndexed { index, (source, entry, _) ->
                currentCoroutineContext().ensureActive()
                val offset = offsets.getValue(entry.id)
                var transferred = offset
                val started = System.nanoTime()
                resolver.openInputStream(source.uri)?.use { raw ->
                    BufferedInputStream(raw, BUFFER_BYTES).use { stream ->
                        skipFully(stream, offset)
                        val buffer = ByteArray(FRAME_BYTES - 8)
                        while (transferred < entry.sizeBytes) {
                            currentCoroutineContext().ensureActive()
                            val wanted = minOf(buffer.size.toLong(), entry.sizeBytes - transferred).toInt()
                            val read = readAtMost(stream, buffer, wanted)
                            check(read > 0) { "انتهى الملف قبل اكتمال الإرسال: ${entry.name}" }
                            val frame = ByteArrayOutputStream(read + 8)
                            DataOutputStream(frame).use { data ->
                                data.writeByte(TYPE_CHUNK)
                                data.writeShort(index)
                                data.writeInt(read)
                                data.write(buffer, 0, read)
                            }
                            writeFrame(output, sendKey, noncePrefix, sendSequence++, frame.toByteArray())
                            transferred += read
                            onProgress(FileProgress(
                                entry.id, entry.name, transferred, entry.sizeBytes,
                                rate(transferred - offset, started), false
                            ))
                        }
                        check(stream.read() == -1) { "تغير حجم الملف أثناء الإرسال: ${entry.name}" }
                    }
                } ?: error("تعذر إعادة فتح الملف: ${entry.name}")
                val end = ByteArrayOutputStream()
                DataOutputStream(end).use { data -> data.writeByte(TYPE_FILE_END); data.writeShort(index) }
                writeFrame(output, sendKey, noncePrefix, sendSequence++, end.toByteArray())
                outcomes += UltraShareBatchProtocol.FileResult(
                    entry.id, UltraShareBatchProtocol.FileStatus.COMPLETED,
                    entry.sizeBytes
                )
            }
            writeFrame(output, sendKey, noncePrefix, sendSequence++, byteArrayOf(TYPE_BATCH_END.toByte()))
            val results = decodeResults(
                readFrame(input, receiveKey, noncePrefix, receiveSequence, UltraShareBatchProtocol.MAX_MANIFEST_BYTES), manifest
            )
            val byId = results.associateBy { it.fileId }
            BatchOutcome(manifest.batchId, manifest.entries.map { entry ->
                val result = byId.getValue(entry.id)
                FileOutcome(entry.id, entry.name, null, result.status, result.transferredBytes, result.error)
            })
        }
    }

    private suspend fun receiveSession(
        socket: Socket,
        pairingSecret: String,
        destinationDirectory: File,
        stateDirectory: File,
        onAuthenticated: () -> Unit,
        onProgress: (FileProgress) -> Unit
    ): BatchOutcome {
        configure(socket)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
        output.writeInt(HANDSHAKE_MAGIC)
        val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        val secret = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
        output.write(challenge)
        output.flush()
        val proof = ByteArray(32).also(input::readFully)
        if (isDiscoveryProbeProof(proof)) throw DiscoveryProbeConnection()
        val authenticated = MessageDigest.isEqual(proof, hmac(secret, challenge))
        val peerHost = socket.inetAddress.hostAddress.orEmpty()
        // Do not reveal whether the short pairing secret was correct until the
        // independent control-channel approval for this peer has also been verified.
        val approved = isPeerSessionAuthorized(
            authenticated = authenticated,
            sessionActive = authenticated && UltraShareControlChannel.isServerSessionActive(peerHost)
        )
        output.writeBoolean(approved)
        output.flush()
        check(approved) {
            if (!authenticated) "فشل التحقق من رمز الاقتران."
            else "لا توجد موافقة اتصال نشطة لهذا الجهاز."
        }
        onAuthenticated()
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
        output.write(noncePrefix)
        output.flush()
        socket.soTimeout = SESSION_TIMEOUT_MS
        val receiveKey = deriveKey(secret, challenge, "client-to-server")
        val sendKey = deriveKey(secret, challenge, "server-to-client")
        var receiveSequence = 0L
        var sendSequence = 0L

        val manifest = UltraShareBatchProtocol.decode(
            readFrame(input, receiveKey, noncePrefix, receiveSequence++, UltraShareBatchProtocol.MAX_MANIFEST_BYTES)
        )
        val completedFiles = HashMap<String, File>()
        val partials = manifest.entries.map { entry ->
            val identity = UltraShareTransport.buildTransferId(entry.name, entry.sizeBytes, entry.sha256)
            val partial = File(stateDirectory, ".ultrashare-v2-$identity.part")
            val receipt = File(stateDirectory, ".ultrashare-v2-$identity.received")
            val digestHex = entry.sha256.joinToString("") { "%02x".format(it) }
            val completed = receipt.takeIf { it.isFile }?.let { marker ->
                runCatching {
                    val safeName = UltraShareTransport.completedReceiptFileName(
                        marker.readText(Charsets.UTF_8), entry.sizeBytes, digestHex
                    ) ?: return@runCatching null
                    val candidate = File(destinationDirectory, safeName)
                    if (!candidate.isFile || candidate.length() != entry.sizeBytes) return@runCatching null
                    val digest = FileInputStream(candidate).use { stream ->
                        val md = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count > 0) md.update(buffer, 0, count)
                        }
                        md.digest()
                    }
                    candidate.takeIf { MessageDigest.isEqual(entry.sha256, digest) }
                }.getOrNull()
            }
            if (completed != null) completedFiles[entry.id] = completed
            val offset = if (completed != null) entry.sizeBytes
                else if (partial.isFile && partial.length() <= entry.sizeBytes) partial.length()
                else 0L
            if (offset == 0L && partial.exists()) partial.delete()
            entry.id to Pair(partial, offset)
        }.toMap()
        val offsets = ByteArrayOutputStream()
        DataOutputStream(offsets).use { data ->
            data.writeShort(manifest.entries.size)
            manifest.entries.forEach { entry ->
                data.writeUTF(entry.id)
                data.writeLong(partials.getValue(entry.id).second)
            }
        }
        writeFrame(output, sendKey, noncePrefix, sendSequence++, offsets.toByteArray())

        val outcomes = ArrayList<FileOutcome>(manifest.entries.size)
        for ((index, entry) in manifest.entries.withIndex()) {
            val (partial, offset) = partials.getValue(entry.id)
            var transferred = offset
            if (completedFiles[entry.id] == null) {
                FileOutputStream(partial, offset > 0L).use { file ->
                    try {
                        val startedAt = System.nanoTime()
                        while (transferred < entry.sizeBytes) {
                            currentCoroutineContext().ensureActive()
                            val remaining = entry.sizeBytes - transferred
                            val maximumPayload = minOf(FRAME_BYTES.toLong(), remaining + 7L).toInt()
                            val payload = readFrame(input, receiveKey, noncePrefix, receiveSequence++, maximumPayload)
                            val frame = DataInputStream(ByteArrayInputStream(payload))
                            require(frame.readUnsignedByte() == TYPE_CHUNK) { "إطار بيانات غير متوقع." }
                            require(frame.readUnsignedShort() == index) { "وصل جزء لملف غير متوقع." }
                            val length = frame.readInt()
                            require(length in 1..(FRAME_BYTES - 8) && length == frame.available())
                            require(transferred + length <= entry.sizeBytes)
                            val bytes = ByteArray(length).also(frame::readFully)
                            file.write(bytes)
                            transferred += length
                            onProgress(FileProgress(
                                entry.id, entry.name, transferred, entry.sizeBytes,
                                rate(transferred - offset, startedAt), true
                            ))
                        }
                    } finally {
                        // Make the partial offset durable even when the socket drops mid-file.
                        runCatching { file.fd.sync() }
                    }
                }
            }
            val endPayload = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 64)
            DataInputStream(ByteArrayInputStream(endPayload)).use { data ->
                require(data.readUnsignedByte() == TYPE_FILE_END && data.readUnsignedShort() == index && data.available() == 0)
            }
            val previouslyCompleted = completedFiles[entry.id]
            val destination = if (previouslyCompleted != null) {
                previouslyCompleted
            } else {
                val actualDigest = FileInputStream(partial).use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                    digest.digest()
                }
                if (!MessageDigest.isEqual(entry.sha256, actualDigest)) {
                    runCatching { partial.delete() }
                    outcomes += FileOutcome(
                        entry.id, entry.name, null, UltraShareBatchProtocol.FileStatus.FAILED,
                        transferred, "فشل التحقق من بصمة الملف."
                    )
                    continue
                }
                UltraShareTransport.withDestinationPublicationLock(destinationDirectory) {
                    val target = uniqueDestination(destinationDirectory, entry.name)
                    val identity = UltraShareTransport.buildTransferId(entry.name, entry.sizeBytes, entry.sha256)
                    val marker = File(stateDirectory, ".ultrashare-v2-$identity.received")
                    val markerTemp = File(stateDirectory, ".ultrashare-v2-$identity.received.tmp")
                    val receiptContent =
                        "${entry.sizeBytes}:${entry.sha256.joinToString("") { "%02x".format(it) }}:${target.name}"
                    // Persist the receipt before publishing the renamed file. If the process
                    // dies between these operations, the next session ignores the receipt
                    // while the verified partial remains available for a safe retry.
                    writeUltraShareReceiptAtomically(markerTemp, marker, receiptContent)
                    try {
                        check(partial.renameTo(target)) { "تعذر تثبيت الملف المستلم." }
                    } catch (failure: Exception) {
                        runCatching { marker.delete() }
                        throw failure
                    }
                    target
                }
            }
            outcomes += FileOutcome(
                entry.id, entry.name,
                UltraShareTransport.ReceivedFile(destination, entry.name, entry.sizeBytes, entry.mimeType),
                UltraShareBatchProtocol.FileStatus.COMPLETED, entry.sizeBytes
            )
        }
        val batchEnd = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 64)
        require(batchEnd.contentEquals(byteArrayOf(TYPE_BATCH_END.toByte()))) { "نهاية الدفعة غير صالحة." }
        val resultPayload = encodeResults(manifest, outcomes)
        writeFrame(output, sendKey, noncePrefix, sendSequence, resultPayload)
        return BatchOutcome(manifest.batchId, outcomes)
    }

    private fun decodeOffsets(payload: ByteArray, manifest: UltraShareBatchProtocol.Manifest): Map<String, Long> {
        val result = DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val count = data.readUnsignedShort()
            require(count == manifest.entries.size)
            buildMap {
                repeat(count) {
                    val id = data.readUTF()
                    val offset = data.readLong()
                    val entry = manifest.entries.firstOrNull { it.id == id } ?: error("معرّف ملف غير معروف.")
                    require(offset in 0..entry.sizeBytes)
                    require(put(id, offset) == null)
                }
                require(data.available() == 0)
            }
        }
        require(result.keys == manifest.entries.map { it.id }.toSet())
        return result
    }

    private fun encodeResults(manifest: UltraShareBatchProtocol.Manifest, outcomes: List<FileOutcome>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeShort(outcomes.size)
            outcomes.forEach { outcome ->
                data.writeUTF(outcome.fileId)
                data.writeByte(outcome.status.ordinal)
                data.writeLong(outcome.transferredBytes)
                val error = outcome.error.orEmpty().take(240)
                data.writeUTF(error)
            }
        }
        return bytes.toByteArray()
    }

    private fun decodeResults(payload: ByteArray, manifest: UltraShareBatchProtocol.Manifest): List<UltraShareBatchProtocol.FileResult> {
        val results = DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val count = data.readUnsignedShort()
            require(count == manifest.entries.size)
            List(count) {
                val id = data.readUTF()
                val status = UltraShareBatchProtocol.FileStatus.values()[data.readUnsignedByte()]
                val bytes = data.readLong()
                val error = data.readUTF().ifBlank { null }
                UltraShareBatchProtocol.FileResult(id, status, bytes, error)
            }.also { require(data.available() == 0) }
        }
        return UltraShareBatchProtocol.validateResults(manifest, results)
    }

    internal fun isPeerSessionAuthorized(authenticated: Boolean, sessionActive: Boolean): Boolean =
        authenticated && sessionActive

    private fun configure(socket: Socket) {
        socket.sendBufferSize = BUFFER_BYTES
        socket.receiveBufferSize = BUFFER_BYTES
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    private fun writeFrame(output: DataOutputStream, key: ByteArray, prefix: ByteArray, sequence: Long, plain: ByteArray) {
        require(plain.size <= UltraShareBatchProtocol.MAX_MANIFEST_BYTES + 64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, sequence)))
        val encrypted = cipher.doFinal(plain)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    private fun readFrame(input: DataInputStream, key: ByteArray, prefix: ByteArray, sequence: Long, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in TAG_BYTES..(maximum + TAG_BYTES))
        val encrypted = ByteArray(size).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, sequence)))
        return cipher.doFinal(encrypted).also { require(it.size <= maximum) }
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray =
        ByteArray(12).also { result ->
            prefix.copyInto(result)
            repeat(8) { index -> result[4 + index] = (sequence ushr (56 - 8 * index)).toByte() }
        }

    private fun deriveKey(secret: ByteArray, challenge: ByteArray, direction: String): ByteArray =
        hmac(secret, challenge + "MalekPlay Ultra Share v2/$direction".toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(payload)
        }

    private suspend fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "تعذر الوصول إلى موضع الاستئناف." }
                remaining -= read
            }
        }
    }

    private fun readAtMost(input: java.io.InputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val count = input.read(buffer, total, wanted - total)
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                buffer[total++] = value.toByte()
            } else total += count
        }
        return total
    }

    private fun rate(bytes: Long, started: Long): Long =
        ((bytes.toDouble() * 1_000_000_000.0) / (System.nanoTime() - started).coerceAtLeast(1L))
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()

    private fun uniqueDestination(directory: File, name: String): File {
        val safe = UltraShareTransport.sanitizeFileName(name)
        val original = File(directory, safe)
        if (!original.exists()) return original
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val stem = safe.substring(0, dot)
        val ext = safe.substring(dot)
        var index = 1
        while (index < 10_000) {
            val candidate = File(directory, "$stem ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
        error("تعذر إنشاء اسم فريد للملف.")
    }

    private const val HANDSHAKE_MAGIC = 0x4D505532 // MPU2 data-session handshake
    private val DISCOVERY_PROBE_PROOF = ByteArray(32) { 0x5A }

    internal fun discoveryProbeProof(): ByteArray = DISCOVERY_PROBE_PROOF.copyOf()

    internal fun isDiscoveryProbeProof(proof: ByteArray): Boolean =
        MessageDigest.isEqual(proof, DISCOVERY_PROBE_PROOF)
}

private class DiscoveryProbeConnection : Exception("Discovery reachability probe")


/**
 * Writes a completed-transfer receipt through a synced temporary file and atomic rename.
 * The receipt is committed before the data file is renamed, avoiding an untracked
 * completed file if the process is killed during finalization.
 */
internal fun writeUltraShareReceiptAtomically(temp: File, marker: File, content: String) {
    require(temp.parentFile?.canonicalFile == marker.parentFile?.canonicalFile) {
        "يجب أن يكون ملف سجل النقل المؤقت والنهائي في المجلد نفسه."
    }
    FileOutputStream(temp).use { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    // File.renameTo replacement behavior varies by filesystem; remove an older
    // invalid receipt explicitly so the synced temporary file can be committed.
    if (marker.exists()) check(marker.delete()) { "تعذر استبدال سجل الاستلام القديم." }
    check(temp.renameTo(marker)) { "تعذر حفظ سجل اكتمال الملف." }
}
