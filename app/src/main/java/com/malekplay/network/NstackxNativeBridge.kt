package com.malekplay.network

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

    fun startDiscovery(): Int =
        if (libraryLoaded) nativeStartDiscovery() else LOAD_ERROR

    fun stopDiscovery(): Int =
        if (libraryLoaded) nativeStopDiscovery() else LOAD_ERROR

    fun release() {
        if (libraryLoaded) nativeRelease()
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
    private external fun nativeRelease()

    private const val LOAD_ERROR = -1100
    private const val INVALID_ARGUMENT = -1101
}
