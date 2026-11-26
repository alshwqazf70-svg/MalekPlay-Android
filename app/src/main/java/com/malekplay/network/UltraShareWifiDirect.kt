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
        private val onError: (String) -> Unit,
        private val onLocalAddress: (String?) -> Unit,
        private val onGroupInfo: (ssid: String, passphrase: String, ownerAddress: String?, wifiSecurity: String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        @Volatile private var channel: WifiP2pManager.Channel = initialChannel
        private var closed = false
        private var localInvitationService: WifiP2pDnsSdServiceInfo? = null
        private var localInvitationId: String? = null
        // A DNS-SD clear/add request may still be inside the framework even before
        // localInvitationService is assigned by its success callback.
        private var localInvitationOperationPending = false
        // A timed-out/failed clear leaves registration state unknown; never reuse it blindly.
        private var localInvitationCleanupPending = false
        private var localInvitationAdvertisementTimeout: Runnable? = null
        // Prevent delayed clear/add callbacks from publishing an obsolete QR identity.
        private var localInvitationToken = 0
        // Tracks both the pending addServiceRequest call and the installed request.
        // A request must be visible to cancellation before crossing the async framework boundary.
        private var invitationRequest: WifiP2pDnsSdServiceRequest? = null
        private var invitationRequestChannel: WifiP2pManager.Channel? = null
        private var invitationRequestInstalled = false
        // Separates retries for the same service request; old framework callbacks
        // must not mutate the state of a newer discoverServices invocation.
        private var invitationDiscoveryToken = 0
        private var peerDiscoveryStopToken = 0
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
        // Tracks a previously confirmed P2P group even before its first client pairs.
        // A receiver may publish DNS-SD while waiting for the first QR scan.
        private var formedGroupObserved = false
        // Owner-side peer confirmation is retried because Android may report GROUP_FORMED
        // before GroupInfo.clientList is populated on some OEM P2P stacks.
        private var ownerPeerCheckToken = 0
        private var ownerPeerCheckDeadlineElapsedMs = 0L
        private var ownerPeerCheckInFlight = false
        private var ownerPeerCheckRunnable: Runnable? = null
        private var ownerPeerCheckTimeout: Runnable? = null
        private var connectionAttemptToken = 0
        private var connectionInfoRequestToken = 0
        private var groupCredentialsRequestToken = 0
        private var connectionTimeout: Runnable? = null
        private var groupAttemptToken = 0
        private var groupAttemptTimeout: Runnable? = null
        private var lastP2pRadioState: Int? = null
        // The discovered P2P address is only a framework connection selector.
        // It is never compared with the remote group's reported MAC: Android/OEM
        // privacy behavior can rotate or virtualize that address during negotiation.
        @Volatile private var pairingTargetDeviceAddress: String? = null
        private var clientPeerCheckToken = 0
        private var clientPeerCheckInFlight = false
        private var clientPeerCheckRunnable: Runnable? = null
        private var clientPeerCheckTimeout: Runnable? = null
        private var clientPeerCheckDeadlineElapsedMs = 0L

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
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECTION_CHANGED_BROADCAST",
                            mapOf(
                                "networkInfoPresent" to (networkInfo != null),
                                "state" to networkInfo?.state?.name.orEmpty(),
                                "detailedState" to networkInfo?.detailedState?.name.orEmpty(),
                                "connected" to (networkInfo?.isConnected == true),
                                "sdkInt" to Build.VERSION.SDK_INT
                            )
                        )
                        requestConnectionInfo()
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val discoveryState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            Int.MIN_VALUE
                        )
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "DISCOVERY_STATE_BROADCAST",
                            mapOf(
                                "state" to discoveryState,
                                "stateName" to when (discoveryState) {
                                    WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> "STARTED"
                                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> "STOPPED"
                                    else -> "UNKNOWN"
                                },
                                "sdkInt" to Build.VERSION.SDK_INT
                            )
                        )
                    }
                }
            }
        }

        init {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
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
                recordAdvertisementFailure("على Android 6–12 يجب تشغيل خدمة الموقع لنشر واكتشاف دعوة Wi-Fi Direct.")
                return
            }
            val service = WifiP2pDnsSdServiceInfo.newInstance(
                "malekplay",
                "_malekplay._tcp",
                mapOf("peer" to peerId)
            )
            val advertisementToken = ++localInvitationToken
            val advertisementChannel = channel
            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
            localInvitationAdvertisementTimeout = null
            fun isCurrentAdvertisement(): Boolean =
                isCurrentWifiDirectAdvertisementOperation(closed, localInvitationToken, advertisementToken)

            try {
                if (canReuseWifiDirectAdvertisement(
                        samePeer = localInvitationId == peerId,
                        servicePresent = localInvitationService != null,
                        operationPending = localInvitationOperationPending,
                        cleanupPending = localInvitationCleanupPending
                    )
                ) {
                    onAdvertised()
                    return
                }
                localInvitationOperationPending = true
                val advertisementTimeout = Runnable {
                    if (isCurrentAdvertisement()) {
                        localInvitationAdvertisementTimeout = null
                        // Invalidate late clear/add callbacks before asking the ViewModel
                        // to retire this P2P group and activate its independent fallback.
                        localInvitationToken++
                        localInvitationOperationPending = false
                        localInvitationCleanupPending = true
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "LOCAL_INVITATION_ADVERTISEMENT_TIMEOUT",
                            mapOf("timeoutMs" to 10_000, "advertisementToken" to advertisementToken)
                        )
                        recordAdvertisementFailure("انتهت مهلة نشر دعوة Wi-Fi Direct؛ سننتقل إلى مسار اتصال بديل.")
                    }
                }
                localInvitationAdvertisementTimeout = advertisementTimeout
                mainHandler.postDelayed(advertisementTimeout, 10_000L)
                manager.clearLocalServices(advertisementChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentAdvertisement()) return
                        localInvitationCleanupPending = false
                        try {
                            manager.addLocalService(advertisementChannel, service, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    if (!isCurrentAdvertisement()) {
                                        // The add completed after cancellation or a newer
                                        // invitation. Remove only this stale service record.
                                        runCatching { manager.removeLocalService(advertisementChannel, service, null) }
                                        UltraShareDiagnostics.event(
                                            "WIFI_DIRECT",
                                            "STALE_LOCAL_INVITATION_ADD_REMOVED"
                                        )
                                        return
                                    }
                                    localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                                    localInvitationAdvertisementTimeout = null
                                    localInvitationOperationPending = false
                                    localInvitationCleanupPending = false
                                    localInvitationService = service
                                    localInvitationId = peerId
                                    UltraShareDiagnostics.event(
                                        "WIFI_DIRECT",
                                        "LOCAL_INVITATION_ADVERTISED",
                                        mapOf("advertisementToken" to advertisementToken)
                                    )
                                    onAdvertised()
                                }

                                override fun onFailure(reason: Int) {
                                    if (isCurrentAdvertisement()) {
                                        localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                                        localInvitationAdvertisementTimeout = null
                                        localInvitationOperationPending = false
                                        localInvitationCleanupPending = true
                                        recordAdvertisementFailure("تعذر إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                                    }
                                }
                            })
                        } catch (failure: SecurityException) {
                            if (isCurrentAdvertisement()) {
                                localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                                localInvitationAdvertisementTimeout = null
                                localInvitationOperationPending = false
                                localInvitationCleanupPending = true
                                recordAdvertisementFailure("يلزم إذن الأجهزة القريبة لإعلان دعوة Wi-Fi Direct.")
                            }
                        } catch (failure: RuntimeException) {
                            if (isCurrentAdvertisement()) {
                                localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                                localInvitationAdvertisementTimeout = null
                                localInvitationOperationPending = false
                                localInvitationCleanupPending = true
                                UltraShareDiagnostics.failure("WIFI_DIRECT_ADD_LOCAL_INVITATION", failure)
                                recordAdvertisementFailure(failure.localizedMessage ?: "تعذر إعلان دعوة Wi-Fi Direct.")
                            }
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (isCurrentAdvertisement()) {
                            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                            localInvitationAdvertisementTimeout = null
                            localInvitationOperationPending = false
                            localInvitationCleanupPending = true
                            recordAdvertisementFailure("تعذر تحديث إعلان دعوة MalekPlay عبر Wi-Fi Direct (رمز $reason).")
                        }
                    }
                })
            } catch (failure: SecurityException) {
                if (isCurrentAdvertisement()) {
                    localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                    localInvitationAdvertisementTimeout = null
                    localInvitationOperationPending = false
                    localInvitationCleanupPending = true
                    recordAdvertisementFailure("يلزم إذن الأجهزة القريبة لإعلان دعوة Wi-Fi Direct.")
                }
            } catch (failure: RuntimeException) {
                if (isCurrentAdvertisement()) {
                    localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                    localInvitationAdvertisementTimeout = null
                    localInvitationOperationPending = false
                    localInvitationCleanupPending = true
                    UltraShareDiagnostics.failure("WIFI_DIRECT_ADVERTISE_INVITATION", failure)
                    recordAdvertisementFailure(failure.localizedMessage ?: "تعذر إعلان دعوة Wi-Fi Direct.")
                }
            }
        }

        private fun recordAdvertisementFailure(message: String) {
            // The active Android receiver flow publishes its QR only after this
            // DNS-SD advertisement succeeds. Silently recording the failure leaves
            // LegacyUltraShareViewModel waiting forever with no usable QR. Propagate
            // the failure through the session callback so the receiver can perform
            // its verified LAN/Hotspot fallback, matching the working main behavior.
            UltraShareDiagnostics.event(
                "WIFI_DIRECT", "DNS_SD_ADVERTISEMENT_FAILED",
                mapOf("message" to message.take(180))
            )
            reportError(message)
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
            if (peerDiscoveryActive || invitationRequest != null) {
                reportError("هناك عملية اكتشاف Wi-Fi Direct أخرى نشطة أو قيد الإعداد؛ أوقفها وانتظر تأكيد Android قبل البحث عن دعوة QR.")
                return
            }
            if (!legacyDiscoveryPrerequisite()) {
                UltraShareDiagnostics.event("WIFI_DIRECT", "LOCATION_SERVICES_DISABLED", mapOf("sdkInt" to Build.VERSION.SDK_INT, "operation" to "discover_invitation"))
                reportError("على Android 6–12 يجب تشغيل خدمة الموقع للسماح باكتشاف أجهزة Wi-Fi Direct.")
                return
            }

            // Bind every DNS-SD callback to this exact request before registering
            // listeners. Android may deliver late TXT records after cancellation or
            // after a new scan for the same QR identity has already started.
            val request = WifiP2pDnsSdServiceRequest.newInstance()
            val requestChannel = channel
            invitationRequest = request
            invitationRequestChannel = requestChannel
            invitationRequestInstalled = false
            invitationMatch = onMatch
            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERY_REQUESTED", mapOf("sdkInt" to Build.VERSION.SDK_INT))

            try {
                manager.setDnsSdResponseListeners(
                    requestChannel,
                    WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
                        if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return@DnsSdServiceResponseListener
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "INVITATION_SERVICE_RESPONSE",
                            mapOf(
                                "instanceNamePresent" to !instanceName.isNullOrBlank(),
                                "registrationTypePresent" to !registrationType.isNullOrBlank(),
                                "deviceNamePresent" to !device.deviceName.isNullOrBlank(),
                                "deviceStatus" to statusLabel(device.status)
                            )
                        )
                    },
                    WifiP2pManager.DnsSdTxtRecordListener { _, records, device ->
                        if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                            UltraShareDiagnostics.event("WIFI_DIRECT", "STALE_INVITATION_TXT_CALLBACK_IGNORED")
                            return@DnsSdTxtRecordListener
                        }
                        val advertisedPeer = records["peer"]
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "INVITATION_TXT_RECORD_RECEIVED",
                            mapOf(
                                "peerIdentityMatched" to (advertisedPeer == peerId),
                                "peerIdentityPresent" to !advertisedPeer.isNullOrBlank(),
                                "deviceNamePresent" to !device.deviceName.isNullOrBlank(),
                                "deviceStatus" to statusLabel(device.status)
                            )
                        )
                        if (advertisedPeer != peerId) return@DnsSdTxtRecordListener
                        val callback = invitationMatch ?: return@DnsSdTxtRecordListener
                        val peer = Peer(
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز MalekPlayer",
                            deviceAddress = device.deviceAddress.orEmpty(),
                            status = statusLabel(device.status)
                        )
                        if (peer.deviceAddress.isNotBlank()) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "INVITATION_DISCOVERED",
                                mapOf("deviceName" to peer.name, "deviceStatus" to peer.status)
                            )
                            // Consume the callback before entering ViewModel code;
                            // duplicate records cannot initiate competing connects.
                            invitationMatch = null
                            callback(peer)
                        }
                    }
                )
                UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_CREATED", mapOf("sdkInt" to Build.VERSION.SDK_INT))
                prepareInvitationServiceRequest(request, 0, requestChannel)
            } catch (failure: SecurityException) {
                if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                    invitationRequest = null
                    invitationRequestChannel = null
                    invitationRequestInstalled = false
                    invitationMatch = null
                }
                reportError("يلزم إذن الأجهزة القريبة للبحث عن دعوة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                    invitationRequest = null
                    invitationRequestChannel = null
                    invitationRequestInstalled = false
                    invitationMatch = null
                }
                UltraShareDiagnostics.failure("WIFI_DIRECT_DISCOVER_INVITATION", failure)
                reportError(failure.localizedMessage ?: "تعذر البحث عن دعوة Wi-Fi Direct.")
            }
        }

        /**
         * Some Android/OEM P2P stacks briefly return BUSY while an earlier DNS-SD
         * operation is being retired. Retry cleanup serially; never add a second
         * service request until Android confirms the previous request list is clear.
         */
        @SuppressLint("MissingPermission")
        private fun prepareInvitationServiceRequest(
            request: WifiP2pDnsSdServiceRequest,
            attempt: Int,
            requestChannel: WifiP2pManager.Channel
        ) {
            if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return
            try {
                UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_CLEAR_REQUESTED", mapOf("attempt" to attempt))
                manager.clearServiceRequests(requestChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_CLEAR_CONFIRMED", mapOf("attempt" to attempt))
                        // The user may have cancelled while Android was clearing the old
                        // request list. Never add a request after its invitation was invalidated.
                        if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return
                        try {
                            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_ADD_REQUESTED", mapOf("attempt" to attempt))
                            manager.addServiceRequest(requestChannel, request, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_ADD_CONFIRMED", mapOf("attempt" to attempt))
                                    if (closed || !isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                                        // addServiceRequest completed after cancellation or
                                        // session closure. Remove this late request; it must
                                        // never start DNS-SD discovery or remain registered.
                                        runCatching {
                                            manager.removeServiceRequest(requestChannel, request, null)
                                        }
                                        return
                                    }
                                    invitationRequestInstalled = true
                                    startInvitationServiceDiscovery(request, 0, requestChannel)
                                }

                                override fun onFailure(reason: Int) {
                                    if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return
                                    if (reason == WifiP2pManager.BUSY && attempt < 3) {
                                        UltraShareDiagnostics.event(
                                            "WIFI_DIRECT",
                                            "SERVICE_REQUEST_ADD_RETRY",
                                            mapOf("reason" to reason, "attempt" to attempt + 1)
                                        )
                                        mainHandler.postDelayed(
                                            {
                                                if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                                                    prepareInvitationServiceRequest(request, attempt + 1, requestChannel)
                                                }
                                            },
                                            500L * (attempt + 1)
                                        )
                                    } else {
                                        invitationRequest = null
                                        invitationRequestChannel = null
                                        invitationRequestInstalled = false
                                        reportError("تعذر تهيئة البحث عن دعوة Wi-Fi Direct (رمز $reason).")
                                    }
                                }
                            })
                        } catch (failure: SecurityException) {
                            if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                                invitationRequest = null
                                invitationRequestChannel = null
                                invitationRequestInstalled = false
                                reportError("يلزم إذن الأجهزة القريبة لإعداد البحث عن دعوة Wi-Fi Direct.")
                            }
                        } catch (failure: RuntimeException) {
                            if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                                invitationRequest = null
                                invitationRequestChannel = null
                                invitationRequestInstalled = false
                                UltraShareDiagnostics.failure("WIFI_DIRECT_ADD_INVITATION_REQUEST", failure)
                                reportError(failure.localizedMessage ?: "تعذر تهيئة البحث عن دعوة Wi-Fi Direct.")
                            }
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return
                        val retryable = reason == WifiP2pManager.BUSY ||
                            reason == WifiP2pManager.ERROR
                        if (retryable && attempt < 3) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "SERVICE_REQUEST_CLEAR_RETRY",
                                mapOf("reason" to reason, "attempt" to attempt + 1)
                            )
                            mainHandler.postDelayed(
                                {
                                    if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                                        prepareInvitationServiceRequest(request, attempt + 1, requestChannel)
                                    }
                                },
                                500L * (attempt + 1)
                            )
                        } else {
                            invitationRequest = null
                            invitationRequestChannel = null
                            invitationRequestInstalled = false
                            reportError("تعذر تنظيف طلبات اكتشاف Wi-Fi Direct بعد إعادة المحاولة (رمز $reason).")
                        }
                    }
                })
            } catch (failure: SecurityException) {
                if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                    invitationRequest = null
                    invitationRequestChannel = null
                    invitationRequestInstalled = false
                    reportError("يلزم إذن الأجهزة القريبة لتنظيف طلبات اكتشاف Wi-Fi Direct.")
                }
            } catch (failure: RuntimeException) {
                if (isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) {
                    invitationRequest = null
                    invitationRequestChannel = null
                    invitationRequestInstalled = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_CLEAR_SERVICE_REQUESTS", failure)
                    reportError(failure.localizedMessage ?: "تعذر تنظيف طلبات اكتشاف Wi-Fi Direct.")
                }
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
            invitationDiscoveryToken++
            val requestChannel = invitationRequestChannel ?: channel
            val peerStopToken = ++peerDiscoveryStopToken
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            var cleanupStage = if (peerDiscoveryActive) "stop_peer_discovery" else "remove_service_request"
            var cleanupTimeout: Runnable? = null
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) {
                    cleanupTimeout?.let(mainHandler::removeCallbacks)
                    onResult(success)
                }
            }
            cleanupTimeout = Runnable {
                if (completed.compareAndSet(false, true)) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "INVITATION_DISCOVERY_CLEANUP_TIMEOUT",
                        mapOf("timeoutMs" to 5_000, "stage" to cleanupStage)
                    )
                    onResult(false)
                }
            }
            // Android/OEM P2P callbacks are not guaranteed to arrive. A missing
            // stopPeerDiscovery/removeServiceRequest callback must not strand QR
            // pairing after the exact receiver has already been discovered.
            mainHandler.postDelayed(requireNotNull(cleanupTimeout), 5_000L)
            fun removeRequest(stopSucceeded: Boolean) {
                cleanupStage = "remove_service_request"
                val request = invitationRequest
                val requestWasInstalled = invitationRequestInstalled
                val requestChannel = invitationRequestChannel ?: channel
                if (request == null) {
                    finish(stopSucceeded)
                    return
                }
                if (!requestWasInstalled) {
                    // clearServiceRequests/addServiceRequest is still in flight. Invalidate
                    // the request identity so a late add callback removes only its own
                    // service request instead of starting discovery after cancellation.
                    invitationRequest = null
                    invitationRequestChannel = null
                    invitationRequestInstalled = false
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "INVITATION_REQUEST_CANCELLED_DURING_SETUP",
                        mapOf("stopSucceeded" to stopSucceeded)
                    )
                    finish(false)
                    return
                }
                // Keep an installed request attached to the session until Android
                // confirms removal. If removeServiceRequest fails, a later cleanup
                // attempt must still be able to remove this exact request; clearing
                // the field early made the next stop falsely report success.
                try {
                    UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_REMOVE_REQUESTED")
                    manager.removeServiceRequest(requestChannel, request, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            // removeServiceRequest belongs to this captured request only.
                            // A newer QR scan may already have installed another request;
                            // never let this late callback clear that newer global state.
                            if (invitationRequest === request) {
                                invitationRequest = null
                                invitationRequestChannel = null
                                invitationRequestInstalled = false
                            }
                            UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_SERVICE_REQUEST_REMOVE_CONFIRMED")
                            finish(stopSucceeded)
                        }
                        override fun onFailure(reason: Int) {
                            // The framework did not confirm removal. Retain the exact
                            // request/channel as installed so the next stop can retry;
                            // never turn a failed cleanup into a false clean state.
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
                cleanupStage = "stop_peer_discovery"
                UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERY_STOP_REQUESTED")
                manager.stopPeerDiscovery(requestChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentWifiDirectDiscoveryStop(closed, peerDiscoveryStopToken, peerStopToken)) return
                        peerDiscoveryActive = false
                        UltraShareDiagnostics.event("WIFI_DIRECT", "INVITATION_DISCOVERY_STOP_CONFIRMED")
                        removeRequest(true)
                    }
                    override fun onFailure(reason: Int) {
                        if (closed || peerDiscoveryStopToken != peerStopToken) return
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
            // Invalidate an addLocalService callback that may still be in flight.
            val cleanupToken = ++localInvitationToken
            val cleanupChannel = channel
            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
            localInvitationAdvertisementTimeout = null
            if (!localInvitationOperationPending && !localInvitationCleanupPending &&
                localInvitationService == null && localInvitationId == null
            ) {
                onResult(true)
                return
            }
            localInvitationOperationPending = true
            localInvitationCleanupPending = true
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            var cleanupTimeout: Runnable? = null
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) {
                    cleanupTimeout?.let(mainHandler::removeCallbacks)
                    val stillCurrent = isCurrentWifiDirectAdvertisementOperation(
                        closed, localInvitationToken, cleanupToken
                    )
                    if (stillCurrent) {
                        localInvitationOperationPending = false
                        localInvitationCleanupPending = !success
                        if (success) {
                            localInvitationService = null
                            localInvitationId = null
                        }
                    }
                    onResult(success && stillCurrent)
                }
            }
            cleanupTimeout = Runnable {
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT",
                    "LOCAL_INVITATION_CLEAR_TIMEOUT",
                    mapOf("timeoutMs" to 5_000, "cleanupToken" to cleanupToken)
                )
                finish(false)
            }
            // Do not report cleanup success just because addLocalService has not
            // delivered its callback yet. A pending add must be followed by a
            // framework-confirmed clear before fallback can reuse the P2P radio.
            mainHandler.postDelayed(requireNotNull(cleanupTimeout), 5_000L)
            try {
                manager.clearLocalServices(cleanupChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        finish(true)
                    }
                    override fun onFailure(reason: Int) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "LOCAL_INVITATION_CLEAR_FAILED",
                            mapOf("reason" to reason, "cleanupToken" to cleanupToken)
                        )
                        finish(false)
                    }
                })
            } catch (failure: RuntimeException) {
                UltraShareDiagnostics.failure("WIFI_DIRECT_CLEAR_LOCAL_INVITATION", failure)
                finish(false)
            }
        }

        /**
         * OEM Wi-Fi P2P stacks can briefly report BUSY/ERROR while the radio is
         * changing discovery state. Retry the service scan a few times before
         * handing control back to the QR/LAN fallback.
         */
        private fun failInvitationServiceDiscovery(
            request: WifiP2pDnsSdServiceRequest,
            message: String
        ) {
            if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request)) return
            // A terminal discoverServices failure must release the installed request.
            // Otherwise a later QR scan on this Session is rejected as "already active".
            stopInvitationDiscoveryChecked { cleaned ->
                if (closed) return@stopInvitationDiscoveryChecked
                if (!cleaned) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "SERVICE_DISCOVERY_FAILURE_CLEANUP_UNCONFIRMED"
                    )
                    reportError("$message تعذر تأكيد تنظيف طلب الاكتشاف؛ أُغلقت محاولة Wi-Fi Direct الحالية.")
                } else {
                    reportError(message)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun startInvitationServiceDiscovery(
            request: WifiP2pDnsSdServiceRequest,
            attempt: Int,
            requestChannel: WifiP2pManager.Channel
        ) {
            if (!isCurrentWifiDirectInvitationRequest(closed, invitationRequest, request) || !invitationRequestInstalled) return
            val discoveryToken = ++invitationDiscoveryToken
            peerDiscoveryStopToken++
            fun isCurrentAttempt(): Boolean =
                isCurrentWifiDirectInvitationDiscovery(
                    sessionClosed = closed,
                    currentRequest = invitationRequest,
                    callbackRequest = request,
                    currentToken = invitationDiscoveryToken,
                    callbackToken = discoveryToken
                )

            // Mark the operation pending before crossing the asynchronous framework boundary.
            // A stop request arriving before onSuccess must still wait for stopPeerDiscovery().
            peerDiscoveryActive = true
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "INVITATION_SERVICE_DISCOVERY_REQUESTED",
                mapOf("attempt" to attempt, "discoveryToken" to discoveryToken, "sdkInt" to Build.VERSION.SDK_INT)
            )
            try {
                manager.discoverServices(requestChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentAttempt()) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "STALE_SERVICE_DISCOVERY_CALLBACK_IGNORED",
                                mapOf("attempt" to attempt, "discoveryToken" to discoveryToken)
                            )
                            return
                        }
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "SERVICE_DISCOVERY_STARTED",
                            mapOf("attempt" to attempt, "discoveryToken" to discoveryToken)
                        )
                    }

                    override fun onFailure(reason: Int) {
                        if (!isCurrentAttempt()) {
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "STALE_SERVICE_DISCOVERY_FAILURE_IGNORED",
                                mapOf("attempt" to attempt, "discoveryToken" to discoveryToken, "reason" to reason)
                            )
                            return
                        }
                        peerDiscoveryActive = false
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "SERVICE_DISCOVERY_FAILED",
                            mapOf("reason" to reason, "attempt" to attempt, "discoveryToken" to discoveryToken)
                        )
                        val retryable = reason == WifiP2pManager.BUSY ||
                            reason == WifiP2pManager.ERROR
                        if (retryable && attempt < 3) {
                            mainHandler.postDelayed({
                                if (isCurrentAttempt()) {
                                    startInvitationServiceDiscovery(request, attempt + 1, requestChannel)
                                }
                            }, 600L * (attempt + 1))
                        } else {
                            failInvitationServiceDiscovery(
                                request,
                                "تعذر البحث عن دعوة Wi-Fi Direct (رمز $reason)."
                            )
                        }
                    }
                })
            } catch (failure: SecurityException) {
                if (isCurrentAttempt()) {
                    peerDiscoveryActive = false
                    failInvitationServiceDiscovery(
                        request,
                        "يلزم إذن الأجهزة القريبة للبحث عن دعوة Wi-Fi Direct."
                    )
                }
            } catch (failure: RuntimeException) {
                if (isCurrentAttempt()) {
                    peerDiscoveryActive = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_START_SERVICE_DISCOVERY", failure)
                    failInvitationServiceDiscovery(
                        request,
                        failure.localizedMessage ?: "تعذر بدء البحث عن دعوة Wi-Fi Direct."
                    )
                }
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
            // Invalidate a delayed callback from a previously timed-out stop request.
            peerDiscoveryStopToken++
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
            val stopToken = ++peerDiscoveryStopToken
            val discoveryChannel = channel
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            var timeout: Runnable? = null
            fun finish(success: Boolean) {
                if (completed.compareAndSet(false, true)) {
                    timeout?.let(mainHandler::removeCallbacks)
                    onResult(success)
                }
            }
            timeout = Runnable {
                if (peerDiscoveryStopToken == stopToken && !closed) {
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "PEER_DISCOVERY_STOP_TIMEOUT",
                        mapOf("timeoutMs" to 5_000, "stopToken" to stopToken)
                    )
                }
                // A stale callback still belongs to a waiting caller. Complete it
                // as unconfirmed instead of leaving create/connect suspended forever.
                finish(false)
            }
            mainHandler.postDelayed(requireNotNull(timeout), 5_000L)
            // If this Session never started discovery (or it already stopped), there is
            // no P2P discovery operation to serialize against group creation/connect.
            if (!peerDiscoveryActive) {
                finish(true)
                return
            }
            try {
                manager.stopPeerDiscovery(discoveryChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentWifiDirectDiscoveryStop(closed, peerDiscoveryStopToken, stopToken)) return
                        peerDiscoveryActive = false
                        finish(true)
                    }
                    override fun onFailure(reason: Int) {
                        if (!isCurrentWifiDirectDiscoveryStop(closed, peerDiscoveryStopToken, stopToken)) return
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "PEER_DISCOVERY_STOP_FAILED",
                            mapOf("reason" to reason, "stopToken" to stopToken)
                        )
                        finish(false)
                    }
                })
            } catch (failure: SecurityException) {
                if (isCurrentWifiDirectDiscoveryStop(closed, peerDiscoveryStopToken, stopToken)) {
                    UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_DISCOVERY_SECURITY", failure)
                    reportError("يلزم إذن الأجهزة القريبة لإيقاف البحث قبل الاتصال.")
                    finish(false)
                }
            } catch (failure: RuntimeException) {
                if (peerDiscoveryStopToken == stopToken) {
                    UltraShareDiagnostics.failure("WIFI_DIRECT_STOP_DISCOVERY", failure)
                    finish(false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun createGroup(onCreated: () -> Unit = {}) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            if (!UltraSharePlatformCompatibility.canCreateWifiDirectGroup(Build.VERSION.SDK_INT)) {
                reportError("هذا الإصدار من Android لا يدعم إنشاء مجموعة Wi-Fi Direct.")
                return
            }
            // Explicit receiver group creation is a new owner-side pairing flow,
            // not a continuation of a previous client connection attempt.
            connectionInfoRequestToken++
            groupCredentialsRequestToken++
            // Supersede any previous create/await group attempt before waiting for
            // discovery shutdown; its delayed retries must not create a second group.
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            groupAttemptTimeout = null
            groupAttemptToken++
            cancelClientPeerConfirmation()
            // A new owner-group attempt must not advertise a route retained from an older group.
            runCatching { onLocalAddress(null) }
            pairingTargetDeviceAddress = null
            wasConnected = false
            formedGroupObserved = false
            if (peerDiscoveryActive) {
                // Stop an earlier nearby-device scan serially before creating the
                // receiver group. Starting both operations concurrently is rejected
                // by several Android 6–10/OEM P2P stacks.
                stopDiscoveryChecked { stopped ->
                    if (!stopped) {
                        reportError("تعذر تأكيد إيقاف بحث Wi-Fi Direct قبل إنشاء المجموعة.")
                    } else if (!closed) {
                        createGroupAttempt(onCreated, 0)
                    }
                }
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
                        // GroupInfo may outlive the P2P connection that created it.
                        // It is not proof that Android currently has a formed group:
                        // stale owner records were previously enough to publish QR
                        // before the ConnectionInfo callback reported groupFormed.
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "EXISTING_OWNER_GROUP_REQUIRES_CONNECTION_CONFIRMATION",
                            mapOf("clientCount" to existingGroup.clientList.size)
                        )
                        awaitReceiverGroup(onCreated, 0, token)
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
                        try {
                            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    finishGroupAttempt(token)
                                    val retryToken = groupAttemptToken
                                    mainHandler.postDelayed({
                                        if (isCurrentWifiDirectGroupRetry(closed, groupAttemptToken, retryToken)) {
                                            createGroupAttempt(onCreated, attempt + 1)
                                        }
                                    }, 500L)
                                }

                                override fun onFailure(reason: Int) {
                                    finishGroupAttempt(token)
                                    reportError("تعذر إغلاق مجموعة Wi-Fi Direct السابقة (رمز النظام $reason). أوقف الاتصال القديم ثم أعد المحاولة.")
                                }
                            })
                        } catch (failure: SecurityException) {
                            finishGroupAttempt(token)
                            reportError("يلزم إذن الأجهزة القريبة لإغلاق مجموعة Wi-Fi Direct السابقة.")
                        } catch (failure: RuntimeException) {
                            finishGroupAttempt(token)
                            UltraShareDiagnostics.failure("WIFI_DIRECT_REMOVE_STALE_GROUP", failure)
                            reportError(failure.localizedMessage ?: "تعذر إغلاق مجموعة Wi-Fi Direct السابقة.")
                        }
                        return@requestGroupInfo
                    }
                    try {
                        val listener = object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                if (!closed && isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) {
                                    awaitReceiverGroup(onCreated, 0, token)
                                }
                            }

                            override fun onFailure(reason: Int) {
                                if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return
                                UltraShareDiagnostics.event(
                                    "WIFI_DIRECT",
                                    "CREATE_GROUP_REJECTED_BY_FRAMEWORK",
                                    mapOf("reason" to reason, "attempt" to attempt)
                                )
                                if (shouldRetryWifiDirectConnectFailure(reason, attempt)) {
                                    finishGroupAttempt(token)
                                    runCatching { manager.cancelConnect(channel, null) }
                                    val retryToken = groupAttemptToken
                                    mainHandler.postDelayed({
                                        if (isCurrentWifiDirectGroupRetry(closed, groupAttemptToken, retryToken)) {
                                            createGroupAttempt(onCreated, attempt + 1)
                                        }
                                    }, 1_000L * (attempt + 1))
                                } else {
                                    finishGroupAttempt(token)
                                    reportError(groupFailureMessage(reason))
                                }
                            }
                        }

                        // Android 16 can negotiate WPA3-only P2P groups. Force the
                        // legacy WPA2 profile so Android 6–15 devices can join the
                        // same group through WifiManager.addNetwork/WifiNetworkSpecifier.
                        // Keep the API-36 callsite guard explicit so Android
                        // lint can prove the newer WifiP2pConfig builder is version-safe.
                        val requiresLegacyCompatibility = Build.VERSION.SDK_INT >= 36
                        val compatibilityConfig = if (requiresLegacyCompatibility) {
                            val configResult = runCatching {
                                android.net.wifi.p2p.WifiP2pConfig.Builder()
                                    .setPccModeConnectionType(
                                        android.net.wifi.p2p.WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY
                                    )
                                    .build()
                            }
                            if (configResult.isFailure) {
                                UltraShareDiagnostics.failure(
                                    "WIFI_DIRECT_LEGACY_COMPAT_GROUP_CONFIG",
                                    configResult.exceptionOrNull()!!
                                )
                                finishGroupAttempt(token)
                                reportError(
                                    "تعذر تهيئة مجموعة Wi-Fi Direct بوضع WPA2 المتوافق مع Android 6–15؛ سنستخدم مسار نقطة الاتصال البديل."
                                )
                                return@requestGroupInfo
                            }
                            configResult.getOrThrow()
                        } else null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && compatibilityConfig != null) {
                            // Never silently retry with an unconstrained Android 16
                            // group: that could become WPA3-SAE-only and strand
                            // Android 6–15 joiners. Framework failure triggers the
                            // existing LocalOnlyHotspot fallback instead.
                            manager.createGroup(channel, compatibilityConfig, listener)
                        } else {
                            manager.createGroup(channel, listener)
                        }
                    } catch (failure: SecurityException) {
                        finishGroupAttempt(token)
                        reportError("يلزم إذن الأجهزة القريبة لإنشاء مجموعة Wi-Fi Direct.")
                    } catch (failure: RuntimeException) {
                        finishGroupAttempt(token)
                        UltraShareDiagnostics.failure("WIFI_DIRECT_CREATE_GROUP", failure)
                        reportError(failure.localizedMessage ?: "تعذر على Android بدء إنشاء مجموعة Wi-Fi Direct.")
                    }
                }
            } catch (failure: SecurityException) {
                finishGroupAttempt(token)
                reportError("يلزم إذن الأجهزة القريبة لإنشاء مجموعة Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                finishGroupAttempt(token)
                reportError(failure.localizedMessage ?: "تعذر تهيئة مجموعة Wi-Fi Direct.")
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestGroupCredentials(groupOwnerAddress: String?) {
            if (closed) return
            val requestChannel = channel
            val requestToken = ++groupCredentialsRequestToken
            try {
                manager.requestGroupInfo(requestChannel) { group ->
                    if (!isCurrentWifiDirectGroupInfoRequest(
                            closed, groupCredentialsRequestToken, requestToken
                        ) || group?.isGroupOwner != true
                    ) return@requestGroupInfo
                    publishGroupCredentials(group, groupOwnerAddress)
                }
            } catch (failure: Exception) {
                if (isCurrentWifiDirectGroupInfoRequest(closed, groupCredentialsRequestToken, requestToken)) {
                    UltraShareDiagnostics.failure("WIFI_DIRECT_GROUP_CREDENTIALS", failure)
                }
            }
        }

        private fun publishGroupCredentials(
            group: android.net.wifi.p2p.WifiP2pGroup?,
            groupOwnerAddress: String?
        ) {
            val ownerGroup = group ?: return
            if (closed || !ownerGroup.isGroupOwner) return
            val ssid = runCatching { ownerGroup.networkName?.trim().orEmpty() }.getOrDefault("")
            val passphrase = runCatching { ownerGroup.passphrase?.trim().orEmpty() }.getOrDefault("")
            val ownerAddress = groupOwnerAddress?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
            val isWpa3Sae = Build.VERSION.SDK_INT >= 36 && runCatching {
                ownerGroup.securityType == android.net.wifi.p2p.WifiP2pGroup.SECURITY_TYPE_WPA3_SAE
            }.getOrDefault(false)
            val wifiSecurity = wifiDirectQrSecurity(Build.VERSION.SDK_INT, isWpa3Sae)
            if (ssid.isNotBlank() && passphrase.length in 8..63) {
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT", "GROUP_SOFTAP_CREDENTIALS_READY",
                    mapOf("ssidPresent" to true, "passphraseLength" to passphrase.length)
                )
                runCatching { onGroupInfo(ssid, passphrase, ownerAddress, wifiSecurity) }
                    .onFailure { UltraShareDiagnostics.failure("WIFI_DIRECT_GROUP_INFO_CALLBACK", it) }
            } else {
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT", "GROUP_SOFTAP_CREDENTIALS_UNAVAILABLE",
                    mapOf("ssidPresent" to ssid.isNotBlank(), "passphrasePresent" to passphrase.isNotBlank())
                )
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
            if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (!isCurrentWifiDirectGroupAttempt(closed, groupAttemptToken, token)) return@requestConnectionInfo
                    if (isFormedWifiDirectOwnerGroup(info.groupFormed, info.isGroupOwner)) {
                        finishGroupAttempt(token)
                        // Group creation is only receiver readiness. requestConnectionInfo()
                        // separately confirms a client through GroupInfo.clientList.
                        UltraShareDiagnostics.event("WIFI_DIRECT", "OWNER_GROUP_READY_WAITING_FOR_PEER")
                        requestGroupCredentials(info.groupOwnerAddress?.hostAddress)
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

        /** Invalidates any group-info callback before the caller retires this P2P group. */
        fun invalidateGroupCredentials() {
            groupCredentialsRequestToken++
        }

        @SuppressLint("MissingPermission")
        fun removeGroup(onComplete: (Boolean) -> Unit = {}) {
            if (closed) {
                onComplete(false)
                return
            }
            // Credentials from the group being removed must not be published after
            // fallback has cleared the ViewModel's P2P network state.
            groupCredentialsRequestToken++
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
                // Serialize discovery shutdown before the connect request; some OEMs
                // reject a connection if peer discovery is still marked active.
                stopDiscoveryChecked { stopped ->
                    if (!stopped) {
                        reportError("تعذر إيقاف بحث Wi-Fi Direct قبل الاتصال بالجهاز.")
                    } else if (!closed) {
                        connect(deviceAddress, preferThisDeviceAsGroupOwner)
                    }
                }
                return
            }
            if (!deviceAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
                reportError("عنوان جهاز Wi-Fi Direct غير صالح؛ أعد البحث عن الأجهزة.")
                return
            }
            if (connectionInProgress) return
            connectionInfoRequestToken++
            cancelClientPeerConfirmation()
            // Do not carry an address from a previous P2P group into this pairing attempt.
            runCatching { onLocalAddress(null) }
            pairingTargetDeviceAddress = deviceAddress.lowercase()
            wasConnected = false
            formedGroupObserved = false
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "CONNECT_TARGET_PINNED",
                mapOf("targetAddressPresent" to pairingTargetDeviceAddress?.isNotBlank(), "preferThisDeviceAsGroupOwner" to preferThisDeviceAsGroupOwner)
            )
            connectionInProgress = true
            val token = ++connectionAttemptToken
            // requestPeers is asynchronous and some OEM Wi-Fi P2P implementations
            // fail to deliver its callback. Bound this preflight separately from the
            // later 30-second group-negotiation timeout so the session cannot remain
            // permanently stuck in connectionInProgress.
            connectionTimeout?.let(mainHandler::removeCallbacks)
            val peerRefreshFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val peerRefreshTimeout = Runnable {
                if (claimWifiDirectPeerRefreshCompletion(peerRefreshFinished) &&
                    isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)
                ) {
                    connectionTimeout = null
                    // DNS-SD supplied this exact, freshly observed peer address. Do not
                    // abort a valid QR invitation merely because an OEM never answers
                    // requestPeers(); proceed with the address already authenticated by
                    // the invitation TXT record and let connect() report the real result.
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "PEER_REFRESH_TIMEOUT_USING_DNSSD_ADDRESS",
                        mapOf("attempt" to 0)
                    )
                    connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, 0)
                }
            }
            connectionTimeout = peerRefreshTimeout
            mainHandler.postDelayed(peerRefreshTimeout, 5_000L)
            try {
                // Refresh the peer list immediately before connecting; old peer entries
                // can remain visible after an invitation has already failed. DNS-SD's
                // exact matched address remains a valid fallback if the list is delayed.
                manager.requestPeers(channel) { peers ->
                    if (!claimWifiDirectPeerRefreshCompletion(peerRefreshFinished)) return@requestPeers
                    if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestPeers
                    connectionTimeout?.let(mainHandler::removeCallbacks)
                    connectionTimeout = null
                    val currentPeer = peers.deviceList.firstOrNull {
                        it.deviceAddress.equals(deviceAddress, ignoreCase = true)
                    }
                    val targetAddress = currentPeer?.deviceAddress ?: deviceAddress
                    connectAttempt(targetAddress, preferThisDeviceAsGroupOwner, token, 0)
                }
            } catch (failure: SecurityException) {
                if (claimWifiDirectPeerRefreshCompletion(peerRefreshFinished)) {
                    connectionInProgress = false
                    connectionTimeout?.let(mainHandler::removeCallbacks)
                    connectionTimeout = null
                    pairingTargetDeviceAddress = null
                    cancelClientPeerConfirmation()
                    connectionAttemptToken++
                    reportError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
                }
            } catch (failure: RuntimeException) {
                if (claimWifiDirectPeerRefreshCompletion(peerRefreshFinished) &&
                    isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)
                ) {
                    connectionTimeout?.let(mainHandler::removeCallbacks)
                    connectionTimeout = null
                    UltraShareDiagnostics.failure("WIFI_DIRECT_PEER_PREFLIGHT", failure)
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "PEER_REFRESH_ERROR_USING_DNSSD_ADDRESS",
                        mapOf("attempt" to 0, "error" to failure.javaClass.simpleName)
                    )
                    connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, 0)
                }
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
                groupOwnerIntent = wifiDirectNeutralGroupOwnerIntent(preferThisDeviceAsGroupOwner)
                wps.setup = WpsInfo.PBC
            }
            val callbackCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
            val callbackTimeout = Runnable {
                if (callbackCompleted.compareAndSet(false, true) &&
                    isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)
                ) {
                    connectionTimeout = null
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        "CONNECT_REQUEST_CALLBACK_TIMEOUT",
                        mapOf("attempt" to attempt, "timeoutMs" to 10_000)
                    )
                    // A missing ActionListener callback is different from an accepted
                    // connect() whose group negotiation is still running. Recover only
                    // the silent request; never cut short the later 30-second negotiation.
                    if (shouldRetryWifiDirectConnectCallbackTimeout(attempt)) {
                        recoverAndRetryConnect(
                            deviceAddress = deviceAddress,
                            preferThisDeviceAsGroupOwner = preferThisDeviceAsGroupOwner,
                            token = token,
                            attempt = attempt,
                            reason = WifiP2pManager.ERROR
                        )
                    } else {
                        connectionInProgress = false
                        connectionAttemptToken++
                        pairingTargetDeviceAddress = null
                        cancelClientPeerConfirmation()
                        reportError("لم يستجب نظام Android لطلب اتصال Wi-Fi Direct بعد محاولات متعددة.")
                    }
                }
            }
            connectionTimeout?.let(mainHandler::removeCallbacks)
            connectionTimeout = callbackTimeout
            mainHandler.postDelayed(callbackTimeout, 10_000L)
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!callbackCompleted.compareAndSet(false, true)) return
                        UltraShareDiagnostics.event("WIFI_DIRECT", "CONNECT_REQUEST_ACCEPTED_BY_FRAMEWORK", mapOf("attempt" to attempt))
                        if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        connectionTimeout = null
                        val timeout = Runnable {
                            if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                                connectionInProgress = false
                                connectionTimeout = null
                                UltraShareDiagnostics.event(
                                    "WIFI_DIRECT",
                                    "CONNECT_WAIT_TIMEOUT",
                                    mapOf("attempt" to attempt, "peerDeviceAddress" to deviceAddress, "timeoutMs" to 30_000)
                                )
                                runCatching { manager.cancelConnect(channel, null) }
                                if (shouldRetryWifiDirectConnectFailure(WifiP2pManager.ERROR, attempt)) {
                                    UltraShareDiagnostics.event(
                                        "WIFI_DIRECT",
                                        "CONNECT_RETRY_SCHEDULED",
                                        mapOf("attempt" to attempt, "nextAttempt" to attempt + 1, "delayMs" to (800L * (attempt + 1)))
                                    )
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
                                    pairingTargetDeviceAddress = null
                                    cancelClientPeerConfirmation()
                                    reportError("انتهت مهلة اتصال Wi-Fi Direct بعد محاولات متعددة. تأكد من بقاء الجهازين قريبين ثم أعد المحاولة.")
                                }
                            }
                        }
                        connectionTimeout = timeout
                        mainHandler.postDelayed(timeout, 30_000L)
                    }

                    override fun onFailure(reason: Int) {
                        if (!callbackCompleted.compareAndSet(false, true)) return
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        connectionTimeout = null
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "CONNECT_REQUEST_REJECTED_BY_FRAMEWORK",
                            mapOf(
                                "reason" to reason,
                                "attempt" to attempt,
                                "peerDeviceAddress" to deviceAddress
                            )
                        )
                        if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
                        if (shouldRetryWifiDirectConnectFailure(reason, attempt)) {
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
                            pairingTargetDeviceAddress = null
                            cancelClientPeerConfirmation()
                            connectionAttemptToken++
                            reportError(connectFailureMessage(reason))
                        }
                    }
                })
            } catch (failure: SecurityException) {
                if (!callbackCompleted.compareAndSet(false, true)) return
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                pairingTargetDeviceAddress = null
                cancelClientPeerConfirmation()
                connectionAttemptToken++
                reportError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            } catch (failure: RuntimeException) {
                if (!callbackCompleted.compareAndSet(false, true)) return
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                pairingTargetDeviceAddress = null
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
                        mapOf("attempt" to attempt, "discoveryRequested" to true)
                    )
                    connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                }
            }
            mainHandler.postDelayed(timeout, 4_500L)

            fun readRefreshedPeers(discoveryResult: String, discoveryReason: Int? = null) {
                if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT",
                    "CONNECT_RECOVERY_DISCOVERY_FINISHED",
                    mapOf("result" to discoveryResult, "reason" to (discoveryReason ?: -1), "attempt" to attempt)
                )
                mainHandler.postDelayed({
                    if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@postDelayed
                    try {
                        manager.requestPeers(channel) { peers ->
                            if (!refreshFinished.compareAndSet(false, true)) return@requestPeers
                            mainHandler.removeCallbacks(timeout)
                            if (!isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) return@requestPeers
                            val refreshedPeer = peers.deviceList.firstOrNull {
                                it.deviceAddress.equals(deviceAddress, ignoreCase = true)
                            }
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "CONNECT_RECOVERY_PEERS_REFRESHED",
                                mapOf(
                                    "peerCount" to peers.deviceList.size,
                                    "targetFound" to (refreshedPeer != null),
                                    "targetStatus" to refreshedPeer?.status,
                                    "attempt" to attempt,
                                    "discoveryResult" to discoveryResult
                                )
                            )
                            mainHandler.postDelayed({
                                if (isCurrentWifiDirectConnectionAttempt(closed, connectionInProgress, connectionAttemptToken, token)) {
                                    UltraShareDiagnostics.event(
                                        "WIFI_DIRECT",
                                        "CONNECT_RECOVERY_RETRY_SCHEDULED",
                                        mapOf("attempt" to attempt + 1, "targetFound" to (refreshedPeer != null))
                                    )
                                    // Retain the QR-discovered address when the peer cache is
                                    // temporarily empty; a fresh discovery cycle now precedes it.
                                    connectAttempt(refreshedPeer?.deviceAddress ?: deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
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
                            connectAttempt(deviceAddress, preferThisDeviceAsGroupOwner, token, attempt + 1)
                        }
                    }
                }, 900L)
            }

            try {
                // A plain requestPeers() only reads Android's possibly stale cache.
                // Trigger a new radio discovery cycle first, then read the refreshed list.
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        readRefreshedPeers("started")
                    }

                    override fun onFailure(reason: Int) {
                        // BUSY commonly means another P2P discovery is still retiring.
                        // Still refresh the peer cache after a short settling period.
                        readRefreshedPeers("not_started", reason)
                    }
                })
            } catch (failure: SecurityException) {
                if (refreshFinished.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    reportError("يلزم إذن الأجهزة القريبة لإعادة اكتشاف جهاز Wi-Fi Direct.")
                }
            } catch (failure: RuntimeException) {
                readRefreshedPeers("exception")
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

        private fun publishLocalP2pAddress(fallbackAddress: String? = null, attempt: Int = 0) {
            if (closed) return
            val validatedFallback = fallbackAddress?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
            val localAddress = validatedFallback
                ?: UltraShareNetworkRouting.wifiDirectLocalIpv4Addresses().firstOrNull()
            if (localAddress != null) {
                runCatching { onLocalAddress(localAddress) }
                    .onFailure { UltraShareDiagnostics.failure("WIFI_DIRECT_LOCAL_ADDRESS_CALLBACK", it) }
            } else if (attempt < 20) {
                mainHandler.postDelayed({
                    if (!closed) publishLocalP2pAddress(validatedFallback, attempt + 1)
                }, 250L)
            }
        }

        private fun requestConnectionInfo() {
            // Ignore an older framework callback if another connection/group query
            // was issued after it. Android broadcasts and OEM callbacks can overlap.
            val requestToken = ++connectionInfoRequestToken
            UltraShareDiagnostics.event(
                "WIFI_DIRECT",
                "CONNECTION_INFO_QUERY_STARTED",
                mapOf("requestToken" to requestToken)
            )
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (!isCurrentWifiDirectInfoRequest(closed, connectionInfoRequestToken, requestToken)) {
                        UltraShareDiagnostics.event(
                            "WIFI_DIRECT",
                            "STALE_CONNECTION_INFO_CALLBACK_IGNORED",
                            mapOf("requestToken" to requestToken, "currentToken" to connectionInfoRequestToken)
                        )
                        return@requestConnectionInfo
                    }
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        if (info.groupFormed) "GROUP_FORMED" else "GROUP_NOT_FORMED",
                        mapOf("isGroupOwner" to info.isGroupOwner, "groupOwnerHost" to info.groupOwnerAddress?.hostAddress)
                    )
                    if (!info.groupFormed) {
                        groupCredentialsRequestToken++
                        cancelOwnerPeerConfirmation()
                        cancelClientPeerConfirmation()
                        val invitationMustBeRetired = shouldInvalidateWifiDirectInvitation(
                            groupFormed = info.groupFormed,
                            previouslyFormed = formedGroupObserved,
                            invitationAdvertised = localInvitationService != null ||
                                localInvitationOperationPending
                        )
                        formedGroupObserved = false
                        if (invitationMustBeRetired || wasConnected) {
                            // Retire every delayed advertisement callback before clearing
                            // Android's local DNS-SD table. A QR published for a group
                            // that no longer exists must never remain actionable.
                            localInvitationToken++
                            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
                            localInvitationAdvertisementTimeout = null
                            localInvitationOperationPending = false
                            localInvitationCleanupPending = true
                            localInvitationService = null
                            localInvitationId = null
                            runCatching { manager.clearLocalServices(channel, null) }
                            runCatching { onLocalAddress(null) }
                            wasConnected = false
                            pairingTargetDeviceAddress = null
                            UltraShareDiagnostics.event(
                                "WIFI_DIRECT",
                                "FORMED_GROUP_LOST_INVITATION_RETIRED",
                                mapOf("invitationWasAdvertised" to invitationMustBeRetired)
                            )
                            reportError("انقطع اتصال Wi-Fi Direct أو فُقدت مجموعة الدعوة. أُبطلت الدعوة القديمة؛ أعد الاتصال أو استخدم مسار الشبكة المحلية الاحتياطي.")
                        }
                        return@requestConnectionInfo
                    }
                    formedGroupObserved = true
                    if (info.isGroupOwner) {
                        cancelClientPeerConfirmation()
                        val localGroupOwnerAddress = info.groupOwnerAddress?.hostAddress
                            ?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                        requestGroupCredentials(localGroupOwnerAddress)
                        publishLocalP2pAddress(
                            localGroupOwnerAddress?.takeIf { UltraShareCore.isValidPrivateLanHost(it) }
                        )
                        // Do not equate an empty owner group with a paired device. Poll
                        // GroupInfo briefly because GROUP_FORMED can precede clientList updates.
                        if (!ownerPeerCheckInFlight && ownerPeerCheckRunnable == null) {
                            ownerPeerCheckToken++
                            // Do not arm the disconnect deadline while a newly-created
                            // owner group is empty. A receiver may wait indefinitely for
                            // the first QR scan; only a peer that has connected can time out.
                            ownerPeerCheckDeadlineElapsedMs = if (
                                shouldStartWifiDirectOwnerPeerDeadline(clientCount = 0, wasConnected = wasConnected)
                            ) {
                                android.os.SystemClock.elapsedRealtime() + 35_000L
                            } else 0L
                            requestOwnerPeerConfirmation(info, ownerPeerCheckToken, 0)
                        }
                        return@requestConnectionInfo
                    }
                    cancelOwnerPeerConfirmation()
                    val host = info.groupOwnerAddress?.hostAddress
                    val expectedAddress = pairingTargetDeviceAddress
                    if (host == null || !UltraShareCore.isValidPrivateLanHost(host)) {
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
         * Confirms that Android formed a client group during the active QR pairing attempt.
         * Do not compare discovery MACs with GroupInfo MACs: they are not stable peer IDs.
         * The short-lived MPC2 challenge authenticates the actual peer before transfer.
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
            if (clientPeerCheckDeadlineElapsedMs == 0L) {
                clientPeerCheckDeadlineElapsedMs = android.os.SystemClock.elapsedRealtime() + 35_000L
            }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (!completed.compareAndSet(false, true)) return@Runnable
                // A timeout from an older connection attempt must never clear the
                // in-flight flag or timeout handle belonging to a newer attempt.
                if (!isCurrentWifiDirectPeerConfirmation(closed, clientPeerCheckToken, token)) return@Runnable
                clientPeerCheckTimeout = null
                clientPeerCheckInFlight = false
                scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
            }
            clientPeerCheckTimeout = timeout
            mainHandler.postDelayed(timeout, 1_500L)
            try {
                manager.requestGroupInfo(channel) { group ->
                    if (!completed.compareAndSet(false, true)) return@requestGroupInfo
                    mainHandler.removeCallbacks(timeout)
                    // Check ownership before touching shared request state: an OEM may
                    // deliver this callback after cancellation and a newer pairing.
                    if (!isCurrentWifiDirectPeerConfirmation(closed, clientPeerCheckToken, token)) {
                        UltraShareDiagnostics.event("WIFI_DIRECT", "STALE_CLIENT_GROUP_INFO_CALLBACK_IGNORED")
                        return@requestGroupInfo
                    }
                    clientPeerCheckTimeout = null
                    clientPeerCheckInFlight = false
                    val actualAddress = group?.owner?.deviceAddress
                    // Wi-Fi Direct MAC addresses are not stable identifiers on every
                    // Android/OEM combination (notably newer Android group owners).
                    // The user selected this peer from its short-lived QR invitation;
                    // the subsequent MPC2 challenge proves the shared session PIN.
                    // Requiring the framework's owner MAC to equal the discovery MAC
                    // rejects valid Android 10 <-> Android 14 groups.
                    val matches = isReadyWifiDirectClientGroup(
                        groupPresent = group != null,
                        groupIsOwner = group?.isGroupOwner == true,
                        pairingAttemptActive = expectedAddress.isNotBlank()
                    )
                    UltraShareDiagnostics.event(
                        "WIFI_DIRECT",
                        if (matches) "CLIENT_GROUP_OWNER_CONFIRMED" else "CLIENT_GROUP_OWNER_PENDING",
                        mapOf(
                            "attempt" to attempt,
                            "expectedAddressPresent" to expectedAddress.isNotBlank(),
                            "actualAddressPresent" to !actualAddress.isNullOrBlank(),
                            "macAddressCompared" to false,
                            "groupIsOwner" to (group?.isGroupOwner == true),
                            "clientCount" to (group?.clientList?.size ?: 0)
                        )
                    )
                    if (matches && !info.isGroupOwner && info.groupFormed) {
                        clientPeerCheckRunnable?.let(mainHandler::removeCallbacks)
                        clientPeerCheckRunnable = null
                        connectionInProgress = false
                        clientPeerCheckDeadlineElapsedMs = 0L
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
                            publishLocalP2pAddress()
                        }
                    } else if (wasConnected) {
                        wasConnected = false
                        pairingTargetDeviceAddress = null
                        cancelClientPeerConfirmation()
                        reportError("تغير جهاز مالك مجموعة Wi-Fi Direct أو انقطع الاقتران مع الجهاز المحدد. أعد الاقتران قبل إرسال الملفات.")
                    } else {
                        scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
                    }
                }
            } catch (failure: SecurityException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    if (!isCurrentWifiDirectPeerConfirmation(closed, clientPeerCheckToken, token)) return
                    clientPeerCheckTimeout = null
                    clientPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_CLIENT_GROUP_INFO_SECURITY", failure)
                    scheduleClientPeerConfirmation(info, host, expectedAddress, token, attempt)
                }
            } catch (failure: RuntimeException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    if (!isCurrentWifiDirectPeerConfirmation(closed, clientPeerCheckToken, token)) return
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
            val remainingMs = clientPeerCheckDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()
            if (!shouldRetryWifiDirectPeerConfirmation(attempt) || remainingMs <= 0L) {
                clientPeerCheckDeadlineElapsedMs = 0L
                connectionInProgress = false
                connectionTimeout?.let(mainHandler::removeCallbacks)
                connectionTimeout = null
                connectionAttemptToken++
                pairingTargetDeviceAddress = null
                UltraShareDiagnostics.event(
                    "WIFI_DIRECT",
                    "CLIENT_GROUP_OWNER_CONFIRMATION_FAILED",
                    mapOf("attempts" to attempt + 1, "groupFormed" to info.groupFormed, "timeoutMs" to 35_000)
                )
                reportError("أنشأ Android مجموعة Wi-Fi Direct، لكن لم نتمكن من تأكيد أن الجهاز المتصل هو الجهاز الذي اخترته خلال 35 ثانية. أوقف الاتصال السابق وأعد الاقتران.")
                return
            }
            val retry = Runnable {
                if (!closed && token == clientPeerCheckToken && !wasConnected) {
                    clientPeerCheckRunnable = null
                    requestClientPeerConfirmation(info, host, expectedAddress, token, attempt + 1)
                }
            }
            clientPeerCheckRunnable = retry
            mainHandler.postDelayed(retry, minOf(500L, remainingMs))
        }

        private fun cancelClientPeerConfirmation() {
            clientPeerCheckToken++
            clientPeerCheckRunnable?.let(mainHandler::removeCallbacks)
            clientPeerCheckRunnable = null
            clientPeerCheckTimeout?.let(mainHandler::removeCallbacks)
            clientPeerCheckTimeout = null
            clientPeerCheckInFlight = false
            clientPeerCheckDeadlineElapsedMs = 0L
        }

        @SuppressLint("MissingPermission")
        private fun requestOwnerPeerConfirmation(
            info: android.net.wifi.p2p.WifiP2pInfo,
            token: Int,
            attempt: Int
        ) {
            if (closed || token != ownerPeerCheckToken || ownerPeerCheckInFlight) return
            ownerPeerCheckInFlight = true
            val remainingMs = if (ownerPeerCheckDeadlineElapsedMs == 0L) 1_500L else
                (ownerPeerCheckDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
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
            mainHandler.postDelayed(timeout, minOf(1_500L, remainingMs))
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
                    if (!shouldStartWifiDirectOwnerPeerDeadline(clientCount, wasConnected)) {
                        // Keep an empty receiver room alive and poll slowly. Android can
                        // publish GROUP_FORMED before GroupInfo.clientList is populated,
                        // and an additional connection-changed broadcast is not guaranteed.
                        ownerPeerCheckDeadlineElapsedMs = 0L
                        scheduleOwnerPeerConfirmation(info, token, attempt)
                        return@requestGroupInfo
                    }
                    if (ownerPeerCheckDeadlineElapsedMs == 0L) {
                        ownerPeerCheckDeadlineElapsedMs =
                            android.os.SystemClock.elapsedRealtime() + 35_000L
                    }
                    val expectedPeer = pairingTargetDeviceAddress
                    // P2P MACs can rotate between DNS-SD discovery and group
                    // formation. A live group with a client is sufficient here;
                    // the subsequent temporary QR/PIN control handshake authenticates
                    // the actual peer before any file-transfer endpoint is authorized.
                    val expectedClientPresent = isReadyWifiDirectOwnerGroup(
                        groupIsOwner = group?.isGroupOwner == true,
                        clientCount = clientCount,
                        pairingAttemptActive = expectedPeer.isNullOrBlank() ||
                            (connectionInProgress && pairingTargetDeviceAddress == expectedPeer)
                    )
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
                        ownerPeerCheckDeadlineElapsedMs = 0L
                        connectionInProgress = false
                        connectionTimeout?.let(mainHandler::removeCallbacks)
                        connectionTimeout = null
                        if (shouldEmitWifiDirectConnected(wasConnected)) {
                            wasConnected = true
                            // On the group owner, Android also reports the P2P interface address.
                            // Forward it so the receiver UI can show a real Wi-Fi Direct address
                            // instead of waiting forever for a non-P2P WLAN address.
                            onConnected(info.groupOwnerAddress?.hostAddress, true)
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
                    if (!isCurrentWifiDirectPeerConfirmation(closed, ownerPeerCheckToken, token)) return
                    if (ownerPeerCheckTimeout === timeout) ownerPeerCheckTimeout = null
                    ownerPeerCheckInFlight = false
                    UltraShareDiagnostics.failure("WIFI_DIRECT_GROUP_INFO_SECURITY", failure)
                    scheduleOwnerPeerConfirmation(info, token, attempt)
                }
            } catch (failure: RuntimeException) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout)
                    if (!isCurrentWifiDirectPeerConfirmation(closed, ownerPeerCheckToken, token)) return
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
            val waitingForFirstPeer = ownerPeerCheckDeadlineElapsedMs == 0L
            val remainingMs = if (waitingForFirstPeer) Long.MAX_VALUE else
                ownerPeerCheckDeadlineElapsedMs - android.os.SystemClock.elapsedRealtime()
            if (!waitingForFirstPeer &&
                (!shouldRetryWifiDirectPeerConfirmation(attempt) || remainingMs <= 0L)
            ) {
                ownerPeerCheckRunnable = null
                ownerPeerCheckDeadlineElapsedMs = 0L
                UltraShareDiagnostics.event("WIFI_DIRECT", "GROUP_PEER_CONFIRMATION_TIMEOUT", mapOf("attempts" to attempt))
                reportError("تم إنشاء مجموعة Wi-Fi Direct لكن لم يؤكد Android انضمام الهاتف الآخر خلال 35 ثانية. أبقِ الجهازين قريبين ثم أعد الاقتران.")
                return
            }
            ownerPeerCheckRunnable?.let(mainHandler::removeCallbacks)
            val next = Runnable {
                if (!closed && token == ownerPeerCheckToken && !wasConnected) {
                    ownerPeerCheckRunnable = null
                    // Do not consume the bounded confirmation budget while the room
                    // is empty. Start a fresh 35-second budget only after a client appears.
                    requestOwnerPeerConfirmation(info, token, if (waitingForFirstPeer) 0 else attempt + 1)
                }
            }
            ownerPeerCheckRunnable = next
            val delayMs = wifiDirectOwnerPeerConfirmationDelayMs(hasObservedClient = !waitingForFirstPeer)
            mainHandler.postDelayed(next, if (waitingForFirstPeer) delayMs else minOf(delayMs, remainingMs))
        }

        private fun cancelOwnerPeerConfirmation() {
            ownerPeerCheckToken++
            ownerPeerCheckDeadlineElapsedMs = 0L
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
            invitationDiscoveryToken++
            localInvitationToken++
            peerDiscoveryStopToken++
            connectionAttemptToken++
            connectionInfoRequestToken++
            groupCredentialsRequestToken++
            connectionTimeout?.let(mainHandler::removeCallbacks)
            connectionTimeout = null
            groupAttemptToken++
            groupAttemptTimeout?.let(mainHandler::removeCallbacks)
            groupAttemptTimeout = null
            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
            localInvitationAdvertisementTimeout = null
            localInvitationService = null
            localInvitationId = null
            localInvitationOperationPending = false
            localInvitationCleanupPending = false
            invitationMatch = null
            invitationRequest = null
            invitationRequestChannel = null
            invitationRequestInstalled = false
            peerDiscoveryActive = false
            runCatching { onLocalAddress(null) }
            wasConnected = false
            pairingTargetDeviceAddress = null
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
            connectionInfoRequestToken++
            groupCredentialsRequestToken++
            pairingTargetDeviceAddress = null
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
            invitationRequestChannel = null
            invitationRequestInstalled = false
            peerDiscoveryActive = false
            localInvitationService = null
            localInvitationId = null
            localInvitationOperationPending = false
            localInvitationCleanupPending = false
            localInvitationAdvertisementTimeout?.let(mainHandler::removeCallbacks)
            localInvitationAdvertisementTimeout = null
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
        onError: (String) -> Unit,
        onLocalAddress: (String?) -> Unit = {},
        onGroupInfo: (ssid: String, passphrase: String, ownerAddress: String?, wifiSecurity: String) -> Unit = { _, _, _, _ -> }
    ): Session {
        val manager = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: error("هذا الجهاز لا يدعم Wi-Fi Direct.")
        var session: Session? = null
        val channel = manager.initialize(
            context.applicationContext,
            Looper.getMainLooper(),
            WifiP2pManager.ChannelListener { session?.onChannelDisconnected() }
        ) ?: error("تعذر تهيئة Wi-Fi Direct على هذا الجهاز.")
        val created = Session(context, manager, channel, onPeers, onConnected, onError, onLocalAddress, onGroupInfo)
        session = created
        return created
    }
}

internal fun isCurrentWifiDirectGroupInfoRequest(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

internal fun canReuseWifiDirectAdvertisement(
    samePeer: Boolean,
    servicePresent: Boolean,
    operationPending: Boolean,
    cleanupPending: Boolean
): Boolean = samePeer && servicePresent && !operationPending && !cleanupPending

internal fun isCurrentWifiDirectAdvertisementOperation(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

internal fun isCurrentWifiDirectInvitationRequest(
    sessionClosed: Boolean,
    currentRequest: Any?,
    callbackRequest: Any?
): Boolean = !sessionClosed && currentRequest != null && currentRequest === callbackRequest

/** A retry callback is valid only for both its request identity and discovery generation. */
internal fun isCurrentWifiDirectInvitationDiscovery(
    sessionClosed: Boolean,
    currentRequest: Any?,
    callbackRequest: Any?,
    currentToken: Int,
    callbackToken: Int
): Boolean =
    isCurrentWifiDirectInvitationRequest(sessionClosed, currentRequest, callbackRequest) &&
        currentToken == callbackToken

/** A delayed stopPeerDiscovery callback cannot alter a newer discovery generation. */
internal fun isCurrentWifiDirectDiscoveryStop(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

/**
 * A formed client group belongs to the current pairing flow when Android reports
 * a client-side group and the user has an active QR-selected peer attempt.
 * Do not compare P2P MAC addresses: Android/OEM privacy behavior can rotate them.
 * The short-lived QR secret is validated by the MPC2 control-channel handshake.
 */
internal fun isReadyWifiDirectClientGroup(
    groupPresent: Boolean,
    groupIsOwner: Boolean,
    pairingAttemptActive: Boolean
): Boolean = groupPresent && !groupIsOwner && pairingAttemptActive

internal fun isReadyWifiDirectOwnerGroup(
    groupIsOwner: Boolean,
    clientCount: Int,
    pairingAttemptActive: Boolean
): Boolean = groupIsOwner && clientCount > 0 && pairingAttemptActive

/** Retry transient framework BUSY/ERROR responses, but never unsupported hardware or bad permissions. */
internal fun shouldRetryWifiDirectConnectFailure(reason: Int, attempt: Int): Boolean =
    attempt in 0..2 &&
        (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR)

/** A silent connect() ActionListener is treated as a transient framework ERROR. */
internal fun shouldRetryWifiDirectConnectCallbackTimeout(attempt: Int): Boolean =
    shouldRetryWifiDirectConnectFailure(WifiP2pManager.ERROR, attempt)


/** Converts the Android P2P radio extra to a stable diagnostic label. */
internal fun wifiP2pRadioStateName(state: Int): String = when (state) {
    WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "ENABLED"
    WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "DISABLED"
    Int.MIN_VALUE -> "MISSING"
    else -> "UNKNOWN"
}

/** Android 6 through 12L require Location services enabled for Wi-Fi Direct service discovery. */
internal fun requiresWifiDirectLocationService(sdkInt: Int): Boolean =
    UltraSharePlatformCompatibility.requiresLocationServicesForWifiDirect(sdkInt)

/** Bounds owner-side client confirmation retries without treating an empty group as paired. */
internal fun shouldRetryWifiDirectPeerConfirmation(attempt: Int, maxAttempts: Int = 70): Boolean =
    maxAttempts > 0 && attempt in 0 until maxAttempts

/** Emits the connected callback only on a disconnected-to-connected transition. */
internal fun shouldEmitWifiDirectConnected(wasConnected: Boolean): Boolean = !wasConnected

/** Retire an invitation only after a real group existed or its DNS-SD record was installed. */
internal fun shouldInvalidateWifiDirectInvitation(
    groupFormed: Boolean,
    previouslyFormed: Boolean,
    invitationAdvertised: Boolean
): Boolean = !groupFormed && (previouslyFormed || invitationAdvertised)

/** GroupInfo alone is not readiness: ConnectionInfo must confirm a formed owner group. */
internal fun isFormedWifiDirectOwnerGroup(
    groupFormed: Boolean,
    connectionInfoSaysOwner: Boolean
): Boolean = groupFormed && connectionInfoSaysOwner

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

/** Rejects delayed connection-info callbacks after a newer framework query or session closure. */
internal fun isCurrentWifiDirectPeerConfirmation(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

internal fun isCurrentWifiDirectInfoRequest(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

/** Ensures timeout, callback, and synchronous failure can complete peer preflight only once. */
internal fun claimWifiDirectPeerRefreshCompletion(
    finished: java.util.concurrent.atomic.AtomicBoolean
): Boolean = finished.compareAndSet(false, true)

/** Rejects delayed callbacks after timeout, completion, closure, or a newer attempt. */
internal fun isCurrentWifiDirectConnectionAttempt(
    sessionClosed: Boolean,
    connectionInProgress: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && connectionInProgress && currentToken == callbackToken

/** Rejects delayed group-setup callbacks after timeout, closure, or a newer attempt. */
internal fun isCurrentWifiDirectGroupRetry(
    sessionClosed: Boolean,
    currentToken: Int,
    scheduledToken: Int
): Boolean = !sessionClosed && currentToken == scheduledToken

internal fun isCurrentWifiDirectGroupAttempt(
    sessionClosed: Boolean,
    currentToken: Int,
    callbackToken: Int
): Boolean = !sessionClosed && currentToken == callbackToken

/** Force WPA2-compatible group security on Android 16 for Android 6–15 joiners. */
internal fun shouldForceLegacyWifiDirectSecurity(sdkInt: Int): Boolean = sdkInt >= 36

internal fun wifiDirectQrSecurity(sdkInt: Int, isWpa3Sae: Boolean): String =
    if (sdkInt >= 36 && isWpa3Sae) "wpa3" else "wpa2"

internal fun shouldStartWifiDirectOwnerPeerDeadline(clientCount: Int, wasConnected: Boolean): Boolean =
    clientCount > 0 || wasConnected

/** Prefer local ownership for a creator and client role for a device joining an existing group. */
internal fun wifiDirectNeutralGroupOwnerIntent(preferThisDeviceAsGroupOwner: Boolean): Int =
    if (preferThisDeviceAsGroupOwner) 15 else 0
