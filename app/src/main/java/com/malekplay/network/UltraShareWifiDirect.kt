package com.malekplay.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Looper
import android.os.Handler
import android.os.Build
import android.location.LocationManager
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
        private val mainHandler = Handler(Looper.getMainLooper())
        private var connectionInProgress = false
        private var wasConnected = false
        private var connectionAttemptToken = 0
        private var connectionTimeout: Runnable? = null

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
        fun advertiseInvitation(peerId: String, onAdvertised: () -> Unit = {}) {
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
                if (localInvitationId == peerId && localInvitationService != null) {
                    onAdvertised()
                    return
                }
                manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed) return
                        manager.addLocalService(channel, service, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (!closed) {
                                    localInvitationService = service
                                    localInvitationId = peerId
                                    onAdvertised()
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
            if (!legacyDiscoveryPrerequisite()) {
                onError("على Android 10–12 يجب تشغيل خدمة الموقع للسماح باكتشاف أجهزة Wi-Fi Direct.")
                return
            }
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
        fun stopInvitationDiscovery(onStopped: () -> Unit = {}) {
            if (closed) {
                onStopped()
                return
            }
            invitationMatch = null
            fun removeRequestAndComplete() {
                val request = invitationRequest
                if (request == null) {
                    onStopped()
                    return
                }
                runCatching {
                    manager.removeServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            invitationRequest = null
                            onStopped()
                        }
                        override fun onFailure(reason: Int) {
                            invitationRequest = null
                            onStopped()
                        }
                    })
                }.onFailure {
                    invitationRequest = null
                    onStopped()
                }
            }
            runCatching {
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = removeRequestAndComplete()
                    override fun onFailure(reason: Int) = removeRequestAndComplete()
                })
            }.onFailure {
                removeRequestAndComplete()
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
            if (!legacyDiscoveryPrerequisite()) {
                onError("على Android 10–12 يجب تشغيل خدمة الموقع للسماح باكتشاف أجهزة Wi-Fi Direct.")
                return
            }
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
        fun createGroup(onCreated: () -> Unit = {}) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            try {
                manager.requestGroupInfo(channel) { existingGroup ->
                    if (closed) return@requestGroupInfo
                    if (existingGroup?.isGroupOwner == true) {
                        wasConnected = true
                        onCreated()
                        return@requestGroupInfo
                    }
                    if (existingGroup != null) {
                        onError("هذا الهاتف متصل حالياً بمجموعة Wi-Fi Direct أخرى. افصل المجموعة الحالية ثم أنشئ مجموعة الاستقبال.")
                        return@requestGroupInfo
                    }
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (!closed) awaitReceiverGroup(onCreated, 0)
                        }
                        override fun onFailure(reason: Int) { onError(groupFailureMessage(reason)) }
                    })
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لإنشاء مجموعة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun awaitReceiverGroup(onCreated: () -> Unit, attempt: Int) {
            if (closed) return
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (closed) return@requestConnectionInfo
                    if (info.groupFormed && info.isGroupOwner) {
                        wasConnected = true
                        onCreated()
                    } else if (attempt < 20) {
                        mainHandler.postDelayed({ awaitReceiverGroup(onCreated, attempt + 1) }, 500L)
                    } else {
                        onError("لم تكتمل تهيئة مجموعة Wi-Fi Direct خلال 10 ثوانٍ. أوقف نقطة الاتصال أو أي اتصال مباشر آخر ثم أعد المحاولة.")
                    }
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة للتحقق من إنشاء مجموعة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun removeGroup() {
            if (closed) return
            runCatching {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                })
            }
        }

        @SuppressLint("MissingPermission")
        fun connect(deviceAddress: String, preferThisDeviceAsGroupOwner: Boolean) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            if (!deviceAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
                onError("عنوان جهاز Wi-Fi Direct غير صالح؛ أعد البحث عن الأجهزة.")
                return
            }
            if (connectionInProgress) return
            connectionInProgress = true
            val token = ++connectionAttemptToken
            try {
                // Refresh the peer list immediately before connecting; old peer entries
                // can remain visible after an invitation has already failed.
                manager.requestPeers(channel) { peers ->
                    if (closed || token != connectionAttemptToken) return@requestPeers
                    val currentPeer = peers.deviceList.firstOrNull {
                        it.deviceAddress.equals(deviceAddress, ignoreCase = true)
                    }
                    // A DNS-SD TXT match is itself a fresh peer sighting. Some OEM
                    // stacks omit service-discovered peers from requestPeers briefly.
                    val targetAddress = currentPeer?.deviceAddress ?: deviceAddress
                    connectAttempt(targetAddress, preferThisDeviceAsGroupOwner, token, 0)
                }
            } catch (failure: SecurityException) {
                connectionInProgress = false
                onError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun connectAttempt(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != connectionAttemptToken) return
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                // Receiver owns the group; sender explicitly prefers the remote receiver.
                groupOwnerIntent = if (preferThisDeviceAsGroupOwner) 15 else 0
                wps.setup = WpsInfo.PBC
            }
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed || token != connectionAttemptToken) return
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        val timeout = Runnable {
                            if (!closed && connectionInProgress && token == connectionAttemptToken) {
                                connectionInProgress = false
                                runCatching { manager.cancelConnect(channel, null) }
                                onError("انتهت مهلة اتصال Wi-Fi Direct بعد 30 ثانية. تأكد من بقاء الجهازين قريبين ثم أعد المحاولة.")
                            }
                        }
                        connectionTimeout = timeout
                        mainHandler.postDelayed(timeout, 30_000L)
                    }

                    override fun onFailure(reason: Int) {
                        if (closed || token != connectionAttemptToken) return
                        if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) && attempt < 2) {
                            runCatching { manager.cancelConnect(channel, null) }
                            mainHandler.postDelayed({
                                if (!closed && connectionInProgress && token == connectionAttemptToken) {
                                    connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                                }
                            }, 900L * (attempt + 1))
                        } else {
                            connectionInProgress = false
                            onError(connectFailureMessage(reason))
                        }
                    }
                })
            } catch (failure: SecurityException) {
                connectionInProgress = false
                onError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            }
        }

        private fun connectFailureMessage(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "هذا الهاتف لا يدعم Wi-Fi Direct."
            WifiP2pManager.BUSY -> "ميزة Wi-Fi Direct مشغولة باتصال آخر. أوقف الاتصال السابق ثم أعد المحاولة."
            WifiP2pManager.NO_SERVICE_REQUESTS -> "لم يتم العثور على طلب اكتشاف صالح. أعد البحث عن الجهاز."
            WifiP2pManager.ERROR -> "رفض نظام Android طلب Wi-Fi Direct بعد محاولات إعادة الاتصال. أوقف Wi-Fi Direct في الجهازين ثم فعّله مجدداً."
            else -> "تعذر الاتصال عبر Wi-Fi Direct (رمز النظام $reason). تأكد من تشغيل Wi-Fi وميزة Wi-Fi Direct على الجهازين."
        }

        private fun groupFailureMessage(reason: Int): String = when (reason) {
            WifiP2pManager.BUSY -> "ميزة Wi-Fi Direct مشغولة. افصل أي مجموعة سابقة ثم أعد إنشاء المجموعة."
            WifiP2pManager.P2P_UNSUPPORTED -> "هذا الهاتف لا يدعم إنشاء مجموعة Wi-Fi Direct."
            WifiP2pManager.ERROR -> "تعذر على Android إنشاء مجموعة Wi-Fi Direct (خطأ النظام 0). سيظل الاستقبال عبر Wi-Fi العادية متاحاً."
            else -> "تعذر إنشاء مجموعة Wi-Fi Direct (رمز النظام $reason). سيظل الاستقبال عبر الشبكة المحلية متاحاً."
        }

        private fun legacyDiscoveryPrerequisite(): Boolean {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) return true
            val location = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            return location?.isLocationEnabled == true
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
                    // Android may broadcast CONNECTION_CHANGED while negotiation is
                    // still pending. A non-formed group is not proof of a disconnect.
                    if (!info.groupFormed) {
                        if (wasConnected) {
                            wasConnected = false
                            onError("انقطع اتصال Wi-Fi Direct. أعد الاتصال أو استخدم مسار الشبكة المحلية الاحتياطي.")
                        }
                        return@requestConnectionInfo
                    }
                    wasConnected = true
                    connectionInProgress = false
                    connectionTimeout?.let(mainHandler::removeCallbacks)
                    connectionTimeout = null
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
            if (connectionInProgress) runCatching { manager.cancelConnect(channel, null) }
            connectionInProgress = false
            connectionAttemptToken++
            connectionTimeout?.let(mainHandler::removeCallbacks)
            connectionTimeout = null
            mainHandler.removeCallbacksAndMessages(null)
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
