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
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.draw.scale
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
import com.malekplay.network.UltraShareTransport
import com.malekplay.network.UltraShareBatchTransport
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareNegotiation
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareReceivedEntry
import com.malekplay.network.UltraShareReceivedHistoryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareWifiDirect
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
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
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
    val peerTransfers: List<com.malekplay.network.UltraSharePeerTransfer> = emptyList()
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
    private var approvedPeerCandidates: List<com.malekplay.network.UltraShareNegotiation.Candidate> = emptyList()

    init {
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
            approvedPeerCandidates = emptyList()
            _state.value = _state.value.copy(approvedPeerHost = null, requestingConnection = true, error = null, status = "تم إرسال طلب الاتصال؛ بانتظار موافقة الجهاز الآخر...")
            try {
                val decision = UltraShareControlChannel.request(
                    host = host.trim(),
                    pairingSecret = secret.trim(),
                    peerName = android.os.Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                    network = UltraShareNetworkRouting.findNetworkForHost(context, host.trim()),
                    peerId = UltraShareDiscovery.localPeerId(context),
                    onSessionClosed = { disconnectedHost ->
                        UltraShareForegroundService.disconnectApprovedPeer(disconnectedHost)
                    }
                )
                if (decision == UltraShareControlChannel.Decision.ACCEPTED) {
                    UltraShareForegroundService.registerApprovedPeer(
                        name = UltraShareControlChannel.sessionPeerName(host.trim()) ?: "جهاز MalekPlay المستقبل",
                        host = host.trim(),
                        role = "أرسل طلب الاتصال"
                    )
                    approvedPeerCandidates = UltraShareNegotiation.orderedCandidates(discoveredCandidates)
                    _state.value = _state.value.copy(
                        approvedPeerHost = host.trim(),
                        status = if (approvedPeerCandidates.size > 1)
                            "وافق الجهاز الآخر على الاتصال. تم تجهيز ${approvedPeerCandidates.size} مسارات قابلة للتجربة."
                        else "وافق الجهاز الآخر على الاتصال. يمكنك الآن بدء النقل المشفر.",
                        requestingConnection = false,
                        error = null
                    )
                } else {
                    approvedPeerCandidates = emptyList()
                    _state.value = _state.value.copy(
                        approvedPeerHost = null,
                        status = "رفض الجهاز الآخر طلب الاتصال.",
                        requestingConnection = false,
                        error = null
                    )
                }
            } catch (failure: Exception) {
                approvedPeerCandidates = emptyList()
                _state.value = _state.value.copy(
                    approvedPeerHost = null,
                    requestingConnection = false,
                    error = failure.localizedMessage ?: "تعذر إتمام طلب الاتصال."
                )
            }
        }
    }

    fun disconnectPeer(host: String) {
        UltraShareForegroundService.disconnectApprovedPeer(host)
        if (_state.value.approvedPeerHost == host) clearApprovedConnection()
    }

    fun clearApprovedConnection() {
        _state.value.approvedPeerHost?.let { UltraShareForegroundService.disconnectApprovedPeer(it) }
        approvedPeerCandidates = emptyList()
        _state.value = _state.value.copy(approvedPeerHost = null)
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
            val found = LinkedHashMap<String, UltraShareDiscovery.Peer>()
            try {
                // Short repeated discovery windows make newly-started receivers appear
                // without forcing the user to restart the scan manually.
                while (true) {
                    val peers = UltraShareDiscovery.discoverPeers(scanWindowMs = 1_500L, context = context)
                    peers.forEach { peer -> found[UltraShareDiscovery.identityKey(peer)] = peer }
                    _state.value = _state.value.copy(
                        discoveredPeers = found.values.toList(),
                        status = if (found.isEmpty()) "نبحث باستمرار عن أجهزة MalekPlay القريبة..."
                        else "تم العثور على ${found.size} جهاز؛ البحث مستمر."
                    )
                    delay(350)
                }
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

    fun sendBatch(uris: List<Uri>, host: String, secret: String): Boolean {
        if (uris.isEmpty() || _state.value.sending ||
            host.isBlank() || _state.value.approvedPeerHost != host.trim() ||
            !UltraShareTransport.isValidPairingSecret(secret)
        ) return false
        return runCatching {
            UltraShareForegroundService.startSendingBatch(context, uris, host, secret, approvedPeerCandidates)
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
        if (host.isBlank() || _state.value.approvedPeerHost != host.trim() ||
            !UltraShareTransport.isValidPairingSecret(secret) || _state.value.sending
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
            host.isBlank() || _state.value.approvedPeerHost != host.trim() ||
            !UltraShareTransport.isValidPairingSecret(secret) ||
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
