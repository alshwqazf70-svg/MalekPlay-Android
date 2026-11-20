package com.malekplay.network

import android.net.Network
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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
    const val DEFAULT_MAX_PEERS = 8
    internal const val MAX_PARALLEL_SOURCE_PREPARATION = 2
    const val MAX_CONCURRENT_PEERS = 16

    /** Scale parallel receive workers to the Android app heap rather than risking OOM on old phones. */
    internal fun recommendedMaxPeers(memoryClassMb: Int): Int {
        require(memoryClassMb > 0)
        return when {
            memoryClassMb >= 512 -> MAX_CONCURRENT_PEERS
            memoryClassMb >= 256 -> DEFAULT_MAX_PEERS
            else -> 4
        }
    }

    const val DEFAULT_PORT = 38_742
    const val COPY_BUFFER_BYTES = 1024 * 1024
    private const val BUFFER_BYTES = 1024 * 1024
    internal const val SOCKET_BUFFER_BYTES = 2 * 1024 * 1024
    // 1 MiB encrypted payloads amortize frame headers and AES-GCM setup on Wi-Fi.
    // Buffers are allocated once per session and reused for every chunk.
    internal const val CHUNK_BYTES = 1024 * 1024
    private const val PROGRESS_UPDATE_INTERVAL_BYTES = 1024L * 1024L
    private const val PROGRESS_UPDATE_INTERVAL_NANOS = 80_000_000L
    // A checkpoint is committed only after the corresponding partial bytes are fsynced.
    private const val RESUME_CHECKPOINT_INTERVAL_BYTES = 4L * 1024L * 1024L
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private const val SESSION_TIMEOUT_MS = 30_000
    // Verification and publication may take longer than an ordinary data-frame gap,
    // especially for multi-gigabyte files on slower storage.
    private const val MIN_FILE_VERIFY_TIMEOUT_MS = 120_000
    // Leave headroom below Android's six-hour dataSync foreground-service limit.
    private const val MAX_FILE_VERIFY_TIMEOUT_MS = 5 * 60 * 60 * 1_000 + 30 * 60 * 1_000
    private const val VERIFY_BYTES_PER_SECOND = 2L * 1024L * 1024L
    private const val CHALLENGE_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4
    private const val TAG_BYTES = 16
    private const val TYPE_CHUNK = 1
    private const val TYPE_FILE_END = 2
    private const val TYPE_BATCH_END = 3
    private val peerMutexRegistry = UltraSharePeerMutexRegistry()
    private val failedPairingAttempts = HashMap<String, Pair<Int, Long>>()

    private fun enforcePairingCooldown(peerHost: String) {
        synchronized(failedPairingAttempts) {
            val state = failedPairingAttempts[peerHost] ?: return
            val now = System.nanoTime()
            if (state.second > now) throw IOException("تم إيقاف محاولات الاقتران مؤقتاً لهذا الجهاز.")
            if (state.second != 0L) failedPairingAttempts.remove(peerHost)
        }
    }

    private fun recordPairingResult(peerHost: String, authenticated: Boolean) {
        synchronized(failedPairingAttempts) {
            if (authenticated) failedPairingAttempts.remove(peerHost) else {
                val attempts = (failedPairingAttempts[peerHost]?.first ?: 0) + 1
                val cooldownUntil = if (attempts >= 5) System.nanoTime() + 60_000_000_000L else 0L
                failedPairingAttempts[peerHost] = (if (attempts >= 5) 0 else attempts) to cooldownUntil
            }
        }
    }

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
        val receivedFile: UltraShareCore.ReceivedFile?,
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
        onPeerError: (String, Throwable) -> Unit = { _, _ -> },
        onListening: (Int) -> Unit = {},
        pairingSecretResolver: ((String) -> String?)? = null
    ) = withContext(Dispatchers.IO) {
        require(UltraShareCore.isValidPairingSecret(pairingSecret))
        require(port in 0..65535)
        require(maxPeers in 1..MAX_CONCURRENT_PEERS) { "عدد الأجهزة المتزامنة يتجاوز الحد الآمن لهذا الإصدار." }
        check(destinationDirectory.exists() || destinationDirectory.mkdirs())
        check(destinationDirectory.isDirectory)
        val permits = Semaphore(maxPeers)
        // Publication is disk/MediaStore work and must not occupy a receive socket
        // permit. Bound it independently so slow Downloads copies cannot make the
        // listener reject the next queued files with a false "connection refused".
        val publicationPermits = Semaphore(minOf(3, maxPeers).coerceAtLeast(1))
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port), maxPeers * 2)
            onListening(server.localPort)
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
                            val socketCancellation = closeSocketOnJobCancellation(
                                currentCoroutineContext()[Job] ?: error("جلسة النقل بلا Job نشط."),
                                socket
                            )
                            try {
                                val peerIdentity = UltraShareControlChannel.authenticatedPeerIdForHost(peer) ?: peer
                                // Resume checkpoints are scoped to the receive directory.
                                // Include that directory in the lock key so independent rooms/
                                // endpoints never block each other merely because they share a peer.
                                val receiveLockKey = destinationDirectory.canonicalPath + "|" + peerIdentity
                                val result = peerMutexRegistry.withLock(receiveLockKey) {
                                    val peerState = File(
                                        destinationDirectory,
                                        ".ultrashare-v2-state/" + MessageDigest.getInstance("SHA-256")
                                            .digest(peerIdentity.toByteArray(Charsets.UTF_8))
                                            .take(12).joinToString("") { "%02x".format(it) }
                                    )
                                    check(peerState.exists() || peerState.mkdirs())
                                    receiveSession(
                                        socket, pairingSecret, pairingSecretResolver,
                                        destinationDirectory, peerState,
                                        onAuthenticated = {
                                            authenticated = true
                                            onPeerStarted(peer)
                                        },
                                        onProgress = { progress -> onProgress(peer, progress) }
                                    )
                                }
                                // receiveSession has already sent the authenticated batch
                                // receipt. Run slow MediaStore publication independently so
                                // this accepted socket can close and the receive permit is
                                // released before the next queue connection arrives.
                                launch {
                                    publicationPermits.withPermit {
                                        try {
                                            onBatchFinished(peer, result)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (publicationFailure: Exception) {
                                            UltraShareDiagnostics.failure(
                                                "DATA_RECEIVE_PUBLICATION_CALLBACK",
                                                publicationFailure,
                                                "peerHost=" + peer + ";batchId=" + result.batchId
                                            )
                                            onPeerError(peer, publicationFailure)
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: DiscoveryProbeConnection) {
                                // A deliberate reachability probe is not a failed transfer.
                            } catch (failure: java.io.EOFException) {
                                if (!authenticated) {
                                    // Route checks may connect and close before sending a
                                    // proof. This is a normal pre-auth probe, not a failed file.
                                    UltraShareDiagnostics.event(
                                        "CONTROL_CHANNEL",
                                        "PRE_AUTH_PROBE_DISCONNECTED",
                                        mapOf("peerHost" to peer, "reason" to "EOF_BEFORE_PROOF")
                                    )
                                } else {
                                    UltraShareDiagnostics.failure(
                                        "DATA_RECEIVE_SESSION",
                                        failure,
                                        "peerHost=" + peer
                                    )
                                    onPeerError(peer, failure)
                                }
                            } catch (failure: Exception) {
                                // Socket.close() is used to unblock a suspended read on cancellation.
                                // Do not report that expected close-induced IOException as a peer failure.
                                currentCoroutineContext().ensureActive()
                                UltraShareDiagnostics.failure(
                                    "DATA_RECEIVE_SESSION",
                                    failure,
                                    "peerHost=" + peer
                                )
                                onPeerError(peer, failure)
                            } finally {
                                socketCancellation.dispose()
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

    /**
     * Ordered, fail-fast bounded fan-out used for source preparation. Results keep
     * input order even when slower content providers finish after later sources.
     */
    internal suspend fun <T, R> mapSourcesBounded(
        sources: List<T>,
        maxConcurrency: Int = MAX_PARALLEL_SOURCE_PREPARATION,
        transform: suspend (T) -> R
    ): List<R> {
        require(maxConcurrency in 1..MAX_CONCURRENT_PEERS) {
            "حد التوازي لتجهيز الملفات غير صالح."
        }
        if (sources.isEmpty()) return emptyList()
        val permits = Semaphore(maxConcurrency)
        return coroutineScope {
            sources.map { source ->
                async {
                    permits.withPermit { transform(source) }
                }
            }.awaitAll()
        }
    }

    /** A per-item preparation result: ordinary file failures do not cancel sibling items. */
    internal data class IndependentSourceResult<T>(
        val value: T? = null,
        val failure: Exception? = null
    )

    /**
     * Bounded ordered preparation that isolates ordinary per-source failures.
     * Cancellation is deliberately rethrown so stopping the parent transfer still
     * cancels every in-flight content-provider read.
     */
    internal suspend fun <T, R> mapSourcesIndependently(
        sources: List<T>,
        maxConcurrency: Int = MAX_PARALLEL_SOURCE_PREPARATION,
        transform: suspend (T) -> R
    ): List<IndependentSourceResult<R>> = mapSourcesBounded(sources, maxConcurrency) { source ->
        try {
            IndependentSourceResult(value = transform(source))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            IndependentSourceResult(failure = failure)
        }
    }

    private data class PreparedSource(
        val sourceIndex: Int,
        val source: Source,
        val entry: UltraShareBatchProtocol.FileEntry,
        val digest: ByteArray
    )

    private fun preparationFailureOutcome(source: Source, failure: Exception): FileOutcome {
        val safeName = runCatching { UltraShareCore.sanitizeFileName(source.name) }
            .getOrDefault("ملف غير صالح")
        val reason = when (failure) {
            is SecurityException -> "انتهت صلاحية الوصول إلى الملف."
            is IOException -> "تعذرت قراءة الملف من موفر المحتوى."
            is IllegalArgumentException -> failure.message?.take(160)
                ?: "بيانات الملف غير صالحة."
            else -> "تعذر تجهيز الملف."
        }
        return FileOutcome(
            fileId = source.fileId,
            fileName = safeName,
            receivedFile = null,
            status = UltraShareBatchProtocol.FileStatus.FAILED,
            transferredBytes = 0L,
            error = reason
        )
    }

    suspend fun sendBatch(
        resolver: ContentResolver,
        sources: List<Source>,
        host: String,
        port: Int = DEFAULT_PORT,
        pairingSecret: String,
        network: Network? = null,
        networks: List<Network> = emptyList(),
        onProgress: (FileProgress) -> Unit = {}
    ): BatchOutcome = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty() && sources.size <= UltraShareBatchProtocol.MAX_FILES)
        require(UltraShareCore.isValidPrivateLanHost(host))
        require(UltraShareCore.isValidPairingSecret(pairingSecret))
        if (!UltraShareControlChannel.hasAnyActiveSession(host)) {
            throw IOException("انتهت قناة الموافقة؛ يجب إعادة الاتصال قبل بدء نقل V2.")
        }
        UltraShareDiagnostics.event(
            "DATA_SEND",
            "SOURCE_PREPARATION_STARTED",
            mapOf("peerHost" to host, "fileCount" to sources.size)
        )
        require(sources.map { it.fileId }.distinct().size == sources.size) {
            "تحتوي قائمة الإرسال على معرّف ملف مكرر."
        }
        // Prepare files independently: a revoked URI or malformed item is recorded
        // as a per-file failure instead of cancelling every healthy item in the queue.
        // The bounded mapper still preserves input order and propagates parent cancellation.
        val preparationResults = mapSourcesIndependently(
            sources.withIndex().toList(),
            MAX_PARALLEL_SOURCE_PREPARATION
        ) { indexed ->
            val source = indexed.value
            require(source.sizeBytes in 1..UltraShareCore.MAX_FILE_BYTES)
            val name = UltraShareCore.sanitizeFileName(source.name)
            val mime = UltraShareCore.sanitizeMimeType(source.mimeType)
            val digest = resolver.openInputStream(source.uri)?.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_BYTES)
                var countTotal = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = readAtMost(input, buffer, buffer.size)
                    if (count == 0) break
                    countTotal += count
                    require(countTotal <= source.sizeBytes) { "تغير حجم الملف أثناء التجهيز: $name" }
                    md.update(buffer, 0, count)
                }
                require(countTotal == source.sizeBytes) { "حجم الملف لا يطابق بياناته: $name" }
                md.digest()
            } ?: throw IOException("تعذر فتح الملف: $name")
            PreparedSource(
                sourceIndex = indexed.index,
                source = source,
                entry = UltraShareBatchProtocol.newEntry(
                    name, mime, source.sizeBytes, digest, source.fileId
                ),
                digest = digest
            )
        }
        val prepared = preparationResults.mapNotNull { it.value }
        val manifest = UltraShareBatchProtocol.Manifest(
            java.util.UUID.randomUUID().toString(), prepared.map { it.entry }
        )
        val preparationFailures = preparationResults.count { it.failure != null }
        UltraShareDiagnostics.event(
            "DATA_SEND",
            "SOURCE_PREPARATION_COMPLETED",
            mapOf(
                "peerHost" to host,
                "batchId" to manifest.batchId,
                "fileCount" to manifest.entries.size,
                "failedPreparationCount" to preparationFailures,
                "totalBytes" to manifest.totalBytes
            )
        )
        if (prepared.isEmpty()) {
            val failedOutcomes = sources.mapIndexed { index, source ->
                preparationFailureOutcome(
                    source,
                    checkNotNull(preparationResults[index].failure)
                )
            }
            UltraShareDiagnostics.event(
                "DATA_SEND",
                "BATCH_SKIPPED_NO_READABLE_SOURCES",
                mapOf("peerHost" to host, "failedFiles" to failedOutcomes.size)
            )
            return@withContext BatchOutcome(manifest.batchId, failedOutcomes)
        }
        val target = java.net.InetAddress.getByName(host)
        // Keep the preferred Network first, exhaust all distinct Android routes,
        // then try the process-default route exactly as the preflight probe does.
        val routeCandidates = UltraShareNetworkRouting.orderedRoutesWithDefaultFallback(
            (listOfNotNull(network) + networks).distinct().take(4)
        )
        UltraShareDiagnostics.event(
            "DATA_SEND",
            "ROUTE_CANDIDATES_READY",
            mapOf("peerHost" to host, "routeCount" to routeCandidates.size, "port" to port)
        )
        var connectedSocket: Socket? = null
        var connectFailure: IOException? = null
        for ((routeIndex, route) in routeCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val candidate = Socket()
            UltraShareDiagnostics.event(
                "DATA_SEND",
                "ROUTE_ATTEMPT",
                mapOf(
                    "peerHost" to host,
                    "routeIndex" to routeIndex,
                    "networkHandle" to (route?.networkHandle ?: 0L)
                )
            )
            try {
                route?.bindSocket(candidate)
                candidate.connect(InetSocketAddress(target, port), HANDSHAKE_TIMEOUT_MS)
                connectedSocket = candidate
                UltraShareDiagnostics.event(
                    "DATA_SEND",
                    "TCP_CONNECTED",
                    mapOf(
                        "peerHost" to host,
                        "routeIndex" to routeIndex,
                        "networkHandle" to (route?.networkHandle ?: 0L),
                        "localAddress" to candidate.localAddress?.hostAddress
                    )
                )
                break
            } catch (failure: IOException) {
                UltraShareDiagnostics.failure(
                    "DATA_SEND_ROUTE_" + routeIndex,
                    failure,
                    "peerHost=" + host + ";port=" + port + ";networkHandle=" + (route?.networkHandle ?: 0L)
                )
                runCatching { candidate.close() }
                connectFailure = failure
            } catch (failure: RuntimeException) {
                // Stale/OEM Network handles can throw while binding before connect().
                // Treat only route-state/argument failures as a failed candidate; do not
                // hide permission errors, cancellation, or programming defects.
                if (failure is kotlinx.coroutines.CancellationException ||
                    (failure !is IllegalStateException && failure !is IllegalArgumentException)
                ) throw failure
                val routeFailure = IOException("Android network route is no longer usable.", failure)
                UltraShareDiagnostics.failure(
                    "DATA_SEND_ROUTE_" + routeIndex,
                    routeFailure,
                    "peerHost=" + host + ";port=" + port + ";networkHandle=" + (route?.networkHandle ?: 0L)
                )
                runCatching { candidate.close() }
                connectFailure = routeFailure
            }
        }
        val socket = connectedSocket ?: run {
            val failure = connectFailure ?: IOException("تعذر الاتصال بأي مسار شبكة متاح.")
            UltraShareDiagnostics.failure(
                "DATA_SEND_TCP_CONNECT",
                failure,
                "peerHost=" + host + ";port=" + port + ";routeCount=" + routeCandidates.size
            )
            throw failure
        }
        var readPhase = "HANDSHAKE"
        val socketCancellation = closeSocketOnJobCancellation(
            currentCoroutineContext()[Job] ?: error("جلسة الإرسال بلا Job نشط."),
            socket
        )
        try {
        socket.use {
            // A fresh socket is bound to each candidate before connect; failed routes
            // are closed before the next route is attempted.
            configure(socket)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
            UltraShareDiagnostics.event("DATA_SEND", "TCP_HANDSHAKE_STARTED", mapOf("peerHost" to host))
            check(input.readInt() == HANDSHAKE_MAGIC) { "الجهاز الآخر لا يدعم جلسة بيانات V2." }
            val challenge = ByteArray(CHALLENGE_BYTES).also(input::readFully)
            val secret = UltraShareCore.derivePairingKey(pairingSecret, challenge)
            var noncePrefixToWipe: ByteArray? = null
            var sendKeyToWipe: ByteArray? = null
            var receiveKeyToWipe: ByteArray? = null
            try {
            output.write(hmac(secret, challenge))
            output.flush()
            check(input.readBoolean()) { "رفض الجهاز المستقبل رمز الاقتران." }
            UltraShareDiagnostics.event("DATA_SEND", "DATA_AUTHENTICATED", mapOf("peerHost" to host))
            // The receiver may hash existing completed files before it can answer
            // with resume offsets; allow slow storage to finish that preflight.
            readPhase = "RESUME_SCAN"
            socket.soTimeout = verificationTimeoutMillis(manifest.totalBytes)
            val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(input::readFully).also { noncePrefixToWipe = it }
            val sendKey = deriveKey(secret, challenge, "client-to-server").also { sendKeyToWipe = it }
            val receiveKey = deriveKey(secret, challenge, "server-to-client").also { receiveKeyToWipe = it }
            val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val receiveCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val sendKeySpec = SecretKeySpec(sendKey, "AES")
            val receiveKeySpec = SecretKeySpec(receiveKey, "AES")
            val encryptedFrameBuffer = ByteArray(CHUNK_BYTES + 7 + TAG_BYTES)
            val plainChunkFrame = ByteArray(CHUNK_BYTES + 7)
            var sendSequence = 0L
            var receiveSequence = 0L

            writeFrame(output, sendKey, noncePrefix, sendSequence++, UltraShareBatchProtocol.encode(manifest), cipher = sendCipher, keySpec = sendKeySpec, encryptedBuffer = encryptedFrameBuffer)
            UltraShareDiagnostics.event(
                "DATA_SEND",
                "MANIFEST_SENT",
                mapOf("peerHost" to host, "batchId" to manifest.batchId, "fileCount" to manifest.entries.size)
            )
            val offsetsPayload = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 32 * 1024, cipher = receiveCipher, keySpec = receiveKeySpec)
            val offsets = decodeOffsets(offsetsPayload, manifest)
            readPhase = "SESSION"
            socket.soTimeout = SESSION_TIMEOUT_MS
            UltraShareDiagnostics.event(
                "DATA_SEND",
                "RESUME_OFFSETS_RECEIVED",
                mapOf(
                    "peerHost" to host,
                    "batchId" to manifest.batchId,
                    "resumedBytes" to offsets.values.sum()
                )
            )
            val reusableSourceChunk = ByteArray(CHUNK_BYTES - 8)
            prepared.forEachIndexed { index, preparedSource ->
                currentCoroutineContext().ensureActive()
                val source = preparedSource.source
                val entry = preparedSource.entry
                val preparedDigest = preparedSource.digest
                val offset = offsets.getValue(entry.id)
                UltraShareDiagnostics.event(
                    "DATA_SEND",
                    "FILE_SEND_STARTED",
                    mapOf(
                        "peerHost" to host,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "sizeBytes" to entry.sizeBytes,
                        "resumeOffset" to offset
                    )
                )
                var transferred = offset
                val started = System.nanoTime()
                var lastProgressAt = started
                var lastProgressBytes = offset
                val streamedDigest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(source.uri)?.use { raw ->
                    BufferedInputStream(raw, BUFFER_BYTES).use { stream ->
                        skipAndDigest(stream, offset, streamedDigest)
                        val buffer = reusableSourceChunk
                        while (transferred < entry.sizeBytes) {
                            currentCoroutineContext().ensureActive()
                            val wanted = minOf(buffer.size.toLong(), entry.sizeBytes - transferred).toInt()
                            val read = readAtMost(stream, buffer, wanted)
                            check(read > 0) { "انتهى الملف قبل اكتمال الإرسال: ${entry.name}" }
                            plainChunkFrame[0] = TYPE_CHUNK.toByte()
                            plainChunkFrame[1] = (index ushr 8).toByte()
                            plainChunkFrame[2] = index.toByte()
                            plainChunkFrame[3] = (read ushr 24).toByte()
                            plainChunkFrame[4] = (read ushr 16).toByte()
                            plainChunkFrame[5] = (read ushr 8).toByte()
                            plainChunkFrame[6] = read.toByte()
                            System.arraycopy(buffer, 0, plainChunkFrame, 7, read)
                            streamedDigest.update(buffer, 0, read)
                            writeFrame(
                                output, sendKey, noncePrefix, sendSequence++, plainChunkFrame,
                                flush = false, cipher = sendCipher, keySpec = sendKeySpec,
                                encryptedBuffer = encryptedFrameBuffer, inputLength = read + 7
                            )
                            transferred += read
                            val progressNow = System.nanoTime()
                            if (transferred - lastProgressBytes >= PROGRESS_UPDATE_INTERVAL_BYTES ||
                                progressNow - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_NANOS ||
                                transferred == entry.sizeBytes
                            ) {
                                onProgress(FileProgress(
                                    entry.id, entry.name, transferred, entry.sizeBytes,
                                    rate(transferred - offset, started), false
                                ))
                                lastProgressAt = progressNow
                                lastProgressBytes = transferred
                            }
                        }
                        check(stream.read() == -1) { "تغير حجم الملف أثناء الإرسال: ${entry.name}" }
                    }
                } ?: error("تعذر إعادة فتح الملف: ${entry.name}")
                val actualDigest = streamedDigest.digest()
                if (!MessageDigest.isEqual(preparedDigest, actualDigest)) {
                    UltraShareDiagnostics.event(
                        "DATA_SEND",
                        "SOURCE_DIGEST_CHANGED",
                        mapOf(
                            "peerHost" to host,
                            "batchId" to manifest.batchId,
                            "fileId" to entry.id,
                            "fileName" to entry.name,
                            "expectedBytes" to entry.sizeBytes,
                            "sentBytes" to transferred
                        )
                    )
                    throw IOException("تغير محتوى الملف أثناء الإرسال؛ أوقفنا النقل لحماية سلامة الملف: ${entry.name}")
                }
                UltraShareDiagnostics.event(
                    "DATA_SEND",
                    "FILE_SOURCE_DIGEST_VERIFIED",
                    mapOf("peerHost" to host, "batchId" to manifest.batchId, "fileId" to entry.id)
                )
                val end = ByteArrayOutputStream()
                DataOutputStream(end).use { data -> data.writeByte(TYPE_FILE_END); data.writeShort(index) }
                writeFrame(output, sendKey, noncePrefix, sendSequence++, end.toByteArray(), cipher = sendCipher, keySpec = sendKeySpec, encryptedBuffer = encryptedFrameBuffer)
                UltraShareDiagnostics.event(
                    "DATA_SEND",
                    "FILE_STREAM_SENT",
                    mapOf(
                        "peerHost" to host,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "sizeBytes" to entry.sizeBytes
                    )
                )
            }
            writeFrame(output, sendKey, noncePrefix, sendSequence++, byteArrayOf(TYPE_BATCH_END.toByte()), cipher = sendCipher, keySpec = sendKeySpec, encryptedBuffer = encryptedFrameBuffer)
            // The receiver verifies SHA-256 and publishes files before sending its final
            // receipt. Allow slower storage to finish, but never wait indefinitely.
            readPhase = "FINAL_RECEIPT"
            socket.soTimeout = verificationTimeoutMillis(manifest.totalBytes)
            val results = decodeResults(
                readFrame(input, receiveKey, noncePrefix, receiveSequence, UltraShareBatchProtocol.MAX_MANIFEST_BYTES, cipher = receiveCipher, keySpec = receiveKeySpec), manifest
            )
            val byId = results.associateBy { it.fileId }
            val remoteOutcomes = prepared.associate { preparedSource ->
                val entry = preparedSource.entry
                val result = byId.getValue(entry.id)
                preparedSource.sourceIndex to FileOutcome(
                    entry.id, entry.name, null, result.status, result.transferredBytes, result.error
                )
            }
            val batchOutcome = BatchOutcome(
                manifest.batchId,
                preparationResults.mapIndexed { index, preparation ->
                    remoteOutcomes[index] ?: preparationFailureOutcome(
                        sources[index],
                        checkNotNull(preparation.failure)
                    )
                }
            )
            UltraShareDiagnostics.event(
                "DATA_SEND",
                "BATCH_SEND_COMPLETED",
                mapOf(
                    "peerHost" to host,
                    "batchId" to manifest.batchId,
                    "completedFiles" to batchOutcome.files.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED },
                    "failedFiles" to batchOutcome.files.count { it.status != UltraShareBatchProtocol.FileStatus.COMPLETED }
                )
            )
            batchOutcome
            } finally {
                noncePrefixToWipe?.fill(0)
                sendKeyToWipe?.fill(0)
                receiveKeyToWipe?.fill(0)
                secret.fill(0)
                challenge.fill(0)
            }
        }
        } catch (failure: SocketTimeoutException) {
            UltraShareDiagnostics.failure(
                "DATA_SEND_READ_TIMEOUT",
                failure,
                "peerHost=$host;phase=$readPhase;port=$port"
            )
            throw failure
        } finally {
            socketCancellation.dispose()
        }
    }

    private suspend fun receiveSession(
        socket: Socket,
        pairingSecret: String,
        pairingSecretResolver: ((String) -> String?)?,
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
        output.write(challenge)
        output.flush()
        val proof = ByteArray(32).also(input::readFully)
        // Detect the explicit reachability probe before running the expensive PIN KDF.
        if (isDiscoveryProbeProof(proof)) throw DiscoveryProbeConnection()
        val peerHost = socket.inetAddress.hostAddress.orEmpty()
        enforcePairingCooldown(peerHost)
        if (!UltraShareControlChannel.hasAnyActiveSession(peerHost)) {
            throw IOException("لا توجد جلسة موافقة نشطة لهذا الجهاز.")
        }
        val selectedPairingSecret = selectPeerPairingSecret(
            fallbackSecret = pairingSecret,
            peerHost = peerHost,
            resolver = pairingSecretResolver
        )
        val secret = UltraShareCore.derivePairingKey(selectedPairingSecret, challenge)
        var noncePrefixToWipe: ByteArray? = null
        var receiveKeyToWipe: ByteArray? = null
        var sendKeyToWipe: ByteArray? = null
        var readPhase = "HANDSHAKE"
        try {
        val authenticated = MessageDigest.isEqual(proof, hmac(secret, challenge))
        recordPairingResult(peerHost, authenticated)
        // Do not reveal whether the short pairing secret was correct until the
        // independent control-channel approval for this peer has also been verified.
        val approved = isPeerSessionAuthorized(
            authenticated = authenticated,
            sessionActive = authenticated && UltraShareControlChannel.hasAnyActiveSession(peerHost)
        )
        output.writeBoolean(approved)
        output.flush()
        check(approved) {
            if (!authenticated) "فشل التحقق من رمز الاقتران."
            else "لا توجد موافقة اتصال نشطة لهذا الجهاز."
        }
        onAuthenticated()
        UltraShareDiagnostics.event(
            "DATA_RECEIVE",
            "DATA_AUTHENTICATED",
            mapOf("peerHost" to peerHost)
        )
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
            .also { noncePrefixToWipe = it }
        output.write(noncePrefix)
        output.flush()
        readPhase = "SESSION"
        socket.soTimeout = SESSION_TIMEOUT_MS
        val receiveKey = deriveKey(secret, challenge, "client-to-server").also { receiveKeyToWipe = it }
        val sendKey = deriveKey(secret, challenge, "server-to-client").also { sendKeyToWipe = it }
        val receiveCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val receiveKeySpec = SecretKeySpec(receiveKey, "AES")
        val sendKeySpec = SecretKeySpec(sendKey, "AES")
        val reusableEncryptedChunk = ByteArray(CHUNK_BYTES + 7 + TAG_BYTES)
        val reusablePlainChunk = ByteArray(CHUNK_BYTES + 7)
        var receiveSequence = 0L
        var sendSequence = 0L

        val manifest = UltraShareBatchProtocol.decode(
            readFrame(input, receiveKey, noncePrefix, receiveSequence++, UltraShareBatchProtocol.MAX_MANIFEST_BYTES, cipher = receiveCipher, keySpec = receiveKeySpec)
        )
        UltraShareDiagnostics.event(
            "DATA_RECEIVE",
            "MANIFEST_RECEIVED",
            mapOf(
                "peerHost" to peerHost,
                "batchId" to manifest.batchId,
                "fileCount" to manifest.entries.size,
                "totalBytes" to manifest.entries.sumOf { it.sizeBytes }
            )
        )
        val duplicateTransferIdentities = manifest.entries
            .groupingBy { UltraShareCore.buildTransferId(it.name, it.sizeBytes, it.sha256) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        fun transferStorageIdentity(entry: UltraShareBatchProtocol.FileEntry): String {
            val contentIdentity = UltraShareCore.buildTransferId(entry.name, entry.sizeBytes, entry.sha256)
            val perFileIdentity = contentIdentity + "-" + entry.id.replace("-", "")
            val perFilePrefix = ".ultrashare-v2-$perFileIdentity"
            val legacyPrefix = ".ultrashare-v2-$contentIdentity"
            val hasPerFileState = listOf(".part", ".resume", ".resume.tmp", ".received")
                .any { File(stateDirectory, perFilePrefix + it).exists() }
            if (hasPerFileState) return perFileIdentity
            val hasLegacyState = listOf(".part", ".resume", ".resume.tmp", ".received")
                .any { File(stateDirectory, legacyPrefix + it).exists() }
            // Read old checkpoints/receipts created before file-ID isolation, but
            // always create new per-entry state so identical files never collide.
            return if (hasLegacyState && contentIdentity !in duplicateTransferIdentities) {
                contentIdentity
            } else perFileIdentity
        }
        val completedFiles = HashMap<String, File>()
        val partials = manifest.entries.map { entry ->
            val identity = transferStorageIdentity(entry)
            val partial = File(stateDirectory, ".ultrashare-v2-$identity.part")
            val checkpoint = File(stateDirectory, ".ultrashare-v2-$identity.resume")
            val checkpointTemp = File(stateDirectory, ".ultrashare-v2-$identity.resume.tmp")
            val receipt = File(stateDirectory, ".ultrashare-v2-$identity.received")
            val digestHex = entry.sha256.joinToString("") { "%02x".format(it) }
            val completed = receipt.takeIf { it.isFile }?.let { marker ->
                runCatching {
                    val safeName = UltraShareCore.completedReceiptFileName(
                        marker.readText(Charsets.UTF_8), entry.sizeBytes, digestHex
                    ) ?: return@runCatching null
                    val candidate = File(destinationDirectory, safeName)
                    if (!candidate.isFile || candidate.length() != entry.sizeBytes) return@runCatching null
                    val digest = FileInputStream(candidate).use { stream ->
                        val md = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = readAtMost(stream, buffer, buffer.size)
                            if (count == 0) break
                            md.update(buffer, 0, count)
                        }
                        md.digest()
                    }
                    candidate.takeIf {
                        MessageDigest.isEqual(entry.sha256, digest) &&
                            UltraShareMediaSignature.isValid(it, entry.name, entry.mimeType)
                    }
                }.getOrNull()
            }
            if (completed != null) completedFiles[entry.id] = completed
            val offset = if (completed != null) entry.sizeBytes
                else resumableFileOffset(partial, checkpoint, entry.sizeBytes, digestHex)
            UltraShareDiagnostics.event(
                "DATA_RECEIVE",
                "FILE_RESUME_STATE",
                mapOf(
                    "peerHost" to peerHost,
                    "batchId" to manifest.batchId,
                    "fileId" to entry.id,
                    "fileName" to entry.name,
                    "expectedBytes" to entry.sizeBytes,
                    "resumeOffset" to offset,
                    "alreadyCompleted" to (completed != null)
                )
            )
            if (offset == 0L && partial.exists()) partial.delete()
            entry.id to Pair(partial, offset)
        }.toMap()
        val requiredRemainingBytes = manifest.entries.sumOf { entry ->
            entry.sizeBytes - partials.getValue(entry.id).second
        }
        val reserveBytes = 64L * 1024 * 1024
        val availableBytes = destinationDirectory.usableSpace
        require(availableBytes >= requiredRemainingBytes &&
            availableBytes - requiredRemainingBytes >= reserveBytes) {
            "مساحة التخزين غير كافية لاستقبال الدفعة؛ يلزم ترك 64 ميجابايت احتياطياً."
        }

        val offsets = ByteArrayOutputStream()
        DataOutputStream(offsets).use { data ->
            data.writeShort(manifest.entries.size)
            manifest.entries.forEach { entry ->
                data.writeUTF(entry.id)
                data.writeLong(partials.getValue(entry.id).second)
            }
        }
        writeFrame(output, sendKey, noncePrefix, sendSequence++, offsets.toByteArray(), cipher = sendCipher, keySpec = sendKeySpec)

        val outcomes = ArrayList<FileOutcome>(manifest.entries.size)
        for ((index, entry) in manifest.entries.withIndex()) {
            val (partial, offset) = partials.getValue(entry.id)
            val identity = transferStorageIdentity(entry)
            val checkpoint = File(stateDirectory, ".ultrashare-v2-$identity.resume")
            val checkpointTemp = File(stateDirectory, ".ultrashare-v2-$identity.resume.tmp")
            val digestHex = entry.sha256.joinToString("") { "%02x".format(it) }
            var transferred = offset
            val streamedReceiveDigest = if (completedFiles[entry.id] == null) {
                MessageDigest.getInstance("SHA-256").also { digest ->
                    // A resumed prefix must seed the digest; newly received bytes are
                    // hashed below as they are written, so completed files are never
                    // read back from storage solely for SHA-256 verification.
                    if (offset > 0L) FileInputStream(partial).use { existing ->
                        skipAndDigest(existing, offset, digest)
                    }
                }
            } else null
            if (completedFiles[entry.id] == null) {
                UltraShareDiagnostics.event(
                    "DATA_RECEIVE",
                    "FILE_WRITE_STARTED",
                    mapOf(
                        "peerHost" to peerHost,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "resumeOffset" to offset,
                        "partialPath" to partial.name
                    )
                )
                FileOutputStream(partial, offset > 0L).use { file ->
                    var streamFailure: Throwable? = null
                    var lastCheckpointOffset = offset
                    try {
                        val startedAt = System.nanoTime()
                        var lastProgressAt = startedAt
                        var lastProgressBytes = offset
                        while (transferred < entry.sizeBytes) {
                            currentCoroutineContext().ensureActive()
                            val remaining = entry.sizeBytes - transferred
                            val maximumPayload = minOf(CHUNK_BYTES.toLong(), remaining + 7L).toInt()
                            if (transferred == offset && offset > 0L) {
                                socket.soTimeout = maxOf(SESSION_TIMEOUT_MS, verificationTimeoutMillis(offset))
                            }
                            val payloadLength = readFrameInto(
                                input, receiveKey, noncePrefix, receiveSequence++, maximumPayload,
                                receiveCipher, receiveKeySpec, reusableEncryptedChunk, reusablePlainChunk
                            )
                            if (transferred == offset) socket.soTimeout = SESSION_TIMEOUT_MS
                            require(payloadLength >= 8 && (reusablePlainChunk[0].toInt() and 0xff) == TYPE_CHUNK)
                            val frameIndex = ((reusablePlainChunk[1].toInt() and 0xff) shl 8) or
                                (reusablePlainChunk[2].toInt() and 0xff)
                            require(frameIndex == index) { "وصل جزء لملف غير متوقع." }
                            val length = ((reusablePlainChunk[3].toInt() and 0xff) shl 24) or
                                ((reusablePlainChunk[4].toInt() and 0xff) shl 16) or
                                ((reusablePlainChunk[5].toInt() and 0xff) shl 8) or
                                (reusablePlainChunk[6].toInt() and 0xff)
                            require(length in 1..(CHUNK_BYTES - 8) && length + 7 == payloadLength)
                            require(transferred + length <= entry.sizeBytes)
                            streamedReceiveDigest?.update(reusablePlainChunk, 7, length)
                            file.write(reusablePlainChunk, 7, length)
                            transferred += length
                            if (transferred - lastCheckpointOffset >= RESUME_CHECKPOINT_INTERVAL_BYTES ||
                                transferred == entry.sizeBytes
                            ) {
                                // Ordering is intentional: data reaches stable storage before
                                // the checkpoint that authorizes a future resume can replace
                                // the previous checkpoint.
                                file.fd.sync()
                                writeUltraShareResumeCheckpointAtomically(
                                    checkpointTemp, checkpoint, entry.sizeBytes, digestHex, transferred
                                )
                                lastCheckpointOffset = transferred
                                UltraShareDiagnostics.event(
                                    "DATA_RECEIVE",
                                    "RESUME_CHECKPOINT_COMMITTED",
                                    mapOf(
                                        "peerHost" to peerHost,
                                        "batchId" to manifest.batchId,
                                        "fileId" to entry.id,
                                        "durableBytes" to transferred
                                    )
                                )
                            }
                            val progressNow = System.nanoTime()
                            if (transferred - lastProgressBytes >= PROGRESS_UPDATE_INTERVAL_BYTES ||
                                progressNow - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_NANOS ||
                                transferred == entry.sizeBytes
                            ) {
                                onProgress(FileProgress(
                                    entry.id, entry.name, transferred, entry.sizeBytes,
                                    rate(transferred - offset, startedAt), true
                                ))
                                lastProgressAt = progressNow
                                lastProgressBytes = transferred
                            }
                        }
                    } catch (failure: Throwable) {
                        streamFailure = failure
                        throw failure
                    } finally {
                        // A resume offset is trusted only after the kernel confirms the
                        // partial file was synchronized. Never hide fsync failures or
                        // replace the original transfer/cancellation failure.
                        try {
                            file.fd.sync()
                        } catch (syncFailure: IOException) {
                            UltraShareDiagnostics.failure(
                                "DATA_RECEIVE_PARTIAL_SYNC",
                                syncFailure,
                                "peerHost=$peerHost;batchId=${manifest.batchId};fileId=${entry.id}"
                            )
                            val originalFailure = streamFailure
                            if (originalFailure != null) originalFailure.addSuppressed(syncFailure)
                            else throw syncFailure
                        }
                    }
                }
                UltraShareDiagnostics.event(
                    "DATA_RECEIVE",
                    "FILE_PARTIAL_DURABLE",
                    mapOf(
                        "peerHost" to peerHost,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "durableBytes" to partial.length()
                    )
                )
            }
            UltraShareDiagnostics.event(
                "DATA_RECEIVE",
                "FILE_END_WAITING",
                mapOf("peerHost" to peerHost, "batchId" to manifest.batchId, "fileId" to entry.id)
            )
            val endPayload = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 64, cipher = receiveCipher, keySpec = receiveKeySpec)
            DataInputStream(ByteArrayInputStream(endPayload)).use { data ->
                require(data.readUnsignedByte() == TYPE_FILE_END && data.readUnsignedShort() == index && data.available() == 0)
            }
            val previouslyCompleted = completedFiles[entry.id]
            val destination = if (previouslyCompleted != null) {
                previouslyCompleted
            } else {
                UltraShareDiagnostics.event(
                    "DATA_RECEIVE",
                    "FILE_VERIFY_STARTED",
                    mapOf(
                        "peerHost" to peerHost,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "partialBytes" to partial.length()
                    )
                )
                val actualDigest = checkNotNull(streamedReceiveDigest).digest()
                if (!MessageDigest.isEqual(entry.sha256, actualDigest)) {
                    UltraShareDiagnostics.event(
                        "DATA_RECEIVE",
                        "FILE_DIGEST_MISMATCH",
                        mapOf(
                            "peerHost" to peerHost,
                            "batchId" to manifest.batchId,
                            "fileId" to entry.id,
                            "fileName" to entry.name,
                            "expectedBytes" to entry.sizeBytes,
                            "actualBytes" to partial.length()
                        )
                    )
                    runCatching { partial.delete() }
                    runCatching { checkpoint.delete() }
                    runCatching { checkpointTemp.delete() }
                    outcomes += FileOutcome(
                        entry.id, entry.name, null, UltraShareBatchProtocol.FileStatus.FAILED,
                        transferred, "فشل التحقق من بصمة الملف."
                    )
                    continue
                }
                UltraShareDiagnostics.event(
                    "DATA_RECEIVE",
                    "FILE_DIGEST_VERIFIED",
                    mapOf(
                        "peerHost" to peerHost,
                        "batchId" to manifest.batchId,
                        "fileId" to entry.id,
                        "fileName" to entry.name,
                        "sizeBytes" to entry.sizeBytes
                    )
                )
                if (!UltraShareMediaSignature.isValid(partial, entry.name, entry.mimeType)) {
                    runCatching { partial.delete() }
                    runCatching { checkpoint.delete() }
                    runCatching { checkpointTemp.delete() }
                    outcomes += FileOutcome(
                        entry.id, entry.name, null, UltraShareBatchProtocol.FileStatus.FAILED,
                        transferred, "محتوى الملف لا يطابق نوع الوسائط المعلن."
                    )
                    continue
                }
                UltraShareDiagnostics.event(
                    "DATA_RECEIVE",
                    "FILE_PUBLICATION_STARTED",
                    mapOf("peerHost" to peerHost, "batchId" to manifest.batchId, "fileId" to entry.id)
                )
                UltraShareCore.withDestinationPublicationLock(destinationDirectory) {
                    val target = uniqueDestination(destinationDirectory, entry.name)
                    val identity = transferStorageIdentity(entry)
                    val marker = File(stateDirectory, ".ultrashare-v2-$identity.received")
                    val markerTemp = File(stateDirectory, ".ultrashare-v2-$identity.received.tmp")
                    val receiptContent =
                        "${entry.sizeBytes}:${entry.sha256.joinToString("") { "%02x".format(it) }}:${target.name}"
                    // Persist the receipt before publishing the renamed file. If the process
                    // dies between these operations, the next session ignores the receipt
                    // while the verified partial remains available for a safe retry.
                    writeUltraShareReceiptAtomically(markerTemp, marker, receiptContent)
                    UltraShareDiagnostics.event(
                        "DATA_RECEIVE",
                        "FILE_RECEIPT_DURABLE",
                        mapOf(
                            "peerHost" to peerHost,
                            "batchId" to manifest.batchId,
                            "fileId" to entry.id,
                            "receipt" to marker.name
                        )
                    )
                    try {
                        check(partial.renameTo(target)) { "تعذر تثبيت الملف المستلم." }
                        // The published file is now protected by the durable receipt;
                        // the partial-resume metadata is no longer needed.
                        runCatching { checkpoint.delete() }
                        runCatching { checkpointTemp.delete() }
                    } catch (failure: Exception) {
                        runCatching { marker.delete() }
                        throw failure
                    }
                    target
                }
            }
            UltraShareDiagnostics.event(
                "DATA_RECEIVE",
                "FILE_PUBLISHED",
                mapOf(
                    "peerHost" to peerHost,
                    "batchId" to manifest.batchId,
                    "fileId" to entry.id,
                    "fileName" to entry.name,
                    "sizeBytes" to entry.sizeBytes,
                    "destinationName" to destination.name
                )
            )
            outcomes += FileOutcome(
                entry.id, entry.name,
                UltraShareCore.ReceivedFile(destination, destination.name, entry.sizeBytes, entry.mimeType),
                UltraShareBatchProtocol.FileStatus.COMPLETED, entry.sizeBytes
            )
        }
        val batchEnd = readFrame(input, receiveKey, noncePrefix, receiveSequence++, 64, cipher = receiveCipher, keySpec = receiveKeySpec)
        require(batchEnd.contentEquals(byteArrayOf(TYPE_BATCH_END.toByte()))) { "نهاية الدفعة غير صالحة." }
        val resultPayload = encodeResults(manifest, outcomes)
        writeFrame(output, sendKey, noncePrefix, sendSequence, resultPayload, cipher = sendCipher, keySpec = sendKeySpec)
        UltraShareDiagnostics.event(
            "DATA_RECEIVE",
            "BATCH_COMPLETED",
            mapOf(
                "peerHost" to peerHost,
                "batchId" to manifest.batchId,
                "completedFiles" to outcomes.count { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED },
                "failedFiles" to outcomes.count { it.status != UltraShareBatchProtocol.FileStatus.COMPLETED }
            )
        )
        return BatchOutcome(manifest.batchId, outcomes)
        } catch (failure: SocketTimeoutException) {
            UltraShareDiagnostics.failure(
                "DATA_RECEIVE_READ_TIMEOUT",
                failure,
                "peerHost=${socket.inetAddress.hostAddress.orEmpty()};phase=$readPhase"
            )
            throw failure
        } finally {
            noncePrefixToWipe?.fill(0)
            receiveKeyToWipe?.fill(0)
            sendKeyToWipe?.fill(0)
            secret.fill(0)
            challenge.fill(0)
            proof.fill(0)
        }
    }

    /**
     * Hashing completed/resumable files is local-storage work, not network inactivity.
     * Scale the read deadline with the batch size so multi-gigabyte transfers on slower
     * devices are not falsely failed after the old fixed two-minute window.
     */
    internal fun verificationTimeoutMillis(totalBytes: Long): Int {
        require(totalBytes >= 0L)
        val estimatedMillis = kotlin.math.ceil(
            totalBytes.toDouble() * 1_000.0 / VERIFY_BYTES_PER_SECOND.toDouble()
        ).toLong()
        return estimatedMillis
            .coerceIn(MIN_FILE_VERIFY_TIMEOUT_MS.toLong(), MAX_FILE_VERIFY_TIMEOUT_MS.toLong())
            .toInt()
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

    internal fun selectPeerPairingSecret(
        fallbackSecret: String,
        peerHost: String,
        resolver: ((String) -> String?)?
    ): String {
        val selected = if (resolver != null) {
            resolver(peerHost) ?: error("لم يتم العثور على مفتاح نقل موثق لهذا الجهاز.")
        } else fallbackSecret
        require(UltraShareCore.isValidPairingSecret(selected)) {
            "لا توجد بيانات اعتماد صالحة لهذا الجهاز."
        }
        return selected
    }

    internal fun isPeerSessionAuthorized(authenticated: Boolean, sessionActive: Boolean): Boolean =
        authenticated && sessionActive

    @OptIn(InternalCoroutinesApi::class)
    internal fun closeSocketOnJobCancellation(job: Job, socket: Socket): DisposableHandle =
        job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                // Half-close first so the peer observes EOF rather than a reset,
                // then close both directions to release any locally blocked read.
                runCatching { if (!socket.isOutputShutdown) socket.shutdownOutput() }
                runCatching { if (!socket.isInputShutdown) socket.shutdownInput() }
                runCatching { socket.close() }
            }
        }

    private fun configure(socket: Socket) {
        socket.sendBufferSize = SOCKET_BUFFER_BYTES
        socket.receiveBufferSize = SOCKET_BUFFER_BYTES
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    private fun writeFrame(
        output: DataOutputStream, key: ByteArray, prefix: ByteArray, sequence: Long,
        plain: ByteArray, flush: Boolean = true, cipher: Cipher? = null,
        keySpec: SecretKeySpec? = null, encryptedBuffer: ByteArray? = null,
        inputOffset: Int = 0, inputLength: Int = plain.size
    ) {
        require(inputOffset >= 0 && inputLength >= 0 && inputOffset <= plain.size - inputLength)
        require(inputLength <= maxOf(UltraShareBatchProtocol.MAX_MANIFEST_BYTES + 64, CHUNK_BYTES + 7))
        val activeCipher = cipher ?: Cipher.getInstance("AES/GCM/NoPadding")
        activeCipher.init(Cipher.ENCRYPT_MODE, keySpec ?: SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, sequence)))
        if (encryptedBuffer != null) {
            val encryptedSize = activeCipher.doFinal(plain, inputOffset, inputLength, encryptedBuffer, 0)
            output.writeInt(encryptedSize)
            output.write(encryptedBuffer, 0, encryptedSize)
        } else {
            val encrypted = activeCipher.doFinal(plain, inputOffset, inputLength)
            output.writeInt(encrypted.size)
            output.write(encrypted)
        }
        if (flush) output.flush()
    }

    private fun readFrame(
        input: DataInputStream, key: ByteArray, prefix: ByteArray, sequence: Long, maximum: Int,
        cipher: Cipher? = null, keySpec: SecretKeySpec? = null
    ): ByteArray {
        val size = input.readInt()
        require(size in TAG_BYTES..(maximum + TAG_BYTES))
        val encrypted = ByteArray(size).also(input::readFully)
        val activeCipher = cipher ?: Cipher.getInstance("AES/GCM/NoPadding")
        activeCipher.init(Cipher.DECRYPT_MODE, keySpec ?: SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, sequence)))
        return activeCipher.doFinal(encrypted).also { require(it.size <= maximum) }
    }

    private fun readFrameInto(
        input: DataInputStream, key: ByteArray, prefix: ByteArray, sequence: Long, maximum: Int,
        cipher: Cipher, keySpec: SecretKeySpec, encryptedBuffer: ByteArray, plainBuffer: ByteArray
    ): Int {
        val size = input.readInt()
        require(size in TAG_BYTES..(maximum + TAG_BYTES) && size <= encryptedBuffer.size)
        input.readFully(encryptedBuffer, 0, size)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, sequence)))
        val plainSize = cipher.doFinal(encryptedBuffer, 0, size, plainBuffer, 0)
        require(plainSize <= maximum && plainSize <= plainBuffer.size)
        return plainSize
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

    /** Advances to a resume offset while including the existing prefix in source verification. */
    internal suspend fun skipAndDigest(
        input: java.io.InputStream,
        count: Long,
        digest: MessageDigest
    ) {
        var remaining = count
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = readAtMost(input, buffer, wanted)
            require(read > 0) { "تعذر الوصول إلى موضع الاستئناف." }
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }

    private suspend fun skipFully(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                val read = readAtMost(input, buffer, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "تعذر الوصول إلى موضع الاستئناف." }
                remaining -= read
            }
        }
    }

    internal fun readAtMost(input: java.io.InputStream, buffer: ByteArray, wanted: Int): Int {
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
        val safe = UltraShareCore.sanitizeFileName(name)
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
 * Returns only a checkpointed offset. Bytes beyond the checkpoint are discarded,
 * because a crash may have extended the partial file without committing a matching
 * durable checkpoint. Missing/invalid metadata deliberately restarts that file at 0.
 */
internal fun resumableFileOffset(
    partial: File,
    checkpoint: File,
    expectedSizeBytes: Long,
    expectedDigestHex: String
): Long {
    require(expectedSizeBytes in 0..UltraShareCore.MAX_FILE_BYTES)
    require(expectedDigestHex.matches(Regex("[0-9a-f]{64}")))
    require(partial.parentFile?.canonicalFile == checkpoint.parentFile?.canonicalFile)
    val fields = runCatching { checkpoint.readText(Charsets.UTF_8).split(':') }.getOrNull()
    val parsedOffset = fields?.takeIf {
        it.size == 3 && it[0] == expectedSizeBytes.toString() && it[1] == expectedDigestHex
    }?.get(2)?.toLongOrNull()
    if (!partial.isFile || parsedOffset == null || parsedOffset !in 0..expectedSizeBytes ||
        partial.length() < parsedOffset
    ) {
        if (partial.isFile) {
            java.io.RandomAccessFile(partial, "rw").use { file ->
                file.setLength(0L)
                file.fd.sync()
            }
        }
        runCatching { checkpoint.delete() }
        return 0L
    }
    if (partial.length() != parsedOffset) {
        java.io.RandomAccessFile(partial, "rw").use { file ->
            file.setLength(parsedOffset)
            file.fd.sync()
        }
    }
    return parsedOffset
}

/** Commits a resume checkpoint only after the caller has fsynced the partial data. */
internal fun writeUltraShareResumeCheckpointAtomically(
    temp: File,
    checkpoint: File,
    expectedSizeBytes: Long,
    digestHex: String,
    durableOffset: Long
) {
    require(temp.parentFile?.canonicalFile == checkpoint.parentFile?.canonicalFile)
    require(expectedSizeBytes in 0..UltraShareCore.MAX_FILE_BYTES)
    require(digestHex.matches(Regex("[0-9a-f]{64}")))
    require(durableOffset in 0..expectedSizeBytes)
    writeFileAtomically(
        temp,
        checkpoint,
        "$expectedSizeBytes:$digestHex:$durableOffset".toByteArray(Charsets.UTF_8),
        "تعذر تثبيت نقطة استئناف الملف بأمان."
    )
}

/**
 * Writes a completed-transfer receipt through a synced temporary file and atomic rename.
 * The receipt is committed before the data file is renamed, avoiding an untracked
 * completed file if the process is killed during finalization.
 */
internal fun writeFileAtomically(
    temp: File,
    target: File,
    content: ByteArray,
    failureMessage: String
) {
    require(temp.parentFile?.canonicalFile == target.parentFile?.canonicalFile) {
        "يجب أن يكون الملف المؤقت والملف النهائي في المجلد نفسه."
    }
    FileOutputStream(temp).use { output ->
        output.write(content)
        output.fd.sync()
    }
    // Both files are constrained to the same directory, so Android's
    // same-filesystem rename replaces the destination without a delete gap.
    // If rename fails, the previous destination remains untouched.
    check(temp.renameTo(target)) { failureMessage }
}

internal fun writeUltraShareReceiptAtomically(temp: File, marker: File, content: String) {
    writeFileAtomically(
        temp,
        marker,
        content.toByteArray(Charsets.UTF_8),
        "تعذر حفظ سجل اكتمال الملف دون فقدان السجل السابق."
    )
}