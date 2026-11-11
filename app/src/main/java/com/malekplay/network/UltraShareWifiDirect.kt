package com.malekplay.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable

/**
 * Lifecycle-bound Wi-Fi Direct discovery and connection helper.
 * Pairing secrets are deliberately not part of discovery or connection messages.
 */
object UltraShareWifiDirect {
    data class Peer(
        val name: String,
        val deviceAddress: String,
        val status: String
    )

    class Session internal constructor(
        context: Context,
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        private val onPeers: (List<Peer>) -> Unit,
        private val onConnected: (host: String?, isGroupOwner: Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        private var closed = false
        private var localInvitationService: WifiP2pDnsSdServiceInfo? = null
        private var localInvitationId: String? = null
        private var invitationRequest: WifiP2pDnsSdServiceRequest? = null
        private var invitationMatch: ((Peer) -> Unit)? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            onError("فعّل Wi-Fi وميزة Wi-Fi Direct من إعدادات الجهاز.")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                }
            }
        }

        init {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        /**
         * Advertises this receiver's short-lived invitation identity through Wi-Fi Direct
         * DNS-SD. The pairing secret is deliberately excluded from the public TXT record.
         * A sender learns only which P2P device to connect to; MPC2 still authenticates it.
         */
        @SuppressLint("MissingPermission")
        fun advertiseInvitation(peerId: String) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            require(peerId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "هوية دعوة Wi-Fi Direct غير صالحة."
            }
            val service = WifiP2pDnsSdServiceInfo.newInstance(
                "malekplay",
                "_malekplay._tcp",
                mapOf("peer" to peerId)
            )
            try {
                if (localInvitationId == peerId && localInvitationService != null) return
                manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed) return
                        manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (!closed) {
                                    localInvitationService = service
                                    localInvitationId = peerId
                                }
                            }
                            override fun onFailure(reason: Int) {
                                onError("تعذر إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                            }
                        })
                    }
                    override fun onFailure(reason: Int) {
                        onError("تعذر تحديث إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لإعلان دعوة Wi-Fi Direct.")
            }
        }

        /**
         * Searches for the exact invitation ID carried by QR. On a match, the caller
         * receives the P2P device address and can request a direct group connection.
         * The QR secret is never sent in discovery records.
         */
        @SuppressLint("MissingPermission")
        fun discoverInvitation(peerId: String, onMatch: (Peer) -> Unit) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            require(peerId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "هوية دعوة Wi-Fi Direct غير صالحة."
            }
            invitationMatch = onMatch
            try {
                manager.setDnsSdResponseListeners(
                    channel,
                    WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                    WifiP2pManager.DnsSdTxtRecordListener { _, records, device ->
                        if (closed || records["peer"] != peerId) return@DnsSdTxtRecordListener
                        val peer = Peer(
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز MalekPlay",
                            deviceAddress = device.deviceAddress.orEmpty(),
                            status = statusLabel(device.status)
                        )
                        if (peer.deviceAddress.isNotBlank()) invitationMatch?.invoke(peer)
                    }
                )
                val request = WifiP2pDnsSdServiceRequest.newInstance()
                manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed) return
                        manager.addServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (closed) return
                                invitationRequest = request
                                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() = Unit
                                    override fun onFailure(reason: Int) {
                                        onError("تعذر البحث عن دعوة Wi-Fi Direct (رمز $reason).")
                                    }
                                })
                            }
                            override fun onFailure(reason: Int) {
                                onError("تعذر تهيئة البحث عن دعوة Wi-Fi Direct (رمز $reason).")
                            }
                        })
                    }
                    override fun onFailure(reason: Int) {
                        onError("تعذر تنظيف طلبات اكتشاف Wi-Fi Direct (رمز $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة للبحث عن دعوة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stopInvitationDiscovery() {
            if (closed) return
            invitationMatch = null
            runCatching {
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                })
            }
            invitationRequest?.let { request ->
                runCatching {
                    manager.removeServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { invitationRequest = null }
                        override fun onFailure(reason: Int) { invitationRequest = null }
                    })
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun stopInvitationAdvertisement() {
            if (closed) return
            runCatching {
                manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        localInvitationService = null
                        localInvitationId = null
                    }
                    override fun onFailure(reason: Int) {
                        localInvitationService = null
                        localInvitationId = null
                    }
                })
            }
        }

        @SuppressLint("MissingPermission")
        fun discover() {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            try {
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        onError("تعذر البحث عبر Wi-Fi Direct (رمز الخطأ $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم منح إذن الأجهزة القريبة للبحث عبر Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stopDiscovery() {
            if (closed) return
            runCatching {
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                })
            }
        }

        @SuppressLint("MissingPermission")
        fun connect(deviceAddress: String, preferThisDeviceAsGroupOwner: Boolean) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                // A receiving phone should own the group; a sending phone prefers
                // the remote receiver so groupOwnerAddress identifies its TCP server.
                groupOwnerIntent = if (preferThisDeviceAsGroupOwner) 15 else 0
                wps.setup = WpsInfo.PBC
            }
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        // Connection details arrive through WIFI_P2P_CONNECTION_CHANGED_ACTION.
                    }
                    override fun onFailure(reason: Int) {
                        onError("تعذر الاتصال بجهاز Wi-Fi Direct (رمز الخطأ $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestPeers() {
            try {
                manager.requestPeers(channel) { list ->
                    if (closed) return@requestPeers
                    onPeers(list.deviceList.map { device ->
                        Peer(
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز Wi-Fi Direct",
                            deviceAddress = device.deviceAddress.orEmpty(),
                            status = statusLabel(device.status)
                        )
                    }.filter { it.deviceAddress.isNotBlank() })
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لعرض أجهزة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestConnectionInfo() {
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (closed) return@requestConnectionInfo
                    if (!info.groupFormed) {
                        onError("انقطع اتصال Wi-Fi Direct.")
                        return@requestConnectionInfo
                    }
                    if (info.isGroupOwner) {
                        onConnected(null, true)
                    } else {
                        val host = info.groupOwnerAddress?.hostAddress
                        if (host != null && UltraShareTransport.isValidPrivateLanHost(host)) {
                            onConnected(host, false)
                        } else {
                            onError("تم اتصال Wi-Fi Direct لكن تعذر تحديد عنوان جهاز الاستقبال.")
                        }
                    }
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لقراءة اتصال Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        override fun close() {
            if (closed) return
            closed = true
            // Stop active discovery when the screen/ViewModel goes away. This does
            // not tear down an established group, so an in-flight TCP transfer can
            // continue over the existing Wi-Fi Direct connection.
            runCatching { manager.stopPeerDiscovery(channel, null) }
            runCatching { manager.clearServiceRequests(channel, null) }
            runCatching { manager.clearLocalServices(channel, null) }
            invitationMatch = null
            invitationRequest = null
            localInvitationService = null
            localInvitationId = null
            runCatching { appContext.unregisterReceiver(receiver) }
        }

        private fun statusLabel(status: Int): String = when (status) {
            WifiP2pDevice.AVAILABLE -> "متاح"
            WifiP2pDevice.INVITED -> "تمت دعوته"
            WifiP2pDevice.CONNECTED -> "متصل"
            WifiP2pDevice.FAILED -> "فشل الاتصال"
            WifiP2pDevice.UNAVAILABLE -> "غير متاح"
            else -> "غير معروف"
        }
    }

    fun open(
        context: Context,
        onPeers: (List<Peer>) -> Unit,
        onConnected: (host: String?, isGroupOwner: Boolean) -> Unit,
        onError: (String) -> Unit
    ): Session {
        val manager = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: error("هذا الجهاز لا يدعم Wi-Fi Direct.")
        val channel = manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
            ?: error("تعذر تهيئة Wi-Fi Direct على هذا الجهاز.")
        return Session(context, manager, channel, onPeers, onConnected, onError)
    }
}
