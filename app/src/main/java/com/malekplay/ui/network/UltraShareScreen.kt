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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareTransport
import com.malekplay.data.repository.MediaRepository
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareQueueEntry
import com.malekplay.network.UltraShareReceivedEntry
import com.malekplay.network.UltraShareReceivedHistoryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareWifiDirect
import com.malekplay.network.UltraShareControlChannel
import com.malekplay.network.UltraShareNetworkRouting
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
import java.util.UUID
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
    val receivedHistory: List<UltraShareReceivedEntry> = emptyList(),
    val wifiDirectPeers: List<UltraShareWifiDirect.Peer> = emptyList(),
    val wifiDirectHost: String? = null,
    val invitationId: String? = null
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var wifiDirectScanTimeout: Job? = null
    private var wifiDirectSession: UltraShareWifiDirect.Session? = null
    private var invitationTimeout: Job? = null
    private var pendingInvitation: UltraShareQrCode.PairingDetails? = null
    private var pendingInvitationReady: ((String) -> Unit)? = null
    private val invitationPreferences by lazy {
        context.getSharedPreferences("ultra_share_invitation", Context.MODE_PRIVATE)
    }

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
                    receivedFile = serviceState.receivedFile ?: current.receivedFile,
                    status = serviceState.status ?: current.status,
                    error = serviceState.error,
                    queueCompleted = serviceState.queueCompleted,
                    queueTotal = serviceState.queueTotal,
                    queueCurrentName = serviceState.queueCurrentName,
                    queueTotalBytes = serviceState.queueTotalBytes,
                    queueTransferredBytes = serviceState.queueTransferredBytes
                )
                // Restore only after applying the service snapshot, otherwise the
                // snapshot copy would overwrite the invitation ID we just recovered.
                if (serviceState.receiving && _state.value.invitationId == null) {
                    invitationPreferences.getString("active_invitation_id", null)
                        ?.let(::activateReceiverInvitation)
                }
            }
        }
    }

    private fun completeInvitationConnection(host: String, message: String) {
        val callback = pendingInvitationReady ?: return
        val pairing = pendingInvitation ?: return
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
            val candidates = (listOf(host) + pairing.hosts)
                .filter { UltraShareTransport.isValidPrivateLanHost(it) }
                .distinct()
            var connectedHost: String? = null
            var lastFailure: Exception? = null
            for (candidate in candidates) {
                try {
                    if (!UltraShareControlChannel.isSessionActive(candidate)) {
                        val decision = withContext(Dispatchers.IO) {
                            UltraShareControlChannel.request(
                                host = candidate,
                                pairingSecret = pairing.pairingSecret,
                                peerName = Build.MODEL?.take(48)?.ifBlank { "MalekPlay Android" }
                                    ?: "MalekPlay Android",
                                network = UltraShareNetworkRouting.findNetworkForHost(context, candidate),
                                peerId = UltraShareDiscovery.localPeerId(context)
                            )
                        }
                        check(decision == UltraShareControlChannel.Decision.ACCEPTED) {
                            "لم يوافق جهاز الاستقبال على طلب الاقتران."
                        }
                    }
                    connectedHost = candidate
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
            pendingInvitationReady = null
            pendingInvitation = null
            if (connectedHost != null) {
                _state.value = _state.value.copy(
                    wifiDirectHost = connectedHost,
                    scanning = false,
                    error = null,
                    status = "$message تم إنشاء اتصال آمن وجاهز للإرسال والاستقبال."
                )
                callback(connectedHost)
            } else {
                _state.value = _state.value.copy(
                    wifiDirectHost = null,
                    scanning = false,
                    error = lastFailure?.localizedMessage
                        ?: "تعذر إنشاء اتصال آمن. تأكد من تشغيل الاستقبال وصحة رمز الاقتران."
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
                    if (_state.value.receiving) {
                        UltraShareForegroundService.refreshLocalAddresses(context)
                        _state.value = _state.value.copy(
                            status = "مجموعة Wi-Fi Direct جاهزة للاستقبال."
                        )
                    } else {
                        val fallback = pendingInvitation?.hosts?.firstOrNull()
                        if (!fallback.isNullOrBlank()) {
                            completeInvitationConnection(fallback, "تعذر تحديد مسار Wi-Fi Direct؛ سيتم استخدام شبكة Wi-Fi العادية.")
                        }
                    }
                } else if (!remoteHost.isNullOrBlank()) {
                    if (pendingInvitationReady != null) {
                        completeInvitationConnection(remoteHost, "تم الاتصال تلقائياً عبر Wi-Fi Direct؛ الجهاز جاهز للإرسال.")
                    } else {
                        _state.value = _state.value.copy(
                            wifiDirectHost = remoteHost,
                            status = "تم اتصال Wi-Fi Direct."
                        )
                    }
                }
            },
            onError = { message ->
                val fallback = pendingInvitation?.hosts?.firstOrNull()
                if (pendingInvitationReady != null && !fallback.isNullOrBlank()) {
                    completeInvitationConnection(fallback, "تعذر Wi-Fi Direct؛ تم اختيار مسار Wi-Fi العادي تلقائياً.")
                } else if (_state.value.receiving) {
                    // Wi-Fi Direct ERROR(0) is non-fatal: the authenticated TCP
                    // receiver remains available on the advertised WLAN addresses.
                    _state.value = _state.value.copy(
                        scanning = false,
                        error = null,
                        status = "تعذر Wi-Fi Direct على هذا الهاتف؛ الاستقبال مستمر عبر Wi-Fi العادية. $message"
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

    private fun activateReceiverInvitation(invitationId: String) {
        if (!invitationId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return
        _state.value = _state.value.copy(invitationId = invitationId)
        runCatching {
            ensureWifiDirectSession().createGroup {
                runCatching {
                    ensureWifiDirectSession().advertiseInvitation(invitationId) {
                        _state.value = _state.value.copy(
                            status = "الجهاز جاهز. امسح رمز QR للاتصال التلقائي."
                        )
                    }
                }.onFailure { failure ->
                    _state.value = _state.value.copy(
                        status = "الاستقبال جاهز عبر Wi-Fi العادية؛ تعذر إعلان Wi-Fi Direct.",
                        error = failure.localizedMessage
                    )
                }
            }
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                status = "الاستقبال جاهز عبر Wi-Fi العادية؛ تعذر إنشاء مجموعة Wi-Fi Direct.",
                error = failure.localizedMessage
            )
        }
    }

    fun startReceiving() {
        if (_state.value.receiving || _state.value.sending) return
        val secret = UltraShareTransport.createPairingSecret()
        val invitationId = UUID.randomUUID().toString().replace("-", "")
        _state.value = _state.value.copy(
            pairingSecret = secret,
            invitationId = invitationId,
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
                    invitationId = null,
                    error = it.localizedMessage ?: context.getString(R.string.ultra_share_background_unavailable)
                )
                return
            }
        invitationPreferences.edit().putString("active_invitation_id", invitationId).apply()
        // ERROR(0) never disables the regular WLAN receive endpoint.
        activateReceiverInvitation(invitationId)
    }

    fun connectInvitation(pairing: UltraShareQrCode.PairingDetails, onReady: (String) -> Unit) {
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
                    }.onFailure {
                        val fallback = pairing.hosts.firstOrNull()
                        if (!fallback.isNullOrBlank()) {
                            completeInvitationConnection(fallback, "تعذر إنشاء اتصال Wi-Fi Direct؛ تم التحويل إلى شبكة Wi-Fi العادية.")
                        } else {
                            _state.value = _state.value.copy(scanning = false, error = it.localizedMessage)
                        }
                    }
                }
            }
            invitationTimeout?.cancel()
            invitationTimeout = viewModelScope.launch {
                delay(15_000)
                if (pendingInvitationReady != null) {
                    session.stopInvitationDiscovery()
                    val fallback = pairing.hosts.firstOrNull()
                    if (!fallback.isNullOrBlank()) {
                        completeInvitationConnection(fallback, "لم يظهر الجهاز عبر Wi-Fi Direct؛ جارٍ استخدام شبكة Wi-Fi العادية.")
                    } else {
                        pendingInvitationReady = null
                        pendingInvitation = null
                        _state.value = _state.value.copy(
                            scanning = false,
                            error = "لم يتم العثور على الجهاز. تأكد من تشغيل الاستقبال وابقاء رمز QR مفتوحاً."
                        )
                    }
                }
            }
        }.onFailure { failure ->
            val fallback = pairing.hosts.firstOrNull()
            if (!fallback.isNullOrBlank()) {
                completeInvitationConnection(fallback, "تعذر بدء Wi-Fi Direct؛ تم اختيار شبكة Wi-Fi العادية.")
            } else {
                pendingInvitationReady = null
                pendingInvitation = null
                _state.value = _state.value.copy(scanning = false, error = failure.localizedMessage)
            }
        }
    }
    fun stopReceiving() {
        wifiDirectSession?.stopInvitationAdvertisement()
        wifiDirectSession?.removeGroup()
        invitationPreferences.edit().remove("active_invitation_id").apply()
        _state.value = _state.value.copy(invitationId = null)
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
            val session = ensureWifiDirectSession()
            _state.value = _state.value.copy(
                scanning = true,
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
        // Discovery is no longer needed once the user has chosen a peer.
        // Stop it before negotiation so it cannot keep consuming radio time.
        session.stopDiscovery()
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
        if (_state.value.sending || _state.value.receiving) return
        if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
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
        if (uris.isEmpty() || _state.value.sending || _state.value.receiving ||
            host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)
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
        wifiDirectScanTimeout?.cancel()
        invitationTimeout?.cancel()
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
    var pendingMediaUris by remember(initialUris) { mutableStateOf(initialUris.distinct()) }

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

    val qrPayload = if (state.receiving && state.invitationId != null) {
        // QR v3 can identify a receiver even before Android publishes its P2P
        // interface address. LAN hosts remain optional fallbacks.
        UltraShareQrCode.createReceiverPayload(
            state.localAddresses,
            state.pairingSecret,
            state.invitationId
        )
    } else {
        state.localAddresses.takeIf { state.receiving && it.isNotEmpty() }?.let { addresses ->
            UltraShareQrCode.createReceiverPayload(addresses, state.pairingSecret)
        }
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

    // Independent on-device QR scanner: does not require Google Play Services scanner modules.
    var pendingWifiDirectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val wifiDirectPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingWifiDirectAction
        pendingWifiDirectAction = null
        if (!grants.values.all { it }) {
            Toast.makeText(context, "لم يُمنح إذن Wi-Fi Direct؛ سيحاول MalekPlay الاتصال عبر شبكة Wi-Fi العادية.", Toast.LENGTH_LONG).show()
        }
        // Even on denial, execute the requested action: its connection path has a
        // bounded WLAN fallback and must not strand the user on the permission dialog.
        action?.invoke()
    }
    fun requestWifiDirectAction(action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else {
            pendingWifiDirectAction = action
            wifiDirectPermissionLauncher.launch(missing.toTypedArray())
        }
    }
    fun requestWifiDirectScan() {
        requestWifiDirectAction { viewModel.scanWifiDirectPeers() }
    }
    val scanQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (!raw.isNullOrBlank()) {
            runCatching { UltraShareQrCode.parsePayload(raw) }
                .onSuccess { pairing ->
                    requestWifiDirectAction { acceptPairing(pairing) }
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_scan_failed, Toast.LENGTH_LONG).show()
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
                    requestWifiDirectAction { acceptPairing(pairing) }
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, R.string.ultra_share_clipboard_invalid, Toast.LENGTH_LONG).show()
                }
        }
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
                    requestWifiDirectAction { acceptPairing(pairing) }
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
                onClick = viewModel::scanNearbyDevices,
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
                            stringResource(R.string.ultra_share_port, UltraShareTransport.DEFAULT_PORT),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(stringResource(R.string.ultra_share_pairing_code), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(state.pairingSecret.chunked(3).joinToString(" "), color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ultra_share_code_warning), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
                        enabled = !state.sending && !state.receiving
                    ) { Text("فيديوهات") }
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending && !state.receiving
                    ) { Text("موسيقى") }
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending && !state.receiving
                    ) { Text("ملفات") }
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
                    "فعّل Wi-Fi Direct على الجهازين. يفضّل MalekPlay جعل جهاز الاستقبال مالك مجموعة الاتصال.",
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
                                OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), enabled = !state.sending && !state.receiving) {
                                    Text("إضافة ملفات")
                                }
                                Button(
                                    onClick = {
                                        if (viewModel.sendBatch(pendingMediaUris, host.trim(), secret.trim())) {
                                            pendingMediaUris = emptyList()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.sending && !state.receiving && host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
                                ) {
                                    Icon(Icons.Filled.Send, contentDescription = null)
                                    Text("إرسال الآن", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            if (host.isBlank() || !UltraShareTransport.isValidPairingSecret(secret)) {
                                Text("أكمل ربط الجهاز أولًا، ثم اضغط إرسال الآن.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (pendingMediaUris.isEmpty()) {
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending && !state.receiving) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Text("اختيار ملفات للإرسال", modifier = Modifier.padding(start = 8.dp))
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
                                        TextButton(onClick = { viewModel.retryFile(entry, host.trim(), secret.trim()) }, enabled = !state.sending && !state.receiving && host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)) {
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
                            "حُفظ تلقائياً في التنزيلات / MalekPlay / Received",
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
                Text("1. اضغط «إنشاء مجموعة» أو «بدء الاستقبال» واترك صفحة MalekPlay مفتوحة أثناء تجهيز الربط.\n2. اعرض رمز QR أو رمز الاقتران المكوّن من 9 أرقام وعنوان الشبكة.\n3. على الهاتف المرسل امسح الرمز من «الانضمام»، أو أدخل عنوان الجهاز ورمز الاقتران يدوياً.\n4. بعد اكتمال النقل ستجد الملف في التنزيلات / MalekPlay / Received.")
                Text("إرسال الملفات من هذا الهاتف:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("1. اختر «فيديوهات» أو «موسيقى» أو «ملفات».\n2. اضغط «الأجهزة القريبة» واختر الجهاز المكتشف، أو استخدم «Wi-Fi Direct» عند الحاجة.\n3. إذا لم يظهر الجهاز، أدخل عنوانه في خانة المضيف ورمز الاقتران في خانته.\n4. اضغط «إرسال الآن» لمتابعة الطابور والتقدم والسرعة.")
                Text("إذا تعذر الاتصال: تأكد أن الجهازين على الشبكة المحلية نفسها، وأن الاستقبال يعمل، وأن عنوان المضيف والرمز صحيحان. يمكن استخدام نقطة اتصال الهاتف المستقبل بدلاً من الراوتر. لا تشارك رمز الاقتران مع أشخاص غير موثوقين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
