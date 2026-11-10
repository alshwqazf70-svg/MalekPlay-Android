package com.malekplay.ui.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.*
import com.malekplay.data.repository.MediaRepository
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
import javax.inject.Inject

// --- حالة الواجهة (State) ---
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

// --- العقل المدبر (ViewModel) ---
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
    
    // 🔥 استخدام محرك البحث السريع mDNS
    private val nsdDiscoveryManager = NsdDiscoveryManager(context)

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
                if (received?.publishedUri != null && received.file.absolutePath != current.receivedFile?.file?.absolutePath) {
                    viewModelScope.launch(Dispatchers.IO) { runCatching { mediaRepository.refresh() } }
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
                    approvedPeerHost = current.approvedPeerHost?.takeIf { approved -> serviceState.activePeers.any { it.host == approved } }
                )
            }
        }
    }

    fun startReceiving() {
        if (_state.value.receiving) return
        val secret = UltraShareTransport.createPairingSecret()
        _state.value = _state.value.copy(pairingSecret = secret, receiving = true, progress = null, error = null, status = context.getString(R.string.ultra_share_waiting))
        runCatching { UltraShareForegroundService.startReceiving(context, secret) }
            .onFailure {
                _state.value = _state.value.copy(receiving = false, pairingSecret = "", error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable))
            }
    }

    fun requestConnection(host: String, secret: String, candidates: List<com.malekplay.network.UltraShareNegotiation.Candidate> = emptyList()) {
        if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
            _state.value = _state.value.copy(error = "أدخل عنوان الجهاز ورمز الاقتران الصحيح أولاً.")
            return
        }
        val discoveredCandidates = candidates.ifEmpty {
            _state.value.discoveredPeers.firstOrNull { peer -> peer.host == host.trim() || peer.candidates.any { it.host == host.trim() } }?.candidates.orEmpty()
        }
        viewModelScope.launch {
            approvedPeerCandidates = emptyList()
            _state.value = _state.value.copy(approvedPeerHost = null, requestingConnection = true, error = null, status = "تم إرسال طلب الاتصال؛ بانتظار موافقة الجهاز الآخر...")
            try {
                val decision = UltraShareControlChannel.request(
                    host = host.trim(), pairingSecret = secret.trim(),
                    peerName = android.os.Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                    network = UltraShareNetworkRouting.findNetworkForHost(context, host.trim()),
                    peerId = UltraShareDiscovery.localPeerId(context),
                    onSessionClosed = { disconnectedHost -> UltraShareForegroundService.disconnectApprovedPeer(disconnectedHost) }
                )
                if (decision == UltraShareControlChannel.Decision.ACCEPTED) {
                    UltraShareForegroundService.registerApprovedPeer(
                        name = UltraShareControlChannel.sessionPeerName(host.trim()) ?: "جهاز MalekPlay المستقبل",
                        host = host.trim(), role = "أرسل طلب الاتصال"
                    )
                    approvedPeerCandidates = UltraShareNegotiation.orderedCandidates(discoveredCandidates)
                    _state.value = _state.value.copy(
                        approvedPeerHost = host.trim(),
                        status = if (approvedPeerCandidates.size > 1) "وافق الجهاز الآخر. تم تجهيز ${approvedPeerCandidates.size} مسارات." else "وافق الجهاز الآخر على الاتصال.",
                        requestingConnection = false, error = null
                    )
                } else {
                    approvedPeerCandidates = emptyList()
                    _state.value = _state.value.copy(approvedPeerHost = null, status = "رفض الجهاز الآخر طلب الاتصال.", requestingConnection = false, error = null)
                }
            } catch (failure: Exception) {
                approvedPeerCandidates = emptyList()
                _state.value = _state.value.copy(approvedPeerHost = null, requestingConnection = false, error = failure.localizedMessage ?: "تعذر إتمام طلب الاتصال.")
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

    fun acceptConnection() = UltraShareForegroundService.acceptConnection(context)
    fun rejectConnection() = UltraShareForegroundService.rejectConnection(context)
    fun startHotspot() = UltraShareForegroundService.startHotspot(context)
    fun stopHotspot() = UltraShareForegroundService.stopHotspot(context)

    fun stopReceiving() {
        UltraShareForegroundService.stopReceiving(context)
        _state.value = _state.value.copy(receiving = false, pairingSecret = "", localAddresses = emptyList(), progress = if (_state.value.sending) UltraShareForegroundService.state.value.sendProgress else null, status = context.getString(R.string.ultra_share_stopped))
    }

    // 🔥 دالة البحث المحدثة (mDNS)
    fun scanNearbyDevices() {
        if (scanJob != null) {
            scanJob?.cancel()
            _state.value = _state.value.copy(scanning = false, status = "تم إيقاف البحث.")
            return
        }
        _state.value = _state.value.copy(scanning = true, discoveredPeers = emptyList(), error = null, status = "جارٍ البحث السريع (mDNS)...")
        
        scanJob = viewModelScope.launch {
            try {
                nsdDiscoveryManager.discoverServices().collect { service ->
                    val newPeer = UltraShareDiscovery.Peer(
                        host = service.host ?: "",
                        name = service.name,
                        candidates = emptyList() // يمكن إضافة الـ candidates لاحقاً إذا لزم الأمر
                    )
                    val currentPeers = _state.value.discoveredPeers.toMutableList()
                    if (!currentPeers.any { it.host == newPeer.host }) {
                        currentPeers.add(newPeer)
                        _state.value = _state.value.copy(discoveredPeers = currentPeers, status = "تم العثور على ${currentPeers.size} جهاز.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage ?: "تعذر البحث.")
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
                onPeers = { peers -> _state.value = _state.value.copy(wifiDirectPeers = peers, scanning = false) },
                onConnected = { remoteHost, isGroupOwner ->
                    if (isGroupOwner) {
                        if (UltraShareForegroundService.state.value.receiving) {
                            UltraShareForegroundService.refreshLocalAddresses(context)
                            _state.value = _state.value.copy(status = "اتصل Wi-Fi Direct بنجاح؛ جاهز للاستقبال.")
                        } else {
                            _state.value = _state.value.copy(status = "أنت الآن مالك المجموعة.")
                        }
                    } else if (!remoteHost.isNullOrBlank()) {
                        _state.value = _state.value.copy(wifiDirectHost = remoteHost, status = "تم اتصال Wi-Fi Direct.")
                    }
                },
                onError = { message -> _state.value = _state.value.copy(scanning = false, error = message) }
            ).also { wifiDirectSession = it }
            _state.value = _state.value.copy(scanning = true, wifiDirectHost = null, error = null, status = "جارٍ البحث عبر Wi-Fi Direct...")
            session.discover()
            viewModelScope.launch {
                delay(15_000)
                if (_state.value.scanning) _state.value = _state.value.copy(scanning = false)
            }
        } catch (failure: Exception) {
            _state.value = _state.value.copy(scanning = false, error = failure.localizedMessage)
        }
    }

    fun connectWifiDirectPeer(peer: UltraShareWifiDirect.Peer) {
        val session = wifiDirectSession ?: run { scanWifiDirectPeers(); wifiDirectSession }
        if (session == null) return
        runCatching { session.connect(peer.deviceAddress, preferThisDeviceAsGroupOwner = UltraShareForegroundService.state.value.receiving) }
    }

    fun send(uri: Uri, host: String, secret: String) = sendBatch(listOf(uri), host, secret)

    fun sendBatch(uris: List<Uri>, host: String, secret: String): Boolean {
        if (uris.isEmpty() || _state.value.sending || host.isBlank() || _state.value.approvedPeerHost != host.trim() || !UltraShareTransport.isValidPairingSecret(secret)) return false
        return runCatching {
            UltraShareForegroundService.startSendingBatch(context, uris, host, secret, approvedPeerCandidates)
            _state.value = _state.value.copy(sending = true, progress = null, queueCompleted = 0, queueTotal = uris.size, error = null, status = "بدأ إرسال ${uris.size} ملفات.")
            true
        }.getOrElse {
            _state.value = _state.value.copy(sending = false, error = it.localizedMessage)
            false
        }
    }

    fun resumePendingBatch(host: String, secret: String) {
        if (host.isBlank() || _state.value.approvedPeerHost != host.trim() || !UltraShareTransport.isValidPairingSecret(secret) || _state.value.sending) return
        viewModelScope.launch {
            val pendingUris = UltraShareQueueStore.pending(context).map { it.parsedUri }
            if (pendingUris.isNotEmpty()) sendBatch(pendingUris, host, secret)
        }
    }

    fun retryFile(entry: UltraShareQueueEntry, host: String, secret: String) {
        if (_state.value.sending || host.isBlank() || entry.status != UltraShareQueueStore.STATUS_FAILED) return
        runCatching { UltraShareForegroundService.retryFile(context, entry.parsedUri, host, secret) }
    }

    fun skipQueuedFile(entry: UltraShareQueueEntry) = viewModelScope.launch { UltraShareQueueStore.skip(context, entry.parsedUri) }
    fun pauseSending() { if (_state.value.sending) UltraShareForegroundService.pause(context) }
    fun clearQueue() = viewModelScope.launch { if (!_state.value.sending) UltraShareQueueStore.clear(context) }
    fun clearReceivedHistory() = viewModelScope.launch { if (!_state.value.receiving) UltraShareReceivedHistoryStore.clear(context) }
    fun cancelSending() { UltraShareForegroundService.cancelSending(context) }
    fun clearMessages() { _state.value = _state.value.copy(error = null, status = null) }

    fun exportReceived(destination: Uri) {
        val received = _state.value.receivedFile ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination, "w") ?: error("تعذر فتح وجهة الحفظ.")
                    received.file.inputStream().use { input -> output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) } }
                }
                _state.value = _state.value.copy(status = context.getString(R.string.ultra_share_exported, received.fileName))
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage)
            }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        wifiDirectSession?.close()
        wifiDirectSession = null
        super.onCleared()
    }
}

