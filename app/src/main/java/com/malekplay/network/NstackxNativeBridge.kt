package com.malekplay.network

import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * JNI facade for the pinned NSTACKX DFinder discovery engine.
 *
 * Discovery results are hints only. Pairing/authentication and file transfer
 * continue through Ultra Share's existing authenticated session until the
 * DFile port passes two-device acceptance tests.
 */
object NstackxNativeBridge {
    data class Peer(
        val deviceId: String,
        val deviceName: String,
        val networkInterface: String
    )

    fun interface Listener {
        fun onPeerFound(peer: Peer)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val dfileListeners = CopyOnWriteArraySet<DFileListener>()
    // Kotlin mirrors native DFile handles so discovery teardown can be deferred
    // until every in-flight transfer has explicitly closed its session.
    private val activeDFileSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    internal fun activeDFileSessionCount(): Int = activeDFileSessions.size
    internal fun hasActiveDFileSessions(): Boolean = activeDFileSessions.isNotEmpty()

    data class DFileEvent(
        val sessionId: Int,
        val eventType: Int,
        val transferId: Int,
        val transferredBytes: Long,
        val totalBytes: Long,
        val fileNames: List<String>,
        val message: String
    ) {
        /** The pinned upstream API says fatal sessions cannot be reused and must be closed. */
        val isFatal: Boolean
            get() = eventType == DFILE_ON_FATAL_ERROR
    }

    const val DFILE_ON_CONNECT_SUCCESS = 1
    const val DFILE_ON_CONNECT_FAIL = 2
    const val DFILE_ON_FILE_LIST_RECEIVED = 3
    const val DFILE_ON_FILE_RECEIVE_SUCCESS = 4
    const val DFILE_ON_FILE_RECEIVE_FAIL = 5
    const val DFILE_ON_FILE_SEND_SUCCESS = 6
    const val DFILE_ON_FILE_SEND_FAIL = 7
    const val DFILE_ON_FATAL_ERROR = 8
    const val DFILE_ON_SESSION_IN_PROGRESS = 9
    const val DFILE_ON_TRANS_IN_PROGRESS = 10

    fun interface DFileListener {
        fun onEvent(event: DFileEvent)
    }
    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("nstackx_engine")
        true
    }.getOrDefault(false)

    val isAvailable: Boolean
        get() = libraryLoaded

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * @return zero when DFinder initialized and the local device registered.
     * interfaceName and ipv4Address must describe the same active Android Network.
     */
    @Synchronized
    fun initialize(
        deviceName: String,
        deviceId: String,
        interfaceName: String,
        ipv4Address: String,
        networkHandle: Long
    ): Int {
        if (!libraryLoaded) return LOAD_ERROR
        if (deviceName.isBlank() || deviceId.isBlank() ||
            interfaceName.isBlank() || ipv4Address.isBlank() || networkHandle <= 0L
        ) return INVALID_ARGUMENT
        return nativeInitialize(deviceName, deviceId, interfaceName, ipv4Address, networkHandle)
    }

    @Synchronized
    fun startDiscovery(): Int =
        if (libraryLoaded) nativeStartDiscovery() else LOAD_ERROR

    @Synchronized
    fun stopDiscovery(): Int =
        if (libraryLoaded) nativeStopDiscovery() else LOAD_ERROR


    /**
     * Start the native DFile receiver. The 16-byte key must be derived from
     * the authenticated QR/PIN session; never pass the visible PIN directly.
     * The destination must be an existing app-owned writable directory.
     */
    fun startDFileServer(
        localIpv4: String,
        port: Int,
        sessionKey: ByteArray,
        destinationDirectory: File
    ): Int {
        if (!libraryLoaded) return LOAD_ERROR
        require(UltraShareTransport.isValidPrivateLanHost(localIpv4))
        require(port in 1..65535 && sessionKey.size == 16)
        require(destinationDirectory.isDirectory && destinationDirectory.canWrite())
        val keyCopy = sessionKey.copyOf()
        return try {
            NstackxAndroidDiscoverySession.withActiveEngine {
                val sessionId = nativeDFileStartServer(
                    localIpv4, port, keyCopy, destinationDirectory.canonicalPath
                )
                if (sessionId > 0) activeDFileSessions.add(sessionId)
                sessionId
            } ?: NOT_INITIALIZED
        } finally {
            keyCopy.fill(0)
        }
    }

    /** Start a native DFile sender to a private IPv4 endpoint. */
    fun startDFileClient(remoteIpv4: String, port: Int, sessionKey: ByteArray): Int {
        if (!libraryLoaded) return LOAD_ERROR
        require(UltraShareTransport.isValidPrivateLanHost(remoteIpv4))
        require(port in 1..65535 && sessionKey.size == 16)
        val keyCopy = sessionKey.copyOf()
        return try {
            NstackxAndroidDiscoverySession.withActiveEngine {
                val sessionId = nativeDFileStartClient(remoteIpv4, port, keyCopy)
                if (sessionId > 0) activeDFileSessions.add(sessionId)
                sessionId
            } ?: NOT_INITIALIZED
        } finally {
            keyCopy.fill(0)
        }
    }


