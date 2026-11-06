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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareTransport
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareWifiDirect
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null,
    val queueCompleted: Int = 0,
    val queueTotal: Int = 0,
    val queueCurrentName: String? = null,
    val queueTotalBytes: Long = 0L,
    val queueTransferredBytes: Long = 0L,
    val queueEntries: List<UltraShareQueueEntry> = emptyList(),
    val wifiDirectPeers: List<UltraShareWifiDirect.Peer> = emptyList(),
    val wifiDirectHost: String? = null
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null

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
            UltraShareForegroundService.state.collect { serviceState ->
                val current = _state.value
                _state.value = current.copy(
                    pairingSecret = serviceState.pairingSecret,
                    localAddresses = serviceState.localAddresses,
                    receiving = serviceState.receiving,
                    sending = serviceState.sending,
                    progress = serviceState.progress,
                    receivedFile = serviceState.receivedFile ?: current.receivedFile,
                    status = serviceState.status ?: current.status,
                    error = serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes
                )
            }
        }
    }

    fun startReceiving() {
        if (_state.value.receiving || _state.value.sending) return
        val secret = UltraShareTransport.createPairingSecret()
        _state.value = _state.value.copy(
            pairingSecret = secret,
            receiving = true,
            sending = false,
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

    fun stopReceiving() {
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
                val peers = UltraShareDiscovery.discoverPeers()
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
                        _state.value = _state.value.copy(
                            status = "اتصل Wi-Fi Direct، لكن هذا الهاتف أصبح مالك المجموعة. اجعل جهاز الاستقبال هو مالك المجموعة ثم أعد الاتصال."
                        )
                    } else if (!remoteHost.isNullOrBlank()) {
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct. أدخل رمز الاقتران الظاهر على جهاز الاستقبال."
                        )
                    }
                },
                onError = { message ->
                    _state.value = _state.value.copy(scanning = false, error = message)
                }
            ).also { wifiDirectSession = it }
            _state.value = _state.value.copy(scanning = true, error = null, status = "جارٍ البحث عن أجهزة Wi-Fi Direct...")
            session.discover()
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
        runCatching { session.connect(peer.deviceAddress) }
            .onFailure {
                _state.value = _state.value.copy(error = it.localizedMessage ?: "تعذر الاتصال بالجهاز.")
            }
    }

    fun send(uri: Uri, host: String, secret: String) {
        if (_state.value.sending || _state.value.receiving) return
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

    fun sendBatch(uris: List<Uri>, host: String, secret: String) {
        if (uris.isEmpty() || _state.value.sending || _state.value.receiving) return
        runCatching {
            UltraShareForegroundService.startSendingBatch(context, uris, host, secret)
            _state.value = _state.value.copy(
                sending = true, progress = null, queueCompleted = 0,
                queueTotal = uris.size, queueCurrentName = null, error = null,
                status = "بدأ إرسال " + uris.size + " ملفات بالتتابع"
            )
        }.onFailure {
            _state.value = _state.value.copy(
                sending = false,
                error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
            )
        }
    }

    fun resumePendingBatch(host: String, secret: String) {
        if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret) ||
            _state.value.sending || _state.value.receiving
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
        if (_state.value.sending || _state.value.receiving ||
            host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret) ||
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
        if (entry.status != UltraShareQueueStore.STATUS_PENDING) return
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
        UltraShareForegroundService.cancel(context)
        _state.value = _state.value.copy(
            progress = null,
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: UltraShareViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    fun pastePairingDetails() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val raw = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (raw.isNullOrBlank()) {
            Toast.makeText(context, R.string.ultra_share_clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            runCatching { UltraShareQrCode.parsePayload(raw) }
                .onSuccess { pairing ->
                    host = pairing.host
                    secret = pairing.pairingSecret
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_clipboard_invalid, Toast.LENGTH_LONG).show()
                }
        }
    }
    val qrPayload = state.localAddresses.firstOrNull()?.takeIf { state.receiving }?.let { address ->
        UltraShareQrCode.createPayload(address, UltraShareTransport.DEFAULT_PORT, state.pairingSecret)
    }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { payload -> runCatching { UltraShareQrCode.createBitmap(payload) }.getOrNull() }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            viewModel.sendBatch(uris, host.trim(), secret.trim())
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.exportReceived(uri) }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.scanWifiDirectPeers()
        } else {
            Toast.makeText(context, "يلزم منح إذن الأجهزة القريبة لاستخدام Wi-Fi Direct.", Toast.LENGTH_LONG).show()
        }
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
        state.wifiDirectHost?.let { host = it }
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
                    host = pairing.host
                    secret = pairing.pairingSecret
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
            .background(Brush.verticalGradient(listOf(Color(0xFF20122F), MalekPlayColors.Background, Color(0xFF07060C))))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MalekPlayColors.TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ultra_share_title), style = MaterialTheme.typography.headlineSmall, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.ultra_share_subtitle), style = MaterialTheme.typography.bodySmall, color = MalekPlayColors.TextSecondary)
            }
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(28.dp))
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_receive), color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.ultra_share_receive_hint), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (state.receiving) {
                    Text(stringResource(R.string.ultra_share_receiver_address), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    if (state.localAddresses.isEmpty()) {
                        Text(stringResource(R.string.ultra_share_no_address), color = MaterialTheme.colorScheme.error)
                    } else {
                        state.localAddresses.forEach { address ->
                            Text(address, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            stringResource(R.string.ultra_share_port, UltraShareTransport.DEFAULT_PORT),
                            color = MalekPlayColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(stringResource(R.string.ultra_share_pairing_code), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(state.pairingSecret.chunked(4).joinToString(" "), color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(stringResource(R.string.ultra_share_code_warning), color = MalekPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    qrBitmap?.let { bitmap ->
                        val activeQrPayload = qrPayload ?: return@let
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.ultra_share_qr_description),
                            modifier = Modifier
                                .size(220.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Text(
                            stringResource(R.string.ultra_share_qr_hint),
                            color = MalekPlayColors.TextSecondary,
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
                Button(
                    onClick = { if (state.receiving) viewModel.stopReceiving() else viewModel.startReceiving() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending
                ) {
                    if (state.receiving) Text(stringResource(R.string.ultra_share_stop_receiving))
                    else Text(stringResource(R.string.ultra_share_start_receiving))
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Send, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_send), color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::scanNearbyDevices,
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
                    enabled = !state.sending && !state.receiving && !state.scanning
                ) {
                    Text(if (state.scanning) "جارٍ البحث عبر Wi-Fi Direct..." else "البحث عبر Wi-Fi Direct")
                }
                state.wifiDirectPeers.forEach { peer ->
                    TextButton(
                        onClick = { viewModel.connectWifiDirectPeer(peer) },
                        enabled = !state.sending && !state.receiving
                    ) {
                        Text(
                            "Wi-Fi Direct • " + peer.name + " • " + peer.status,
                            color = MalekPlayColors.PurpleBright
                        )
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ultra_share_host)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.filter { char -> char.isLetterOrDigit() }.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ultra_share_pairing_code)) },
                    singleLine = true
                )
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
                Button(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving &&
                        host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Text(stringResource(R.string.ultra_share_choose_send), modifier = Modifier.padding(start = 8.dp))
                }
                if (state.queueTotal > 0) {
                    Text(
                        "طابور الإرسال: " + state.queueCompleted + " / " + state.queueTotal +
                            (state.queueCurrentName?.let { " • " + it } ?: ""),
                        color = MalekPlayColors.TextSecondary,
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
                            color = MalekPlayColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (state.queueEntries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("حالة الملفات المحفوظة", color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                        state.queueEntries.take(12).forEach { entry ->
                            val label = when (entry.status) {
                                UltraShareQueueStore.STATUS_COMPLETED -> "اكتمل"
                                UltraShareQueueStore.STATUS_SENDING -> "جارٍ الإرسال"
                                UltraShareQueueStore.STATUS_FAILED -> "فشل — قابل لإعادة المحاولة"
                                UltraShareQueueStore.STATUS_SKIPPED -> "تم التخطي"
                                else -> "بانتظار الإرسال"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "• " + entry.displayName + " — " + label,
                                    color = if (entry.status == UltraShareQueueStore.STATUS_FAILED) MaterialTheme.colorScheme.error else MalekPlayColors.TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (entry.status == UltraShareQueueStore.STATUS_PENDING) {
                                    TextButton(
                                        onClick = { viewModel.skipQueuedFile(entry) },
                                        enabled = true
                                    ) { Text("تخطّي") }
                                }
                                if (entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                    TextButton(
                                        onClick = { viewModel.retryFile(entry, host.trim(), secret.trim()) },
                                        enabled = !state.sending && !state.receiving &&
                                            host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
                                    ) {
                                        Text("إعادة المحاولة")
                                    }
                                }
                            }
                        }
                        if (state.queueEntries.size > 12) {
                            Text("و " + (state.queueEntries.size - 12) + " ملفات أخرى", color = MalekPlayColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!state.sending && state.queueEntries.any {
                            it.status == UltraShareQueueStore.STATUS_PENDING ||
                                it.status == UltraShareQueueStore.STATUS_FAILED
                        }) {
                        OutlinedButton(
                            onClick = { viewModel.resumePendingBatch(host.trim(), secret.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.receiving && host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
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

        state.progress?.let { progress ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17131F)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress.fileName, color = MalekPlayColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        color = MalekPlayColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        state.receivedFile?.let { received ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18291F)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF66D99A))
                        Text(stringResource(R.string.ultra_share_receive_complete), color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(received.fileName, color = MalekPlayColors.TextPrimary)
                    Text(formatTransferBytes(received.sizeBytes), color = MalekPlayColors.TextSecondary)
                    OutlinedButton(onClick = { exportFile.launch(received.fileName) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.ultra_share_save_to_device), modifier = Modifier.padding(start = 8.dp))
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
            color = MalekPlayColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(12.dp))
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
