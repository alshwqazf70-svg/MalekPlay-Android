package com.malekplay.ui.network

import android.content.Context
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.network.UltraShareTransport
import com.malekplay.network.UltraShareControlChannel
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareNegotiation
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareReceivedEntry
import com.malekplay.network.UltraShareReceivedHistoryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareApprovedSendTarget
import com.malekplay.network.UltraSharePeerDeliveryStore
import com.malekplay.network.UltraShareWifiDirect
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UltraShareUiState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val sending: Boolean = false,
    val scanning: Boolean = false,
    val discoveredPeers: List<UltraShareDiscovery.Peer> = emptyList(),
    val progress: UltraShareTransport.Progress? = null,
    val sendProgress: UltraShareTransport.Progress? = null,
    val receiveProgress: UltraShareTransport.Progress? = null,
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
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
    val hotspotSsid: String? = null,
    val hotspotPassword: String? = null,
    val hotspotStarting: Boolean = false,
    val hotspotActive: Boolean = false,
    val pendingConnection: UltraShareControlChannel.Request? = null,
    val approvedPeerHost: String? = null,
    val requestingConnection: Boolean = false,
    val activePeers: List<com.malekplay.network.UltraSharePeerSession> = emptyList(),
    val peerTransfers: List<com.malekplay.network.UltraSharePeerTransfer> = emptyList(),
    val approvedSenderPeerCount: Int = 0,
    val approvedSenderPeerHosts: List<String> = emptyList()
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null
    private companion object {
        const val PEER_SIGHTING_TTL_MS = 12_000L
    }

    init {
        viewModelScope.launch {
            if (!UltraShareForegroundService.state.value.sending) {
                UltraShareQueueStore.recoverInterrupted(context)
                UltraSharePeerDeliveryStore.recoverInterrupted(context)
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
                    sending = serviceState.sending,
                    progress = serviceState.progress,
                    sendProgress = serviceState.sendProgress,
                    receiveProgress = serviceState.receiveProgress,
                    receivedFile = serviceState.receivedFile ?: current.receivedFile,
                    status = serviceState.status ?: current.status,
                    error = serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes,
                    hotspotSsid = serviceState.hotspotSsid,
                    hotspotPassword = serviceState.hotspotPassword,
                    hotspotStarting = serviceState.hotspotStarting,
                    hotspotActive = serviceState.hotspotActive,
                    pendingConnection = serviceState.pendingConnection,
                    activePeers = serviceState.activePeers,
                    peerTransfers = serviceState.peerTransfers,
                    approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                    approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                    approvedPeerHost = current.approvedPeerHost?.takeIf { approved ->
                        serviceState.activePeers.any { it.host == approved }
                    }
                )
            }
        }
    }

    fun startReceiving() {
        if (_state.value.receiving) return
        val secret = UltraShareTransport.createPairingSecret()
        _state.value = _state.value.copy(
            pairingSecret = secret,
            receiving = true,
            progress = null,
            error = null,
            status = context.getString(R.string.ultra_share_waiting)
        )
        runCatching { UltraShareForegroundService.startReceiving(context, secret) }
            .onFailure {
                _state.value = _state.value.copy(
                    receiving = false,
                    pairingSecret = "",
                    error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
                )
            }
    }

    fun requestConnection(
        host: String,
        secret: String,
        candidates: List<com.malekplay.network.UltraShareNegotiation.Candidate> = emptyList()
    ) {
        if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(error = "أدخل عنوان الجهاز ورمز الاقتران الصحيح أولاً.")
            return
        }
        val discoveredCandidates = candidates.ifEmpty {
            _state.value.discoveredPeers.firstOrNull { peer ->
                peer.host == host.trim() || peer.candidates.any { it.host == host.trim() }
            }?.candidates.orEmpty()
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                requestingConnection = true,
                error = null,
                status = "تم إرسال طلب الاتصال؛ نختبر مسارات الجهاز وننتظر موافقته..."
            )
            try {
                val orderedRoutes = UltraShareNegotiation.orderedCandidates(discoveredCandidates)
                // Try the selected address first, then every other verified interface
                // belonging to the same discovered peer. A rejected approval is final;
                // only transport failures move to another route.
                val routeHosts = (listOf(host.trim()) + orderedRoutes.map { it.host })
                    .distinct()
                var acceptedHost: String? = null
                var rejected = false
                var lastFailure: Exception? = null
                for (routeHost in routeHosts) {
                    try {
                        val decision = UltraShareControlChannel.request(
                            host = routeHost,
                            pairingSecret = secret.trim(),
                            peerName = android.os.Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                            network = UltraShareNetworkRouting.findNetworkForHost(context, routeHost),
                            peerId = UltraShareDiscovery.localPeerId(context),
                            onSessionClosed = { disconnectedHost ->
                                UltraShareForegroundService.disconnectApprovedPeer(disconnectedHost)
                                forgetApprovedSenderTarget(disconnectedHost)
                            }
                        )
                        if (decision == UltraShareControlChannel.Decision.ACCEPTED) {
                            check(UltraShareControlChannel.isSessionActive(routeHost)) {
                                "انقطعت قناة التحكم قبل اكتمال الاقتران."
                            }
                            acceptedHost = routeHost
                            break
                        }
                        rejected = true
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        lastFailure = failure
                    }
                }
                if (acceptedHost != null) {
                    val connectedHost = acceptedHost
                    val peerName = UltraShareControlChannel.sessionPeerName(connectedHost)
                        ?: "جهاز MalekPlay المستقبل"
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val approvedTarget = UltraShareApprovedSendTarget(
                        sessionId = sessionId,
                        host = connectedHost,
                        secret = secret.trim(),
                        candidates = orderedRoutes
                    )
                    UltraShareForegroundService.registerApprovedSendTarget(approvedTarget)
                    UltraShareForegroundService.registerApprovedPeer(
                        name = peerName,
                        host = connectedHost,
                        role = "أرسل طلب الاتصال",
                        routeCandidates = orderedRoutes,
                        sessionId = sessionId
                    )
                    _state.value = _state.value.copy(
                        approvedPeerHost = connectedHost,
                        approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                        approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                        status = if (connectedHost != host.trim())
                            "تم الاقتران عبر مسار بديل بنجاح. جُهزت ${orderedRoutes.size} مسارات احتياطية للنقل."
                        else if (orderedRoutes.size > 1)
                            "وافق الجهاز الآخر على الاتصال. تم تجهيز ${orderedRoutes.size} مسارات للنقل."
                        else "وافق الجهاز الآخر على الاتصال. يمكنك الآن بدء النقل المشفر.",
                        requestingConnection = false,
                        error = null
                    )
                } else {
                    val previousTarget = UltraShareForegroundService.approvedSendTargets().lastOrNull()
                    _state.value = _state.value.copy(
                        approvedPeerHost = previousTarget?.host,
                        approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                        approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                        status = if (rejected) "رفض الجهاز الآخر طلب الاتصال."
                            else "تعذر الوصول إلى الجهاز عبر المسارات المكتشفة.",
                        requestingConnection = false,
                        error = if (rejected) null else lastFailure?.localizedMessage
                            ?: "تعذر إتمام طلب الاتصال."
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(requestingConnection = false)
                throw cancelled
            } catch (failure: Exception) {
                val previousTarget = UltraShareForegroundService.approvedSendTargets().lastOrNull()
                _state.value = _state.value.copy(
                    approvedPeerHost = previousTarget?.host,
                    approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
                    approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host },
                    requestingConnection = false,
                    error = failure.localizedMessage ?: "تعذر إتمام طلب الاتصال."
                )
            }
        }
    }

    private fun forgetApprovedSenderTarget(host: String) {
        UltraShareForegroundService.removeApprovedSendTarget(host)
        val next = UltraShareForegroundService.approvedSendTargets().lastOrNull()
        _state.value = _state.value.copy(
            approvedPeerHost = next?.host,
            approvedSenderPeerCount = UltraShareForegroundService.approvedSendTargets().size,
            approvedSenderPeerHosts = UltraShareForegroundService.approvedSendTargets().map { it.host }
        )
    }

    fun disconnectPeer(host: String) {
        UltraShareForegroundService.disconnectApprovedPeer(host)
        forgetApprovedSenderTarget(host)
    }

    /** Disconnect only the currently selected sender peer; keep other approved peers available. */
    fun clearApprovedConnection() {
        val currentHost = _state.value.approvedPeerHost ?: return
        UltraShareForegroundService.disconnectApprovedPeer(currentHost)
        forgetApprovedSenderTarget(currentHost)
    }

    fun acceptConnection() {
        UltraShareForegroundService.acceptConnection(context)
    }

    fun rejectConnection() {
        UltraShareForegroundService.rejectConnection(context)
    }

    fun startHotspot() {
        UltraShareForegroundService.startHotspot(context)
    }

    fun stopHotspot() {
        UltraShareForegroundService.stopHotspot(context)
    }

    fun stopReceiving() {
        UltraShareForegroundService.stopReceiving(context)
        _state.value = _state.value.copy(
            receiving = false,
            pairingSecret = "",
            localAddresses = emptyList(),
            progress = if (_state.value.sending) UltraShareForegroundService.state.value.sendProgress else null,
            status = context.getString(R.string.ultra_share_stopped)
        )
    }

    fun scanNearbyDevices() {
        if (scanJob != null) {
            scanJob?.cancel()
            _state.value = _state.value.copy(scanning = false, status = "تم إيقاف البحث عن الأجهزة.")
            return
        }
        _state.value = _state.value.copy(
            scanning = true, discoveredPeers = emptyList(), error = null,
            status = "جارٍ البحث المستمر عن أجهزة MalekPlay على الشبكة..."
        )
        scanJob = viewModelScope.launch {
            // Keep the latest sighting time per stable peer identity. A device that
            // leaves Wi-Fi must disappear from the radar instead of remaining selectable.
            val found = LinkedHashMap<String, Pair<UltraShareDiscovery.Peer, Long>>()
            try {
                // Short repeated discovery windows make newly-started receivers appear
                // without forcing the user to restart the scan manually.
                while (true) {
                    val peers = UltraShareDiscovery.discoverPeers(scanWindowMs = 1_500L, context = context)
                    val now = System.currentTimeMillis()
                    peers.forEach { peer ->
                        found[UltraShareDiscovery.identityKey(peer)] = peer to now
                    }
                    found.entries.removeAll { (_, sighting) ->
                        !UltraShareDiscovery.isSightingFresh(sighting.second, now, PEER_SIGHTING_TTL_MS)
                    }
                    val visible = found.values.map { it.first }
                    _state.value = _state.value.copy(
                        discoveredPeers = visible,
                        status = if (visible.isEmpty()) "نبحث باستمرار عن أجهزة MalekPlay القريبة..."
                        else "تم العثور على ${visible.size} جهاز؛ البحث مستمر."
                    )
                    delay(350)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    discoveredPeers = emptyList(),
                    error = failure.localizedMessage ?: context.getString(R.string.ultra_share_discovery_unavailable)
                )
            } finally {
                scanJob = null
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }

    fun scanWifiDirectPeers() {
        try {
            val session = wifiDirectSession ?: UltraShareWifiDirect.open(
                context = context,
                onPeers = { peers ->
                    _state.value = _state.value.copy(
                        wifiDirectPeers = peers,
                        scanning = false,
                        status = if (peers.isEmpty()) "لم يتم العثور على أجهزة Wi-Fi Direct."
                        else "تم العثور على " + peers.size + " أجهزة عبر Wi-Fi Direct."
                    )
                },
                onConnected = { remoteHost, isGroupOwner ->
                    if (isGroupOwner) {
                        val receiving = UltraShareForegroundService.state.value.receiving
                        if (receiving) {
                            UltraShareForegroundService.refreshLocalAddresses(context)
                            _state.value = _state.value.copy(
                                status = "اتصل Wi-Fi Direct بنجاح؛ هذا الهاتف جاهز للاستقبال."
                            )
                        } else {
                            _state.value = _state.value.copy(
                                status = "أصبح هذا الهاتف مالك مجموعة Wi-Fi Direct. أعد الاتصال مع جعل جهاز الاستقبال هو مالك المجموعة."
                            )
                        }
                    } else if (!remoteHost.isNullOrBlank()) {
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct. أدخل رمز الاقتران الظاهر على جهاز الاستقبال."
                        )
                    }
                },
                onError = { message ->
                    _state.value = _state.value.copy(
                        scanning = false,
                        wifiDirectHost = null,
                        error = message
                    )
                }
            ).also { wifiDirectSession = it }
            _state.value = _state.value.copy(
                scanning = true,
                wifiDirectHost = null,
                error = null,
                status = "جارٍ البحث عن أجهزة Wi-Fi Direct..."
            )
            session.discover()
            viewModelScope.launch {
                delay(15_000)
                if (_state.value.scanning) {
                    _state.value = _state.value.copy(
                        scanning = false,
                        status = if (_state.value.wifiDirectPeers.isEmpty())
                            "انتهى البحث عبر Wi-Fi Direct دون العثور على أجهزة."
                        else _state.value.status
                    )
                }
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
        runCatching {
            session.connect(
                peer.deviceAddress,
                preferThisDeviceAsGroupOwner = UltraShareForegroundService.state.value.receiving
            )
        }
            .onFailure {
                _state.value = _state.value.copy(error = it.localizedMessage ?: "تعذر الاتصال بالجهاز.")
            }
    }

    fun send(uri: Uri, host: String, secret: String) {
        if (_state.value.sending) return
        runCatching {
            UltraShareForegroundService.startSending(context, uri, host, secret)
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                error = null,
                status = context.getString(R.string.ultra_share_sending)
            )
        }.onFailure {
            _state.value = _state.value.copy(
                sending = false,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun sendBatch(uris: List<Uri>, host: String): Boolean {
        val target = UltraShareForegroundService.approvedSendTarget(host.trim())
        if (uris.isEmpty() || _state.value.sending || target == null) return false
        return runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(context, uris, listOf(target))
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

    fun sendBatchToApprovedPeers(uris: List<Uri>): Boolean {
        val targets = UltraShareForegroundService.approvedSendTargets()
        if (uris.isEmpty() || _state.value.sending || targets.isEmpty()) return false
        return runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(context, uris, targets)
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                queueCompleted = 0,
                queueTotal = uris.distinctBy { it.toString() }.size,
                queueCurrentName = null,
                error = null,
                status = "بدأ الإرسال إلى " + targets.size + " أجهزة مقترنة"
            )
            true
        }.getOrElse { failure ->
            _state.value = _state.value.copy(
                sending = false,
                error = failure.localizedMessage ?: "تعذر بدء الإرسال الجماعي."
            )
            false
        }
    }

    fun resumePendingBatch(host: String) {
        val target = UltraShareForegroundService.approvedSendTarget(host.trim()) ?: return
        if (_state.value.sending) return
        viewModelScope.launch {
            val peerEntries = UltraSharePeerDeliveryStore.observe(context).first()
                .filter { it.peerSessionId == target.sessionId }
            val pendingUris = if (peerEntries.isNotEmpty()) {
                peerEntries.filter {
                    it.status == UltraSharePeerDeliveryStore.STATUS_PENDING ||
                        it.status == UltraSharePeerDeliveryStore.STATUS_FAILED
                }.map { it.parsedUri }
            } else {
                UltraShareQueueStore.pending(context).map { it.parsedUri }
            }
            if (pendingUris.isEmpty()) {
                _state.value = _state.value.copy(status = "لا توجد ملفات متبقية لهذا الجهاز؛ تم الحفاظ على الملفات التي استلمها سابقاً.")
            } else {
                runCatching {
                    UltraShareForegroundService.startSendingBatchToPeers(context, pendingUris, listOf(target))
                    _state.value = _state.value.copy(
                        sending = true,
                        queueCompleted = 0,
                        queueTotal = pendingUris.size,
                        error = null,
                        status = "استئناف الملفات المتبقية لهذا الجهاز فقط"
                    )
                }.onFailure { failure ->
                    _state.value = _state.value.copy(error = failure.localizedMessage ?: "تعذر استئناف النقل.")
                }
            }
        }
    }

    fun retryFile(entry: UltraShareQueueEntry, host: String) {
        val target = UltraShareForegroundService.approvedSendTarget(host.trim())
        if (_state.value.sending || target == null ||
            entry.status != UltraShareQueueStore.STATUS_FAILED
        ) return
        runCatching {
            UltraShareForegroundService.startSendingBatchToPeers(
                context, listOf(entry.parsedUri), listOf(target)
            )
            _state.value = _state.value.copy(
                sending = true,
                progress = null,
                error = null,
                status = "إعادة محاولة إرسال " + entry.displayName + " إلى هذا الجهاز فقط"
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
                        output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) }
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
        UltraShareForegroundService.cancelSending(context)
        _state.value = _state.value.copy(
            progress = if (_state.value.receiving) UltraShareForegroundService.state.value.receiveProgress else null,
            status = context.getString(R.string.ultra_share_send_cancelled)
        )
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, status = null)
    }

    override fun onCleared() {
        scanJob?.cancel()
        wifiDirectSession?.close()
        wifiDirectSession = null
        // Transfer jobs belong to the foreground service and deliberately outlive this ViewModel.
        super.onCleared()
    }
}
