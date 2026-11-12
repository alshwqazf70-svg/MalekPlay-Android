package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        stop()

        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: error("خدمة اتصالات Android غير متاحة.")
        val candidate = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val iface = properties.interfaceName ?: return@mapNotNull null
                val rank = when {
                    iface.startsWith("p2p", ignoreCase = true) -> 3
                    iface.startsWith("wlan", ignoreCase = true) -> 2
                    iface.startsWith("eth", ignoreCase = true) ||
                        iface.startsWith("rndis", ignoreCase = true) -> 1
                    else -> return@mapNotNull null
                }
                val capabilities = connectivity.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) return@mapNotNull null
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

        return Started(candidate.network, candidate.interfaceName, candidate.localIpv4, handle)
            .also { started = it }
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
        if (started != null) {
            runCatching { NstackxNativeBridge.stopDiscovery() }
            NstackxNativeBridge.release()
            started = null
        }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    @Synchronized
    fun current(): Started? = started
}
