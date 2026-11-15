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
        initialChannel: WifiP2pManager.Channel,
        private val onPeers: (List<Peer>) -> Unit,
        private val onConnected: (host: String?, isGroupOwner: Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        @Volatile private var channel: WifiP2pManager.Channel = initialChannel
        private var closed = false
        private var localInvitationService: WifiP2pDnsSdServiceInfo? = null
        private var localInvitationId: String? = null
        private var invitationRequest: WifiP2pDnsSdServiceRequest? = null
        private var invitationMatch: ((Peer) -> Unit)? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Records callback/API failures with the exact MalekPlay call site before surfacing them. */
        private fun reportError(message: String, stage: String = "WIFI_DIRECT") {
            val frame = Throwable().stackTrace.firstOrNull {
                it.className.startsWith("com.malekplay.") &&
                    !it.className.endsWith("UltraShareWifiDirect\$Session")
            }
            UltraShareDiagnostics.event(
                stage,
                "FAILURE",
                mapOf(
                    "message" to message,
                    "sourceClass" to frame?.className.orEmpty(),
                    "sourceMethod" to frame?.methodName.orEmpty(),
                    "sourceLine" to (frame?.lineNumber ?: -1)
                )
            )
            onError(message)
        }
        private var connectionInProgress = false
        @Volatile private var peerDiscoveryActive = false
        private var wasConnected = false
        // Owner-side peer confirmation is retried because Android may report GROUP_FORMED
        // before GroupInfo.clientList is populated on some OEM P2P stacks.
        private var ownerPeerCheckToken = 0
        private var ownerPeerCheckInFlight = false
        private var ownerPeerCheckRunnable: Runnable? = null
        private var ownerPeerCheckTimeout: Runnable? = null
        private var connectionAttemptToken = 0
        private var connectionTimeout: Runnable? = null
        private var groupAttemptToken = 0
        private var groupAttemptTimeout: Runnable? = null
        private var lastP2pRadioState: Int? = null
        // A client-side GROUP_FORMED broadcast is not enough: verify the actual
        // group owner MAC against the peer selected by this pairing attempt.
        @Volatile private var expectedPeerDeviceAddress: String? = null
        private var clientPeerCheckToken = 0
        private var clientPeerCheckInFlight = false
        private var clientPeerCheckRunnable: Runnable? = null
        private var clientPeerCheckTimeout: Runnable? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        // A missing OEM extra must not be mistaken for DISABLED.
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            Int.MIN_VALUE
                        )
                        val stateName = wifiP2pRadioStateName(state)
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "RADIO_STATE_CHANGED",
                            mapOf(
                                "state" to state,
                                "stateName" to stateName,
                                "extraPresent" to intent.hasExtra(WifiP2pManager.EXTRA_WIFI_STATE),
                                "sdkInt" to Build.VERSION.SDK_INT
                            )
                        )
                        when (state) {
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                                lastP2pRadioState = state
                                UltraShareDiagnostics.event("WIFI_DIRECT", "RADIO_READY")
                            }
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                                val shouldReport = lastP2pRadioState != state
                                lastP2pRadioState = state
                                if (shouldReport) {
                                    reportError("ميزة Wi-Fi Direct غير مفعلة حالياً. فعّل Wi-Fi وميزة Wi-Fi Direct من إعدادات الجهاز.")
                                }
                            }
                            else -> {
                                lastP2pRadioState = state
                                UltraShareDiagnostics.event(
                                    "WIFI_DIRECT",
                                    "RADIO_STATE_UNRECOGNIZED",
                                    mapOf("state" to state, "extraPresent" to intent.hasExtra(WifiP2pManager.EXTRA_WIFI_STATE))
                                )
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "PEERS_CHANGED_BROADCAST")
                        requestPeers()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "CONNECTION_CHANGED_BROADCAST")
                        requestConnectionInfo()
                    }
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
                ContextCompat.RECEIVER_EXPORTED
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
            if (!legacyDiscoveryPrerequisite()) {
                UltraShareDiagnostics.event("WIFI_DIRECT", "LOCATION_SERVICES_DISABLED", mapOf("sdkInt" to Build.VERSION.SDK_INT, "operation" to "advertise_invitation"))
                reportError("على Android 6–12 يجب تشغيل خدمة الموقع لنشر واكتشاف دعوة Wi-Fi Direct.")
                return
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
                                reportError("تعذر إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                            }
                        })
                    }
                    override fun onFailure(reason: Int) {
                        reportError("تعذر تحديث إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                reportError("يلزم إذن الأجهزة القريبة لإعلان دعوة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_ADVERTISE_INVITATION", failure)
                reportError(failure.localizedMessage ?: "تعذر إعلان دعوة Wi-Fi Direct.")
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
            if (peerDiscoveryActive) {
                reportError("هناك عملية اكتشاف Wi-Fi Direct أخرى نشطة؛ أوقفها وانتظر تأكيد Android قبل البحث عن دعوة QR.")
                return
            }
            invitationMatch = onMatch
            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERY_REQUESTED", mapOf("sdkInt" to Build.VERSION.SDK_INT))
            if (!legacyDiscoveryPrerequisite()) {
                UltraShareDiagnostics.event("WIFI_DIRECT", "LOCATION_SERVICES_DISABLED", mapOf("sdkInt" to Build.VERSION.SDK_INT, "operation" to "discover_invitation"))
                reportError("على Android 6–12 يجب تشغيل خدمة الموقع للسماح باكتشاف أجهزة Wi-Fi Direct.")
                return
            }
            try {
                manager.setDnsSdResponseListeners(
                    channel,
                    WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                    WifiP2pManager.DnsSdTxtRecordListener { _, records, device ->
                        if (closed || records["peer"] != peerId) return@DnsSdTxtRecordListener
                        val callback = invitationMatch ?: return@DnsSdTxtRecordListener
                        val peer = Peer(
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز MalekPlay",
                            deviceAddress = device.deviceAddress.orEmpty(),
                            status = statusLabel(device.status)
                        )
                        if (peer.deviceAddress.isNotBlank()) {
                            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERED", mapOf("deviceName" to peer.name, "deviceStatus" to peer.status))
                            // DNS-SD may report the same TXT record more than once.
                            // Consume the invitation match before invoking UI code so
                            // duplicate callbacks cannot start competing P2P connects.
                            invitationMatch = null
                            callback(peer)
                        }
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
                                startInvitationServiceDiscovery(request, 0)
                            }
                            override fun onFailure(reason: Int) {
                                reportError("تعذر تهيئة البحث عن دعوة Wi-Fi Direct (رمز $reason).")
                            }
                        })
                    }
                    override fun onFailure(reason: Int) {
                        reportError("تعذر تنظيف طلبات اكتشاف Wi-Fi Direct (رمز $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                reportError("يلزم إذن الأجهزة القريبة للبحث عن دعوة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_DISCOVER_INVITATION", failure)
                reportError(failure.localizedMessage ?: "تعذر البحث عن دعوة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stopInvitationDiscovery(onStopped: () -> Unit = {}) {
            // Never silently strand the caller: connect() is allowed only after
            // Android confirms discovery/request cleanup, and a blocked transition
            // must be visible in both diagnostics and the UI.
            stopInvitationDiscoveryChecked { stopped ->
                if (stopped) {
                    onStopped()
                } else if (!closed) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "DISCOVERY_STOP_BLOCKED_CONNECT",
                        mapOf("operation" to "invitation_service_discovery")
                    )
                    reportError("تعذر تأكيد إيقاف اكتشاف دعوة Wi-Fi Direct؛ لم نبدأ الاتصال لتجنب تعارض عمليات الشبكة. أعد المحاولة.")
                }
            }
        }

        /** Only reports success when Android confirms discovery and request cleanup. */
        @SuppressLint("MissingPermission")
        fun stopInvitationDiscoveryChecked(onResult: (Boolean) -> Unit) {
            if (closed) {
                onResult(false)
                return
            }
            invitationMatch = null
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) onResult(success)
            }
            fun removeRequest(stopSucceeded: Boolean) {
                val request = invitationRequest
                // Invalidate delayed DNS-SD retry callbacks immediately, before the
                // asynchronous removeServiceRequest callback returns.
                invitationRequest = null
                if (request == null) {
                    finish(stopSucceeded)
                    return
                }
                try {
                    manager.removeServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            invitationRequest = null
                            finish(stopSucceeded)
                        }
                        override fun onFailure(reason: Int) {
                            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_REQUEST_REMOVE_FAILED", mapOf("reason" to reason))
                            finish(false)
                        }
                    })
                } catch (failure: RuntimeException) {
                    UltraShareDiagnostics.failure("WIFI_DIRECT_REMOVE_INVITATION_REQUEST", failure)
                    finish(false)
                }
            }
            if (!peerDiscoveryActive) {
                removeRequest(true)
                return
            }
            try {
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        peerDiscoveryActive = false
                        removeRequest(true)
                    }
                    override fun onFailure(reason: Int) {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERY_STOP_FAILED", mapOf("reason" to reason))
                        removeRequest(false)
                    }
                })
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_INVITATION_DISCOVERY", failure)
                removeRequest(false)
            }
        }

        @SuppressLint("MissingPermission")
        fun stopInvitationAdvertisement() {
            stopInvitationAdvertisementChecked { }
        }

        /** Confirms local DNS-SD advertisement cleanup before switching transports. */
        @SuppressLint("MissingPermission")
        fun stopInvitationAdvertisementChecked(onResult: (Boolean) -> Unit) {
            if (closed) {
                onResult(false)
                return
            }
            if (localInvitationService == null && localInvitationId == null) {
                onResult(true)
                return
            }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        localInvitationService = null
                        localInvitationId = null
                        if (completed.compareAndSet(false, true)) onResult(true)
                    }
                    override fun onFailure(reason: Int) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "LOCAL_INVITATION_CLEAR_FAILED",
                            mapOf("reason" to reason)
                        )
                        if (completed.compareAndSet(false, true)) onResult(false)
                    }
                })
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_CLEAR_LOCAL_INVITATION", failure)
                if (completed.compareAndSet(false, true)) onResult(false)
            }
        }

        /**
         * OEM Wi-Fi P2P stacks can briefly report BUSY/ERROR while the radio is
         * changing discovery state. Retry the service scan a few times before
         * handing control back to the QR/LAN fallback.
         */
        @SuppressLint("MissingPermission")
        private fun startInvitationServiceDiscovery(
            request: WifiP2pDnsSdServiceRequest,
            attempt: Int
        ) {
            if (closed || invitationRequest !== request) return
            // Mark the operation pending before crossing the asynchronous framework boundary.
            // A stop request arriving before onSuccess must still wait for stopPeerDiscovery().
            peerDiscoveryActive = true
            try {
                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "SERVICE_DISCOVERY_STARTED", mapOf("attempt" to attempt))
                    }

                    override fun onFailure(reason: Int) {
                        peerDiscoveryActive = false
                        UltraShareDiagnostics.event("WIFI_DIRECT", "SERVICE_DISCOVERY_FAILED", mapOf("reason" to reason, "attempt" to attempt))
                        if (closed || invitationRequest !== request) return
                        val retryable = reason == WifiP2pManager.BUSY ||
                            reason == WifiP2pManager.ERROR
                        if (retryable && attempt < 3) {
                            mainHandler.postDelayed({
                                if (!closed && invitationRequest === request) {
                                    startInvitationServiceDiscovery(request, attempt + 1)
                                }
                            }, 600L * (attempt + 1))
                        } else {
                            reportError("تعذر البحث عن دعوة Wi-Fi Direct (رمز $reason).")
                        }
                    }
                })
            } catch (failure: SecurityException) {
                peerDiscoveryActive = false
                reportError("يلزم إذن الأجهزة القريبة للبحث عن دعوة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                peerDiscoveryActive = false
                UltraShareDiagnostics.failure("WIFI_DIRECT_START_SERVICE_DISCOVERY", failure)
                reportError(failure.localizedMessage ?: "تعذر بدء البحث عن دعوة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun discover() {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            if (peerDiscoveryActive) {
                reportError("عملية اكتشاف Wi-Fi Direct نشطة بالفعل؛ أوقف البحث الحالي قبل بدء بحث جديد.")
                return
            }
            if (!legacyDiscoveryPrerequisite()) {
                reportError("على Android 6–12 يجب تشغيل خدمة الموقع للسماح باكتشاف أجهزة Wi-Fi Direct.")
                return
            }
            // Set pending before the async call so stopDiscoveryChecked also serializes
            // against a discoverPeers request whose callback has not arrived yet.
            peerDiscoveryActive = true
            try {
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                    }
                    override fun onFailure(reason: Int) {
                        peerDiscoveryActive = false
                        reportError("تعذر البحث عبر Wi-Fi Direct (رمز الخطأ $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                peerDiscoveryActive = false
                reportError("يلزم منح إذن الأجهزة القريبة للبحث عبر Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                peerDiscoveryActive = false
                UltraShareDiagnostics.failure("WIFI_DIRECT_START_PEER_DISCOVERY", failure)
                reportError(failure.localizedMessage ?: "تعذر بدء البحث عبر Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stopDiscovery(onStopped: () -> Unit = {}) {
            // Do not let legacy call sites race connect/createGroup after a failed stop.
            // A false result must not leave the UI indefinitely waiting in "connecting".
            stopDiscoveryChecked { stopped ->
                if (stopped) {
                    onStopped()
                } else if (!closed) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "DISCOVERY_STOP_BLOCKED_CONNECT",
                        mapOf("operation" to "peer_discovery")
                    )
                    reportError("تعذر تأكيد إيقاف بحث Wi-Fi Direct؛ لم نبدأ الاتصال لتجنب تعارض عمليات الشبكة. أوقف البحث ثم أعد المحاولة.")
                }
            }
        }

        /**
         * Reports success only when the framework confirms discovery has stopped.
         * ERROR/BUSY is ambiguous, so connection/group creation must not race ahead.
         */
        @SuppressLint("MissingPermission")
        fun stopDiscoveryChecked(onResult: (Boolean) -> Unit) {
            if (closed) {
                onResult(false)
                return
            }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) onResult(success)
            }
            // If this Session never started discovery (or it already stopped), there is
            // no P2P discovery operation to serialize against group creation/connect.
            if (!peerDiscoveryActive) {
                finish(true)
                return
            }
            try {
                manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        peerDiscoveryActive = false
                        finish(true)
                    }
                    override fun onFailure(reason: Int) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "PEER_DISCOVERY_STOP_FAILED",
                            mapOf("reason" to reason)
                        )
                        finish(false)
                    }
                })
            } catch (failure: SecurityException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_DISCOVERY_SECURITY", failure)
                reportError("يلزم إذن الأجهزة القريبة لإيقاف البحث قبل الاتصال.")
                finish(false)
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_DISCOVERY", failure)
                finish(false)
            }
        }

        @SuppressLint("MissingPermission")
        fun createGroup(onCreated: () -> Unit = {}) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            // Explicit receiver group creation is a new owner-side pairing flow,
            // not a continuation of a previous client connection attempt.
            cancelClientPeerConfirmation()
            expectedPeerDeviceAddress = null
            wasConnected = false
            if (peerDiscoveryActive) {
                reportError("لا يمكن إنشاء مجموعة Wi-Fi Direct أثناء استمرار عملية الاكتشاف؛ انتظر تأكيد إيقافها أولاً.")
                return
            }
            createGroupAttempt(onCreated, 0)
        }

        @SuppressLint("MissingPermission")
        private fun createGroupAttempt(onCreated: () -> Unit, attempt: Int) {
            if (closed) return
            val token = ++groupAttemptToken
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            val timeout = Runnable {
                if (isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) {
                    groupAttemptTimeout = null
                    groupAttemptToken++
                    reportError("لم يستجب نظام Android لتهيئة مجموعة Wi-Fi Direct خلال 15 ثانية. أوقف أي اتصال مباشر قديم ثم أعد المحاولة.")
                }
            }
            groupAttemptTimeout = timeout
            mainHandler.postDelayed(timeout, 15_000L)
            try {
                manager.requestGroupInfo(channel) { existingGroup ->
                    if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return@requestGroupInfo
                    if (existingGroup?.isGroupOwner == true) {
                        finishGroupAttempt(token)
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "OWNER_GROUP_READY_WAITING_FOR_PEER",
                            mapOf("clientCount" to existingGroup.clientList.size)
                        )
                        onCreated()
                        requestConnectionInfo()
                        return@requestGroupInfo
                    }
                    if (existingGroup != null) {
                        // A stale client-side P2P group can survive a previous failed
                        // pairing attempt. Remove only this non-owner group, then retry
                        // creation after Android reports completion. Keep an existing
                        // receiver-owned group intact because it may already serve peers.
                        if (attempt >= 2) {
                            finishGroupAttempt(token)
                            reportError("تعذر تحرير مجموعة Wi-Fi Direct السابقة بعد عدة محاولات. أوقف الاتصال المباشر القديم ثم أعد المحاولة.")
                            return@requestGroupInfo
                        }
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                finishGroupAttempt(token)
                                mainHandler.postDelayed({
                                    if (!closed) createGroupAttempt(onCreated, attempt + 1)
                                }, 500L)
                            }

                            override fun onFailure(reason: Int) {
                                finishGroupAttempt(token)
                                reportError("تعذر إغلاق مجموعة Wi-Fi Direct السابقة (رمز النظام $reason). أوقف الاتصال القديم ثم أعد المحاولة.")
                            }
                        })
                        return@requestGroupInfo
                    }
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (!closed && isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) {
                                awaitReceiverGroup(onCreated, 0, token)
                            }
                        }

                        override fun onFailure(reason: Int) {
                            if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return
                            if ((reason == WifiP2pManager.ERROR || reason == WifiP2pManager.BUSY) && attempt < 2) {
                                finishGroupAttempt(token)
                                runCatching { manager.cancelConnect(channel, null) }
                                mainHandler.postDelayed({
                                    if (!closed) createGroupAttempt(onCreated, attempt + 1)
                                }, 1_000L * (attempt + 1))
                            } else {
                                finishGroupAttempt(token)
                                reportError(groupFailureMessage(reason))
                            }
                        }
                    })
                }
            } catch (failure: SecurityException) {
                finishGroupAttempt(token)
                reportError("يلزم إذن الأجهزة القريبة لإنشاء مجموعة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                finishGroupAttempt(token)
                reportError(failure.localizedMessage ?: "تعذر تهيئة مجموعة Wi-Fi Direct.")
            }
        }

        private fun finishGroupAttempt(token: Int) {
            if (groupAttemptToken != token) return
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            groupAttemptTimeout = null
            groupAttemptToken++
        }

        @SuppressLint("MissingPermission")
        private fun awaitReceiverGroup(onCreated: () -> Unit, attempt: Int, token: Int) {
            if (closed) return
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return@requestConnectionInfo
                    if (info.groupFormed && info.isGroupOwner) {
                        finishGroupAttempt(token)
                        // Group creation is only receiver readiness. requestConnectionInfo()
                        // separately confirms a client through GroupInfo.clientList.
                        UltraShareDiagnostics.event("WIFI_DIRECT", "OWNER_GROUP_READY_WAITING_FOR_PEER")
                        onCreated()
                        requestConnectionInfo()
                    } else if (attempt < 20) {
                        mainHandler.postDelayed({ awaitReceiverGroup(onCreated, attempt + 1, token) }, 500L)
                    } else {
                        finishGroupAttempt(token)
                        reportError("لم تكتمل تهيئة مجموعة Wi-Fi Direct خلال 10 ثوانٍ. أوقف نقطة الاتصال أو أي اتصال مباشر آخر ثم أعد المحاولة.")
                    }
                }
            } catch (failure: SecurityException) {
                finishGroupAttempt(token)
                reportError("يلزم إذن الأجهزة القريبة للتحقق من إنشاء مجموعة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                finishGroupAttempt(token)
                reportError(failure.localizedMessage ?: "تعذر التحقق من إنشاء مجموعة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        fun removeGroup(onComplete: (Boolean) -> Unit = {}) {
            if (closed) {
                onComplete(false)
                return
            }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (completed.compareAndSet(false, true)) onComplete(false)
            }
            mainHandler.postDelayed(timeout, 5_000L)
            try {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        mainHandler.removeCallbacks(timeout)
                        if (completed.compareAndSet(false, true)) onComplete(true)
                    }
                    override fun onFailure(reason: Int) {
                        mainHandler.removeCallbacks(timeout)
                        if (!completed.compareAndSet(false, true)) return
                        // ERROR can mean there was no group to remove. Verify that
                        // Android reports no formed group before permitting fallback.
                        val verificationFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                        val verificationTimeout = Runnable {
                            if (verificationFinished.compareAndSet(false, true)) onComplete(false)
                        }
                        mainHandler.postDelayed(verificationTimeout, 2_500L)
                        try {
                            manager.requestConnectionInfo(channel) { info ->
                                mainHandler.removeCallbacks(verificationTimeout)
                                if (verificationFinished.compareAndSet(false, true)) {
                                    onComplete(!info.groupFormed)
                                }
                            }
                        } catch (failure: SecurityException) {
                            mainHandler.removeCallbacks(verificationTimeout)
                            if (verificationFinished.compareAndSet(false, true)) onComplete(false)
                        } catch (failure: RuntimeException) {
                            mainHandler.removeCallbacks(verificationTimeout)
                            if (verificationFinished.compareAndSet(false, true)) onComplete(false)
                        }
                    }
                })
            } catch (failure: SecurityException) {
                mainHandler.removeCallbacks(timeout)
                if (completed.compareAndSet(false, true)) onComplete(false)
            } catch (failure: RuntimeException) {
                mainHandler.removeCallbacks(timeout)
                if (completed.compareAndSet(false, true)) onComplete(false)
            }
        }

        @SuppressLint("MissingPermission")
        fun connect(deviceAddress: String, preferThisDeviceAsGroupOwner: Boolean) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            if (peerDiscoveryActive) {
                reportError("لا يمكن بدء اتصال Wi-Fi Direct أثناء استمرار الاكتشاف؛ أوقف عملية البحث وانتظر تأكيد Android أولاً.")
                return
            }
            if (!deviceAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
                reportError("عنوان جهاز Wi-Fi Direct غير صالح؛ أعد البحث عن الأجهزة.")
                return
            }
            if (connectionInProgress) return
            cancelClientPeerConfirmation()
            expectedPeerDeviceAddress = deviceAddress.lowercase()
            wasConnected = false
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "CONNECT_TARGET_PINNED",
                mapOf("targetAddressPresent" to expectedPeerDeviceAddress?.isNotBlank(), "preferThisDeviceAsGroupOwner" to preferThisDeviceAsGroupOwner)
            )
            connectionInProgress = true
            val token = ++connectionAttemptToken
            // requestPeers is asynchronous and some OEM Wi-Fi P2P implementations
            // fail to deliver its callback. Bound this preflight separately from the
            // later 30-second group-negotiation timeout so the session cannot remain
            // permanently stuck in connectionInProgress.
            connectionTimeout?.let(mainHandler::removeCallbacks)
            val peerRefreshTimeout = Runnable {
                if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                    connectionInProgress = false
                    connectionTimeout = null
                    expectedPeerDeviceAddress = null
                    cancelClientPeerConfirmation()
                    // Invalidate any requestPeers callback that arrives after this timeout.
                    connectionAttemptToken++
                    reportError("لم يستجب Android لتحديث قائمة أجهزة Wi-Fi Direct. أعد البحث ثم حاول الاتصال مجدداً.")
                }
            }
            connectionTimeout = peerRefreshTimeout
            mainHandler.postDelayed(peerRefreshTimeout, 5_000L)
            try {
                // Refresh the peer list immediately before connecting; old peer entries
                // can remain visible after an invitation has already failed.
                manager.requestPeers(channel) { peers ->
                    if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestPeers
                    connectionTimeout?.let(mainHandler::removeCallbacks)
                    connectionTimeout = null
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
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                expectedPeerDeviceAddress = null
                cancelClientPeerConfirmation()
                connectionAttemptToken++
                reportError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun connectAttempt(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int
        ) {
            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
            UltraShareDiagnostics.event("WIFI_DIRECT", "CONNECT_REQUESTED", mapOf("preferThisDeviceAsGroupOwner" to preferThisDeviceAsGroupOwner, "attempt" to attempt))
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                // Receiver owns the group; sender explicitly prefers the remote receiver.
                groupOwnerIntent = if (preferThisDeviceAsGroupOwner) 15 else 0
                wps.setup = WpsInfo.PBC
            }
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "CONNECT_REQUEST_ACCEPTED_BY_FRAMEWORK", mapOf("attempt" to attempt))
                        if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        val timeout = Runnable {
                            if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                                connectionInProgress = false
                                connectionTimeout = null
                                runCatching { manager.cancelConnect(channel, null) }
                                if (attempt < 2) {
                                    mainHandler.postDelayed({
                                        if (!closed && !connectionInProgress && token == connectionAttemptToken) {
                                            connectionInProgress = true
                                            connectAttempt(
                                                deviceAddress,
                                                preferThisDeviceAsGroupOwner,
                                                token,
                                                attempt + 1
                                            )
                                        }
                                    }, 800L * (attempt + 1))
                                } else {
                                    connectionAttemptToken++
                                    expectedPeerDeviceAddress = null
                                    cancelClientPeerConfirmation()
                                    reportError("انتهت مهلة اتصال Wi-Fi Direct بعد محاولات متعددة. تأكد من بقاء الجهازين قريبين ثم أعد المحاولة.")
                                }
                            }
                        }
                        connectionTimeout = timeout
                        mainHandler.postDelayed(timeout, 30_000L)
                    }

                    override fun onFailure(reason: Int) {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "CONNECT_REQUEST_REJECTED_BY_FRAMEWORK", mapOf("reason" to reason, "attempt" to attempt))
                        if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
                        if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) && attempt < 2) {
                            recoverAndRetryConnect(
                                deviceAddress = deviceAddress,
                                preferThisDeviceAsGroupOwner = preferThisDeviceAsGroupOwner,
                                token = token,
                                attempt = attempt,
                                reason = reason
                            )
                        } else {
                            connectionInProgress = false
                            connectionTimeout?.let(mainHandler::removeCallbacks)
                            connectionTimeout = null
                            expectedPeerDeviceAddress = null
                            cancelClientPeerConfirmation()
                            connectionAttemptToken++
                            reportError(connectFailureMessage(reason))
                        }
                    }
                })
            } catch (failure: SecurityException) {
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                expectedPeerDeviceAddress = null
                cancelClientPeerConfirmation()
                connectionAttemptToken++
                reportError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                expectedPeerDeviceAddress = null
                cancelClientPeerConfirmation()
                connectionAttemptToken++
                UltraShareDiagnostics.failure("WIFI_DIRECT_CONNECT", failure)
                reportError(failure.localizedMessage ?: "تعذر بدء اتصال Wi-Fi Direct.")
            }
        }

        /**
         * An immediate P2P ERROR/BUSY is not a useful reason to resend the same
         * connect() call against possibly stale framework state. Cancel negotiation,
         * inspect any leftover client group, then refresh peers before retrying.
         * A group-owner group is never removed here: on this side it may be the
         * receiver's already-established group.
         */
        @SuppressLint("MissingPermission")
        private fun recoverAndRetryConnect(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int,
            reason: Int
        ) {
            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "CONNECT_RECOVERY_STARTED",
                mapOf("reason" to reason, "attempt" to attempt)
            )
            runCatching { manager.cancelConnect(channel, null) }
            mainHandler.postDelayed({
                if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@postDelayed

                val inspectionFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                val inspectionTimeout = Runnable {
                    if (inspectionFinished.compareAndSet(false, true)) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_RECOVERY_GROUP_INSPECTION_TIMEOUT",
                            mapOf("attempt" to attempt)
                        )
                        refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                    }
                }
                mainHandler.postDelayed(inspectionTimeout, 2_000L)

                try {
                    manager.requestGroupInfo(channel) { group ->
                        if (!inspectionFinished.compareAndSet(false, true)) return@requestGroupInfo
                        mainHandler.removeCallbacks(inspectionTimeout)
                        if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestGroupInfo

                        if (group == null) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "CONNECT_RECOVERY_NO_STALE_GROUP",
                                mapOf("attempt" to attempt)
                            )
                            refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                            return@requestGroupInfo
                        }

                        // requestGroupInfo alone cannot distinguish a stale client group
                        // from a group that actually formed just before connect() reported
                        // failure. Never remove a group until connection info confirms it
                        // is not active, and never remove a group-owner group from this path.
                        val connectionCheckFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                        val connectionCheckTimeout = Runnable {
                            if (connectionCheckFinished.compareAndSet(false, true)) {
                                if (group.isGroupOwner) {
                                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                } else {
                                    removeStaleClientGroupAndRetry(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                }
                            }
                        }
                        mainHandler.postDelayed(connectionCheckTimeout, 1_500L)
                        try {
                            manager.requestConnectionInfo(channel) { info ->
                                if (!connectionCheckFinished.compareAndSet(false, true)) return@requestConnectionInfo
                                mainHandler.removeCallbacks(connectionCheckTimeout)
                                if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestConnectionInfo
                                if (info.groupFormed) {
                                    UltraShareDiagnostics.event(
                                        "WIFI_DIRECT",
                                        "CONNECT_RECOVERY_GROUP_ALREADY_FORMED",
                                        mapOf("isGroupOwner" to info.isGroupOwner, "attempt" to attempt)
                                    )
                                    requestConnectionInfo()
                                } else if (group.isGroupOwner) {
                                    UltraShareDiagnostics.event(
                                        "WIFI_DIRECT",
                                        "CONNECT_RECOVERY_GROUP_OWNER_NOT_FORMED",
                                        mapOf("attempt" to attempt)
                                    )
                                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                } else {
                                    removeStaleClientGroupAndRetry(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                }
                            }
                        } catch (failure: RuntimeException) {
                            if (connectionCheckFinished.compareAndSet(false, true)) {
                                mainHandler.removeCallbacks(connectionCheckTimeout)
                                if (group.isGroupOwner) {
                                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                } else {
                                    removeStaleClientGroupAndRetry(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                                }
                            }
                        }
                    }
                } catch (failure: RuntimeException) {
                    if (inspectionFinished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(inspectionTimeout)
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_RECOVERY_GROUP_INSPECTION_FAILED",
                            mapOf("type" to failure.javaClass.simpleName, "attempt" to attempt)
                        )
                        refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                    }
                }
            }, 500L * (attempt + 1))
        }

        @SuppressLint("MissingPermission")
        private fun removeStaleClientGroupAndRetry(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int
        ) {
            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "CONNECT_RECOVERY_STALE_CLIENT_GROUP_FOUND",
                mapOf("attempt" to attempt)
            )
            val cleanupFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val cleanupTimeout = Runnable {
                if (cleanupFinished.compareAndSet(false, true)) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_RECOVERY_GROUP_REMOVE_TIMEOUT",
                        mapOf("attempt" to attempt)
                    )
                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                }
            }
            mainHandler.postDelayed(cleanupTimeout, 2_000L)
            try {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!cleanupFinished.compareAndSet(false, true)) return
                        mainHandler.removeCallbacks(cleanupTimeout)
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_RECOVERY_STALE_GROUP_REMOVED",
                            mapOf("attempt" to attempt)
                        )
                        refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                    }

                    override fun onFailure(removeReason: Int) {
                        if (!cleanupFinished.compareAndSet(false, true)) return
                        mainHandler.removeCallbacks(cleanupTimeout)
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_RECOVERY_GROUP_REMOVE_FAILED",
                            mapOf("reason" to removeReason, "attempt" to attempt)
                        )
                        verifyGroupReleasedThenRetry(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                    }
                })
            } catch (failure: RuntimeException) {
                if (cleanupFinished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(cleanupTimeout)
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_RECOVERY_GROUP_REMOVE_EXCEPTION",
                        mapOf("type" to failure.javaClass.simpleName, "attempt" to attempt)
                    )
                    verifyGroupReleasedThenRetry(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun verifyGroupReleasedThenRetry(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int
        ) {
            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
            val verificationFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (verificationFinished.compareAndSet(false, true)) {
                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                }
            }
            mainHandler.postDelayed(timeout, 1_500L)
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (!verificationFinished.compareAndSet(false, true)) return@requestConnectionInfo
                    mainHandler.removeCallbacks(timeout)
                    if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestConnectionInfo
                    if (info.groupFormed) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_RECOVERY_GROUP_STILL_FORMED",
                            mapOf("isGroupOwner" to info.isGroupOwner, "attempt" to attempt)
                        )
                        requestConnectionInfo()
                    } else {
                        refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                    }
                }
            } catch (failure: RuntimeException) {
                if (verificationFinished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    refreshPeersAndRetryConnect(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun refreshPeersAndRetryConnect(
            deviceAddress: String,
            preferThisDeviceAsGroupOwner: Boolean,
            token: Int,
            attempt: Int
        ) {
            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
            val refreshFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (refreshFinished.compareAndSet(false, true) &&
                    isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)
                ) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_RECOVERY_PEER_REFRESH_TIMEOUT",
                        mapOf("attempt" to attempt)
                    )
                    connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                }
            }
            mainHandler.postDelayed(timeout, 2_500L)
            try {
                manager.requestPeers(channel) { peers ->
                    if (!refreshFinished.compareAndSet(false, true)) return@requestPeers
                    mainHandler.removeCallbacks(timeout)
                    if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestPeers
                    val refreshedPeer = peers.deviceList.firstOrNull {
                        it.deviceAddress.equals(deviceAddress, ignoreCase = true)
                    }
                    val retryAddress = refreshedPeer?.deviceAddress ?: deviceAddress
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_RECOVERY_PEERS_REFRESHED",
                        mapOf(
                            "peerCount" to peers.deviceList.size,
                            "targetFound" to (refreshedPeer != null),
                            "targetStatus" to refreshedPeer?.status,
                            "attempt" to attempt
                        )
                    )
                    mainHandler.postDelayed({
                        if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "CONNECT_RECOVERY_RETRY_SCHEDULED",
                                mapOf("attempt" to attempt + 1, "targetFound" to (refreshedPeer != null))
                            )
                            connectAttempt(retryAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                        }
                    }, 450L)
                }
            } catch (failure: RuntimeException) {
                if (refreshFinished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_RECOVERY_PEER_REFRESH_FAILED",
                        mapOf("type" to failure.javaClass.simpleName, "attempt" to attempt)
                    )
                    mainHandler.postDelayed({
                        if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                            connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                        }
                    }, 450L)
                }
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
            if (!requiresWifiDirectLocationService(Build.VERSION.SDK_INT)) return true
            val location = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    location.isLocationEnabled
                } else {
                    // API 23–27 has no LocationManager.isLocationEnabled property.
                    // Wi-Fi Direct discovery on these releases requires at least one
                    // location provider to be enabled as well as runtime location permission.
                    location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
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
                reportError("يلزم إذن الأجهزة القريبة لعرض أجهزة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_REQUEST_PEERS", failure)
                reportError(failure.localizedMessage ?: "تعذر تحديث قائمة أجهزة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestConnectionInfo() {
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (closed) return@requestConnectionInfo
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        if (info.groupFormed) "GROUP_FORMED" else "GROUP_NOT_FORMED",
                        mapOf("isGroupOwner" to info.isGroupOwner, "groupOwnerHost" to info.groupOwnerAddress?.hostAddress)
                    )
                    if (!info.groupFormed) {
                        cancelOwnerPeerConfirmation()
                        cancelClientPeerConfirmation()
                        if (wasConnected) {
                            wasConnected = false
                            expectedPeerDeviceAddress = null
                            reportError("انقطع اتصال Wi-Fi Direct. أعد الاتصال أو استخدم مسار الشبكة المحلية الاحتياطي.")
                        }
                        return@requestConnectionInfo
                    }
                    if (info.isGroupOwner) {
                        cancelClientPeerConfirmation()
                        // Do not equate an empty owner group with a paired device. Poll
                        // GroupInfo briefly because GROUP_FORMED can precede clientList updates.
                        if (!ownerPeerCheckInFlight && ownerPeerCheckRunnable == null) {
                            ownerPeerCheckToken++
                            requestOwnerPeerConfirmation(info, ownerPeerCheckToken, 0)
                        }
                        return@requestConnectionInfo
                    }
                    cancelOwnerPeerConfirmation()
                    val host = info.groupOwnerAddress?.hostAddress
                    val expectedAddress = expectedPeerDeviceAddress
                    if (host == null || !UltraShareTransport.isValidPrivateLanHost(host)) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "GROUP_OWNER_ADDRESS_INVALID",
                            mapOf("addressPresent" to (host != null))
                        )
                        reportError("تم تكوين مجموعة Wi-Fi Direct لكن عنوان مالك المجموعة غير صالح.")
                    } else if (expectedAddress.isNullOrBlank()) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CLIENT_GROUP_WITHOUT_EXPECTED_PEER",
                            mapOf("groupOwnerHostPresent" to true)
                        )
                        reportError("رفض التطبيق استخدام مجموعة Wi-Fi Direct غير مرتبطة بطلب اقتران حالي. ابدأ الاقتران من جديد.")
                    } else {
                        requestClientPeerConfirmation(info, host, expectedAddress, clientPeerCheckToken, 0)
                    }
                }
            } catch (failure: SecurityException) {
                reportError("يلزم إذن الأجهزة القريبة لقراءة اتصال Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_CONNECTION_INFO", failure)
                reportError(failure.localizedMessage ?: "تعذر قراءة حالة اتصال Wi-Fi Direct.")
            }
        }

        /**
         * Confirms that Android's formed client group belongs to the exact peer
         * selected for this pairing attempt. A stale P2P group must never authorize
         * opening MPC2 against an unrelated or QR-advertised fallback address.
         */
        @SuppressLint("MissingPermission")
        private fun requestClientPeerConfirmation(
            info: android.net.wifi.p2p.WifiP2pInfo,
            host: String,
            expectedAddress: String,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != clientPeerCheckToken || clientPeerCheckInFlight ||
                clientPeerCheckRunnable != null
            ) return
            clientPeerCheckInFlight = true
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (!completed.compareAndSet(false, true)) return@Runnable
                clientPeerCheckInFlight = false
                clientPeerCheckTimeout = null
                scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
            }
            clientPeerCheckTimeout = timeout
            mainHandler.postDelayed(timeout, 1_500L)
            try {
                manager.requestGroupInfo(channel) { group ->
                    if (!completed.compareAndSet(false, true)) return@requestGroupInfo
                    mainHandler.removeCallbacks(timeout)
                    clientPeerCheckTimeout = null
                    clientPeerCheckInFlight = false
                    if (closed || token != clientPeerCheckToken) return@requestGroupInfo
                    val actualAddress = group?.owner?.deviceAddress
                    val matches = group != null && !group.isGroupOwner &&
                        isExpectedWifiDirectPeer(expectedAddress, actualAddress)
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        when {
                            matches -> "CLIENT_GROUP_OWNER_CONFIRMED"
                            actualAddress.isNullOrBlank() -> "CLIENT_GROUP_OWNER_IDENTITY_PENDING"
                            else -> "CLIENT_GROUP_OWNER_MISMATCH"
                        },
                        mapOf(
                            "attempt" to attempt,
                            "expectedAddressPresent" to expectedAddress.isNotBlank(),
                            "actualAddressPresent" to !actualAddress.isNullOrBlank(),
                            "addressesMatch" to matches,
                            "groupIsOwner" to (group?.isGroupOwner == true),
                            "clientCount" to (group?.clientList?.size ?: 0)
                        )
                    )
                    if (matches && !info.isGroupOwner && info.groupFormed) {
                        clientPeerCheckRunnable?.let(mainHandler::removeCallbacks)
                        clientPeerCheckRunnable = null
                        connectionInProgress = false
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        connectionTimeout = null
                        if (shouldEmitWifiDirectConnected(wasConnected)) {
                            wasConnected = true
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "GROUP_OWNER_ADDRESS_CONFIRMED",
                                mapOf("groupOwnerHost" to host, "ownerIdentityVerified" to true)
                            )
                            onConnected(host, false)
                        }
                    } else if (wasConnected) {
                        wasConnected = false
                        expectedPeerDeviceAddress = null
                        cancelClientPeerConfirmation()
                        reportError("تغير جهاز مالك مجموعة Wi-Fi Direct أو انقطع الاقتران مع الجهاز المحدد. أعد الاقتران قبل إرسال الملفات.")
                    } else {
                        scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
                    }
                }
            } catch (failure: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    clientPeerCheckTimeout = null
                    clientPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_CLIENT_GROUP_INFO_SECURITY", failure)
                    scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
                }
            } catch (failure: RuntimeException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    clientPeerCheckTimeout = null
                    clientPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_CLIENT_GROUP_INFO", failure)
                    scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
                }
            }
        }

        private fun scheduleClientPeerConfirmation(
            info: android.net.wifi.p2p.WifiP2pInfo,
            host: String,
            expectedAddress: String,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != clientPeerCheckToken || wasConnected ||
                clientPeerCheckRunnable != null
            ) return
            if (attempt >= 8) {
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                connectionAttemptToken++
                expectedPeerDeviceAddress = null
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT",
                    "CLIENT_GROUP_OWNER_CONFIRMATION_FAILED",
                    mapOf("attempts" to attempt + 1, "groupFormed" to info.groupFormed)
                )
                reportError("أنشأ Android مجموعة Wi-Fi Direct، لكن لم نتمكن من تأكيد أن الجهاز المتصل هو الجهاز الذي اخترته. أوقف الاتصال السابق وأعد الاقتران.")
                return
            }
            val retry = Runnable {
                if (!closed && token == clientPeerCheckToken && !wasConnected) {
                    clientPeerCheckRunnable = null
                    requestClientPeerConfirmation(info, host, expectedAddress, token, attempt + 1)
                }
            }
            clientPeerCheckRunnable = retry
            mainHandler.postDelayed(retry, 500L)
        }

        private fun cancelClientPeerConfirmation() {
            clientPeerCheckToken++
            clientPeerCheckRunnable?.let(mainHandler::removeCallbacks)
            clientPeerCheckRunnable = null
            clientPeerCheckTimeout?.let(mainHandler::removeCallbacks)
            clientPeerCheckTimeout = null
            clientPeerCheckInFlight = false
        }

        @SuppressLint("MissingPermission")
        private fun requestOwnerPeerConfirmation(
            info: android.net.wifi.p2p.WifiP2pInfo,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != ownerPeerCheckToken || ownerPeerCheckInFlight) return
            ownerPeerCheckInFlight = true
            // Each request owns its timeout/callback pair. OEM P2P callbacks can
            // arrive after the timeout; without this gate an old callback can clear
            // a newer request's state or complete a later pairing attempt.
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (!completed.compareAndSet(false, true)) return@Runnable
                if (token != ownerPeerCheckToken || closed) return@Runnable
                ownerPeerCheckInFlight = false
                ownerPeerCheckTimeout = null
                scheduleOwnerPeerConfirmation(info, token, attempt)
            }
            ownerPeerCheckTimeout = timeout
            mainHandler.postDelayed(timeout, 1_500L)
            try {
                manager.requestGroupInfo(channel) { group ->
                    if (!completed.compareAndSet(false, true)) return@requestGroupInfo
                    mainHandler.removeCallbacks(timeout)
                    if (closed || token != ownerPeerCheckToken) return@requestGroupInfo
                    if (ownerPeerCheckTimeout === timeout) ownerPeerCheckTimeout = null
                    ownerPeerCheckInFlight = false
                    val clientAddresses = group?.clientList
                        ?.mapNotNull { address -> address.deviceAddress?.takeIf { it.isNotBlank() } }
                        .orEmpty()
                    val clientCount = clientAddresses.size
                    val expectedPeer = expectedPeerDeviceAddress
                    val expectedClientPresent = if (expectedPeer.isNullOrBlank()) {
                        clientCount > 0
                    } else {
                        clientAddresses.any { isExpectedWifiDirectPeer(expectedPeer, it) }
                    }
                    val groupOwnerConfirmed = group?.isGroupOwner == true
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        if (expectedClientPresent && groupOwnerConfirmed) "GROUP_PEER_CONFIRMED" else "GROUP_WAITING_FOR_PEER",
                        mapOf(
                            "clientCount" to clientCount,
                            "expectedPeerPresent" to !expectedPeer.isNullOrBlank(),
                            "expectedClientPresent" to expectedClientPresent,
                            "groupOwnerConfirmed" to groupOwnerConfirmed,
                            "attempt" to attempt
                        )
                    )
                    if (isConfirmedWifiDirectOwnerPeer(
                            groupFormed = info.groupFormed,
                            connectionInfoSaysOwner = info.isGroupOwner,
                            groupInfoSaysOwner = groupOwnerConfirmed,
                            clientCount = clientCount,
                            expectedClientPresent = expectedClientPresent
                        )
                    ) {
                        ownerPeerCheckRunnable?.let(mainHandler::removeCallbacks)
                        ownerPeerCheckRunnable = null
                        connectionInProgress = false
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        connectionTimeout = null
                        if (shouldEmitWifiDirectConnected(wasConnected)) {
                            wasConnected = true
                            onConnected(null, true)
                        }
                    } else if (wasConnected) {
                        wasConnected = false
                        cancelOwnerPeerConfirmation()
                        reportError("غادر الجهاز الآخر مجموعة Wi-Fi Direct. أعد الاقتران لإكمال المشاركة.")
                    } else {
                        scheduleOwnerPeerConfirmation(info, token, attempt)
                    }
                }
            } catch (failure: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    if (ownerPeerCheckTimeout === timeout) ownerPeerCheckTimeout = null
                    ownerPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_GROUP_INFO_SECURITY", failure)
                    scheduleOwnerPeerConfirmation(info, token, attempt)
                }
            } catch (failure: RuntimeException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    if (ownerPeerCheckTimeout === timeout) ownerPeerCheckTimeout = null
                    ownerPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_GROUP_INFO", failure)
                    scheduleOwnerPeerConfirmation(info, token, attempt)
                }
            }
        }

        private fun scheduleOwnerPeerConfirmation(
            info: android.net.wifi.p2p.WifiP2pInfo,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != ownerPeerCheckToken || wasConnected) return
            if (!shouldRetryWifiDirectPeerConfirmation(attempt)) {
                ownerPeerCheckRunnable = null
                UltraShareDiagnostics.event("WIFI_DIRECT", "GROUP_PEER_CONFIRMATION_TIMEOUT", mapOf("attempts" to attempt))
                reportError("تم إنشاء مجموعة Wi-Fi Direct لكن لم يؤكد Android انضمام الهاتف الآخر خلال 10 ثوانٍ. أبقِ الجهازين قريبين ثم أعد الاقتران.")
                return
            }
            ownerPeerCheckRunnable?.let(mainHandler::removeCallbacks)
            val next = Runnable {
                if (!closed && token == ownerPeerCheckToken && !wasConnected) {
                    ownerPeerCheckRunnable = null
                    requestOwnerPeerConfirmation(info, token, attempt + 1)
                }
            }
            ownerPeerCheckRunnable = next
            mainHandler.postDelayed(next, 500L)
        }

        private fun cancelOwnerPeerConfirmation() {
            ownerPeerCheckToken++
            ownerPeerCheckRunnable?.let(mainHandler::removeCallbacks)
            ownerPeerCheckRunnable = null
            ownerPeerCheckTimeout?.let(mainHandler::removeCallbacks)
            ownerPeerCheckTimeout = null
            ownerPeerCheckInFlight = false
        }

        /**
         * WifiP2pManager channels can disconnect after Wi-Fi toggles or OEM service
         * restarts. Reinitialize the channel instead of leaving this Session permanently
         * unusable; the caller receives an error and can safely retry or enter fallback.
         */
        internal fun onChannelDisconnected() {
            if (closed) return
            UltraShareDiagnostics.event("WIFI_DIRECT", "CHANNEL_DISCONNECTED")
            connectionInProgress = false
            connectionAttemptToken++
            connectionTimeout?.let(mainHandler::removeCallbacks)
            connectionTimeout = null
            groupAttemptToken++
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            groupAttemptTimeout = null
            invitationMatch = null
            invitationRequest = null
            peerDiscoveryActive = false
            wasConnected = false
            expectedPeerDeviceAddress = null
            cancelOwnerPeerConfirmation()
            cancelClientPeerConfirmation()
            try {
                val replacement = manager.initialize(
                    appContext,
                    Looper.getMainLooper(),
                    WifiP2pManager.ChannelListener { onChannelDisconnected() }
                )
                if (replacement == null) {
                    reportError("انقطع اتصال Wi-Fi Direct وتعذرت إعادة تهيئة قناة النظام. أوقف Wi-Fi ثم أعد تشغيله.")
                } else {
                    channel = replacement
                    reportError("أعاد Android تهيئة قناة Wi-Fi Direct بعد انقطاعها. أعد المحاولة لإنشاء الاتصال.")
                }
            } catch (failure: SecurityException) {
                reportError("انقطعت قناة Wi-Fi Direct ويلزم إذن الأجهزة القريبة لإعادة تهيئتها.")
            } catch (failure: RuntimeException) {
                reportError(failure.localizedMessage ?: "تعذرت إعادة تهيئة قناة Wi-Fi Direct.")
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
            expectedPeerDeviceAddress = null
            cancelClientPeerConfirmation()
            cancelOwnerPeerConfirmation()
            groupAttemptToken++
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            groupAttemptTimeout = null
            connectionTimeout?.let(mainHandler::removeCallbacks)
            connectionTimeout = null
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { manager.clearServiceRequests(channel, null) }
            runCatching { manager.clearLocalServices(channel, null) }
            invitationMatch = null
            invitationRequest = null
            peerDiscoveryActive = false
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
        var session: Session? = null
        val channel = manager.initialize(
            context.applicationContext,
            Looper.getMainLooper(),
            WifiP2pManager.ChannelListener { session?.onChannelDisconnected() }
        ) ?: error("تعذر تهيئة Wi-Fi Direct على هذا الجهاز.")
        val created = Session(context, manager, channel, onPeers, onConnected, onError)
        session = created
        return created
    }
}