// --- الواجهة الرئيسية (Zapya Style Hub) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UltraShareScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList(),
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var showAdvancedSheet by remember { mutableStateOf(false) }

    // بدء البحث والاستقبال تلقائياً (مثل Zapya)
    LaunchedEffect(Unit) {
        if (!state.receiving) viewModel.startReceiving()
        if (!state.scanning) viewModel.scanNearbyDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("ULTRA SHARE", style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Black)
                        Text("MalekPlay Transfer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
                actions = { 
                    IconButton(onClick = { showAdvancedSheet = true }) { Icon(Icons.Default.MoreVert, contentDescription = "المزيد") } 
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // حالة الاتصال
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(if (state.receiving) Color(0xFF65D6A2) else Color.Gray, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.receiving) "متصل وجاهز للاستقبال" else "غير متصل",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.weight(1f))

            // الرادار
            Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                ZapyaStyleRadar(isScanning = state.scanning)
                Box(
                    modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).border(2.dp, MalekPlayColors.PurpleBright.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(32.dp))
                        Text("هذا الهاتف", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // الأجهزة المكتشفة تحت الرادار
            if (state.discoveredPeers.isNotEmpty()) {
                Text("الأجهزة المكتشفة: ${state.discoveredPeers.size}", fontWeight = FontWeight.Bold, color = MalekPlayColors.PurpleBright)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.discoveredPeers) { peer ->
                        Card(
                            modifier = Modifier.width(120.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color(0xFF65D6A2))
                                Spacer(Modifier.height(4.dp))
                                Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                Text(peer.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.requestConnection(peer.host, state.pairingSecret) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) { Text("اتصال", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = if (state.scanning) "جاري البحث عن أجهزة قريبة..." else "لا توجد أجهزة قريبة",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.weight(1f))

            // الأزرار الضخمة
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { if (!state.receiving) viewModel.startReceiving() },
                    modifier = Modifier.weight(1f).height(80.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("استقبال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = { /* سيتم ربطها بشاشة الإرسال */ },
                    modifier = Modifier.weight(1f).height(80.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, MalekPlayColors.PurpleBright),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("إرسال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // النافذة السفلية للخيارات المتقدمة والسجلات (لإخفاء الفوضى)
    if (showAdvancedSheet) {
        ModalBottomSheet(onDismissRequest = { showAdvancedSheet = false }) {
            AdvancedTransferSheet(state = state, viewModel = viewModel, context = context)
        }
    }
}

// --- مكونات مساعدة ---
@Composable
fun ZapyaStyleRadar(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.1f), radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.15f), radius * 0.66f, center, style = Stroke(2.dp.toPx()))
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.2f), radius * 0.33f, center, style = Stroke(2.dp.toPx()))
        if (isScanning) {
            rotate(rotation, center) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(MalekPlayColors.PurpleBright.copy(alpha = 0f), MalekPlayColors.PurpleBright.copy(alpha = 0.4f))),
                    startAngle = 0f, sweepAngle = 90f, useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2)
                )
            }
        }
    }
}

@Composable
fun AdvancedTransferSheet(state: UltraShareUiState, viewModel: UltraShareViewModel, context: Context) {
    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("خيارات متقدمة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        // هنا يمكنك لصق الإعدادات القديمة: سجل النقل، الحالة، إلخ
        Text("الوضع الحالي: ${if (state.sending) "جارٍ الإرسال" else if (state.receiving) "يستقبل" else "خامل"}")
        Text("الأجهزة النشطة: ${state.activePeers.size}")
        
        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.clearReceivedHistory() }, modifier = Modifier.fillMaxWidth()) { Text("مسح سجل الاستلام") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.clearQueue() }, modifier = Modifier.fillMaxWidth()) { Text("مسح طابور الإرسال") }
    }
}
