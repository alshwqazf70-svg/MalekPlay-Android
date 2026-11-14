package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

/**
 * Lifecycle owner for the native NSTACKX DFinder engine.
 *
 * Selects one explicit Android Network and passes its handle to JNI before
 * discovery starts. This keeps NSTACKX UDP sockets on the selected Wi-Fi or
 * Wi-Fi Direct network instead of relying on the process default route.
 *
 * The current upstream registration supports a bounded local interface list;
 * this first Android bridge intentionally starts one network at a time.
 */
object NstackxAndroidDiscoverySession {
    data class Started(
        val network: Network,
        val interfaceName: String,
        val localIpv4: String,
        val networkHandle: Long
    )

    private data class Candidate(
        val network: Network,
        val interfaceName: String,
        val localIpv4: String,
        val rank: Int
    )

    private var started: Started? = null
    private var peerListener: NstackxNativeBridge.Listener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var callbackConnectivity: ConnectivityManager? = null

    @Synchronized
    fun start(
        context: Context,
        displayName: String = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" }
            ?: "MalekPlayer Android",
        preferredLocalIpv4: String? = null,
        onPeerFound: (NstackxNativeBridge.Peer) -> Unit
    ): Started {
        check(NstackxNativeBridge.isAvailable) {
            "مكتبة NSTACKX الأصلية غير محمّلة. تحقق من بناء NDK."
        }
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: error("خدمة اتصالات Android غير متاحة.")
        val candidate = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val iface = properties.interfaceName ?: return@mapNotNull null
                // Android vendors expose Wi-Fi Direct and SoftAP interfaces under
                // several names (for example p2p-wlan0-0, swlan0 and ap0).
                // Restrict by the actual interface family first; transport flags
                // are not consistently reported for local-only P2P/SoftAP networks.
                val rank = when {
                    iface.startsWith("p2p", ignoreCase = true) -> 4
                    iface.startsWith("wlan", ignoreCase = true) ||
                        iface.startsWith("swlan", ignoreCase = true) ||
                        iface.startsWith("wifi", ignoreCase = true) -> 3
                    UltraShareNetworkRouting.isHotspotInterfaceName(iface) -> 2
                    iface.startsWith("eth", ignoreCase = true) ||
                        iface.startsWith("rndis", ignoreCase = true) -> 1
                    else -> return@mapNotNull null
                }
                val capabilities = connectivity.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                val hasExpectedTransport =
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!hasExpectedTransport && rank < 2) return@mapNotNull null
                val ipv4 = properties.linkAddresses.asSequence()
                    .mapNotNull { it.address as? Inet4Address }
                    .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress }
                    ?.hostAddress ?: return@mapNotNull null
                Candidate(network, iface, ipv4, rank)
            }
            .sortedByDescending { it.rank }
            .let { candidates ->
                if (preferredLocalIpv4.isNullOrBlank()) candidates.firstOrNull()
                else candidates.firstOrNull { it.localIpv4 == preferredLocalIpv4 }
            }
            ?: error(
                if (preferredLocalIpv4.isNullOrBlank())
                    "لم يعثر Android على واجهة Wi-Fi أو Wi-Fi Direct نشطة بعنوان IPv4."
                else "واجهة الشبكة التي استقبلت طلب الاقتران غير متاحة لبدء DFile."
            )

        // NSTACKX is process-global and also owns DFile sessions. Reuse an
        // identical network; do not restart the engine under another caller.
        val existing = started
        if (existing != null) {
            val sameNetwork = isSameNetworkSession(
                existing.interfaceName, existing.localIpv4, existing.networkHandle,
                candidate.interfaceName, candidate.localIpv4, candidate.network.networkHandle
            )
            if (sameNetwork) {
                // A new active consumer cancels a stop that was deferred for DFile.
                stopWhenDFileIdle = false
                setPeerListener(onPeerFound)
                return existing
            }
            check(!NstackxNativeBridge.hasActiveDFileSessions()) {
                "جلسة نقل DFile نشطة على شبكة أخرى؛ لن نعيد تهيئة NSTACKX حتى لا نقطع النقل."
            }
            stopNow()
        }

        val handle = candidate.network.networkHandle
        check(handle > 0L) { "معرّف شبكة Android غير صالح." }
        val peerId = UltraShareDiscovery.localPeerId(appContext)
        val initResult = NstackxNativeBridge.initialize(
            deviceName = displayName,
            deviceId = peerId,
            interfaceName = candidate.interfaceName,
            ipv4Address = candidate.localIpv4,
            networkHandle = handle
        )
        check(initResult == 0) { "فشل تهيئة NSTACKX أو تسجيل الجهاز (رمز $initResult)." }

        setPeerListener(onPeerFound)

        // Android filters Wi-Fi multicast/broadcast receive traffic unless the app
        // holds this lock. Keep it only for the lifetime of native discovery.
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifiManager?.createMulticastLock("MalekPlayer:NSTACKX")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        multicastLock = lock

        val discoveryResult = NstackxNativeBridge.startDiscovery()
        if (discoveryResult != 0) {
            detachPeerListener()
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            multicastLock = null
            NstackxNativeBridge.release()
            error("فشل بدء اكتشاف NSTACKX (رمز $discoveryResult).")
        }

        val active = Started(candidate.network, candidate.interfaceName, candidate.localIpv4, handle)
        started = active

        // NSTACKX binds native UDP sockets to one Android Network handle. When
        // that network disappears or its interface/IP changes, those sockets can
        // start failing with ENETUNREACH. Retire the optional native engine rather
        // than leaving stale sockets alive; the authenticated TCP path is separate.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == active.network && current() == active) stop()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) {
                if (network != active.network || current() != active) return
                val addresses = linkProperties.linkAddresses
                    .mapNotNull { it.address as? Inet4Address }
                    .mapNotNull { it.hostAddress }
                if (linkProperties.interfaceName != active.interfaceName ||
                    active.localIpv4 !in addresses
                ) {
                    stop()
                }
            }
        }
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    // clearCapabilities() was added in API 30. Remove the three
                    // default request constraints explicitly so API 23-29 also
                    // receive callbacks for local-only Wi-Fi Direct/SoftAP networks.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback
            )
            callbackConnectivity = connectivity
            networkCallback = callback
        } catch (failure: Exception) {
            // Discovery remains usable if an OEM denies callback registration.
            // Explicit callers can still stop/restart the session normally.
        }
        return active
    }

    /**
     * Replace the UI observer without restarting the process-global NSTACKX engine.
     * The foreground sharing service may keep discovery alive while the screen/ViewModel
     * is recreated; restarting it would interrupt active DFile sessions.
     */
    @Synchronized
    fun setPeerListener(onPeerFound: (NstackxNativeBridge.Peer) -> Unit) {
        peerListener?.let(NstackxNativeBridge::removeListener)
        val listener = NstackxNativeBridge.Listener(onPeerFound)
        peerListener = listener
        NstackxNativeBridge.addListener(listener)
    }

    /** Detach a screen-scoped observer while leaving native discovery alive. */
    @Synchronized
    fun detachPeerListener() {
        peerListener?.let(NstackxNativeBridge::removeListener)
        peerListener = null
    }

    @Synchronized
    fun stop() {
        detachPeerListener()
        if (shouldDeferNativeRelease(NstackxNativeBridge.activeDFileSessionCount())) {
            // Keep the engine, network binding and multicast lock alive until all
            // DFile handles close. UI lifecycle must not interrupt foreground I/O.
            stopWhenDFileIdle = true
            return
        }
        stopNow()
    }

    /** Complete a deferred engine stop after the last DFile handle is closed. */
    fun onDFileSessionsChanged() {
        synchronized(this) {
            if (shouldCompleteDeferredStop(
                    stopWhenDFileIdle,
                    NstackxNativeBridge.activeDFileSessionCount()
                )
            ) stopNow()
        }
    }

    /** Serialize native DFile creation against engine teardown. */
    fun <T> withActiveEngine(block: () -> T): T? = synchronized(this) {
        if (started == null || stopWhenDFileIdle) null else block()
    }

    private fun stopNow() {
        detachPeerListener()
        networkCallback?.let { callback ->
            runCatching { callbackConnectivity?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        callbackConnectivity = null
        if (started != null) {
            runCatching { NstackxNativeBridge.stopDiscovery() }
            NstackxNativeBridge.release()
            started = null
        }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        stopWhenDFileIdle = false
    }

    @Synchronized
    fun current(): Started? = started

    private var stopWhenDFileIdle = false

    internal fun isSameNetworkSession(
        currentInterface: String,
        currentIpv4: String,
        currentHandle: Long,
        requestedInterface: String,
        requestedIpv4: String,
        requestedHandle: Long
    ): Boolean = currentInterface == requestedInterface &&
        currentIpv4 == requestedIpv4 && currentHandle == requestedHandle

    internal fun shouldDeferNativeRelease(activeDFileSessions: Int): Boolean =
        activeDFileSessions > 0

    internal fun shouldCompleteDeferredStop(
        stopRequested: Boolean,
        activeDFileSessions: Int
    ): Boolean = stopRequested && activeDFileSessions == 0
}