/** Matches the owner MAC returned by WifiP2pGroup against the selected peer. */
internal fun isExpectedWifiDirectPeer(expectedAddress: String?, actualAddress: String?): Boolean {
    val macPattern = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    val expected = expectedAddress?.trim()
    val actual = actualAddress?.trim()
    return !expected.isNullOrBlank() && !actual.isNullOrBlank() &&
        macPattern.matches(expected) && macPattern.matches(actual) &&
        expected.equals(actual, ignoreCase = true)
}

/** Converts the Android P2P radio extra to a stable diagnostic label. */
internal fun wifiP2pRadioStateName(state: Int): String = when (state) {
    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "ENABLED"
    WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "DISABLED"
    Int.MIN_VALUE -> "MISSING"
    else -> "UNKNOWN"
}

/** Android 6 through 12L require Location services enabled for Wi-Fi Direct service discovery. */
internal fun requiresWifiDirectLocationService(sdkInt: Int): Boolean = sdkInt in 23..32

/** Bounds owner-side client confirmation retries without treating an empty group as paired. */
internal fun shouldRetryWifiDirectPeerConfirmation(attempt: Int, maxAttempts: Int = 20): Boolean =
    maxAttempts > 0 && attempt in 0 until maxAttempts

/** Emits the connected callback only on a disconnected-to-connected transition. */
internal fun shouldEmitWifiDirectConnected(wasConnected: Boolean): Boolean = !wasConnected

/** A formed owner group is paired only when both Android callbacks agree on role and peer. */
internal fun isConfirmedWifiDirectOwnerPeer(
    groupFormed: Boolean,
    connectionInfoSaysOwner: Boolean,
    groupInfoSaysOwner: Boolean,
    clientCount: Int,
    expectedClientPresent: Boolean
): Boolean =
    groupFormed && connectionInfoSaysOwner && groupInfoSaysOwner &&
        clientCount > 0 && expectedClientPresent

/** Compatibility predicate retained for older unit-test callers. */
internal fun isConfirmedWifiDirectOwnerPeer(groupFormed: Boolean, isGroupOwner: Boolean, clientCount: Int): Boolean =
    groupFormed && isGroupOwner && clientCount > 0

/** Rejects delayed callbacks after timeout, completion, closure, or a newer attempt. */
internal fun isCurrentWifiDirectConnectionAttempt(
    sessionClosed: Boolean,
    connectionInProgress: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && connectionInProgress && currentToken == callbackToken

/** Rejects delayed group-setup callbacks after timeout, closure, or a newer attempt. */
internal fun isCurrentWifiDirectGroupAttempt(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken