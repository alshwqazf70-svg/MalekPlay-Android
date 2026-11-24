package com.malekplay.ui.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareCore
import com.malekplay.network.UltraShareBatchTransport
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareReceivedEntry
import com.malekplay.network.UltraShareReceivedHistoryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraSharePeerTransfer
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.ExternalWifiDirectQr
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareWifiDirect
import com.malekplay.network.UltraShareControlChannel
import com.malekplay.network.UltraShareDiagnostics
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraShareApprovedSendTarget
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class LegacyUltraShareUiState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val controlChannelReady: Boolean = false,
    val dataChannelReady: Boolean = false,
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
    val hotspotActive: Boolean = false,
    val hotspotStarting: Boolean = false,
    val sending: Boolean = false,
    val scanning: Boolean = false,
    val discoveredPeers: List<UltraShareDiscovery.Peer> = emptyList(),
    val progress: UltraShareCore.Progress? = null,
    val sendProgress: UltraShareCore.Progress? = null,
    val receiveProgress: UltraShareCore.Progress? = null,
    val peerTransfers: List<UltraSharePeerTransfer> = emptyList(),
    val receivedFile: UltraShareCore.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val queueEntries: List<UltraShareQueueEntry> = emptyList(),
    val receivedHistory: List<UltraShareReceivedEntry> = emptyList(),
    val wifiDirectPeers: List<UltraShareWifiDirect.Peer> = emptyList(),
    val wifiDirectHost: String? = null,
    val invitationId: String? = null,
    val wifiDirectInvitationReady: Boolean = false,
    // Latched once per receiver attempt; transient readiness changes never clear it.
    val receiverQrPayload: String? = null,
    val pairedSession: Boolean = false,
    val approvedPeerHost: String? = null
)

@HiltViewModel
class LegacyUltraShareViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LegacyUltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var wifiDirectScanTimeout: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null
    private var invitationTimeout: Job? = null
    private var pendingInvitation: UltraShareQrCode.PairingDetails? = null
    private var pendingInvitationReady: ((String) -> Unit)? = null
    private var reverseInvitationConnectionInProgress = false
    private var hotspotFallbackStarted = false
    private var hotspotNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val invitationPreferences by lazy {
        context.getSharedPreferences("ultra_share_invitation", Context.MODE_PRIVATE)
    }

    init {
        // Initialize diagnostics before an outbound QR handshake; the receiver
        // service may not exist yet on the device that scans the QR.
        UltraShareDiagnostics.initialize(context)
        viewModelScope.launch {
            if (!UltraShareForegroundService.state.value.sending) {
                UltraShareQueueStore.recoverInterrupted(context)
            }
            UltraShareQueueStore.observe(context).collect { entries ->
                _state.value = _state.value.copy(queueEntries = entries)
            }
        }
        viewModelScope.launch {
            UltraShareReceivedHistoryStore.observe(context).collect { entries ->
                _state.value = _state.value.copy(receivedHistory = entries)
            }
        }
        viewModelScope.launch {
            UltraShareForegroundService.state.collect { serviceState ->
                val current = _state.value
                val received = serviceState.receivedFile
                if (received?.publishedUri != null &&
                    received.file.absolutePath != current.receivedFile?.file?.absolutePath
                ) {
                    // Refresh Room after MediaStore publication so received video/audio appears in My Library.
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { mediaRepository.refresh() }
                    }
                }
                _state.value = current.copy(
                    pairingSecret = serviceState.pairingSecret,
                    localAddresses = serviceState.localAddresses,
                    receiving = serviceState.receiving,
                    controlChannelReady = serviceState.controlChannelReady,
                    dataChannelReady = serviceState.dataChannelReady,
                    hotspotSsid = serviceState.hotspotSsid,
                    hotspotPassword = serviceState.hotspotPassword,
                    hotspotActive = serviceState.hotspotActive,
                    hotspotStarting = serviceState.hotspotStarting,
                    sending = serviceState.sending,
                    progress = serviceState.progress,
                    sendProgress = serviceState.sendProgress,
                    receiveProgress = serviceState.receiveProgress,
                    peerTransfers = serviceState.peerTransfers,
                    receivedFile = serviceState.receivedFile ?: current.receivedFile,
                    status = serviceState.status ?: current.status,
                    pairedSession = serviceState.activePeers.isNotEmpty(),
                    approvedPeerHost = serviceState.activePeers.lastOrNull()?.host,
                    error = serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes
                )
                publishStableReceiverQrIfReady()
                // LocalOnlyHotspot v4 QR sessions do not create or restore Wi-Fi Direct invitations.
            }
        }
    }

    override fun onCleared() {
        hotspotNetworkCallback?.let { callback ->
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        hotspotNetworkCallback = null
        invitationTimeout?.cancel()
        wifiDirectScanTimeout?.cancel()
        scanJob?.cancel()
        wifiDirectScanTimeout = null
        invitationTimeout = null
        pendingInvitation = null
        pendingInvitationReady = null
        runCatching { wifiDirectSession?.close() }
        wifiDirectSession = null
        super.onCleared()
    }

    private fun completeReversedInvitationConnection(host: String) {
        if (!_state.value.receiving || _state.value.pairedSession || reverseInvitationConnectionInProgress) return
        // Wi-Fi P2P may report the same formed group more than once. Use this
        // invitation's UI/session state to deduplicate callbacks; an already-open
        // socket for the same IP may belong to an older QR and is never proof that
        // the current invitation secret has been authenticated. Every new QR must
        // complete a fresh MPC2 handshake.
        val secret = _state.value.pairingSecret
        if (!UltraShareCore.isValidPairingSecret(secret) ||
            !UltraShareCore.isValidPrivateLanHost(host)
        ) {
            _state.value = _state.value.copy(
                wifiDirectHost = null,
                scanning = false,
                error = "تعذر بدء الاقتران العكسي: بيانات الجلسة أو عنوان الجهاز غير صالح."
            )
            return
        }
        reverseInvitationConnectionInProgress = true
        wifiDirectSession?.stopInvitationDiscovery()
        invitationTimeout?.cancel()
        invitationTimeout = null
        _state.value = _state.value.copy(
            wifiDirectHost = null,
            scanning = false,
            error = null,
            status = "انعكس دور Wi-Fi Direct؛ جارٍ إنشاء اقتران آمن عبر مالك المجموعة..."
        )
        viewModelScope.launch {
            var accepted = false
            var lastFailure: Exception? = null
            try {
                // The QR creator is now a P2P client. Connect to the remote group
                // owner, which has started a temporary receiver with the same QR secret.
                delay(500)
                val candidates = listOf(host).distinct()
                for (candidate in candidates) {
                    val routes: List<android.net.Network?> =
                        UltraShareNetworkRouting.findNetworksForHost(context, candidate)
                            .map { it as android.net.Network? } + listOf(null)
                    var stopOnProtocolFailure = false
                    for ((routeIndex, route) in routes.withIndex()) {
                        if (accepted || stopOnProtocolFailure) break
                        for (attempt in 0..2) {
                            try {
                                UltraShareDiagnostics.event(
                                    "PAIRING",
                                    "INVITATION_ROUTE_ATTEMPT",
                                    mapOf(
                                        "routeIndex" to routeIndex,
                                        "routeBound" to (route != null),
                                        "networkHandle" to (route?.networkHandle ?: 0L),
                                        "attempt" to attempt,
                                        "host" to candidate
                                    )
                                )
                                val decision = withContext(Dispatchers.IO) {
                                    UltraShareControlChannel.request(
                                        host = candidate,
                                        pairingSecret = secret,
                                        peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" }
                                            ?: "MalekPlayer Android",
                                        network = route,
                                        peerId = UltraShareDiscovery.localPeerId(context),
                                    )
                                }
                                if (decision == UltraShareControlChannel.Decision.REJECTED) {
                                    UltraShareDiagnostics.event(
                                        "PAIRING",
                                        "REVERSED_INVITATION_ROUTE_REJECTED",
                                        mapOf("routeIndex" to routeIndex, "attempt" to attempt, "host" to candidate)
                                    )
                                    stopOnProtocolFailure = true
                                    lastFailure = IllegalStateException("رفض الجهاز الآخر طلب الاقتران الآمن.")
                                } else {
                                    UltraShareDiagnostics.event(
                                        "PAIRING",
                                        "REVERSED_INVITATION_ROUTE_AUTHENTICATED",
                                        mapOf("routeIndex" to routeIndex, "attempt" to attempt, "host" to candidate)
                                    )
                                    accepted = true
                                }
                                break
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                lastFailure = failure
                                UltraShareDiagnostics.event(
                                    "PAIRING",
                                    "REVERSED_INVITATION_ROUTE_FAILED",
                                    mapOf(
                                        "routeIndex" to routeIndex,
                                        "routeBound" to (route != null),
                                        "networkHandle" to (route?.networkHandle ?: 0L),
                                        "attempt" to attempt,
                                        "host" to candidate,
                                        "failureType" to failure.javaClass.simpleName,
                                        "ioFailure" to (failure is java.io.IOException)
                                    )
                                )
                                val retryableRouteFailure = failure is java.io.IOException ||
                                    com.malekplay.network.isRetryableUltraSharePairingFailure(failure)
                                if (!retryableRouteFailure) {
                                    stopOnProtocolFailure = true
                                    break
                                }
                                if (attempt < 2) delay(500L * (attempt + 1))
                            }
                        }
                    }
                    if (accepted || stopOnProtocolFailure) break
                }
                if (accepted) {
                    val remotePeerId = UltraShareControlChannel.sessionPeerId(host)
                    val remotePeerName = UltraShareControlChannel.sessionPeerName(host)
                        ?: "جهاز MalekPlayer"
                    val sessionId = remotePeerId?.takeIf { it.isNotBlank() }
                        ?: java.util.UUID.randomUUID().toString()
                    UltraShareForegroundService.registerApprovedPeer(
                        name = remotePeerName,
                        host = host,
                        role = "اقتران عكسي آمن عبر Wi-Fi Direct",
                        sessionId = sessionId
                    )
                    if (!remotePeerId.isNullOrBlank()) {
                        UltraShareForegroundService.registerApprovedSendTarget(
                            UltraShareApprovedSendTarget(
                                sessionId = remotePeerId,
                                host = host,
                                secret = secret
                            )
                        )
                    }
                    _state.value = _state.value.copy(
                        wifiDirectHost = host,
                        scanning = false,
                        pairedSession = true,
                        error = null,
                        status = "اكتمل الاقتران العكسي بأمان مع $remotePeerName؛ الإرسال والاستقبال متاحان."
                    )
                } else {
                    _state.value = _state.value.copy(
                        wifiDirectHost = null,
                        scanning = false,
                        error = lastFailure?.localizedMessage
                            ?: "تعذر إكمال الاقتران العكسي. أبقِ الجهازين متصلين وأعد المحاولة."
                    )
                }
            } finally {
                reverseInvitationConnectionInProgress = false
            }
        }
    }

    private fun completeInvitationConnection(
        host: String,
        message: String,
        preferredNetwork: android.net.Network? = null,
        connectionFailureHint: String? = null
    ) {
        val callback = pendingInvitationReady ?: return
        val pairing = pendingInvitation ?: return
        // Claim this invitation synchronously before launching IO. Wi-Fi P2P can
        // emit multiple connection broadcasts for the same group; without clearing
        // the pending callback here, each broadcast could start a competing MPC2
        // handshake and replace the just-approved client session.
        pendingInvitationReady = null
        pendingInvitation = null
        // Every terminal path releases DNS-SD discovery before authenticated TCP
        // negotiation. QR possession alone is not treated as a connected session.
        wifiDirectSession?.stopInvitationDiscovery()
        invitationTimeout?.cancel()
        invitationTimeout = null
        _state.value = _state.value.copy(
            wifiDirectHost = host,
            scanning = false,
            error = null,
            status = "تم العثور على الجهاز؛ جارٍ إنشاء قناة اقتران آمنة..."
        )
        viewModelScope.launch {
            val p2pInvitation = !pairing.invitationId.isNullOrBlank()
            val candidates = UltraShareNetworkRouting.invitationHostCandidates(
                confirmedHost = host,
                advertisedHosts = pairing.hosts,
                pinToConfirmedHost = p2pInvitation
            ).filter { UltraShareCore.isValidPrivateLanHost(it) }
            UltraShareDiagnostics.event(
                "PAIRING",
                if (p2pInvitation) "P2P_INVITATION_HOST_PINNED" else "LAN_INVITATION_HOST_CANDIDATES",
                mapOf("candidateCount" to candidates.size, "p2pConfirmedHostOnly" to p2pInvitation)
            )
            var connectedHost: String? = null
            var lastFailure: Exception? = null
            for (candidate in candidates) {
                // Never reuse an existing socket as proof for a newly scanned QR.
                // Its pairing secret may belong to an older invitation; every new
                // invitation must complete MPC2 with the secret carried by this QR.
                var candidateAccepted = false
                var candidateFailure: Exception? = null
                if (!candidateAccepted) {
                    // Try every Android Network with a route to this exact peer,
                    // prioritizing Wi-Fi Direct, then the remaining local links.
                    // Finally try Android's default route for OEM P2P stacks that
                    // do not expose a bindable Network object.
                    val routeCandidates: List<android.net.Network?> =
                        (listOfNotNull(preferredNetwork) +
                            UltraShareNetworkRouting.findNetworksForHost(context, candidate)
                                .map { it as android.net.Network? })
                            .distinctBy { it?.toString() } + listOf(null)
                    var routeAuthenticationOrProtocolFailure = false
                    for ((routeIndex, route) in routeCandidates.withIndex()) {
                        if (candidateAccepted || routeAuthenticationOrProtocolFailure) break
                        for (attempt in 0..1) {
                            try {
                                val decision = withContext(Dispatchers.IO) {
                                    UltraShareControlChannel.request(
                                        host = candidate,
                                        pairingSecret = pairing.pairingSecret,
                                        peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlayer Android" }
                                            ?: "MalekPlayer Android",
                                        network = route,
                                        peerId = UltraShareDiscovery.localPeerId(context),
                                    )
                                }
                                if (decision == UltraShareControlChannel.Decision.REJECTED) {
                                    UltraShareDiagnostics.event(
                                        "PAIRING",
                                        "INVITATION_ROUTE_REJECTED",
                                        mapOf("routeIndex" to routeIndex, "attempt" to attempt, "host" to candidate)
                                    )
                                    candidateFailure = IllegalStateException("رفض جهاز الاستقبال طلب الاقتران.")
                                    break
                                }
                                UltraShareDiagnostics.event(
                                    "PAIRING",
                                    "INVITATION_ROUTE_AUTHENTICATED",
                                    mapOf("routeIndex" to routeIndex, "attempt" to attempt, "host" to candidate)
                                )
                                candidateAccepted = true
                                break
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                candidateFailure = failure
                                UltraShareDiagnostics.event(
                                    "PAIRING",
                                    "INVITATION_ROUTE_FAILED",
                                    mapOf(
                                        "routeIndex" to routeIndex,
                                        "routeBound" to (route != null),
                                        "networkHandle" to (route?.networkHandle ?: 0L),
                                        "attempt" to attempt,
                                        "host" to candidate,
                                        "failureType" to failure.javaClass.simpleName,
                                        "ioFailure" to (failure is java.io.IOException)
                                    )
                                )
                                // An explicit credential rejection is terminal. A wrong
                                // control greeting/version can mean this candidate IP belongs
                                // to another endpoint, so continue with the next verified route.
                                val retryableNetworkFailure = failure is java.io.IOException ||
                                    com.malekplay.network.isRetryableUltraSharePairingFailure(failure)
                                if (!retryableNetworkFailure) {
                                    routeAuthenticationOrProtocolFailure = true
                                    break
                                }
                                if (attempt == 1) break
                                delay(500L * (attempt + 1))
                            }
                        }
                        if (candidateFailure is IllegalStateException &&
                            candidateFailure.message?.contains("رفض جهاز الاستقبال") == true
                        ) break
                        if (!candidateAccepted && routeIndex < routeCandidates.lastIndex) {
                            _state.value = _state.value.copy(
                                status = "تعذر الوصول عبر مسار الشبكة الحالي؛ تجربة مسار اتصال آخر..."
                            )
                        }
                    }
                }
                if (candidateAccepted) {
                    connectedHost = candidate
                    break
                }
                lastFailure = candidateFailure
                if (candidateFailure is IllegalStateException &&
                    candidateFailure.message?.contains("رفض جهاز الاستقبال") == true
                ) break
            }
            if (connectedHost != null) {
                val remotePeerId = UltraShareControlChannel.sessionPeerId(connectedHost)
                val remotePeerName = UltraShareControlChannel.sessionPeerName(connectedHost)
                    ?: "جهاز MalekPlayer"
                // Keep a reconnect target only when the remote peer supplied a
                // verified stable identity; never invent an ID for rediscovery.
                UltraShareForegroundService.registerApprovedPeer(
                    name = remotePeerName,
                    host = connectedHost,
                    role = "اتصال آمن عبر QR أو الرمز",
                    sessionId = remotePeerId ?: java.util.UUID.randomUUID().toString()
                )
                if (!remotePeerId.isNullOrBlank()) {
                    UltraShareForegroundService.registerApprovedSendTarget(
                        UltraShareApprovedSendTarget(
                            sessionId = remotePeerId,
                            host = connectedHost,
                            secret = pairing.pairingSecret
                        )
                    )
                }
                // A QR scan initially authenticates the scanner as a sender. Start
                // its receiver endpoint too, so the QR creator can send back over the
                // same temporary pairing session without clearing this approved peer.
                UltraShareForegroundService.startReceiving(
                    context,
                    pairing.pairingSecret,
                    createHotspot = false
                )
                _state.value = _state.value.copy(
                    wifiDirectHost = connectedHost,
                    scanning = false,
                    pairedSession = true,
                    error = null,
                    status = "$message تم إنشاء اتصال آمن وجاهز للإرسال والاستقبال."
                )
                callback(connectedHost)
            } else {
                _state.value = _state.value.copy(
                    wifiDirectHost = null,
                    scanning = false,
                    error = (lastFailure?.localizedMessage
                        ?: "تعذر إنشاء اتصال آمن. تأكد من تشغيل الاستقبال وصحة رمز الاقتران.") +
                        (connectionFailureHint?.let { "\n$it" } ?: "")
                )
            }
        }
    }

    private fun ensureWifiDirectSession(): UltraShareWifiDirect.Session {
        return wifiDirectSession ?: UltraShareWifiDirect.open(
            context = context,
            onPeers = { peers ->
                _state.value = _state.value.copy(wifiDirectPeers = peers)
            },
            onConnected = { remoteHost, isGroupOwner ->
                if (isGroupOwner) {
                    val pairing = pendingInvitation
                    if (pairing != null && pendingInvitationReady != null &&
                        !pairing.invitationId.isNullOrBlank()
                    ) {
                        // If Android makes the scanner group owner, reverse the
                        // authenticated connection instead of dialing a stale QR IP.
                        pendingInvitationReady = null
                        pendingInvitation = null
                        invitationTimeout?.cancel()
                        invitationTimeout = null
                        wifiDirectSession?.stopInvitationDiscovery()
                        if (UltraShareCore.isValidPairingSecret(pairing.pairingSecret)) {
                            UltraShareForegroundService.startReceiving(
                                context, pairing.pairingSecret, createHotspot = false
                            )
                            _state.value = _state.value.copy(
                                wifiDirectHost = null,
                                scanning = false,
                                error = null,
                                status = "انعكس دور Wi-Fi Direct؛ أصبح هذا الجهاز مستقبلاً مؤقتاً وننتظر مصادقة الجهاز الآخر..."
                            )
                        } else {
                            _state.value = _state.value.copy(
                                wifiDirectHost = null,
                                scanning = false,
                                error = "تعذر استعادة سر جلسة QR للاقتران العكسي. أنشئ رمزاً جديداً."
                            )
                        }
                    } else if (_state.value.receiving) {
                        UltraShareForegroundService.refreshLocalAddresses(context)
                        _state.value = _state.value.copy(
                            status = "مجموعة Wi-Fi Direct جاهزة للاستقبال."
                        )
                    } else {
                        val fallback = pairing?.let(::verifiedLanFallback)
                        if (!fallback.isNullOrBlank()) {
                            completeInvitationConnection(
                                fallback,
                                "تعذر تحديد مسار Wi-Fi Direct؛ تم التحقق من مسار Wi-Fi العادي."
                            )
                        } else if (pendingInvitationReady != null) {
                            val pending = pendingInvitation
                            pendingInvitationReady = null
                            pendingInvitation = null
                            invitationTimeout?.cancel()
                            invitationTimeout = null
                            wifiDirectSession?.stopInvitationDiscovery()
                            if (pending != null && UltraShareCore.isValidPairingSecret(pending.pairingSecret)) {
                                UltraShareForegroundService.startReceiving(
                                    context, pending.pairingSecret, createHotspot = false
                                )
                                _state.value = _state.value.copy(
                                    wifiDirectHost = null,
                                    scanning = false,
                                    error = null,
                                    status = "انعكس دور Wi-Fi Direct؛ أصبح هذا الجهاز مستقبلاً مؤقتاً وننتظر مصادقة الجهاز الآخر..."
                                )
                            } else {
                                _state.value = _state.value.copy(
                                    wifiDirectHost = null,
                                    scanning = false,
                                    error = "تعذر استعادة سر جلسة QR للاقتران العكسي."
                                )
                            }
                        }
                    }
                } else if (!remoteHost.isNullOrBlank()) {
                    if (pendingInvitationReady != null) {
                        completeInvitationConnection(remoteHost, "تم الاتصال تلقائياً عبر Wi-Fi Direct؛ الجهاز جاهز للإرسال.")
                    } else if (_state.value.receiving) {
                        // This is the QR creator. The remote group owner is now the
                        // scanner; authenticate to its temporary receiver with our
                        // existing one-session secret instead of treating its address
                        // as an unauthenticated peer.
                        completeReversedInvitationConnection(remoteHost)
                    } else {
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct."
                        )
                    }
                }
            },
            onError = { message ->
                val pairing = pendingInvitation
                val fallback = pairing?.let(::verifiedLanFallback)
                if (pendingInvitationReady != null && !fallback.isNullOrBlank()) {
                    completeInvitationConnection(
                        fallback,
                        "تعذر Wi-Fi Direct؛ تم التحقق من مسار LAN بديل وتجربته تلقائياً."
                    )
                } else if (pendingInvitationReady != null) {
                    // No safe LAN route is currently visible. Retire this attempt;
                    // the user can retry after joining the same Wi-Fi network.
                    wifiDirectSession?.stopInvitationDiscovery()
                    pendingInvitationReady = null
                    pendingInvitation = null
                    invitationTimeout?.cancel()
                    invitationTimeout = null
                    _state.value = _state.value.copy(
                        scanning = false,
                        wifiDirectHost = null,
                        error = message
                    )
                } else if (_state.value.receiving && !_state.value.wifiDirectInvitationReady &&
                    !_state.value.hotspotActive && !_state.value.hotspotStarting
                ) {
                    // P2P is the primary offline path. If group creation or DNS-SD
                    // fails before a QR is published, retire that attempt and start
                    // the supported LocalOnlyHotspot fallback instead of leaving the
                    // receiver waiting with no usable invitation.
                    startHotspotFallback(message)
                } else if (_state.value.receiving) {
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = null,
                        status = "تعذر استمرار Wi-Fi Direct؛ الاستقبال المحلي ما زال يعمل. $message"
                    )
                } else {
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = message
                    )
                }
            }
        ).also { wifiDirectSession = it }
    }

    /** Publish one immutable QR payload as soon as the authenticated control listener is ready. */
    private fun publishStableReceiverQrIfReady() {
        val current = _state.value
        if (!current.receiving || current.receiverQrPayload != null) return
        // Pairing uses the authenticated MPC2 control listener. The V2 data
        // listener is independent and may recover after pairing; it must not hide
        // the QR or prevent devices from connecting.
        if (!current.controlChannelReady ||
            !UltraShareCore.isValidPairingSecret(current.pairingSecret)
        ) return

        val payload = when {
            current.wifiDirectInvitationReady && !current.invitationId.isNullOrBlank() ->
                runCatching {
                    UltraShareQrCode.createReceiverPayload(
                        hosts = current.localAddresses,
                        pairingSecret = current.pairingSecret,
                        invitationId = current.invitationId
                    )
                }.getOrNull()
            current.hotspotActive && !current.hotspotSsid.isNullOrBlank() &&
                !current.hotspotPassword.isNullOrBlank() && current.localAddresses.isNotEmpty() ->
                runCatching {
                    UltraShareQrCode.createHotspotReceiverPayload(
                        current.localAddresses,
                        current.pairingSecret,
                        current.hotspotSsid,
                        current.hotspotPassword
                    )
                }.getOrNull()
            current.invitationId.isNullOrBlank() && !current.hotspotStarting ->
                runCatching {
                    val lanHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
                        context, current.localAddresses
                    )
                    if (lanHosts.isEmpty()) null
                    else UltraShareQrCode.createReceiverPayload(lanHosts, current.pairingSecret)
                }.getOrNull()
            else -> null
        } ?: return

        // Ignore a delayed callback from an older receiver attempt.
        val latest = _state.value
        if (!latest.receiving || latest.receiverQrPayload != null ||
            latest.pairingSecret != current.pairingSecret || latest.invitationId != current.invitationId
        ) return
        _state.value = latest.copy(receiverQrPayload = payload)
        UltraShareDiagnostics.event(
            "PAIRING_QR", if (current.dataChannelReady) "QR_PUBLISHED_STABLE" else "QR_PUBLISHED_WITHOUT_DATA_LISTENER",
            mapOf("mode" to if (current.wifiDirectInvitationReady) "WIFI_DIRECT" else "HOTSPOT",
                "invitationId" to current.invitationId.orEmpty(), "payloadLength" to payload.length)
        )
    }

    private fun startHotspotFallback(reason: String) {
        if (hotspotFallbackStarted || !_state.value.receiving || _state.value.pairedSession ||
            _state.value.hotspotActive || _state.value.hotspotStarting
        ) return
        hotspotFallbackStarted = true
        wifiDirectSession?.stopInvitationAdvertisement()
        wifiDirectSession?.stopInvitationDiscovery()
        _state.value = _state.value.copy(
            invitationId = null,
            wifiDirectInvitationReady = false,
            scanning = false,
            error = null,
            status = "تعذر Wi-Fi Direct ($reason)؛ جارٍ التأكد من إغلاق مجموعة P2P قبل تشغيل نقطة الاتصال..."
        )
        // Android 6–7 cannot create an in-app LocalOnlyHotspot. A LAN fallback
        // does not create or replace a radio group, so it is safe to publish it
        // without requiring P2P group-removal permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startHotspotOnlyAfterP2pCleanup()
            return
        }
        val session = wifiDirectSession
        if (session == null) {
            hotspotFallbackStarted = false
            _state.value = _state.value.copy(
                hotspotStarting = false,
                error = "تعذر التحقق من جلسة Wi-Fi Direct وإغلاق مجموعة P2P؛ لم نشغل نقطة الاتصال لتجنب تعارض الشبكتين. أعد تشغيل الاستقبال ثم حاول مجدداً.",
                status = "توقف التبديل الآمن لأن جلسة Wi-Fi Direct غير متاحة للتحقق."
            )
            return
        }
        // Fail closed: never request a LocalOnlyHotspot while P2P teardown is unconfirmed.
        session.removeGroup { removed ->
            if (removed) {
                startHotspotOnlyAfterP2pCleanup()
            } else {
                hotspotFallbackStarted = false
                _state.value = _state.value.copy(
                    hotspotStarting = false,
                    error = "تعذر التأكد من إغلاق مجموعة Wi-Fi Direct؛ أوقفنا التبديل لمنع تعارض الشبكتين. أوقف Wi-Fi Direct من إعدادات الهاتف ثم أعد المحاولة.",
                    status = "لم يتم تشغيل نقطة الاتصال لأن حالة مجموعة P2P غير مؤكدة."
                )
            }
        }
    }

    private fun startHotspotOnlyAfterP2pCleanup() {
        if (!_state.value.receiving || _state.value.pairedSession ||
            _state.value.hotspotActive || _state.value.hotspotStarting
        ) return

        // Prefer an already-connected, verified WLAN/Ethernet route before
        // requesting a new SoftAP. This also preserves pairing when Android 13+
        // nearby-device permission is denied but both phones share a LAN.
        UltraShareForegroundService.refreshLocalAddresses(context)
        val lanHosts = UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(
            context, _state.value.localAddresses
        )
        if (lanHosts.isNotEmpty()) {
            _state.value = _state.value.copy(
                localAddresses = lanHosts,
                hotspotStarting = false,
                hotspotActive = false,
                error = null,
                status = "تعذر Wi-Fi Direct؛ الاستقبال جاهز عبر شبكة Wi-Fi المحلية. امسح رمز QR للانضمام."
            )
            publishStableReceiverQrIfReady()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 6–7 have no LocalOnlyHotspot API. Keep the receiver alive;
            // a QR appears automatically when a shared WLAN/Ethernet route exists.
            _state.value = _state.value.copy(
                hotspotStarting = false,
                error = "هذا الإصدار لا يدعم إنشاء نقطة اتصال داخل التطبيق. اتصل بشبكة Wi-Fi مشتركة أو فعّل نقطة اتصال النظام يدوياً؛ سيظهر QR تلقائياً عند توفر عنوان محلي.",
                status = "الاستقبال يعمل؛ ننتظر شبكة محلية صالحة لعرض رمز الاقتران."
            )
            publishStableReceiverQrIfReady()
            return
        }
        _state.value = _state.value.copy(hotspotStarting = true)
        viewModelScope.launch {
            runCatching { UltraShareForegroundService.startLocalHotspot(context) }
                .onFailure { failure ->
                    hotspotFallbackStarted = false
                    _state.value = _state.value.copy(
                        hotspotStarting = false,
                        error = failure.localizedMessage
                            ?: "تعذر تشغيل نقطة الاتصال المحلية البديلة. تحقق من إذن الأجهزة القريبة ودعم Wi-Fi في الجهاز."
                    )
                    publishStableReceiverQrIfReady()
                }
        }
    }

    private fun activateReceiverInvitation(invitationId: String) {
        if (!invitationId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        _state.value = _state.value.copy(
            invitationId = invitationId,
            wifiDirectInvitationReady = false,
            status = "جارٍ تشغيل قناة الاتصال الآمنة قبل إظهار رمز QR..."
        )
        viewModelScope.launch {
            val readiness = withTimeoutOrNull(15_000L) {
                UltraShareForegroundService.state.first { service ->
                    service.controlChannelReady || service.controlChannelError != null
                }
            }
            if (readiness == null || !readiness.controlChannelReady) {
                val reason = readiness?.controlChannelError
                    ?: "لم يبدأ خادم الاتصال الآمن على المنفذ 38744 خلال 15 ثانية."
                // Do not leave a receiver session latched in the UI without a QR:
                // the start button is disabled while receiving=true, so the user
                // otherwise cannot retry after the control listener failed to start.
                // Retire the incomplete session and preserve the actionable error.
                stopReceiving()
                _state.value = _state.value.copy(
                    wifiDirectInvitationReady = false,
                    receiverQrPayload = null,
                    error = reason,
                    status = "توقف الاستقبال لأن قناة الاقتران الآمنة لم تصبح جاهزة. صحّح السبب ثم ابدأ الاستقبال مجدداً."
                )
                UltraShareDiagnostics.event(
                    "PAIRING_QR",
                    "RECEIVER_START_ABORTED_CONTROL_NOT_READY",
                    mapOf(
                        "controlChannelErrorPresent" to !readiness?.controlChannelError.isNullOrBlank(),
                        "timeout" to (readiness == null)
                    )
                )
                return@launch
            }
            runCatching {
                ensureWifiDirectSession().createGroup {
                    runCatching {
                        ensureWifiDirectSession().advertiseInvitation(invitationId) {
                            _state.value = _state.value.copy(
                                wifiDirectInvitationReady = true,
                                status = "الجهاز جاهز. امسح رمز QR للاتصال التلقائي."
                            )
                            publishStableReceiverQrIfReady()
                        }
                    }.onFailure { failure ->
                        startHotspotFallback(
                            failure.localizedMessage ?: "تعذر إعلان دعوة Wi-Fi Direct."
                        )
                    }
                }
            }.onFailure { failure ->
                startHotspotFallback(
                    failure.localizedMessage ?: "تعذر إنشاء مجموعة Wi-Fi Direct."
                )
            }
        }
    }

    fun startReceiving() {
        if (_state.value.receiving) return
        val secret = UltraShareCore.createPairingSecret()
        val invitationId = java.util.UUID.randomUUID().toString().replace("-", "")
        hotspotFallbackStarted = false
        _state.value = _state.value.copy(
            pairingSecret = secret,
            invitationId = invitationId,
            wifiDirectInvitationReady = false,
            receiverQrPayload = null,
            pairedSession = false,
            receiving = true,
            error = null,
            status = context.getString(R.string.ultra_share_waiting)
        )
        runCatching { UltraShareForegroundService.startReceiving(context, secret, createHotspot = false) }
            .onFailure {
                _state.value = _state.value.copy(
                    receiving = false,
                    pairingSecret = "",
                    invitationId = null,
                    wifiDirectInvitationReady = false,
                    error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
                )
                return
            }
        // Prefer a real Wi-Fi Direct group so two phones can pair without a router
        // or internet. The QR is published only after DNS-SD advertises this session.
        // If the device/OEM cannot form a P2P group, activateReceiverInvitation
        // reports the failure and startHotspotFallback switches to the LAN QR path.
        activateReceiverInvitation(invitationId)
    }

    private fun connectThroughLocalHotspot(
        pairing: UltraShareQrCode.PairingDetails,
        onReady: (String) -> Unit
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // WifiNetworkSpecifier is unavailable before Android 10. Still try the
            // authenticated TCP handshake on the current route: this succeeds when
            // the user has already joined the QR hotspot manually in Wi-Fi settings.
            val lanFallback = UltraShareNetworkRouting.findNonP2pLanFallbackHost(
                context, pairing.hosts
            )
            completeInvitationConnection(
                host = lanFallback ?: pairing.host,
                message = if (lanFallback != null)
                    "تم التحقق من مسار LAN الحالي؛ جارٍ إكمال الاقتران."
                else
                    "تم التحقق من الاقتران عبر اتصال Wi-Fi اليدوي.",
                connectionFailureHint = "على Android 9 أو أقدم، اتصل أولاً بشبكة MalekPlayer الظاهرة في رمز QR من إعدادات Wi-Fi، ثم امسح الرمز مرة أخرى."
            )
            return
        }
        val ssid = pairing.hotspotSsid ?: return
        val password = pairing.hotspotPassword ?: return
        val host = pairing.host
        if (!UltraShareCore.isValidPrivateLanHost(host)) {
            _state.value = _state.value.copy(error = "عنوان خادم الاستقبال في رمز QR غير صالح.")
            return
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        hotspotNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        _state.value = _state.value.copy(
            scanning = true,
            error = null,
            status = "جارٍ طلب الانضمام إلى شبكة MalekPlayer من Android..."
        )
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            // LocalOnlyHotspot deliberately has no internet access. The default
            // NetworkRequest asks for INTERNET and can therefore reject this LAN.
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val connectionStarted = AtomicBoolean(false)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Network callbacks may be repeated; only one authenticated handshake
                // may consume this QR invitation.
                if (!connectionStarted.compareAndSet(false, true)) return
                viewModelScope.launch {
                    _state.value = _state.value.copy(
                        status = "اتصل Android بشبكة MalekPlayer؛ جارٍ التحقق من خادم الاستقبال..."
                    )
                    completeInvitationConnection(
                        host,
                        "تم الانضمام إلى شبكة MalekPlayer عبر Android.",
                        network
                    )
                }
            }

            override fun onUnavailable() {
                val lanFallback = UltraShareNetworkRouting.findNonP2pLanFallbackHost(
                    context, pairing.hosts
                )
                if (!lanFallback.isNullOrBlank() && connectionStarted.compareAndSet(false, true)) {
                    hotspotNetworkCallback = null
                    completeInvitationConnection(
                        lanFallback,
                        "تعذر الانضمام إلى نقطة الاتصال؛ تم العثور على مسار LAN بديل والتحقق منه."
                    )
                } else {
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = "لم يكتمل الانضمام إلى شبكة MalekPlayer ولا يوجد مسار LAN بديل متاح. وافق على طلب Wi-Fi أو اتصل بالشبكة المشتركة ثم أعد المحاولة."
                    )
                }
            }

            override fun onLost(network: android.net.Network) {
                if (!_state.value.pairedSession) {
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = "انقطع اتصال شبكة MalekPlayer قبل اكتمال الاقتران."
                    )
                }
            }
        }
        hotspotNetworkCallback = callback
        try {
            connectivity.requestNetwork(request, callback, 30_000)
        } catch (failure: Exception) {
            hotspotNetworkCallback = null
            val lanFallback = UltraShareNetworkRouting.findNonP2pLanFallbackHost(
                context, pairing.hosts
            )
            if (!lanFallback.isNullOrBlank() && connectionStarted.compareAndSet(false, true)) {
                completeInvitationConnection(
                    lanFallback,
                    "تعذر طلب اتصال نقطة الاتصال؛ تم التحقق من مسار LAN بديل."
                )
            } else {
                _state.value = _state.value.copy(
                    scanning = false,
                    error = failure.localizedMessage ?: "تعذر طلب الانضمام إلى شبكة MalekPlayer."
                )
            }
        }
    }

    // A P2P QR may also advertise a LAN address. Use it only when Android
    // currently exposes a matching non-P2P Wi-Fi/Ethernet route; never reuse a
    // stale address from the previous Wi-Fi Direct group.
    private fun verifiedLanFallback(pairing: UltraShareQrCode.PairingDetails): String? =
        if (pairing.invitationId.isNullOrBlank()) {
            pairing.hosts.firstOrNull()
        } else {
            UltraShareNetworkRouting.findNonP2pLanFallbackHost(context, pairing.hosts)
        }

    fun connectInvitation(pairing: UltraShareQrCode.PairingDetails, onReady: (String) -> Unit) {
        // P2P invitations must negotiate Wi-Fi Direct first, even when the QR also
        // carries optional hotspot credentials for a separate fallback transport.
        // Legacy QR entry point: keep P2P invitations ahead of optional hotspot
        // credentials so older navigation paths preserve the same transport priority.
        if (pairing.invitationId.isNullOrBlank() &&
            !pairing.hotspotSsid.isNullOrBlank() &&
            !pairing.hotspotPassword.isNullOrBlank()
        ) {
            pendingInvitation = pairing
            pendingInvitationReady = onReady
            connectThroughLocalHotspot(pairing, onReady)
            return
        }
        val fallbackHost = pairing.hosts.firstOrNull()
        if (pairing.invitationId.isNullOrBlank()) {
            if (fallbackHost.isNullOrBlank()) {
                _state.value = _state.value.copy(error = "لا يوجد عنوان اتصال احتياطي في بيانات الاقتران.")
                return
            }
            pendingInvitation = pairing
            pendingInvitationReady = onReady
            completeInvitationConnection(fallbackHost, "تم إدخال بيانات الاقتران يدوياً.")
            return
        }
        pendingInvitation = pairing
        pendingInvitationReady = onReady
        _state.value = _state.value.copy(
            scanning = true,
            wifiDirectHost = null,
            error = null,
            status = "جارٍ العثور على جهاز الاستقبال المطابق لرمز QR..."
        )
        runCatching {
            val session = ensureWifiDirectSession()
            session.discoverInvitation(pairing.invitationId) { peer ->
                if (pendingInvitationReady == null) return@discoverInvitation
                // The discovery deadline ends once the exact receiver is found.
                // Connection negotiation has its own 30-second timeout in the P2P layer.
                invitationTimeout?.cancel()
                invitationTimeout = null
                session.stopInvitationDiscovery {
                    runCatching {
                        session.connect(peer.deviceAddress, preferThisDeviceAsGroupOwner = false)
                    }.onFailure { connectFailure ->
                        val fallback = verifiedLanFallback(pairing)
                        if (!fallback.isNullOrBlank()) {
                            completeInvitationConnection(
                                fallback,
                                "تعذر إنشاء اتصال Wi-Fi Direct؛ تم التحقق من مسار Wi-Fi العادي وتجربته."
                            )
                        } else {
                            session.stopInvitationDiscovery()
                            pendingInvitationReady = null
                            pendingInvitation = null
                            invitationTimeout?.cancel()
                            invitationTimeout = null
                            _state.value = _state.value.copy(
                                scanning = false,
                                wifiDirectHost = null,
                                error = "تعذر إنشاء اتصال Wi-Fi Direct، ولا يوجد حالياً مسار LAN بديل موثوق. اتصل بالشبكة المشتركة ثم أعد المحاولة."
                            )
                        }
                    }
                }
            }
            invitationTimeout?.cancel()
            invitationTimeout = viewModelScope.launch {
                delay(15_000)
                if (pendingInvitationReady != null) {
                    session.stopInvitationDiscovery()
                    val fallback = verifiedLanFallback(pairing)
                    if (!fallback.isNullOrBlank()) {
                        completeInvitationConnection(
                            fallback,
                            "لم يظهر الجهاز عبر Wi-Fi Direct؛ تم العثور على مسار LAN بديل والتحقق منه."
                        )
                    } else {
                        pendingInvitationReady = null
                        pendingInvitation = null
                        _state.value = _state.value.copy(
                            scanning = false,
                            wifiDirectHost = null,
                            error = "لم يظهر الجهاز عبر Wi-Fi Direct ولا يوجد مسار LAN بديل متاح حالياً. اتصل بالشبكة نفسها على الهاتفين ثم أعد المحاولة."
                        )
                    }
                }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            val fallback = verifiedLanFallback(pairing)
            if (!fallback.isNullOrBlank()) {
                completeInvitationConnection(
                    fallback,
                    "تعذر بدء Wi-Fi Direct؛ تم التحقق من مسار LAN واستخدامه."
                )
            } else {
                pendingInvitationReady = null
                pendingInvitation = null
                _state.value = _state.value.copy(
                    scanning = false,
                    error = failure.localizedMessage
                        ?: "تعذر بدء Wi-Fi Direct ولا يوجد مسار LAN بديل متاح."
                )
            }
        }
    }
    fun stopReceiving() {
        hotspotNetworkCallback?.let { callback ->
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        hotspotNetworkCallback = null
        wifiDirectSession?.stopInvitationAdvertisement()
        wifiDirectSession?.removeGroup()
        invitationPreferences.edit().remove("active_invitation_id").apply()
        hotspotFallbackStarted = false
        _state.value = _state.value.copy(
            invitationId = null,
            wifiDirectInvitationReady = false,
            receiverQrPayload = null,
            pairedSession = false
        )
        UltraShareForegroundService.cancel(context)
        _state.value = _state.value.copy(
            receiving = false,
            pairingSecret = "",
            localAddresses = emptyList(),
            progress = null,
            status = context.getString(R.string.ultra_share_stopped)
        )
    }

    fun scanNearbyDevices() {
        if (scanJob != null) return
        _state.value = _state.value.copy(scanning = true, error = null, status = null)
        scanJob = viewModelScope.launch {
            try {
                val peers = UltraShareDiscovery.discoverPeers(context = context)
                _state.value = _state.value.copy(
                    discoveredPeers = peers,
                    status = if (peers.isEmpty()) context.getString(R.string.ultra_share_no_devices)
                    else context.getString(R.string.ultra_share_devices_found, peers.size)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    error = failure.localizedMessage ?: context.getString(R.string.ultra_share_discovery_unavailable)
                )
            } finally {
                scanJob = null
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }

    /**
     * Unified nearby scan: query Wi-Fi Direct peers and authenticated LAN offers
     * during the same user action. Discovery results are hints only; selecting a
     * device still requires the existing MPC2 invitation handshake.
     */
    fun scanNearbyGroups() {
        if (_state.value.sending || _state.value.receiving || scanJob != null) return
        // Do not keep stale LAN sightings visible as if they belonged to this scan.
        _state.value = _state.value.copy(discoveredPeers = emptyList(), error = null)
        scanWifiDirectPeers()
        scanJob = viewModelScope.launch {
            try {
                val peers = UltraShareDiscovery.discoverPeers(
                    scanWindowMs = 4_000L,
                    context = context
                )
                _state.value = _state.value.copy(
                    discoveredPeers = peers,
                    error = null,
                    status = when {
                        peers.isNotEmpty() && _state.value.wifiDirectPeers.isNotEmpty() ->
                            "اكتُشفت أجهزة عبر الشبكة المحلية وWi-Fi Direct. اختر طريقة الاقتران الآمنة."
                        peers.isNotEmpty() ->
                            "اكتُشفت " + peers.size + " أجهزة عبر الشبكة المحلية. يلزم إكمال مصادقة الاقتران."
                        _state.value.wifiDirectPeers.isNotEmpty() ->
                            "اكتُشفت أجهزة Wi-Fi Direct. يلزم إكمال مصادقة الاقتران."
                        else -> "لم تُكتشف أجهزة قريبة. تأكد من تشغيل Wi-Fi وأن الجهاز الآخر ينشئ مجموعة أو يعلن الاستقبال."
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val p2pPeersAvailable = _state.value.wifiDirectPeers.isNotEmpty()
                _state.value = _state.value.copy(
                    // A failure in UDP/LAN discovery must not hide successful P2P
                    // results or make the complete nearby scan look unsuccessful.
                    error = if (p2pPeersAvailable) null else (
                        failure.localizedMessage ?: "تعذر إكمال البحث عبر الشبكة المحلية."
                    ),
                    status = if (p2pPeersAvailable) {
                        "تعذر البحث عبر الشبكة المحلية، لكن أجهزة Wi-Fi Direct المكتشفة ما زالت متاحة."
                    } else {
                        _state.value.status
                    }
                )
            } finally {
                scanJob = null
                // Wi-Fi Direct owns the shared scan indicator until its bounded
                // 15-second radio-discovery window stops.
                if (wifiDirectScanTimeout == null) {
                    _state.value = _state.value.copy(scanning = false)
                }
            }
        }
    }

    fun scanWifiDirectPeers() {
        try {
            val session = ensureWifiDirectSession()
            _state.value = _state.value.copy(
                scanning = true,
                wifiDirectPeers = emptyList(),
                wifiDirectHost = null,
                error = null,
                status = "جارٍ البحث عن أجهزة Wi-Fi Direct..."
            )
            session.discover()
            wifiDirectScanTimeout?.cancel()
            wifiDirectScanTimeout = viewModelScope.launch {
                delay(15_000)
                // Discovery is deliberately bounded to the user's explicit scan action.
                // Stop the radio scan even when the UI timeout fires; otherwise Android
                // may continue peer discovery after the screen reports that scanning ended.
                wifiDirectSession?.stopDiscovery()
                if (_state.value.scanning) {
                    _state.value = _state.value.copy(
                        scanning = false,
                        status = if (_state.value.wifiDirectPeers.isEmpty())
                            "انتهى البحث عبر Wi-Fi Direct دون العثور على أجهزة."
                        else _state.value.status
                    )
                }
                wifiDirectScanTimeout = null
            }
        } catch (failure: Exception) {
            _state.value = _state.value.copy(
                scanning = false,
                error = failure.localizedMessage ?: "تعذر بدء Wi-Fi Direct على هذا الجهاز."
            )
        }
    }

    fun connectWifiDirectPeer(peer: UltraShareWifiDirect.Peer) {
        val session = wifiDirectSession ?: run {
            scanWifiDirectPeers()
            wifiDirectSession
        }
        if (session == null) {
            _state.value = _state.value.copy(error = "تعذر تهيئة اتصال Wi-Fi Direct.")
            return
        }
        _state.value = _state.value.copy(
            error = null,
            status = "جارٍ الاتصال بـ " + peer.name + " عبر Wi-Fi Direct..."
        )
        // Wait for Android's stopPeerDiscovery callback before connect(). Calling
        // both APIs back-to-back can race on OEM Wi-Fi P2P stacks and return ERROR/BUSY.
        session.stopDiscovery {
            if (wifiDirectSession !== session) return@stopDiscovery
            runCatching {
                session.connect(
                    peer.deviceAddress,
                    preferThisDeviceAsGroupOwner = UltraShareForegroundService.state.value.receiving
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    scanning = false,
                    error = it.localizedMessage ?: "تعذر الاتصال بالجهاز."
                )
            }
        }
    }

    fun send(uri: Uri, host: String, secret: String) {
        if (_state.value.sending) return
        if (host.isBlank() || !UltraShareCore.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(
                sending = false,
                error = context.getString(R.string.ultra_share_transfer_failed)
            )
            return
        }
        // Keep single-file and multi-file sends on one durable queue/state path.
        sendBatch(listOf(uri), host, secret)
    }

    fun sendBatch(uris: List<Uri>, host: String, secret: String): Boolean {
        if (uris.isEmpty() || _state.value.sending ||
            host.isBlank() || !UltraShareCore.isValidPairingSecret(secret)
        ) return false
        return runCatching {
            UltraShareForegroundService.startSendingBatch(context, uris, host, secret)
            _state.value = _state.value.copy(
                sending = true, progress = null, queueCompleted = 0,
                queueTotal = uris.size, queueCurrentName = null, error = null,
                status = "بدأ إرسال " + uris.size + " ملفات بالتتابع"
            )
            true
        }.getOrElse {
            _state.value = _state.value.copy(
                sending = false,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
            false
        }
    }

    fun resumePendingBatch(host: String, secret: String) {
        if (host.isBlank() || !UltraShareCore.isValidPairingSecret(secret) ||
            _state.value.sending
        ) return
        viewModelScope.launch {
            val pendingUris = UltraShareQueueStore.pending(context).map { it.parsedUri }
            if (pendingUris.isEmpty()) {
                _state.value = _state.value.copy(status = "لا توجد ملفات متبقية للاستئناف.")
            } else {
                sendBatch(pendingUris, host, secret)
            }
        }
    }

    fun retryFile(entry: UltraShareQueueEntry, host: String, secret: String) {
        if (_state.value.sending ||
            host.isBlank() || !UltraShareCore.isValidPairingSecret(secret) ||
            entry.status != UltraShareQueueStore.STATUS_FAILED
        ) return
        runCatching {
            UltraShareForegroundService.retryFile(context, entry.parsedUri, host, secret)
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                error = null,
                status = "إعادة محاولة إرسال " + entry.displayName
            )
        }.onFailure {
            _state.value = _state.value.copy(
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun skipQueuedFile(entry: UltraShareQueueEntry) {
        if (entry.status != UltraShareQueueStore.STATUS_PENDING &&
            entry.status != UltraShareQueueStore.STATUS_FAILED
        ) return
        viewModelScope.launch {
            UltraShareQueueStore.skip(context, entry.parsedUri)
        }
    }

    fun pauseSending() {
        if (_state.value.sending) UltraShareForegroundService.pause(context)
    }

    fun clearQueue() {
        viewModelScope.launch {
            if (!_state.value.sending) UltraShareQueueStore.clear(context)
        }
    }

    fun clearReceivedHistory() {
        viewModelScope.launch {
            if (!_state.value.receiving) UltraShareReceivedHistoryStore.clear(context)
        }
    }

    fun exportReceived(destination: Uri) {
        val received = _state.value.receivedFile ?: UltraShareForegroundService.state.value.receivedFile ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination, "w")
                        ?: error("تعذر فتح وجهة الحفظ.")
                    received.file.inputStream().use { input ->
                        output.use { target -> input.copyTo(target, UltraShareBatchTransport.COPY_BUFFER_BYTES) }
                    }
                }
                _state.value = _state.value.copy(
                    status = context.getString(R.string.ultra_share_exported, received.fileName),
                    error = null
                )
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage)
            }
        }
    }

    fun cancelSending() {
        UltraShareForegroundService.cancel(context)
        _state.value = _state.value.copy(
            progress = null,
            status = context.getString(R.string.ultra_share_send_cancelled)
        )
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, status = null)
    }

}

@Composable
fun UltraShareScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: LegacyUltraShareViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var pendingMediaUris by remember(initialUris) { mutableStateOf(initialUris.distinct()) }
    var lastApprovedPeerHost by remember { mutableStateOf<String?>(null) }

    // Keep the QR creator's send target in sync with the peer authenticated by
    // the foreground service. The scanner already fills these fields from the QR.
    LaunchedEffect(state.approvedPeerHost) {
        val approvedHost = state.approvedPeerHost?.takeIf { it.isNotBlank() }
        if (approvedHost != null) {
            host = approvedHost
            lastApprovedPeerHost = approvedHost
        } else {
            lastApprovedPeerHost?.let { previous ->
                if (host == previous) host = ""
            }
            lastApprovedPeerHost = null
        }
    }
    LaunchedEffect(state.pairingSecret, state.receiving) {
        if (state.pairingSecret.isNotBlank()) secret = state.pairingSecret
        else if (!state.receiving) secret = ""
    }

    // Storage access is optional and is requested only after the receive service
    // has started. Denial never blocks pairing or transfer: the verified app-owned
    // MalekPlayer directory remains available on every supported Android version.
    var legacyStoragePermissionAsked by remember { mutableStateOf(false) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "سيستمر الاقتران والاستقبال؛ ستحفظ الملفات داخل مجلد MalekPlayer الخاص بالتطبيق بدلاً من مجلد التنزيلات العام.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    LaunchedEffect(state.receiving) {
        if (state.receiving &&
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            !legacyStoragePermissionAsked &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePermissionAsked = true
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Independent on-device QR scanner: does not require Google Play Services scanner modules.
    var pendingWifiDirectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingWifiDirectAction
        pendingWifiDirectAction = null
        if (!grants.values.all { it }) {
            Toast.makeText(
                context,
                "لم يُمنح إذن Wi-Fi Direct؛ لن نوقف الاقتران. سنواصل تشغيل الاستقبال ونستخدم Wi-Fi العادية أو نقطة الاتصال كبديل متاح.",
                Toast.LENGTH_LONG
            ).show()
        }
        // Permission denial disables only the optional P2P route. The receiver
        // and authenticated LAN/QR pairing must still start.
        action?.invoke()
    }
    val requestWifiDirectAction: (() -> Unit) -> Unit = { action ->
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingWifiDirectAction = action
            wifiDirectPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun acceptPairing(pairing: UltraShareQrCode.PairingDetails) {
        secret = pairing.pairingSecret
        host = pairing.host
        // QR v3 triggers an exact DNS-SD invitation lookup and P2P negotiation.
        // The callback fires only when the direct route is ready or WLAN fallback
        // has been selected, so files never race ahead of connection setup.
        viewModel.connectInvitation(pairing) { resolvedHost ->
            host = resolvedHost
            val queued = pendingMediaUris
            if (queued.isNotEmpty() &&
                viewModel.sendBatch(queued, resolvedHost, pairing.pairingSecret)
            ) {
                pendingMediaUris = emptyList()
            }
        }
    }

    // Plain LAN QR uses the current network and needs no radio permission.
    // P2P invitations and Android 10+ known-SSID hotspot joins use Wi-Fi APIs
    // that require the matching location/nearby-device runtime permission.
    fun acceptPairingWithRequiredPermissions(pairing: UltraShareQrCode.PairingDetails) {
        val needsWifiPermission = !pairing.invitationId.isNullOrBlank() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !pairing.hotspotSsid.isNullOrBlank() &&
                !pairing.hotspotPassword.isNullOrBlank())
        if (needsWifiPermission) {
            requestWifiDirectAction { acceptPairing(pairing) }
        } else {
            acceptPairing(pairing)
        }
    }

    // Render the latched payload instead of deriving it from volatile service flags.
    val qrPayload = state.receiverQrPayload.takeIf { state.receiving }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { payload -> runCatching { UltraShareQrCode.createBitmap(payload) }.getOrNull() }
    }

    fun persistReadGrant(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val alreadyPersisted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (alreadyPersisted) return true
        return runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
    }

    LaunchedEffect(initialUris) {
        val notPersisted = initialUris.distinct().count { !persistReadGrant(it) }
        if (notPersisted > 0) {
            Toast.makeText(
                context,
                "بعض الملفات المشتركة لا تسمح بحفظ إذن الوصول؛ قد تحتاج إلى اختيارها مجدداً إذا أُغلق التطبيق قبل إرسالها.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val notPersisted = uris.count { !persistReadGrant(it) }
            if (notPersisted > 0) {
                Toast.makeText(
                    context,
                    "تعذر حفظ إذن الوصول إلى بعض الملفات؛ أعد اختيارها إذا تعذر استئنافها لاحقاً.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingMediaUris = (pendingMediaUris + uris).distinct()
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.exportReceived(uri) }

    fun requestWifiDirectScan() {
        requestWifiDirectAction { viewModel.scanWifiDirectPeers() }
    }
    val scanQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (!raw.isNullOrBlank()) {
            runCatching { UltraShareQrCode.parsePayload(raw) }
                .onSuccess { pairing ->
                    acceptPairingWithRequiredPermissions(pairing)
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    val externalQr = runCatching { ExternalWifiDirectQr.parse(raw) }.getOrNull()
                    if (externalQr != null) {
                        Toast.makeText(
                            context,
                            "تم التعرف على رمز Zapya/Kuaiya، لكن بياناته لا تكفي لمصادقة MalekPlayer أو الانضمام التلقائي. استخدم رمز MalekPlayer للاتصال الآمن.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.ultra_share_scan_failed, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }
    fun pastePairingDetails() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val raw = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (raw.isNullOrBlank()) {
            Toast.makeText(context, R.string.ultra_share_clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            runCatching { UltraShareQrCode.parsePayload(raw) }
                .onSuccess { pairing ->
                    acceptPairingWithRequiredPermissions(pairing)
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_clipboard_invalid, Toast.LENGTH_LONG).show()
                }
        }
    }
    LaunchedEffect(state.wifiDirectHost) {
        state.wifiDirectHost?.takeIf { it.isNotBlank() }?.let { host = it }
    }
    fun scanQrWithCamera() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("وجّه الكاميرا نحو رمز QR الخاص بـ MalekPlayer")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanQrLauncher.launch(options)
    }

    val scanQrImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        UltraShareQrCode.parsePayload(
                            UltraShareQrCode.decodeImage(context.contentResolver, uri)
                        )
                    }
                }
                result.onSuccess { pairing ->
                    acceptPairingWithRequiredPermissions(pairing)
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.localizedMessage ?: context.getString(R.string.ultra_share_scan_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("ULTRA SHARE", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                        Text(stringResource(R.string.ultra_share_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold)
                        Text(stringResource(R.string.ultra_share_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(34.dp))
                }
                val connectionLabel = when {
                    state.sending && state.receiving -> "يرسل ويستقبل في الوقت نفسه"
                    state.pairedSession -> "متصل؛ الإرسال والاستقبال متاحان"
                    state.sending -> "جارٍ إرسال الوسائط"
                    state.receiving -> "الهاتف جاهز للاستقبال"
                    state.scanning -> "البحث عن الأجهزة القريبة"
                    host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret) -> "بيانات الاقتران جاهزة"
                    else -> "بانتظار ربط جهاز"
                }
                val connectionColor = when {
                    state.sending || state.receiving || state.pairedSession -> Color(0xFF65D6A2)
                    state.scanning -> Color(0xFFFFC66D)
                    else -> MalekPlayColors.PurpleBright
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).background(connectionColor, androidx.compose.foundation.shape.CircleShape))
                    Text(connectionLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                    Text(when { state.sending && state.receiving -> "FULL DUPLEX"; state.pairedSession -> "PAIRED"; state.sending -> "LIVE"; state.receiving -> "ONLINE"; state.scanning -> "SCAN"; else -> "READY" }, color = connectionColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(11.dp)) {
                            Text("الإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.sending) "نشط الآن" else "جاهز", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(11.dp)) {
                            Text("الاستقبال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.receiveProgress != null) "يستقبل الآن" else if (state.receiving) "جاهز" else "غير نشط", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(11.dp)) {
                            Text("الملفات", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            Text("${pendingMediaUris.size}", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        // Zapya-inspired connection landing: two primary actions and quick nearby methods.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { if (!state.receiving) requestWifiDirectAction { viewModel.startReceiving() } },
                modifier = Modifier.weight(1f).height(94.dp),
                enabled = !state.sending && !state.receiving
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("إنشاء مجموعة", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("استقبال الملفات", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = ::scanQrWithCamera,
                modifier = Modifier.weight(1f).height(94.dp),
                enabled = !state.sending && !state.receiving
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("الانضمام", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("مسح رمز QR", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { requestWifiDirectAction { viewModel.scanNearbyGroups() } },
                modifier = Modifier.weight(1f),
                enabled = !state.sending && !state.receiving && !state.scanning
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Text("الأجهزة القريبة", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = ::requestWifiDirectScan,
                modifier = Modifier.weight(1f),
                enabled = !state.sending && !state.scanning
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Text("Wi-Fi Direct", style = MaterialTheme.typography.labelMedium)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_receive), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.ultra_share_receive_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (state.receiving) {
                    Text(stringResource(R.string.ultra_share_receiver_address), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    if (state.localAddresses.isEmpty()) {
                        Text(
                            if (state.invitationId != null) "عنوان Wi-Fi Direct قيد التهيئة؛ يمكن مسح QR للاتصال التلقائي."
                            else stringResource(R.string.ultra_share_no_address),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.localAddresses.forEach { address ->
                            Text(address, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = { UltraShareForegroundService.refreshLocalAddresses(context) }
                        ) {
                            Text("تحديث عناوين الشبكة")
                        }
                        Text(
                            stringResource(R.string.ultra_share_port, UltraShareControlChannel.DEFAULT_PORT),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (state.pairedSession) {
                        Text(
                            state.status ?: "الاتصال الآمن نشط؛ الإرسال والاستقبال متاحان.",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "يمكن للأجهزة الأخرى مسح رمز هذه المجموعة للانضمام؛ سيبقى رمز الاقتران نفسه صالحاً لهذه الجلسة.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(stringResource(R.string.ultra_share_pairing_code), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(state.pairingSecret.chunked(3).joinToString(" "), color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ultra_share_code_warning), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (state.receiving && qrBitmap == null) {
                        Text(
                            "جارٍ تجهيز رمز QR الآمن عبر قناة التحكم. جاهزية نقل الملفات تُعالج بصورة مستقلة؛ لا تمسح رمزاً قديماً أو فارغاً.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    qrBitmap?.let { bitmap ->
                        val activeQrPayload = qrPayload ?: return@let
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.ultra_share_qr_description),
                            modifier = Modifier
                                .size(160.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Text(
                            stringResource(R.string.ultra_share_qr_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("MalekPlayer Ultra Share", activeQrPayload))
                                Toast.makeText(context, R.string.ultra_share_pairing_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Text(stringResource(R.string.ultra_share_copy_pairing_link), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Button(
                    onClick = {
                        if (state.receiving) viewModel.stopReceiving()
                        else requestWifiDirectAction { viewModel.startReceiving() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending
                ) {
                    if (state.receiving) Text(stringResource(R.string.ultra_share_stop_receiving))
                    else Text(stringResource(R.string.ultra_share_start_receiving))
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_send), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("video/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending
                    ) { Text("فيديوهات") }
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending
                    ) { Text("موسيقى") }
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("video/*", "audio/*", "image/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending
                    ) { Text("وسائط") }
                }
                OutlinedButton(
                    onClick = { requestWifiDirectAction { viewModel.scanNearbyGroups() } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving && !state.scanning
                ) {
                    if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text(stringResource(if (state.scanning) R.string.ultra_share_searching else R.string.ultra_share_find_devices), modifier = Modifier.padding(start = 8.dp))
                }
                state.discoveredPeers.forEach { peer ->
                    TextButton(onClick = { host = peer.host }) {
                        Text(
                            stringResource(R.string.ultra_share_peer_select, peer.name, peer.host),
                            color = MalekPlayColors.PurpleBright
                        )
                    }
                }
                OutlinedButton(
                    onClick = ::requestWifiDirectScan,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.scanning
                ) {
                    Text(if (state.scanning) "جارٍ البحث عبر Wi-Fi Direct..." else "البحث عبر Wi-Fi Direct")
                }
                state.wifiDirectPeers.forEach { peer ->
                    TextButton(
                        onClick = { viewModel.connectWifiDirectPeer(peer) },
                        enabled = !state.sending
                    ) {
                        Text(
                            "Wi-Fi Direct • " + peer.name + " • " + peer.status,
                            color = MalekPlayColors.PurpleBright
                        )
                    }
                }
                Text(
                    "فعّل Wi-Fi Direct على الجهازين. يفضّل MalekPlayer جعل جهاز الاستقبال مالك مجموعة الاتصال.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "بديل بلا راوتر: فعّل نقطة الاتصال على جهاز الاستقبال، ثم صِل الجهاز الآخر بها وابدأ الاستقبال.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ultra_share_host)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.filter { char -> char in '0'..'9' }.take(9) },
                    modifier = Modifier.widthIn(max = 220.dp),
                    label = { Text("رمز الاقتران (9 أرقام)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedButton(
                    onClick = ::scanQrWithCamera,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving
                ) {
                    Icon(Icons.Filled.QrCode, contentDescription = null)
                    Text("مسح QR بالكاميرا", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { scanQrImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving
                ) {
                    Icon(Icons.Filled.QrCode, contentDescription = null)
                    Text(stringResource(R.string.ultra_share_scan_qr), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = ::pastePairingDetails,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.ultra_share_paste_pairing), modifier = Modifier.padding(start = 8.dp))
                }
                if (pendingMediaUris.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MalekPlayColors.PurpleDeep.copy(alpha = 0.22f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("قائمة الإرسال", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${pendingMediaUris.size} ملف جاهز للإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { pendingMediaUris = emptyList() }, enabled = !state.sending) { Text("إفراغ") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickFiles.launch(arrayOf("video/*", "audio/*", "image/*")) }, modifier = Modifier.weight(1f), enabled = !state.sending) {
                                    Text("إضافة وسائط")
                                }
                                Button(
                                    onClick = {
                                        val manualHost = host.trim()
                                        val manualSecret = secret.trim()
                                        val manualPairing = UltraShareQrCode.PairingDetails(
                                            host = manualHost,
                                            port = UltraShareControlChannel.DEFAULT_PORT,
                                            pairingSecret = manualSecret,
                                            hosts = listOf(manualHost)
                                        )
                                        viewModel.connectInvitation(manualPairing) { resolvedHost ->
                                            if (viewModel.sendBatch(pendingMediaUris, resolvedHost, manualSecret)) {
                                                pendingMediaUris = emptyList()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.sending && host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                    Text("إرسال الآن", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            if (host.isBlank() || !UltraShareCore.isValidPairingSecret(secret)) {
                                Text("أكمل ربط الجهاز أولًا، ثم اضغط إرسال الآن.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (pendingMediaUris.isEmpty()) {
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("video/*", "audio/*", "image/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Text("اختيار وسائط للإرسال", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (state.queueTotal > 0) {
                    Text(
                        "طابور الإرسال: " + state.queueCompleted + " / " + state.queueTotal +
                            (state.queueCurrentName?.let { " • " + it } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (state.queueTotalBytes > 0L) {
                        val overallFraction = (state.queueTransferredBytes.toDouble() / state.queueTotalBytes.toDouble())
                            .toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { overallFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "الإجمالي: " + formatTransferBytes(state.queueTransferredBytes) +
                                " / " + formatTransferBytes(state.queueTotalBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (state.queueEntries.isNotEmpty()) {
                    val completedCount = state.queueEntries.count { it.status == UltraShareQueueStore.STATUS_COMPLETED }
                    val failedCount = state.queueEntries.count { it.status == UltraShareQueueStore.STATUS_FAILED }
                    val pendingCount = state.queueEntries.count {
                        it.status == UltraShareQueueStore.STATUS_PENDING ||
                            it.status == UltraShareQueueStore.STATUS_SENDING
                    }
                    val skippedCount = state.queueEntries.count { it.status == UltraShareQueueStore.STATUS_SKIPPED }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("سجل عمليات النقل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(
                                "اكتمل $completedCount" to Color(0xFF65D6A2),
                                "فشل $failedCount" to MaterialTheme.colorScheme.error,
                                "معلّق $pendingCount" to MalekPlayColors.PurpleBright,
                                "متخطّى $skippedCount" to MaterialTheme.colorScheme.onSurfaceVariant
                            ).forEach { (label, tint) ->
                                Text(
                                    label,
                                    color = tint,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        state.queueEntries.take(12).forEach { entry ->
                            val label = when (entry.status) {
                                UltraShareQueueStore.STATUS_COMPLETED -> "اكتمل"
                                UltraShareQueueStore.STATUS_SENDING -> "جارٍ الإرسال"
                                UltraShareQueueStore.STATUS_FAILED -> "فشل — قابل لإعادة المحاولة"
                                UltraShareQueueStore.STATUS_SKIPPED -> "تم التخطي"
                                else -> "بانتظار الإرسال"
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(entry.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(label, color = if (entry.status == UltraShareQueueStore.STATUS_FAILED) MaterialTheme.colorScheme.error else if (entry.status == UltraShareQueueStore.STATUS_COMPLETED) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                        entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
                                            Text(
                                                reason,
                                                color = if (entry.status == UltraShareQueueStore.STATUS_FAILED) MaterialTheme.colorScheme.error.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                if (entry.status == UltraShareQueueStore.STATUS_PENDING ||
                                    entry.status == UltraShareQueueStore.STATUS_FAILED
                                ) {
                                    TextButton(
                                        onClick = { viewModel.skipQueuedFile(entry) },
                                        enabled = true
                                    ) {
                                        Text(if (entry.status == UltraShareQueueStore.STATUS_FAILED) "إزالة" else "تخطّي")
                                    }
                                }
                                    if (entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                        TextButton(onClick = { viewModel.retryFile(entry, host.trim(), secret.trim()) }, enabled = !state.sending && host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret)) {
                                            Text("إعادة المحاولة")
                                        }
                                    }
                                }
                            }
                        }
                        if (state.queueEntries.size > 12) {
                            Text("و " + (state.queueEntries.size - 12) + " ملفات أخرى", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!state.sending && state.queueEntries.any {
                            it.status == UltraShareQueueStore.STATUS_PENDING ||
                                it.status == UltraShareQueueStore.STATUS_FAILED
                        }) {
                        OutlinedButton(
                            onClick = { viewModel.resumePendingBatch(host.trim(), secret.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret)
                        ) { Text("استئناف الملفات المتبقية") }
                    }
                    if (!state.sending) {
                        TextButton(onClick = viewModel::clearQueue) { Text("مسح سجل الطابور") }
                    }
                }
                if (state.sending) {
                    if (state.queueTotal > 0) {
                        OutlinedButton(onClick = viewModel::pauseSending, modifier = Modifier.fillMaxWidth()) {
                            Text("إيقاف مؤقت للطابور")
                        }
                    }
                    OutlinedButton(onClick = viewModel::cancelSending, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ultra_share_cancel_send))
                    }
                }
            }
        }

        if (state.sending && state.receiving) {
            state.sendProgress?.let { progress ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("إرسال إلى الجهاز الآخر", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${(progress.fraction * 100).toInt()}%", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                        }
                        Text(progress.fileName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            formatTransferBytes(progress.transferredBytes) + " / " + formatTransferBytes(progress.totalBytes) +
                                " • " + formatTransferBytes(progress.bytesPerSecond) + "/ث",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            state.receiveProgress?.let { progress ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("استقبال من الجهاز الآخر", color = Color(0xFF65D6A2), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${(progress.fraction * 100).toInt()}%", color = Color(0xFF65D6A2), fontWeight = FontWeight.ExtraBold)
                        }
                        Text(progress.fileName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            formatTransferBytes(progress.transferredBytes) + " / " + formatTransferBytes(progress.totalBytes) +
                                " • " + formatTransferBytes(progress.bytesPerSecond) + "/ث",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        state.progress?.let { progress ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("نقل جارٍ الآن", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(progress.fileName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${(progress.fraction * 100).toInt()}%", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                    }
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.ultra_share_progress,
                            (progress.fraction * 100).toInt(),
                            formatTransferBytes(progress.transferredBytes),
                            formatTransferBytes(progress.totalBytes),
                            formatTransferBytes(progress.bytesPerSecond),
                            if (progress.bytesPerSecond > 0L) formatTransferEta(
                                ((progress.totalBytes - progress.transferredBytes).coerceAtLeast(0L) / progress.bytesPerSecond)
                            ) else stringResource(R.string.ultra_share_eta_calculating)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        state.receivedFile?.let { received ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF66D99A))
                        Text(stringResource(R.string.ultra_share_receive_complete), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(received.fileName, color = MaterialTheme.colorScheme.onSurface)
                    Text(formatTransferBytes(received.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (received.publishedUri != null) {
                        Text(
                            "حُفظ تلقائياً في التنزيلات / MalekPlayer",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(received.publishedUri, received.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        Toast.makeText(context, "لا يوجد تطبيق مناسب لفتح هذا الملف.", Toast.LENGTH_LONG).show()
                                    }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("فتح الملف المحفوظ")
                        }
                    }
                    OutlinedButton(onClick = { exportFile.launch(received.fileName) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(
                            if (received.publishedUri != null) "حفظ نسخة في مكان آخر"
                            else stringResource(R.string.ultra_share_save_to_device),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        if (state.receivedHistory.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("سجل الملفات المستلمة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            Text("${state.receivedHistory.size} ملف محفوظ في السجل", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = viewModel::clearReceivedHistory, enabled = !state.receiving) { Text("مسح السجل") }
                    }
                    state.receivedHistory.take(10).forEach { entry ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.fileName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatTransferBytes(entry.sizeBytes) + " • " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.receivedAtEpochMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (entry.isAvailable) "متاح" else "غير موجود", color = if (entry.isAvailable) Color(0xFF65D6A2) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (state.receivedHistory.size > 10) {
                        Text("و ${state.receivedHistory.size - 10} ملفات أخرى", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                val report = UltraShareControlChannel.diagnosticReport()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MalekPlayer connection diagnostics", report))
                Toast.makeText(context, "تم نسخ تقرير تشخيص الاتصال. أرسله هنا.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text("نسخ تقرير تشخيص الاتصال", modifier = Modifier.padding(start = 8.dp))
        }
        state.status?.let { Text(it, color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::clearMessages) { Text(stringResource(R.string.common_dismiss)) }
        }
        Text(
            stringResource(R.string.ultra_share_lan_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        UltraShareHelpCard()
        Spacer(Modifier.height(12.dp))
    }
}

private fun formatTransferEta(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return when {
        hours > 0 -> String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
        minutes > 0 -> String.format(java.util.Locale.US, "%d:%02d", minutes, remainingSeconds)
        else -> "${remainingSeconds}s"
    }
}