    /**
     * Start DFile using the key of an already-approved outgoing MPC2 session.
     * The copied key is zeroed immediately after native session creation.
     */
    fun startApprovedDFileClient(remoteIpv4: String, port: Int): Int {
        val key = UltraShareControlChannel.clientDFileKey(remoteIpv4) ?: return NOT_AUTHENTICATED
        return try {
            startDFileClient(remoteIpv4, port, key)
        } finally {
            key.fill(0)
        }
    }

    /** Start DFile receiver using the key of the matching approved incoming MPC2 peer. */
    fun startApprovedDFileServer(
        peerIpv4: String,
        localIpv4: String,
        port: Int,
        destinationDirectory: File
    ): Int {
        val key = UltraShareControlChannel.serverDFileKey(peerIpv4) ?: return NOT_AUTHENTICATED
        return try {
            startDFileServer(localIpv4, port, key, destinationDirectory)
        } finally {
            key.fill(0)
        }
    }

    /** Queue local files for DFile; blocked Android packages/PDFs remain blocked. */
    @Synchronized
    fun sendDFileFiles(sessionId: Int, files: List<File>): Int {
        if (!libraryLoaded) return LOAD_ERROR
        require(sessionId > 0 && files.isNotEmpty() && files.size <= 500)
        val paths = files.map { file ->
            val canonical = file.canonicalFile
            require(canonical.isFile && canonical.canRead()) { "تعذر قراءة الملف: " + file.name }
            require(UltraShareTransport.isAllowedShareFile(canonical.name, "application/octet-stream")) {
                "لا يمكن مشاركة ملفات APK أو PDF."
            }
            canonical.absolutePath
        }
        return nativeDFileSendFiles(sessionId, paths.toTypedArray())
    }

    fun closeDFileSession(sessionId: Int) {
        if (sessionId <= 0) return
        if (libraryLoaded) nativeDFileCloseSession(sessionId)
        if (activeDFileSessions.remove(sessionId)) {
            // Notify only after native close has returned; fatal callbacks may be
            // completing on a native worker thread.
            NstackxAndroidDiscoverySession.onDFileSessionsChanged()
        }
    }

    fun addDFileListener(listener: DFileListener) {
        dfileListeners += listener
    }

    fun removeDFileListener(listener: DFileListener) {
        dfileListeners -= listener
    }

    @Synchronized
    fun release() {
        // A native release closes every DFile handle. Refuse it while any sender
        // or receiver still owns a session; the lifecycle owner retries when idle.
        if (libraryLoaded && activeDFileSessions.isEmpty()) nativeRelease()
    }

    @JvmStatic
    private fun onNativeDFileEvent(
        sessionId: Int,
        eventType: Int,
        transferId: Int,
        transferredBytes: Long,
        totalBytes: Long,
        fileNames: Array<String>,
        message: String
    ) {
        val event = DFileEvent(
            sessionId = sessionId,
            eventType = eventType,
            transferId = transferId,
            transferredBytes = transferredBytes.coerceAtLeast(0L),
            totalBytes = totalBytes.coerceAtLeast(0L),
            fileNames = fileNames.filter { it.isNotBlank() }.map { it.substringAfterLast('/') }.distinct(),
            message = message
        )
        dfileListeners.forEach { listener -> runCatching { listener.onEvent(event) } }
    }

    @JvmStatic
    private fun onNativeDeviceFound(deviceId: String, deviceName: String, networkInterface: String) {
        if (deviceId.isBlank()) return
        val peer = Peer(deviceId, deviceName, networkInterface)
        listeners.forEach { listener ->
            runCatching { listener.onPeerFound(peer) }
        }
    }

    private external fun nativeInitialize(
        deviceName: String,
        deviceId: String,
        interfaceName: String,
        ipv4Address: String,
        networkHandle: Long
    ): Int

    private external fun nativeStartDiscovery(): Int
    private external fun nativeStopDiscovery(): Int
    private external fun nativeDFileStartServer(
        localIpv4: String,
        port: Int,
        key: ByteArray,
        storagePath: String
    ): Int

    private external fun nativeDFileStartClient(
        remoteIpv4: String,
        port: Int,
        key: ByteArray
    ): Int

    private external fun nativeDFileSendFiles(sessionId: Int, paths: Array<String>): Int
    private external fun nativeDFileCloseSession(sessionId: Int)
    private external fun nativeRelease()

    private const val LOAD_ERROR = -1100
    private const val INVALID_ARGUMENT = -1101
    private const val NOT_INITIALIZED = -1102
    private const val NOT_AUTHENTICATED = -1103
}
