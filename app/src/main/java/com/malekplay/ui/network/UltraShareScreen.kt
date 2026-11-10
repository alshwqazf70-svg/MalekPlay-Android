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

@Composable
fun UltraShareScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: UltraShareViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    fun isMediaUri(uri: Uri): Boolean = runCatching {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")
    }.getOrDefault(false)
    var pendingMediaUris by remember(initialUris) {
        mutableStateOf(initialUris.distinct().filter(::isMediaUri))
    }
    var showDeviceRoom by remember { mutableStateOf(true) }
    var activeMode by remember { mutableStateOf("send") } // Dedicated Zapya-inspired Send / Receive rooms.
    var peerSearch by remember { mutableStateOf("") }
    var showAdvancedPairing by remember { mutableStateOf(false) }
    val visibleDiscoveredPeers = remember(state.discoveredPeers, peerSearch) {
        state.discoveredPeers.filter { peer ->
            peer.name.contains(peerSearch, ignoreCase = true) || peer.host.contains(peerSearch, ignoreCase = true)
        }
    }
    val mediaMimeTypes = arrayOf("video/*", "audio/*", "image/*")

    // Make the phone discoverable and scan automatically when Ultra Share opens.
    // Pairing approval remains explicit on both phones.
    LaunchedEffect(Unit) {
        if (!state.receiving) viewModel.startReceiving()
        if (!state.scanning) viewModel.scanNearbyDevices()
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
                    viewModel.clearApprovedConnection()
                    host = pairing.host
                    secret = pairing.pairingSecret
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret)
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_clipboard_invalid, Toast.LENGTH_LONG).show()
                }
        }
    }
    val qrPayload = state.localAddresses.firstOrNull()?.takeIf { state.receiving }?.let { address ->
        UltraShareQrCode.createReceiverPayload(address, state.pairingSecret)
    }
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
        val allowedInitialUris = initialUris.distinct().filter(::isMediaUri)
        if (allowedInitialUris.size != initialUris.distinct().size) {
            Toast.makeText(context, "تجاهل MalekPlay أي عنصر ليس فيديو أو موسيقى أو صورة.", Toast.LENGTH_LONG).show()
        }
        val notPersisted = allowedInitialUris.count { !persistReadGrant(it) }
        if (notPersisted > 0) {
            Toast.makeText(
                context,
                "بعض الملفات المشتركة لا تسمح بحفظ إذن الوصول؛ قد تحتاج إلى اختيارها مجدداً إذا أُغلق التطبيق قبل إرسالها.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val mediaUris = uris.filter(::isMediaUri)
        if (mediaUris.size != uris.size) {
            Toast.makeText(context, "يسمح MalekPlay بمشاركة الفيديوهات والموسيقى والصور فقط.", Toast.LENGTH_LONG).show()
        }
        if (mediaUris.isNotEmpty()) {
            val notPersisted = mediaUris.count { !persistReadGrant(it) }
            if (notPersisted > 0) {
                Toast.makeText(
                    context,
                    "تعذر حفظ إذن الوصول إلى بعض الملفات؛ أعد اختيارها إذا تعذر استئنافها لاحقاً.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingMediaUris = (pendingMediaUris + mediaUris).distinct()
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.exportReceived(uri) }

    // Independent on-device QR scanner: does not require Google Play Services scanner modules.
    val scanQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (!raw.isNullOrBlank()) {
            runCatching { UltraShareQrCode.parsePayload(raw) }
                .onSuccess { pairing ->
                    viewModel.clearApprovedConnection()
                    host = pairing.host
                    secret = pairing.pairingSecret
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret)
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_scan_failed, Toast.LENGTH_LONG).show()
                }
        }
    }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.scanWifiDirectPeers()
        } else {
            Toast.makeText(context, "يلزم منح إذن الأجهزة القريبة لاستخدام Wi-Fi Direct.", Toast.LENGTH_LONG).show()
        }
    }
    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startHotspot()
        } else {
            Toast.makeText(context, "يلزم منح إذن الأجهزة القريبة لإنشاء شبكة MalekPlay المحلية.", Toast.LENGTH_LONG).show()
        }
    }
    fun requestLocalHotspot() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startHotspot()
        else hotspotPermissionLauncher.launch(missing.toTypedArray())
    }
    fun requestWifiDirectScan() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.scanWifiDirectPeers()
        else wifiDirectPermissionLauncher.launch(missing.toTypedArray())
    }
    LaunchedEffect(state.wifiDirectHost) {
        host = state.wifiDirectHost.orEmpty()
    }
    fun scanQrWithCamera() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("وجّه الكاميرا نحو رمز QR الخاص بـ MalekPlay")
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
                    viewModel.clearApprovedConnection()
                    host = pairing.host
                    secret = pairing.pairingSecret
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret)
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
                    state.sending -> "جارٍ إرسال الوسائط"
                    state.receiving -> "الهاتف جاهز للاستقبال"
                    state.scanning -> "البحث عن الأجهزة القريبة"
                    host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret) -> "بيانات الاقتران جاهزة"
                    else -> "بانتظار ربط جهاز"
                }
                val connectionColor = when {
                    state.sending || state.receiving -> Color(0xFF65D6A2)
                    state.scanning -> Color(0xFFFFC66D)
                    else -> MalekPlayColors.PurpleBright
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).background(connectionColor, androidx.compose.foundation.shape.CircleShape))
                    Text(connectionLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                    Text(when { state.sending -> "LIVE"; state.receiving -> "ONLINE"; state.scanning -> "SCAN"; else -> "READY" }, color = connectionColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
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
                            Text(if (state.receiving) "يستقبل الآن" else "متاح", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(11.dp)) {
                            Text("الوسائط", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            Text("${pendingMediaUris.size}", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        state.pendingConnection?.let { request ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("طلب اتصال وارد", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(request.peerName + " • " + request.peerAddress, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("لن يبدأ نقل الملفات قبل موافقتك.", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = viewModel::acceptConnection, modifier = Modifier.weight(1f)) { Text("قبول") }
                        OutlinedButton(onClick = viewModel::rejectConnection, modifier = Modifier.weight(1f)) { Text("رفض") }
                    }
                }
            }
        }

        // Zapya-inspired connection landing: two primary actions and quick nearby methods.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    activeMode = "receive"
                    if (!state.receiving) viewModel.startReceiving()
                },
                modifier = Modifier.weight(1f).height(94.dp),
                enabled = true
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(if (activeMode == "receive") "استقبال الوسائط ✓" else "استقبال الوسائط", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("ابدأ غرفة الاستقبال", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = {
                    activeMode = "send"
                    showDeviceRoom = true
                    if (!state.scanning) viewModel.scanNearbyDevices()
                    pickFiles.launch(mediaMimeTypes)
                },
                modifier = Modifier.weight(1f).height(94.dp),
                enabled = !state.sending
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(if (activeMode == "send") "إرسال الوسائط ✓" else "إرسال الوسائط", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("فيديو • موسيقى • صور", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(88.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MalekPlayColors.PurpleBright.copy(alpha = 0.42f),
                                    MalekPlayColors.PurpleDeep.copy(alpha = 0.18f)
                                )
                            ),
                            androidx.compose.foundation.shape.CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (activeMode == "send") Icons.Filled.Send else Icons.Filled.Download,
                        contentDescription = null,
                        tint = MalekPlayColors.PurpleBright,
                        modifier = Modifier.size(42.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        if (activeMode == "send") "غرفة إرسال الوسائط" else "غرفة استقبال الوسائط",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        when {
                            state.sending -> "جارٍ نقل الوسائط إلى الجهاز الآخر"
                            state.requestingConnection -> "طلب الاتصال مرسل — بانتظار موافقة الطرف الآخر"
                            state.approvedPeerHost != null -> "اتصال موثّق وجاهز لبدء النقل"
                            activeMode == "receive" && state.receiving -> "هاتفك ظاهر للأجهزة القريبة وجاهز للاستقبال"
                            activeMode == "receive" -> "ابدأ الاستقبال لعرض رمز QR وربط الهاتف الآخر"
                            state.scanning && state.discoveredPeers.isEmpty() -> "نبحث عن أجهزة MalekPlay القريبة..."
                            state.discoveredPeers.isNotEmpty() -> "عُثر على ${state.discoveredPeers.size} جهاز قريب"
                            else -> "اختر الوسائط ثم اتصل بجهاز قريب"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    val roomProgress = if (activeMode == "send") state.sendProgress else state.receiveProgress
                    roomProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${(progress.fraction * 100).toInt()}% • ${formatTransferBytes(progress.transferredBytes)} / ${formatTransferBytes(progress.totalBytes)}",
                            color = MalekPlayColors.PurpleBright,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (state.activePeers.isNotEmpty()) {
                        Text(
                            "متصل: " + state.activePeers.joinToString("، ") { it.name },
                            color = Color(0xFF65D6A2),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        UltraShareSessionRoom(
            state = state,
            activeMode = activeMode,
            localDeviceName = Build.MODEL?.take(40)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
            remoteHost = host,
            selectedMediaCount = pendingMediaUris.size
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::scanNearbyDevices,
                modifier = Modifier.weight(1f),
                enabled = true
            ) {
                if (state.scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Wifi, contentDescription = null)
                Text(if (state.scanning) "إيقاف البحث" else "بحث مستمر", style = MaterialTheme.typography.labelMedium)
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

        Text(
            "الاتصال الأساسي عبر شبكة Wi-Fi المحلية أو نقطة اتصال أحد الهاتفين؛ لا يشترط Wi-Fi Direct، فهو خيار إضافي فقط.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = { showDeviceRoom = !showDeviceRoom },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null)
            Text(if (showDeviceRoom) "إخفاء غرفة الأجهزة" else "فتح غرفة الأجهزة القريبة", modifier = Modifier.padding(start = 8.dp))
        }

        if (showDeviceRoom && activeMode == "send") {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("غرفة الأجهزة القريبة", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("اختر جهازاً لإرسال الوسائط بعد موافقته على الاتصال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = viewModel::scanNearbyDevices) {
                        Text(if (state.scanning) "إيقاف البحث" else "بحث")
                    }
                }
                if (state.activePeers.isNotEmpty()) {
                    Text("الأجهزة المتصلة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Text("تظهر هنا الأجهزة التي اجتازت الاقتران الموثق ونبض الحياة. في وضع V2 تنتقل الدفعة كلها عبر جلسة بيانات واحدة مشفرة، مع استئناف الأجزاء غير المكتملة.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    state.activePeers.forEach { peer ->
                        val peerTransfer = state.peerTransfers.firstOrNull { it.host == peer.host }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(54.dp)
                                        .background(MalekPlayColors.PurpleBright.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(peer.name.firstOrNull()?.uppercase() ?: "M", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(peer.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
                                    Text(peer.host + " • " + peer.role, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    Row(
                                        modifier = Modifier.background(Color(0xFF65D6A2).copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(Modifier.size(6.dp).background(Color(0xFF65D6A2), androidx.compose.foundation.shape.CircleShape))
                                        Text(peer.status, color = Color(0xFF65D6A2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    if (peerTransfer != null) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                peerTransfer.status + if (peerTransfer.fileName.isNotBlank()) " • " + peerTransfer.fileName else "",
                                                color = if (peerTransfer.status.contains("فشل")) MaterialTheme.colorScheme.error else MalekPlayColors.Amber,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (peerTransfer.totalBytes > 0L) {
                                                LinearProgressIndicator(
                                                    progress = { peerTransfer.fraction },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Text(
                                                    formatTransferBytes(peerTransfer.transferredBytes) + " / " +
                                                        formatTransferBytes(peerTransfer.totalBytes) + " • " +
                                                        formatTransferBytes(peerTransfer.bytesPerSecond) + "/s",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                                TextButton(onClick = { viewModel.disconnectPeer(peer.host) }) { Text("قطع الاتصال") }
                            }
                        }
                    }
                }
                val unlistedPeerTransfers = state.peerTransfers.filter { transfer ->
                    state.activePeers.none { it.host == transfer.host }
                }
                if (unlistedPeerTransfers.isNotEmpty()) {
                    Text("جلسات نقل إضافية", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    unlistedPeerTransfers.forEach { transfer ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(transfer.host, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(
                                    transfer.status + if (transfer.fileName.isNotBlank()) " • " + transfer.fileName else "",
                                    color = if (transfer.status.contains("فشل")) MaterialTheme.colorScheme.error else MalekPlayColors.Amber,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (transfer.totalBytes > 0L) {
                                    LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        formatTransferBytes(transfer.transferredBytes) + " / " +
                                            formatTransferBytes(transfer.totalBytes) + " • " +
                                            formatTransferBytes(transfer.bytesPerSecond) + "/s",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("الأجهزة القريبة", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("اختر الجهاز الذي تريد مشاركة الوسائط معه", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Box(
                        modifier = Modifier.background(MalekPlayColors.PurpleBright.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("${state.discoveredPeers.size + state.wifiDirectPeers.size}", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                    }
                }
                OutlinedTextField(
                    value = peerSearch,
                    onValueChange = { peerSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ابحث عن اسم الجهاز أو عنوانه") },
                    leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null) }
                )
                if (visibleDiscoveredPeers.isEmpty() && state.wifiDirectPeers.isEmpty()) {
                    Text(
                        when {
                            state.scanning -> "نبحث الآن عن أجهزة MalekPlay المتاحة..."
                            peerSearch.isNotBlank() -> "لا توجد أجهزة تطابق بحثك. جرّب اسماً أو عنواناً آخر."
                            else -> "ابدأ البحث ليظهر هنا كل جهاز متاح على الشبكة أو عبر Wi-Fi Direct."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                visibleDiscoveredPeers.forEach { peer ->
                    val connectionPathLabel = when (
                        UltraShareNegotiation.selectCandidate(peer.candidates)?.transport
                    ) {
                        UltraShareNegotiation.Transport.WIFI_DIRECT -> "Wi-Fi Direct"
                        UltraShareNegotiation.Transport.WLAN -> "شبكة Wi-Fi"
                        UltraShareNegotiation.Transport.HOTSPOT -> "نقطة اتصال"
                        null -> "شبكة محلية"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp).background(
                                    Brush.linearGradient(listOf(MalekPlayColors.PurpleBright.copy(alpha = 0.28f), Color(0xFF65D6A2).copy(alpha = 0.16f))),
                                    androidx.compose.foundation.shape.CircleShape
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(peer.name.firstOrNull()?.uppercase() ?: "M", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(peer.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(peer.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    connectionPathLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MalekPlayColors.PurpleBright,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("متاح على الشبكة القريبة", style = MaterialTheme.typography.labelSmall, color = Color(0xFF65D6A2), fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    viewModel.clearApprovedConnection()
                                    host = peer.host
                                    if (UltraShareTransport.isValidPairingSecret(secret)) {
                                        viewModel.requestConnection(peer.host, secret.trim(), peer.candidates)
                                    } else {
                                        Toast.makeText(context, "امسح رمز QR الظاهر على جهاز الاستقبال لإتمام الاقتران الآمن.", Toast.LENGTH_SHORT).show()
                                        scanQrWithCamera()
                                    }
                                },
                                enabled = !state.requestingConnection && !state.sending
                            ) {
                                Text(if (state.approvedPeerHost == peer.host) "متصل ✓" else "اتصال")
                            }
                        }
                    }
                }
                state.wifiDirectPeers.forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).background(MalekPlayColors.PurpleBright.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(peer.name.firstOrNull()?.uppercase() ?: "W", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                            Text(peer.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Wi-Fi Direct • ${peer.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { viewModel.connectWifiDirectPeer(peer) }, enabled = !state.sending) {
                            Text("اتصال")
                        }
                    }
                }
            }
        }

        }

        if (activeMode == "receive") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_receive), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Box(
                        modifier = Modifier.background(
                            (if (state.receiving) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.14f),
                            androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                        ).padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (state.receiving) "جاهز للاستقبال" else "متوقف", color = if (state.receiving) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text(stringResource(R.string.ultra_share_receive_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (state.receiving) {
                    Text(stringResource(R.string.ultra_share_receiver_address), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    if (state.localAddresses.isEmpty()) {
                        Text(stringResource(R.string.ultra_share_no_address), color = MaterialTheme.colorScheme.error)
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
                            stringResource(R.string.ultra_share_port, UltraShareBatchTransport.DEFAULT_PORT),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(stringResource(R.string.ultra_share_pairing_code), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(state.pairingSecret.chunked(3).joinToString(" "), color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ultra_share_code_warning), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!state.hotspotActive && !state.hotspotStarting) {
                        OutlinedButton(
                            onClick = ::requestLocalHotspot,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Wifi, contentDescription = null)
                            Text("إنشاء نقطة اتصال MalekPlay", modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(
                            "أنشئ شبكة محلية من داخل التطبيق عند عدم توفر راوتر مشترك. قد يرفض Android ذلك إذا كانت نقطة اتصال أخرى تعمل بالفعل.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else if (state.hotspotStarting && !state.hotspotActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("جارٍ إنشاء شبكة محلية...", modifier = Modifier.padding(start = 8.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = viewModel::stopHotspot) { Text("إلغاء") }
                        }
                    }
                    qrBitmap?.let { bitmap ->
                        val activeQrPayload = qrPayload ?: return@let
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("امسح هذا الرمز من هاتف الإرسال", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier.background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(9.dp)
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.ultra_share_qr_description),
                                        modifier = Modifier.size(176.dp)
                                    )
                                }
                                Text(
                                    stringResource(R.string.ultra_share_qr_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("MalekPlay Ultra Share", activeQrPayload))
                                        Toast.makeText(context, R.string.ultra_share_pairing_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Text(stringResource(R.string.ultra_share_copy_pairing_link), modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                if (state.hotspotActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("شبكة MalekPlay المحلية جاهزة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (!state.hotspotSsid.isNullOrBlank()) {
                                Text("اسم الشبكة: ${state.hotspotSsid}", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (state.hotspotPassword.isNullOrBlank()) "كلمة المرور: بدون كلمة مرور"
                                    else "كلمة المرور: ${state.hotspotPassword}",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "MalekPlay Wi-Fi",
                                                "SSID: ${state.hotspotSsid}\nPassword: ${state.hotspotPassword.orEmpty()}"
                                            )
                                        )
                                        Toast.makeText(context, "تم نسخ بيانات الشبكة.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Text("نسخ بيانات الشبكة", modifier = Modifier.padding(start = 8.dp))
                                }
                            } else {
                                Text("الشبكة نشطة، لكن تعذر قراءة بيانات الدخول من النظام. تحقق من إعدادات Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "صِل الهاتف الآخر بهذه الشبكة، ثم اضغط «الأجهزة القريبة». هذه الشبكة مخصصة للنقل المحلي ولا توفر إنترنت.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                            OutlinedButton(onClick = viewModel::stopHotspot, modifier = Modifier.fillMaxWidth()) {
                                Text("إيقاف نقطة الاتصال")
                            }
                        }
                    }
                }
                Button(
                    onClick = { if (state.receiving) viewModel.stopReceiving() else viewModel.startReceiving() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                ) {
                    if (state.receiving) Text(stringResource(R.string.ultra_share_stop_receiving))
                    else Text(stringResource(R.string.ultra_share_start_receiving))
                }
            }
        }

        if (activeMode == "send") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Send, contentDescription = null, tint = MalekPlayColors.PurpleBright)
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
                        onClick = { pickFiles.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending
                    ) { Text("الصور") }
                }
                OutlinedButton(
                    onClick = viewModel::scanNearbyDevices,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.scanning
                ) {
                    if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text(stringResource(if (state.scanning) R.string.ultra_share_searching else R.string.ultra_share_find_devices), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    "اختر الجهاز من غرفة الأجهزة القريبة أعلاه؛ ستظهر هنا الوسائط المختارة وسجل النقل فقط.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedButton(
                    onClick = { showAdvancedPairing = !showAdvancedPairing },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.QrCode, contentDescription = null)
                    Text(if (showAdvancedPairing) "إخفاء خيارات الاقتران الإضافية" else "خيارات الاقتران الإضافية", modifier = Modifier.padding(start = 8.dp))
                }
                if (showAdvancedPairing) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { value ->
                            if (value != host) viewModel.clearApprovedConnection()
                            host = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ultra_share_host)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { value ->
                            viewModel.clearApprovedConnection()
                            secret = value.filter { char -> char in '0'..'9' }.take(9)
                        },
                        modifier = Modifier.widthIn(max = 220.dp),
                        label = { Text("رمز الاقتران (9 أرقام)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedButton(onClick = ::scanQrWithCamera, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.Filled.QrCode, contentDescription = null)
                        Text("مسح QR بالكاميرا", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { scanQrImage.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.Filled.QrCode, contentDescription = null)
                        Text(stringResource(R.string.ultra_share_scan_qr), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = ::pastePairingDetails, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.ultra_share_paste_pairing), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestConnection(host.trim(), secret.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.sending && !state.requestingConnection && host.isNotBlank() &&
                            UltraShareTransport.isValidPairingSecret(secret) && state.approvedPeerHost != host.trim()
                    ) {
                        Text(when { state.requestingConnection -> "بانتظار موافقة الجهاز..."; state.approvedPeerHost == host.trim() -> "تم قبول الاتصال ✓"; else -> "طلب اتصال وانتظار القبول" })
                    }
                }
                if (pendingMediaUris.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MalekPlayColors.PurpleDeep.copy(alpha = 0.22f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("قائمة الوسائط", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${pendingMediaUris.size} عنصر وسائط جاهز للإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { pendingMediaUris = emptyList() }, enabled = !state.sending) { Text("إفراغ") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickFiles.launch(mediaMimeTypes) }, modifier = Modifier.weight(1f), enabled = !state.sending) {
                                    Text("إضافة وسائط")
                                }
                                Button(
                                    onClick = {
                                        if (viewModel.sendBatch(pendingMediaUris, host.trim(), secret.trim())) {
                                            pendingMediaUris = emptyList()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.sending && host.isNotBlank() && state.approvedPeerHost == host.trim() && UltraShareTransport.isValidPairingSecret(secret)
                                ) {
                                    Icon(Icons.Filled.Send, contentDescription = null)
                                    Text("إرسال الآن", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
                                Text("اطلب الاتصال وانتظر قبول الجهاز الآخر قبل بدء الإرسال.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (pendingMediaUris.isEmpty()) {
                    OutlinedButton(onClick = { pickFiles.launch(mediaMimeTypes) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.Filled.Send, contentDescription = null)
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
                                        if (entry.status == UltraShareQueueStore.STATUS_SENDING &&
                                            state.sendProgress?.fileName == entry.displayName
                                        ) {
                                            val fileProgress = state.sendProgress!!
                                            LinearProgressIndicator(
                                                progress = { fileProgress.fraction },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                formatTransferBytes(fileProgress.transferredBytes) + " / " +
                                                    formatTransferBytes(fileProgress.totalBytes) + " • " +
                                                    formatTransferBytes(fileProgress.bytesPerSecond) + "/s",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
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
                                        TextButton(onClick = { viewModel.retryFile(entry, host.trim(), secret.trim()) }, enabled = !state.sending && host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)) {
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
                            enabled = host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
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

        }

        if (activeMode == "receive") state.receivedFile?.let { received ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF66D99A))
                        Text("خيارات الملف المستلم", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(received.fileName, color = MaterialTheme.colorScheme.onSurface)
                    Text(formatTransferBytes(received.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (received.publishedUri != null) "الموقع العام: التنزيلات / MalekPlay / Received"
                        else "الموقع الداخلي: ${received.file.parentFile?.absolutePath.orEmpty()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (received.publishedUri != null) {
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

        if (activeMode == "receive" && state.receivedHistory.isNotEmpty()) {
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
                                Text(
                                    if (entry.publishedUri != null) "التنزيلات / MalekPlay / Received"
                                    else entry.file.parentFile?.absolutePath.orEmpty(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (entry.isAvailable) "متاح" else "غير موجود", color = if (entry.isAvailable) Color(0xFF65D6A2) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                TextButton(
                                    enabled = entry.isAvailable,
                                    onClick = {
                                        runCatching {
                                            val fileUri = entry.publishedUri?.let { Uri.parse(it) }
                                                ?: FileProvider.getUriForFile(context, context.packageName + ".fileprovider", entry.file)
                                            val intent = Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(fileUri, entry.mimeType)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            context.startActivity(intent)
                                        }.onFailure {
                                            Toast.makeText(context, "تعذر فتح الملف؛ تحقق من وجود تطبيق يدعم هذا النوع.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                ) { Text("فتح") }
                            }
                        }
                    }
                    if (state.receivedHistory.size > 10) {
                        Text("و ${state.receivedHistory.size - 10} ملفات أخرى", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
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

}
@Composable
private fun UltraShareSessionRoom(
    state: UltraShareUiState,
    activeMode: String,
    localDeviceName: String,
    remoteHost: String,
    selectedMediaCount: Int
) {
    val isSendingRoom = activeMode == "send"
    val activePeer = state.activePeers.firstOrNull()
    val remoteName = activePeer?.name
        ?: state.pendingConnection?.peerName
        ?: when {
            state.requestingConnection -> "جارٍ التحقق من الجهاز"
            remoteHost.isNotBlank() -> remoteHost
            isSendingRoom -> "بانتظار جهاز الاستقبال"
            else -> "بانتظار جهاز الإرسال"
        }
    val connected = activePeer != null || state.approvedPeerHost != null
    val pairingPending = state.requestingConnection || state.pendingConnection != null
    val transferring = state.sending || state.sendProgress != null || state.receiveProgress != null
    val currentBatchEntries = if (state.queueTotal > 0) state.queueEntries.takeLast(state.queueTotal) else emptyList()
    val sendCompleted = !state.sending && state.queueTotal > 0 &&
        state.queueCompleted >= state.queueTotal && currentBatchEntries.size == state.queueTotal &&
        currentBatchEntries.all { it.status == UltraShareQueueStore.STATUS_COMPLETED }
    val receiveCompleted = !isSendingRoom && state.receivedFile != null && state.receiveProgress == null &&
        (state.status?.contains("استلام") == true || state.status?.contains("استُلم") == true || state.status?.contains("اكتملت دفعة") == true)
    val status = when {
        state.sending -> "يتم إرسال الوسائط إلى الجهاز الآخر"
        state.receiveProgress != null -> "يتم استقبال الوسائط من الجهاز الآخر"
        state.sendProgress != null -> "جارٍ نقل العنصر الحالي"
        state.pendingConnection != null -> "طلب اتصال وارد — بانتظار موافقتك"
        state.requestingConnection -> "جارٍ التحقق من الاقتران — ننتظر رد الجهاز الآخر"
        connected -> "اتصال موثّق — الجهازان جاهزان للنقل"
        !isSendingRoom && state.receiving -> "هذا الهاتف ظاهر وجاهز لاستقبال الوسائط"
        !isSendingRoom -> "ابدأ الاستقبال لعرض رمز الربط واستقبال الوسائط"
        selectedMediaCount > 0 -> "${selectedMediaCount} عنصر وسائط محدد — اختر جهاز الاستقبال"
        state.scanning -> "نبحث عن أجهزة MalekPlay القريبة"
        else -> "اختر الوسائط ثم اربط الجهازين"
    }
    val statusColor = when {
        transferring -> MalekPlayColors.PurpleBright
        connected -> Color(0xFF65D6A2)
        pairingPending -> Color(0xFFFFC66D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val senderName = if (isSendingRoom) localDeviceName else remoteName
    val remoteAddress = activePeer?.host ?: state.approvedPeerHost ?: remoteHost
    val receiverName = if (isSendingRoom) remoteName else localDeviceName
    val progress = if (isSendingRoom) state.sendProgress else state.receiveProgress
    val overallFraction = if (state.queueTotalBytes > 0L) {
        (state.queueTransferredBytes.toDouble() / state.queueTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("غرفة الاتصال والنقل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("هوية الطرفين وحالة الجلسة", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Box(
                    modifier = Modifier.background(statusColor.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        when {
                            transferring -> "نقل نشط"
                            connected -> "متصل"
                            pairingPending -> "اقتران"
                            else -> "استعداد"
                        },
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UltraShareIdentityTile(
                    modifier = Modifier.weight(1f),
                    name = senderName,
                    role = "المرسل",
                    isLocal = isSendingRoom,
                    isActive = connected || (transferring && isSendingRoom),
                    detail = if (isSendingRoom) "هذا الهاتف" else remoteAddress.takeIf { it.isNotBlank() }
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    UltraShareConnectionGlyph(
                        pairingPending = pairingPending,
                        connected = connected,
                        transferring = transferring
                    )
                    Text(
                        when {
                            transferring -> "نقل"
                            connected -> "آمن"
                            pairingPending -> "ربط"
                            else -> "قريب"
                        },
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                UltraShareIdentityTile(
                    modifier = Modifier.weight(1f),
                    name = receiverName,
                    role = "المستقبل",
                    isLocal = !isSendingRoom,
                    isActive = connected || (transferring && !isSendingRoom),
                    detail = if (isSendingRoom) remoteAddress.takeIf { it.isNotBlank() } else "هذا الهاتف"
                )
            }
            Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (selectedMediaCount > 0 && isSendingRoom && !state.sending) {
                Text("${selectedMediaCount} عنصر وسائط جاهز للإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            progress?.let { current ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current.fileName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text("${(current.fraction * 100).toInt()}%", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                    }
                    LinearProgressIndicator(progress = { current.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatTransferBytes(current.transferredBytes)} / ${formatTransferBytes(current.totalBytes)} • ${formatTransferBytes(current.bytesPerSecond)}/s",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (overallFraction != null && isSendingRoom) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinearProgressIndicator(progress = { overallFraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "تقدم الدفعة: ${state.queueCompleted} / ${state.queueTotal} • ${formatTransferBytes(state.queueTransferredBytes)} / ${formatTransferBytes(state.queueTotalBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (isSendingRoom && state.queueEntries.isNotEmpty()) {
                val sessionItems = currentBatchEntries.ifEmpty { state.queueEntries.takeLast(5) }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("عناصر جلسة النقل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    sessionItems.take(5).forEach { entry ->
                        val itemColor = when (entry.status) {
                            UltraShareQueueStore.STATUS_COMPLETED -> Color(0xFF65D6A2)
                            UltraShareQueueStore.STATUS_FAILED -> MaterialTheme.colorScheme.error
                            UltraShareQueueStore.STATUS_SENDING -> MalekPlayColors.PurpleBright
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val itemLabel = when (entry.status) {
                            UltraShareQueueStore.STATUS_COMPLETED -> "اكتمل"
                            UltraShareQueueStore.STATUS_FAILED -> "فشل — يمكن إعادة المحاولة"
                            UltraShareQueueStore.STATUS_SENDING -> "جارٍ الإرسال"
                            UltraShareQueueStore.STATUS_SKIPPED -> "تم التخطي"
                            else -> "بانتظار الإرسال"
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(itemColor, androidx.compose.foundation.shape.CircleShape))
                                Text(entry.displayName, modifier = Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(itemLabel, color = itemColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            if (entry.status == UltraShareQueueStore.STATUS_SENDING && state.sendProgress?.fileName == entry.displayName) {
                                val itemProgress = state.sendProgress!!
                                LinearProgressIndicator(progress = { itemProgress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(
                                    "${(itemProgress.fraction * 100).toInt()}% • ${formatTransferBytes(itemProgress.transferredBytes)} / ${formatTransferBytes(itemProgress.totalBytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (sessionItems.size > 5) {
                        Text("و ${sessionItems.size - 5} عناصر أخرى في الطابور", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (state.queueEntries.isNotEmpty() && isSendingRoom) {
                val summaryEntries = currentBatchEntries.ifEmpty { state.queueEntries }
                val completed = summaryEntries.count { it.status == UltraShareQueueStore.STATUS_COMPLETED }
                val failed = summaryEntries.count { it.status == UltraShareQueueStore.STATUS_FAILED }
                val waiting = summaryEntries.count {
                    it.status == UltraShareQueueStore.STATUS_PENDING || it.status == UltraShareQueueStore.STATUS_SENDING
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UltraShareQueueCount("مكتمل $completed", Color(0xFF65D6A2), Modifier.weight(1f))
                    UltraShareQueueCount("جارٍ/منتظر $waiting", MalekPlayColors.PurpleBright, Modifier.weight(1f))
                    UltraShareQueueCount("فشل $failed", MaterialTheme.colorScheme.error, Modifier.weight(1f))
                }
            }
            if (sendCompleted && isSendingRoom) {
                UltraShareCompletionPanel(
                    title = "اكتمل إرسال الوسائط",
                    subtitle = "اكتمل نقل ${state.queueCompleted} من ${state.queueTotal} عنصر بنجاح.",
                    detail = "يمكنك الآن إرسال دفعة أخرى أو العودة إلى مكتبة الوسائط."
                )
            }
            if (receiveCompleted) {
                val received = state.receivedFile!!
                UltraShareCompletionPanel(
                    title = "اكتمل استقبال الوسائط",
                    subtitle = received.fileName,
                    detail = formatTransferBytes(received.sizeBytes) + " • " +
                        if (received.publishedUri != null) "حُفظ في التنزيلات / MalekPlay / Received" else "حُفظ داخل مساحة التطبيق"
                )
            }
        }
    }
}

@Composable
private fun UltraShareConnectionGlyph(
    pairingPending: Boolean,
    connected: Boolean,
    transferring: Boolean
) {
    if (pairingPending || transferring) {
        val transition = rememberInfiniteTransition(label = "ultraSharePairingPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ultraSharePairingScale"
        )
        Box(
            modifier = Modifier.size(48.dp).scale(pulse)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MalekPlayColors.PurpleBright.copy(alpha = 0.36f),
                            MalekPlayColors.PurpleDeep.copy(alpha = 0.12f)
                        )
                    ),
                    androidx.compose.foundation.shape.CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.fillMaxSize(), color = MalekPlayColors.PurpleBright, strokeWidth = 2.dp)
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(23.dp))
        }
    } else {
        Box(
            modifier = Modifier.size(48.dp).background(
                Brush.radialGradient(
                    listOf(
                        (if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright).copy(alpha = 0.28f),
                        MalekPlayColors.PurpleDeep.copy(alpha = 0.12f)
                    )
                ),
                androidx.compose.foundation.shape.CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright,
                modifier = Modifier.size(23.dp)
            )
        }
    }
    Text(
        when {
            transferring -> "نقل"
            connected -> "آمن"
            pairingPending -> "ربط"
            else -> "قريب"
        },
        color = when {
            transferring -> MalekPlayColors.PurpleBright
            connected -> Color(0xFF65D6A2)
            pairingPending -> Color(0xFFFFC66D)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun UltraShareIdentityTile(
    modifier: Modifier = Modifier,
    name: String,
    role: String,
    isLocal: Boolean,
    isActive: Boolean,
    detail: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(
                    Brush.linearGradient(
                        listOf(
                            MalekPlayColors.PurpleBright.copy(alpha = 0.30f),
                            if (isActive) Color(0xFF65D6A2).copy(alpha = 0.22f) else MalekPlayColors.PurpleDeep.copy(alpha = 0.20f)
                        )
                    ),
                    androidx.compose.foundation.shape.CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(name.firstOrNull()?.uppercase() ?: "M", color = if (isActive) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                detail ?: if (isLocal) "هذا الهاتف" else if (isActive) "متصل" else "بعيد",
                color = if (isActive && !isLocal) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UltraShareQueueCount(label: String, tint: Color, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier.background(tint.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 5.dp, vertical = 7.dp),
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun UltraShareCompletionPanel(title: String, subtitle: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF164A38))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier.size(66.dp).background(
                    Color(0xFF83E6B7).copy(alpha = 0.16f),
                    androidx.compose.foundation.shape.CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF83E6B7),
                    modifier = Modifier.size(42.dp)
                )
            }
            Text("100% • اكتملت العملية", color = Color(0xFFB6E8D2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(subtitle, color = Color(0xFFE2FFF1), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, color = Color(0xFFB6E8D2), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun formatTransferBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = -1
    do {
        amount /= 1024.0
        index++
    } while (amount >= 1024.0 && index < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", amount, units[index])
}

@Composable
private fun UltraShareHelpCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("طريقة استخدام النقل — مساعدة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء الشرح" else "اضغط لعرض خطوات الإرسال والاستقبال")
            }
            if (expanded) {
                Text("استقبال الملفات على هذا الهاتف:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("1. اضغط «إنشاء مجموعة» أو «بدء الاستقبال».\n2. إذا لم يوجد راوتر مشترك، اضغط «إنشاء نقطة اتصال MalekPlay» وصِل الهاتف الآخر باسم الشبكة وكلمة المرور الظاهرين.\n3. اعرض رمز QR أو رمز الاقتران ذي 9 أرقام وعنوان الشبكة، ثم امسح الرمز من الهاتف المرسل أو أدخل البيانات يدوياً.\n4. على Android 10 فأحدث تُنشر نسخة في التنزيلات / MalekPlay / Received؛ وعلى الإصدارات الأقدم يظهر المسار الداخلي في سجل الملفات المستلمة.")
                Text("إرسال الملفات من هذا الهاتف:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("1. اختر الفيديوهات أو الموسيقى أو الصور التي تريد إرسالها.\n2. اضغط «الأجهزة القريبة» واختر جهاز الاستقبال.\n3. امسح رمز QR الظاهر على جهاز الاستقبال؛ سيرسل MalekPlay طلب الاتصال تلقائياً بعد التحقق من رمز الاقتران.\n4. بعد قبول الطلب، اضغط «إرسال الآن» لمتابعة تقدم الوسائط وسرعة النقل.")
                Text("الاتصال الأساسي عبر شبكة Wi-Fi المحلية أو نقطة اتصال MalekPlay المحلية. نقطة الاتصال التي ينشئها التطبيق مخصصة لنقل الملفات بين الأجهزة القريبة ولا توفر إنترنت أو تستهلك بيانات الهاتف. Wi-Fi Direct خيار إضافي فقط. قد يمنع Android إنشاء نقطة اتصال جديدة إذا كانت نقطة اتصال أخرى تعمل بالفعل. لا تشارك رمز الاقتران مع أشخاص غير موثوقين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
