package com.malekplay.ui.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraSharePeerDeliveryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraSharePlatformCompatibility
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraShareCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private enum class UltraSharePage { HOME, SEND, RECEIVE, JOIN, NEARBY, TRANSFERS, HISTORY }

@Composable
fun UltraShareExperienceScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList(),
    onInitialUrisConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val accelerometer = remember(sensorManager) { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val viewModel: UltraShareViewModel = hiltViewModel()
    val mediaPickerViewModel: MediaPickerViewModel = hiltViewModel()
    val libraryFiles by mediaPickerViewModel.mediaFiles.collectAsState()
    val libraryLoading by mediaPickerViewModel.isLoading.collectAsState()
    val libraryError by mediaPickerViewModel.error.collectAsState()
    var pendingMediaType by remember { mutableStateOf(MediaType.VIDEO) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(UltraSharePage.HOME) }
    var shakeToConnectArmed by remember { mutableStateOf(false) }
    var triggerShakeScan by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var pendingQrPairing by remember { mutableStateOf<UltraShareQrCode.PairingDetails?>(null) }
    var pendingInvitationAdvertisement by remember { mutableStateOf(false) }
    var pendingReceiverStart by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var pendingReceivedExport by remember { mutableStateOf<com.malekplay.network.UltraShareReceivedEntry?>(null) }
    var advancedOptionsExpanded by remember { mutableStateOf(false) }
    var selectedUris by remember(initialUris) {
        mutableStateOf(initialUris.distinct().filter { isUltraShareAllowedFile(context, it) })
    }

    fun handleQr(raw: String) {
        runCatching { UltraShareQrCode.parsePayload(raw) }
            .onSuccess { pairing ->
                host = pairing.host
                secret = pairing.pairingSecret
                pendingQrPairing = pairing
                page = UltraSharePage.SEND
                Toast.makeText(context, "تمت قراءة الدعوة؛ جارٍ العثور على الجهاز والاتصال به تلقائياً.", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Toast.makeText(context, "رمز QR غير صالح أو لا يخص MalekPlayer.", Toast.LENGTH_LONG).show()
            }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val distinctPicked = picked.distinct()
        val accepted = ArrayList<Uri>(distinctPicked.size)
        var rejectedCount = 0
        distinctPicked.forEach { uri ->
            val hasReadableAccess = ensureUltraShareReadableUri(context, uri)
            if (hasReadableAccess) {
                accepted += uri
            } else {
                rejectedCount++
            }
        }
        if (rejectedCount > 0) {
            Toast.makeText(
                context,
                "لم تتم إضافة $rejectedCount ملفاً؛ تعذر على التطبيق قراءة محتواه. أعد اختيار الملف أو تحقق من إمكانية فتحه.",
                Toast.LENGTH_LONG
            ).show()
        }
        selectedUris = (selectedUris + accepted).distinct()
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            val historyEntry = pendingReceivedExport
            if (historyEntry != null) viewModel.exportReceived(historyEntry, uri)
            else viewModel.exportReceived(uri)
        }
        pendingReceivedExport = null
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(::handleQr)
    }
    fun launchQrScanner() {
        qrScanner.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("امسح رمز QR الخاص بجهاز MalekPlayer")
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner()
        else Toast.makeText(context, "يلزم إذن الكاميرا لمسح رمز QR. يمكنك استخدام صورة محفوظة بدلاً من ذلك.", Toast.LENGTH_LONG).show()
    }
    val qrImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        UltraShareQrCode.parsePayload(
                            UltraShareQrCode.decodeImage(context.contentResolver, uri)
                        )
                    }
                }.onSuccess { pairing ->
                    host = pairing.host
                    secret = pairing.pairingSecret
                    pendingQrPairing = pairing
                    page = UltraSharePage.SEND
                }.onFailure {
                    Toast.makeText(context, "تعذر قراءة رمز QR من الصورة.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) mediaPickerViewModel.loadMedia(pendingMediaType)
        else Toast.makeText(context, "يلزم إذن قراءة هذا النوع من الوسائط أو استخدم اختيار الملفات.", Toast.LENGTH_LONG).show()
    }
    fun loadPhoneMedia(type: MediaType) {
        pendingMediaType = type
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type == MediaType.IMAGE || type == MediaType.VIDEO)
        ) {
            arrayOf(
                if (type == MediaType.IMAGE) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(when (type) {
                MediaType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                MediaType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
                MediaType.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
            })
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) mediaPickerViewModel.loadMedia(type) else mediaPermission.launch(missing.toTypedArray())
    }

    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            val pairing = pendingQrPairing
            if (pairing != null) {
                pendingQrPairing = null
                if (!pairing.hotspotSsid.isNullOrBlank() && !pairing.hotspotPassword.isNullOrBlank()) {
                    viewModel.connectHotspotInvitation(pairing)
                } else if (!pairing.invitationId.isNullOrBlank()) {
                    viewModel.joinWifiDirectInvitation(
                        pairing.invitationId,
                        pairing.pairingSecret,
                        pairing.hosts
                    )
                } else if (pairing.host.isNotBlank()) {
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts)
                }
            } else if (pendingReceiverStart) {
                pendingReceiverStart = false
                viewModel.startReceiving()
            } else if (pendingInvitationAdvertisement) {
                pendingInvitationAdvertisement = false
                viewModel.advertiseCurrentWifiDirectInvitation()
            } else viewModel.scanWifiDirectPeers()
        } else {
            val pairing = pendingQrPairing
            val wasAdvertisingInvitation = pendingInvitationAdvertisement
            pendingQrPairing = null
            pendingInvitationAdvertisement = false
            if (pairing != null && !pairing.hotspotSsid.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    "يلزم إذن الأجهزة القريبة للانضمام تلقائياً إلى شبكة MalekPlayer. امنح الإذن ثم أعد مسح QR.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (pairing != null && pairing.hosts.isNotEmpty()) {
                viewModel.requestConnection(
                    pairing.hosts.first(),
                    pairing.pairingSecret,
                    alternateHosts = pairing.hosts.drop(1)
                )
            } else if (pendingReceiverStart) {
                pendingReceiverStart = false
                // Permission denial disables only the optional Wi-Fi Direct route.
                // Start the authenticated receiver anyway; the ViewModel falls back
                // to a same-LAN invitation when a usable non-P2P route exists.
                viewModel.startReceiving()
                Toast.makeText(
                    context,
                    "لم يُمنح إذن Wi-Fi Direct؛ سيستمر الاستقبال عبر الشبكة المحلية إذا كانت متاحة.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (wasAdvertisingInvitation) {
                viewModel.disableWifiDirectInvitation()
            }
        }
    }
    val allNearbyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val wifiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grants[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        } else grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (wifiGranted) viewModel.scanWifiDirectPeers()
        if (bluetoothGranted) viewModel.scanBluetoothPeers()
        if (!wifiGranted && !bluetoothGranted) {
            Toast.makeText(context, "لم يتم منح أذونات اكتشاف الأجهزة القريبة.", Toast.LENGTH_LONG).show()
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val scanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else grants.values.all { it }
        if (scanGranted) viewModel.scanBluetoothPeers()
        else Toast.makeText(context, "يلزم منح إذن Bluetooth Scan وConnect لاكتشاف الأجهزة.", Toast.LENGTH_LONG).show()
    }
    val hotspotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) viewModel.startHotspot()
        else Toast.makeText(context, "يلزم إذن الأجهزة القريبة لإنشاء شبكة محلية.", Toast.LENGTH_LONG).show()
    }

    fun scanQr() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    fun scanWifiDirect() {
        val required = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT).toTypedArray()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.scanWifiDirectPeers() else wifiPermission.launch(missing.toTypedArray())
    }
    fun scanBluetooth() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) viewModel.scanBluetoothPeers() else bluetoothPermission.launch(missing.toTypedArray())
    }

    fun scanAllNearby() {
        if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning) {
            if (state.scanning) viewModel.scanNearbyDevices()
            if (state.wifiDirectScanning) viewModel.stopWifiDirectDiscovery()
            if (state.bluetoothScanning || state.bleScanning) viewModel.stopBluetoothDiscovery()
        } else {
            viewModel.scanNearbyDevices()
            val required = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            val missing = required.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                viewModel.scanWifiDirectPeers()
                viewModel.scanBluetoothPeers()
            } else allNearbyPermission.launch(missing.toTypedArray())
        }
    }
    fun beginNearbySearchFromShake() {
        page = UltraSharePage.NEARBY
        if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning) {
            Toast.makeText(context, "البحث عن الأجهزة جارٍ بالفعل.", Toast.LENGTH_SHORT).show()
        } else {
            scanAllNearby()
        }
    }

    DisposableEffect(shakeToConnectArmed, page, sensorManager, accelerometer) {
        val manager = sensorManager
        val sensor = accelerometer
        if (!shakeToConnectArmed || page != UltraSharePage.HOME || manager == null || sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                private var lastShakeAt = 0L

                override fun onSensorChanged(event: SensorEvent?) {
                    val values = event?.values ?: return
                    if (values.size < 3) return
                    val x = values[0] / SensorManager.GRAVITY_EARTH
                    val y = values[1] / SensorManager.GRAVITY_EARTH
                    val z = values[2] / SensorManager.GRAVITY_EARTH
                    val acceleration = sqrt(x * x + y * y + z * z)
                    val now = SystemClock.elapsedRealtime()
                    if (acceleration >= 2.6f && now - lastShakeAt >= 1_200L) {
                        lastShakeAt = now
                        shakeToConnectArmed = false
                        triggerShakeScan = true
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { manager.unregisterListener(listener) }
        }
    }
    LaunchedEffect(triggerShakeScan) {
        if (triggerShakeScan) {
            triggerShakeScan = false
            beginNearbySearchFromShake()
            Toast.makeText(context, "تم رصد الحركة؛ بدأ البحث عن الأجهزة القريبة.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(page) {
        if (page != UltraSharePage.HOME) shakeToConnectArmed = false
    }

    fun startHotspot() {
        val required = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT).toTypedArray()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startHotspot() else hotspotPermission.launch(missing.toTypedArray())
    }

    fun startReceivingGroup() {
        if (state.receiving) return
        val required = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT).toTypedArray()
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            // Wi-Fi Direct is the primary no-router transport. The ViewModel starts
            // the receiver listeners, creates the P2P group and advertises its QR;
            // LocalOnlyHotspot starts only after a confirmed P2P cleanup on failure.
            viewModel.startReceiving()
        } else {
            pendingReceiverStart = true
            wifiPermission.launch(missing.toTypedArray())
        }
    }
    fun stopReceivingGroup() {
        if (state.receiving) viewModel.stopReceiving()
        if (state.hotspotActive || state.hotspotStarting) viewModel.stopHotspot()
    }

    BackHandler(enabled = page != UltraSharePage.HOME) {
        page = UltraSharePage.HOME
    }

    LaunchedEffect(initialUris) {
        val requested = initialUris.distinct()
        val durableUris = ArrayList<Uri>(requested.size)
        var rejectedCount = 0
        requested.forEach { uri ->
            val hasReadableAccess = ensureUltraShareReadableUri(context, uri)
            if (hasReadableAccess) {
                durableUris += uri
            } else {
                rejectedCount++
            }
        }
        selectedUris = (selectedUris.filterNot { it in requested } + durableUris).distinct()
        if (rejectedCount > 0) {
            Toast.makeText(
                context,
                "لم تتم إضافة $rejectedCount ملفاً مشتركاً؛ تعذر تثبيت إذن قراءة دائم أو قراءة بياناته. أعد اختياره من مدير الملفات.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(pendingQrPairing) {
        val pairing = pendingQrPairing ?: return@LaunchedEffect
        viewModel.rememberInvitationPeer(pairing.invitationId, pairing.pairingSecret)
        // Probe the QR's current-LAN and P2P host hints before asking Android
        // to switch Wi-Fi or requesting any Wi-Fi Direct permission.
        val reachableHosts = kotlinx.coroutines.withTimeoutOrNull(500L) {
            com.malekplay.network.UltraShareDiscovery.probeControlHostsFast(
                pairing.hosts,
                timeoutMs = 400
            )
        }.orEmpty()
        if (reachableHosts.isNotEmpty()) {
            pendingQrPairing = null
            viewModel.requestConnection(
                host = reachableHosts.first(),
                secret = pairing.pairingSecret,
                alternateHosts = reachableHosts.drop(1) + pairing.hosts.filterNot { it in reachableHosts }
            )
            return@LaunchedEffect
        }

        val hasWifiCredentials = !pairing.hotspotSsid.isNullOrBlank() &&
            !pairing.hotspotPassword.isNullOrBlank()
        val needsRuntimePermission =
            (!hasWifiCredentials && pairing.invitationId?.isNotBlank() == true) ||
                (hasWifiCredentials && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        if (needsRuntimePermission) {
            val required = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
            val missing = required.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                wifiPermission.launch(missing.toTypedArray())
                return@LaunchedEffect
            }
        }

        pendingQrPairing = null
        when {
            hasWifiCredentials -> viewModel.connectHotspotInvitation(pairing)
            !pairing.invitationId.isNullOrBlank() -> viewModel.joinWifiDirectInvitation(
                pairing.invitationId,
                pairing.pairingSecret,
                pairing.hosts
            )
            pairing.host.isNotBlank() -> viewModel.requestConnection(
                pairing.host,
                pairing.pairingSecret,
                alternateHosts = pairing.hosts.drop(1)
            )
        }
    }

    // Discovery is deliberately user-initiated. Opening UltraShare must not
    // start a background receiver or continuous LAN scan; the explicit
    // "Create group / Receive files" action starts the receiver and its
    // discovery advertisement, while "Nearby devices" starts a bounded
    // sender-side scan only when the user asks for it.

    // If secure pairing succeeds through an alternate interface, keep the UI and
    // subsequent file-transfer target aligned with the address that actually paired.
    LaunchedEffect(state.approvedPeerHost) {
        state.approvedPeerHost?.let { host = it }
    }
    // Move directly to the live transfer room as soon as either direction starts.
    LaunchedEffect(state.sending, state.sendProgress, state.receiveProgress) {
        if (state.sending || state.sendProgress != null || state.receiveProgress != null) {
            page = UltraSharePage.TRANSFERS
        }
    }
    LaunchedEffect(state.activePeers, state.approvedSenderPeerHosts) {
        if (state.activePeers.isNotEmpty() || state.approvedSenderPeerHosts.isNotEmpty()) {
            page = UltraSharePage.TRANSFERS
        }
    }
    LaunchedEffect(state.wifiDirectHost) {
        state.wifiDirectHost?.let { connectedHost ->
            host = connectedHost
            secret = ""
            page = UltraSharePage.SEND
        }
    }

    val currentWifiDirectFallbackHosts = remember(context, state.localAddresses) {
        UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(context, state.localAddresses)
    }
    val wifiDirectFallbackHosts = viewModel.stableWifiDirectQrHosts(
        state.invitationId, currentWifiDirectFallbackHosts
    )
    val wifiDirectQrPayload = state.invitationId
        .takeIf {
            !state.hotspotSsid.isNullOrBlank() &&
                !state.hotspotPassword.isNullOrBlank() &&
                com.malekplay.network.UltraSharePairingReadiness.canShowWifiDirectQr(
                    receiving = state.receiving,
                    controlChannelReady = state.controlChannelReady,
                    dataChannelReady = state.dataChannelReady,
                    activePeerCount = state.activePeers.size,
                    pairingSecret = state.pairingSecret,
                    invitationId = state.invitationId
                )
        }
        ?.let {
            // v5 advertises the P2P group as a normal WPA2 SoftAP as well as
            // any live WLAN routes, so Android 6–16 can join without DNS-SD.
            val hosts = (
                wifiDirectFallbackHosts +
                    listOfNotNull(state.wifiDirectLocalAddress, "192.168.49.1")
                ).distinct().take(8)
            UltraShareQrCode.createUnifiedReceiverPayload(
                hosts = hosts,
                pairingSecret = state.pairingSecret,
                ssid = state.hotspotSsid,
                wifiPassword = state.hotspotPassword,
                invitationId = it,
                wifiSecurity = state.hotspotSecurity
            )
        }
    val hotspotQrPayload = state.localAddresses
        // Hotspot QR is published only after both listeners and hotspot credentials are ready.
        .takeIf {
            com.malekplay.network.UltraSharePairingReadiness.canShowHotspotQr(
                receiving = state.receiving,
                controlChannelReady = state.controlChannelReady,
                dataChannelReady = state.dataChannelReady,
                hotspotActive = state.hotspotActive,
                activePeerCount = state.activePeers.size,
                pairingSecret = state.pairingSecret,
                hotspotSsid = state.hotspotSsid,
                hotspotPassword = state.hotspotPassword,
                localAddresses = it
            )
        }
        ?.let {
            UltraShareQrCode.createUnifiedReceiverPayload(
                hosts = it,
                pairingSecret = state.pairingSecret,
                ssid = state.hotspotSsid,
                wifiPassword = state.hotspotPassword,
                invitationId = state.invitationId.takeIf { id -> id.isNotBlank() },
                wifiSecurity = state.hotspotSecurity
            )
        }
    val liveLanFallbackHosts = remember(context, state.localAddresses) {
        UltraShareNetworkRouting.nonP2pLocalIpv4Addresses(context, state.localAddresses)
    }
    val lanQrPayload = liveLanFallbackHosts
        .takeIf {
            com.malekplay.network.UltraSharePairingReadiness.canShowLanQr(
                receiving = state.receiving,
                controlChannelReady = state.controlChannelReady,
                dataChannelReady = state.dataChannelReady,
                lanFallbackReady = state.lanFallbackReady,
                activePeerCount = state.activePeers.size,
                pairingSecret = state.pairingSecret,
                localAddresses = it
            )
        }
        ?.let { UltraShareQrCode.createReceiverPayload(it, state.pairingSecret) }
    val candidateQrPayload = wifiDirectQrPayload ?: hotspotQrPayload ?: lanQrPayload
    LaunchedEffect(candidateQrPayload, state.receiverQrPayload) {
        val stable = com.malekplay.network.UltraSharePairingReadiness.stableQrPayload(
            state.receiverQrPayload, candidateQrPayload
        )
        if (state.receiverQrPayload == null && stable != null) {
            viewModel.latchReceiverQrPayload(stable)
        }
    }
    // Keep the room invitation visible after the first peer joins so more
    // devices can join the same group using the same one-session secret.
    val qrPayload = state.receiverQrPayload.takeIf { state.receiving }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { runCatching { UltraShareQrCode.createBitmap(it) }.getOrNull() }
    }
    val visiblePeers = state.discoveredPeers.filter {
        it.name.contains(search, true) || it.host.contains(search, true)
    }
    val hasConnectedPeer = state.activePeers.isNotEmpty() || state.approvedSenderPeerHosts.isNotEmpty()
    fun sendSelectedUrisToRoom() {
        if (selectedUris.isEmpty()) return
        if (viewModel.sendBatchToApprovedPeers(selectedUris)) {
            selectedUris = emptyList()
            onInitialUrisConsumed()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF171021), Color(0xFF100D18), Color(0xFF0D0D12))))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (selectedUris.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF251535))
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hasConnectedPeer) "الغرفة جاهزة — ${selectedUris.size} ملفات بانتظار الإرسال"
                        else "جاهز للإرسال فور الاقتران (${selectedUris.size} ملفات)",
                        color = MalekPlayColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (hasConnectedPeer) {
                        Button(onClick = ::sendSelectedUrisToRoom, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Send, null)
                            Text("إرسال المختار الآن", modifier = Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Text("أنشئ غرفة أو امسح رمز QR؛ ستبقى الملفات محددة هنا حتى يصبح الجهاز الآخر متصلاً.", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (page == UltraSharePage.HOME) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
                Text(
                    "المشاركة مع صديق",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { page = UltraSharePage.JOIN; scanQr() }) {
                    Icon(Icons.Filled.QrCode, contentDescription = "مسح رمز QR", tint = Color(0xFF75E8F4))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top
            ) {
                ZapyaPrimaryAction(
                    modifier = Modifier.weight(1f),
                    title = "إنشاء مجموعة",
                    subtitle = "اعرض رمز QR وانتظر الأصدقاء",
                    icon = { Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF75E8F4), modifier = Modifier.size(58.dp)) }
                ) {
                    startReceivingGroup()
                    page = UltraSharePage.RECEIVE
                }
                ZapyaPrimaryAction(
                    modifier = Modifier.weight(1f),
                    title = "انضمام لمجموعة",
                    subtitle = "اكتشف الأجهزة أو امسح QR",
                    icon = { Icon(Icons.Filled.Send, null, tint = Color(0xFF75E8F4), modifier = Modifier.size(58.dp)) }
                ) {
                    page = UltraSharePage.JOIN
                    scanQr()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning)
                        "جارٍ البحث عن الأصدقاء…" else "الأجهزة القريبة: ${state.discoveredPeers.size + state.wifiDirectPeers.size + state.bluetoothPeers.size + state.blePeers.size}",
                    color = Color(0xFFB7C5E7),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxWidth().height(1.dp)
            ) {
                drawLine(
                    color = Color(0xFF75E8F4).copy(alpha = 0.35f),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D))
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { advancedOptionsExpanded = !advancedOptionsExpanded }) {
                        Text(if (advancedOptionsExpanded) "إخفاء الخيارات المتقدمة" else "الخيارات المتقدمة والاتصال اليدوي")
                    }
                    if (advancedOptionsExpanded) {
                        OutlinedButton(onClick = { page = UltraSharePage.NEARBY; scanAllNearby() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Wifi, null)
                            Text("الأجهزة القريبة", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = { qrImagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.QrCode, null)
                            Text("مسح QR من صورة محفوظة", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = { page = UltraSharePage.SEND }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Send, null)
                            Text("الإدخال اليدوي / اختيار الملفات", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            StatusPanel(
                title = when {
                    state.sending -> "جارٍ إرسال الملفات"
                    state.activePeers.isNotEmpty() || state.approvedSenderPeerHosts.isNotEmpty() -> "جهاز متصل وجاهز"
                    state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning -> "البحث عن الأجهزة القريبة"
                    else -> "جاهز للمشاركة"
                },
                subtitle = "${(state.activePeers.map { it.host } + state.approvedSenderPeerHosts).distinct().size} متصل • ${state.discoveredPeers.size + state.wifiDirectPeers.size + state.bluetoothPeers.size + state.blePeers.size} جهاز قريب • ${selectedUris.size} ملف محدد",
                active = state.sending || state.activePeers.isNotEmpty() || state.approvedSenderPeerHosts.isNotEmpty()
            )
            if (state.activePeers.isNotEmpty() || state.approvedSenderPeerHosts.isNotEmpty() ||
                state.sending || state.receiveProgress != null
            ) {
                OutlinedButton(
                    onClick = { page = UltraSharePage.TRANSFERS },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("فتح غرفة النقل — السرعة والتقدم والوقت المتبقي")
                }
            }
            UltraShareHelpCard()
        } else {
            PageHeader(
                title = when (page) {
                    UltraSharePage.SEND -> "إرسال الملفات"
                    UltraSharePage.RECEIVE -> "استقبال الملفات"
                    UltraSharePage.JOIN -> "الانضمام إلى مجموعة"
                    UltraSharePage.NEARBY -> "الأجهزة القريبة"
                    UltraSharePage.TRANSFERS -> "غرفة النقل"
                    UltraSharePage.HISTORY -> "سجل النقل"
                    else -> "مشاركة الملفات"
                },
                subtitle = when (page) {
                    UltraSharePage.SEND -> "اختر الملفات ثم اتصل بالجهاز المستقبل"
                    UltraSharePage.RECEIVE -> "اعرض رمز الاتصال للهاتف الآخر"
                    UltraSharePage.JOIN -> "امسح رمز QR الذي يظهر على هاتف الاستقبال"
                    UltraSharePage.NEARBY -> "اكتشف الأجهزة المتاحة على الشبكة"
                    UltraSharePage.TRANSFERS -> "متابعة كل جهاز وكل ملف"
                    UltraSharePage.HISTORY -> "الملفات المرسلة والمستلمة"
                    else -> ""
                },
                onBack = { page = UltraSharePage.HOME }
            )

            when (page) {
                UltraSharePage.SEND -> {
                    SendFileScreen(
                        state = state,
                        selectedUris = selectedUris,
                        onSelectedUrisChange = { selectedUris = it },
                        libraryFiles = libraryFiles,
                        libraryLoading = libraryLoading,
                        libraryError = libraryError,
                        onLoadPhoneMedia = ::loadPhoneMedia,
                        onPickMedia = { mimeTypes -> mediaPicker.launch(mimeTypes) },
                        onScanQr = ::scanQr,
                        onShowNearby = {
                            page = UltraSharePage.NEARBY
                            if (!state.scanning && !state.wifiDirectScanning &&
                                !state.bluetoothScanning && !state.bleScanning
                            ) scanAllNearby()
                        },
                        onOpenDevicePicker = {
                            showDevicePicker = true
                            if (!state.scanning && !state.wifiDirectScanning &&
                                !state.bluetoothScanning && !state.bleScanning
                            ) scanAllNearby()
                        },
                        host = host,
                        onHostChange = { value ->
                            if (value.trim() != host) {
                                secret = ""
                            }
                            host = value.trim()
                        },
                        secret = secret,
                        onSecretChange = { value ->
                            secret = value.filter { it in '0'..'9' }.take(9)
                        },
                        onPastePairingDetails = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val raw = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                            if (raw.isNullOrBlank()) {
                                Toast.makeText(context, "الحافظة فارغة.", Toast.LENGTH_SHORT).show()
                            } else {
                                runCatching { UltraShareQrCode.parsePayload(raw) }
                                    .onSuccess { pairing ->
                                        host = pairing.host
                                        secret = pairing.pairingSecret
                                        pendingQrPairing = pairing
                                        page = UltraSharePage.SEND
                                    }
                                    .onFailure { Toast.makeText(context, "بيانات الاقتران في الحافظة غير صالحة.", Toast.LENGTH_LONG).show() }
                            }
                        },
                        onRequestConnection = { viewModel.requestConnection(host, secret) },
                        onSendBatch = { targetHost ->
                            if (viewModel.sendBatch(selectedUris, targetHost)) {
                                selectedUris = emptyList()
                                onInitialUrisConsumed()
                            }
                        },
                        onSendToAllPeers = {
                            if (viewModel.sendBatchToApprovedPeers(selectedUris)) {
                                selectedUris = emptyList()
                                onInitialUrisConsumed()
                            }
                        },
                        onResumeAllPeers = viewModel::resumePendingForAllPeers,
                        onOpenTransfers = { page = UltraSharePage.TRANSFERS }
                    )
                }
                UltraSharePage.RECEIVE -> {
                    ReceiveScreen(
                        state = state,
                        qrBitmap = qrBitmap,
                        qrPayload = qrPayload,
                        onCopyQr = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("MalekPlayer pairing", qrPayload.orEmpty()))
                            Toast.makeText(context, "تم نسخ بيانات الاقتران.", Toast.LENGTH_SHORT).show()
                        },
                        onStartHotspot = ::startHotspot,
                        onStopHotspot = viewModel::stopHotspot,
                        onToggleReceiving = { if (state.receiving) stopReceivingGroup() else startReceivingGroup() },
                        onOpenTransfers = { page = UltraSharePage.TRANSFERS },
                        onSendToGroup = {
                            page = UltraSharePage.SEND
                        },
                        onOpenReceived = { uri, mimeType ->
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, "لا يوجد تطبيق مناسب لفتح الملف.", Toast.LENGTH_LONG).show() }
                        },
                        onExportReceived = { fileName -> exportLauncher.launch(fileName) },
                    )
                }
                UltraSharePage.JOIN -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2940))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier.size(92.dp).background(Color(0xFF263E5C), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.QrCode, null, tint = Color(0xFF75E8F4), modifier = Modifier.size(48.dp))
                            }
                            Text("انضم إلى مجموعة صديقك", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "امسح رمز QR الظاهر على هاتف المضيف، أو ابحث عن الأجهزة القريبة. سيبدأ الاتصال تلقائياً بعد التحقق من رمز الاقتران، ويمكنك نقل الملفات مباشرة.",
                                color = Color(0xFFB7C5E7),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(colors = ultraShareButtonColors(), onClick = ::scanQr, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.QrCode, null)
                                Text("مسح رمز QR بالكاميرا", modifier = Modifier.padding(start = 8.dp))
                            }
                            OutlinedButton(onClick = { qrImagePicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("قراءة QR من صورة محفوظة") }
                            OutlinedButton(onClick = {
                                    scanAllNearby()
                                    page = UltraSharePage.NEARBY
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Wifi, null)
                                Text("البحث عن الأجهزة القريبة", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (state.requestingConnection) {
                        StatusPanel("جارٍ إكمال الاتصال تلقائياً", "يتم التحقق من دعوة QR وتهيئة قناة نقل الملفات؛ لا يحتاج الجهاز الآخر إلى موافقة ثانية.", true)
                    }
                    if (state.blePeers.isNotEmpty()) {
                        Text("أجهزة BLE القريبة (${state.blePeers.size})", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.blePeers.filter { it.name.contains(search, true) || it.address.contains(search, true) }.forEach { peer ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(42.dp).background(MalekPlayColors.PurpleBright.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                            Text("BLE", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(peer.name, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                            Text(peer.address + " • قريب عبر BLE", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Text("اكتشاف BLE يحدد الأجهزة القريبة دون نقل الملفات أو كشف رمز الاقتران؛ أكمل الاتصال عبر Wi-Fi أو QR.", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                    state.error?.let { ErrorPanel(it) }
                    state.status?.let { InfoPanel("حالة الاقتران", it) }
                }

                UltraSharePage.NEARBY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            colors = ultraShareButtonColors(),
                            onClick = ::scanAllNearby,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, null)
                            Text(if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning) "إيقاف البحث" else "بحث شامل")
                        }
                        OutlinedButton(onClick = ::scanQr,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.QrCode, null)
                            Text("مسح QR")
                        }
                    }
                    Text(
                        "البحث يشمل الشبكة المحلية وWi-Fi Direct وBluetooth Classic وBLE. Bluetooth وBLE للاكتشاف فقط؛ يتم الاقتران ونقل الملفات عبر Wi-Fi أو QR.",
                        color = MalekPlayColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("البحث عن جهاز") })
                    OutlinedButton(onClick = ::scanBluetooth, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.bluetoothScanning || state.bleScanning) "إيقاف بحث Bluetooth/BLE" else "بحث Bluetooth وBLE")
                    }
                    UltraShareRadar(
                        isScanning = state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning,
                        discoveredCount = visiblePeers.size,
                        modifier = Modifier.fillMaxWidth().height(340.dp),
                        peerNames = visiblePeers.take(4).map { it.name },
                        onPeerClick = { index ->
                            visiblePeers.getOrNull(index)?.let { peer ->
                                host = peer.host
                                page = UltraSharePage.SEND
                                if (state.approvedPeerHost != peer.host || !UltraShareCore.isValidPairingSecret(secret)) {
                                    secret = ""
                                }
                            }
                        }
                    )
                    if (state.activePeers.isNotEmpty()) {
                        Text("الأجهزة المتصلة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.activePeers.forEach { peer ->
                            InfoPanel(peer.name, peer.host + " • " + peer.role + "\n" + peer.status)
                            TextButton(onClick = { viewModel.disconnectPeer(peer.host) }) { Text("قطع الاتصال") }
                        }
                    }
                    val activeHostSet = state.activePeers.mapTo(mutableSetOf()) { it.host }
                    val reconnectablePeers = state.reconnectablePeers.filterNot { it.host in activeHostSet }
                    if (reconnectablePeers.isNotEmpty()) {
                        Text("أجهزة سابقة • إعادة الاتصال", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        reconnectablePeers.forEach { peer ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(peer.name, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                        Text(peer.host + " • محفوظ لهذه الجلسة فقط", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { viewModel.reconnectPeer(peer.host) }, enabled = !state.requestingConnection) {
                                        Text("إعادة الاتصال")
                                    }
                                    TextButton(onClick = { viewModel.forgetPeer(peer.host) }, enabled = !state.sending) {
                                        Text("نسيان")
                                    }
                                }
                            }
                        }
                    }
                    Text("الأجهزة المكتشفة (" + (visiblePeers.size + state.wifiDirectPeers.size + state.bluetoothPeers.size + state.blePeers.size) + ")", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (visiblePeers.isEmpty() && state.wifiDirectPeers.isEmpty() && state.bluetoothPeers.isEmpty() && state.blePeers.isEmpty()) InfoPanel("الرادار", if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning) "جارٍ البحث عبر الشبكة المحلية وWi-Fi Direct وBluetooth Classic وBLE..." else "لا توجد أجهزة ظاهرة حالياً. تأكد من تشغيل Wi-Fi أو Bluetooth أو استخدم QR أو نقطة الاتصال.")
                    visiblePeers.drop(4).forEach { peer ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).background(MalekPlayColors.PurpleBright.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(peer.name.take(1).uppercase(), color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(peer.name, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                    Text(peer.host, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                Button(colors = ultraShareButtonColors(), onClick = {
                                    val sessionReady = state.approvedPeerHost == peer.host && UltraShareCore.isValidPairingSecret(secret)
                                    host = peer.host
                                    page = UltraSharePage.SEND
                                    if (!sessionReady) {
                                        secret = ""
                                    }
                                }, enabled = !state.requestingConnection) { Text("اتصال") }
                            }
                        }
                    }
                    if (state.wifiDirectPeers.isNotEmpty()) {
                        Text("أجهزة Wi-Fi Direct (${state.wifiDirectPeers.size})", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.wifiDirectPeers.filter {
                            it.name.contains(search, true) || it.deviceAddress.contains(search, true)
                        }.forEach { peer ->
                            OutlinedButton(onClick = { viewModel.connectWifiDirectPeer(peer) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.requestingConnection
                            ) {
                                Text("اتصال مباشر • " + peer.name + " • " + peer.status)
                            }
                        }
                    }
                    if (state.bluetoothPeers.isNotEmpty()) {
                        Text("أجهزة Bluetooth Classic القريبة (${state.bluetoothPeers.size})", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.bluetoothPeers.filter { it.name.contains(search, true) || it.address.contains(search, true) }.forEach { peer ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(42.dp).background(MalekPlayColors.PurpleBright.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                            Text("BT", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(peer.name, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                            Text(peer.address + if (peer.bonded) " • مقترن مسبقاً" else " • قريب", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Text("اكتشاف Bluetooth لا يوفر عنوان نقل ملفات. استخدم Wi-Fi أو QR لإكمال الاقتران الآمن.", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                    state.error?.let { ErrorPanel(it) }
                }

                UltraSharePage.TRANSFERS -> {
                    UltraShareSessionRoom(
                        state = state,
                        activeMode = when {
                            state.sending || state.sendProgress != null -> "send"
                            state.receiveProgress != null -> "receive"
                            state.receivedFile != null && (
                                state.status?.contains("استلام") == true ||
                                    state.status?.contains("استُلم") == true ||
                                    state.status?.contains("اكتملت دفعة") == true
                                ) -> "receive"
                            state.approvedSenderPeerHosts.isNotEmpty() ||
                                !state.approvedPeerHost.isNullOrBlank() ||
                                host.isNotBlank() || selectedUris.isNotEmpty() -> "send"
                            else -> "receive"
                        },
                        localDeviceName = Build.MODEL?.take(40)?.ifBlank { "MalekPlayer Android" } ?: "MalekPlayer Android",
                        remoteHost = host.ifBlank { state.activePeers.firstOrNull()?.host.orEmpty() },
                        selectedMediaCount = selectedUris.size,
                        onPauseSending = viewModel::pauseSending,
                        onResumeSending = {
                            val approvedHosts = state.approvedSenderPeerHosts
                            if (approvedHosts.size > 1) {
                                viewModel.resumePendingForAllPeers()
                            } else {
                                viewModel.resumePendingBatch(
                                    approvedHosts.firstOrNull()
                                        ?: state.approvedPeerHost
                                        ?: host
                                )
                            }
                        },
                        onCancelSending = viewModel::cancelSending,
                        onPickAndSendFiles = {
                            page = UltraSharePage.SEND
                            mediaPicker.launch(arrayOf("*/*"))
                        },
                        onShowGroupQr = {
                            // The QR invitation is only advertised while the local receiver
                            // is running. Start it before opening Receive so this action also
                            // works when the room was entered from an outbound-only session.
                            if (!state.receiving) startReceivingGroup()
                            page = UltraSharePage.RECEIVE
                        },
                        onLeaveRoom = {
                            (state.activePeers.map { it.host } + state.approvedSenderPeerHosts).distinct()
                                .forEach(viewModel::forgetPeer)
                            stopReceivingGroup()
                            page = UltraSharePage.HOME
                        }
                    )
                    val connectedHosts = (
                        state.activePeers.map { it.host } + state.approvedSenderPeerHosts
                    ).toSet()
                    val reconnectableHosts = (
                        state.reconnectablePeers.map { it.host } + state.reconnectableSenderPeerHosts
                    ).distinct().filterNot { it in connectedHosts }
                    if (reconnectableHosts.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2025)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Text(
                                    "انقطع الاتصال بجهاز محفوظ",
                                    color = Color(0xFFFFB4A9),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "يمكنك محاولة إعادة الاتصال دون إعادة اختيار الملفات. إذا تغيّرت شبكة Wi-Fi أو انتهت جلسة الطرف الآخر، افتح الاستقبال لديه وامسح رمز QR جديداً.",
                                    color = MalekPlayColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                reconnectableHosts.forEach { disconnectedHost ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            disconnectedHost,
                                            modifier = Modifier.weight(1f),
                                            color = MalekPlayColors.TextPrimary,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1
                                        )
                                        OutlinedButton(
                                            onClick = { viewModel.reconnectPeer(disconnectedHost) },
                                            enabled = !state.requestingConnection
                                        ) { Text("إعادة الاتصال") }
                                    }
                                }
                            }
                        }
                    }
                    state.peerTransfers.forEach { transfer ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(transfer.host, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                Text(transfer.status + if (transfer.fileName.isNotBlank()) " • " + transfer.fileName else "", color = MalekPlayColors.TextSecondary)
                                if (transfer.totalBytes > 0L) {
                                    LinearProgressIndicator(progress = { transfer.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    Text(formatUltraBytes(transfer.transferredBytes) + " / " + formatUltraBytes(transfer.totalBytes) + " • " + formatUltraBytes(transfer.bytesPerSecond) + "/ث", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (state.peerTransfers.isEmpty() && state.sendProgress == null && state.receiveProgress == null && reconnectableHosts.isEmpty()) {
                        InfoPanel("لا توجد عمليات نقل نشطة", "ستظهر هنا سرعة النقل وتقدم كل جهاز عند بدء المشاركة.")
                    }
                    if (state.sending) {
                        OutlinedButton(onClick = viewModel::pauseSending, modifier = Modifier.fillMaxWidth()) { Text("إيقاف مؤقت") }
                        OutlinedButton(onClick = viewModel::cancelSending, modifier = Modifier.fillMaxWidth()) { Text("إلغاء الإرسال") }
                    }
                    state.queueEntries.take(10).forEach { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                                ) {
                                    val fileNameLower = entry.displayName.lowercase()
                                    val imageFile = fileNameLower.endsWith(".jpg") || fileNameLower.endsWith(".jpeg") ||
                                        fileNameLower.endsWith(".png") || fileNameLower.endsWith(".webp") ||
                                        fileNameLower.endsWith(".gif") || fileNameLower.endsWith(".bmp")
                                    if (imageFile) {
                                        AsyncImage(
                                            model = entry.parsedUri,
                                            contentDescription = entry.displayName,
                                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        val fileGlyph = when {
                                            fileNameLower.endsWith(".mp4") || fileNameLower.endsWith(".mkv") ||
                                                fileNameLower.endsWith(".avi") || fileNameLower.endsWith(".mov") -> "🎬"
                                            fileNameLower.endsWith(".mp3") || fileNameLower.endsWith(".wav") ||
                                                fileNameLower.endsWith(".flac") || fileNameLower.endsWith(".m4a") -> "🎵"
                                            fileNameLower.endsWith(".pdf") -> "📕"
                                            else -> "📄"
                                        }
                                        Box(
                                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF38264E)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(fileGlyph, style = MaterialTheme.typography.headlineSmall)
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            entry.displayName,
                                            color = MalekPlayColors.TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2
                                        )
                                        Text(
                                            queueStatusLabel(entry.status),
                                            color = when (entry.status) {
                                                UltraShareQueueStore.STATUS_COMPLETED -> Color(0xFF65D6A2)
                                                UltraShareQueueStore.STATUS_FAILED -> MaterialTheme.colorScheme.error
                                                UltraShareQueueStore.STATUS_SENDING -> MalekPlayColors.PurpleBright
                                                else -> MalekPlayColors.TextSecondary
                                            },
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (entry.status == UltraShareQueueStore.STATUS_SENDING &&
                                    state.sendProgress?.fileName == entry.displayName
                                ) {
                                    val progress = state.sendProgress!!
                                    LinearProgressIndicator(
                                        progress = { progress.fraction.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "${(progress.fraction * 100).toInt()}% • ${formatUltraBytes(progress.transferredBytes)} / ${formatUltraBytes(progress.totalBytes)}",
                                        color = MalekPlayColors.TextSecondary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                entry.error?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (entry.status == UltraShareQueueStore.STATUS_PENDING || entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                        TextButton(onClick = { viewModel.skipQueuedFile(entry) }) { Text("تخطّي") }
                                    }
                                    if (entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                        TextButton(
                                            onClick = { viewModel.retryFile(entry, host) },
                                            enabled = host.isNotBlank() && host in state.approvedSenderPeerHosts &&
                                                !UltraShareForegroundService.isSendingToPeer(host)
                                        ) { Text("إعادة المحاولة") }
                                    }
                                }
                            }
                        }
                    }
                    if (state.queueEntries.any {
                            it.status == UltraShareQueueStore.STATUS_PENDING || it.status == UltraShareQueueStore.STATUS_FAILED
                        }) {
                        OutlinedButton(onClick = { viewModel.resumePendingBatch(host) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = host.isNotBlank() && host in state.approvedSenderPeerHosts &&
                                !UltraShareForegroundService.isSendingToPeer(host)
                        ) { Text("استئناف الملفات المتبقية لهذا الجهاز") }
                    }
                }

                UltraSharePage.HISTORY -> {
                    Text("الملفات المرسلة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (state.queueEntries.isEmpty()) InfoPanel("لا يوجد سجل إرسال", "ستظهر الملفات التي أرسلتها هنا.")
                    state.queueEntries.take(30).forEach { entry ->
                        InfoPanel(entry.displayName, queueStatusLabel(entry.status) + (entry.error?.let { "\n" + it } ?: ""))
                    }
                    Text("حالة التسليم لكل جهاز", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (state.peerDeliveryEntries.isEmpty()) {
                        InfoPanel("لا يوجد سجل تسليم مفصل", "ستظهر هنا نتيجة كل ملف على كل جهاز بعد الإرسال الجماعي.")
                    }
                    state.peerDeliveryEntries.asReversed().take(40).forEach { delivery ->
                        val deliveryLabel = when (delivery.status) {
                            UltraSharePeerDeliveryStore.STATUS_PENDING -> "بانتظار الإرسال"
                            UltraSharePeerDeliveryStore.STATUS_SENDING -> "جارٍ الإرسال"
                            UltraSharePeerDeliveryStore.STATUS_COMPLETED -> "تم الاستلام بنجاح"
                            UltraSharePeerDeliveryStore.STATUS_FAILED -> "فشل على هذا الجهاز"
                            else -> delivery.status
                        }
                        InfoPanel(
                            delivery.displayName,
                            delivery.peerHost + " • " + deliveryLabel +
                                (delivery.error?.let { "\n" + it } ?: "")
                        )
                    }
                    Text("الملفات المستلمة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (state.receivedHistory.isEmpty()) InfoPanel("لا يوجد سجل استقبال", "ستظهر الملفات المستلمة هنا.")
                    state.receivedHistory.take(30).forEach { entry ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InfoPanel(
                                entry.fileName,
                                formatUltraBytes(entry.sizeBytes) + " • " +
                                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.receivedAtEpochMs)) +
                                    if (entry.isAvailable) "\nالملف موجود في ذاكرة التطبيق ويمكن تصديره." else "\nتعذر العثور على الملف الأصلي؛ لن نعرضه كملف محفوظ."
                            )
                            OutlinedButton(
                                onClick = {
                                    pendingReceivedExport = entry
                                    exportLauncher.launch(entry.fileName)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = entry.isAvailable
                            ) {
                                Icon(Icons.Filled.Download, null)
                                Text("حفظ هذا الملف في التنزيلات أو مجلد تختاره", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (state.receivedHistory.isNotEmpty()) {
                        OutlinedButton(onClick = viewModel::clearReceivedHistory, modifier = Modifier.fillMaxWidth(), enabled = !state.receiving) { Text("مسح سجل الاستقبال") }
                    }
                    if (!state.sending && state.queueEntries.isNotEmpty()) {
                        OutlinedButton(onClick = viewModel::clearQueue, modifier = Modifier.fillMaxWidth()) { Text("مسح سجل الإرسال") }
                    }
                }

                UltraSharePage.HOME -> Unit
            }
            state.error?.takeIf { page != UltraSharePage.SEND && page != UltraSharePage.NEARBY }?.let { ErrorPanel(it) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showDevicePicker) {
        DevicePickerBottomSheet(
            discoveredPeers = state.discoveredPeers,
            wifiDirectPeers = state.wifiDirectPeers,
            activePeers = state.activePeers,
            isScanning = state.scanning || state.wifiDirectScanning,
            onDismiss = { showDevicePicker = false },
            onRefresh = ::scanAllNearby,
            onPeerSelected = { selectedHost ->
                showDevicePicker = false
                host = selectedHost
                page = UltraSharePage.SEND
                val sessionReady = state.approvedPeerHost == selectedHost && UltraShareCore.isValidPairingSecret(secret)
                if (!sessionReady) {
                    secret = ""
                }
            }
        )
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MalekPlayColors.TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}


@Composable
private fun ZapyaPrimaryAction(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.size(148.dp),
            onClick = onClick,
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF172A43))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(5.dp)
                    .background(
                        Brush.radialGradient(listOf(Color(0xFF203D5A), Color(0xFF111D35))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(116.dp)
                        .background(Color(0xFF152B45), CircleShape),
                    contentAlignment = Alignment.Center
                ) { icon() }
            }
        }
        Text(title, color = Color(0xFF78E8F2), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(subtitle, color = Color(0xFFB7C5E7), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, minLines = 2)
    }
}

@Composable
private fun ZapyaShortcut(
    modifier: Modifier = Modifier,
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(94.dp),
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2940).copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            icon()
            Text(title, color = Color(0xFFDCE7F7), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            icon()
            Text(title, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusPanel(title: String, subtitle: String, active: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF251535)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (active) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun InfoPanel(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(body, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ErrorPanel(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/**
 * MediaStore URIs are authorized by the app's media runtime permission and do not
 * support SAF-style persistable grants. Try to persist a grant when the provider
 * offers one, but accept any URI that can actually be opened now; the previous
 * mandatory-persist check incorrectly rejected ordinary Movies/Music library items.
 */
private fun ensureUltraShareReadableUri(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    val alreadyPersisted = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }
    if (!alreadyPersisted) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return isUltraShareAllowedFile(context, uri)
}

private fun isUltraShareAllowedFile(context: Context, uri: Uri): Boolean = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } else null
        } ?: uri.lastPathSegment.orEmpty()
    if (!UltraShareCore.isAllowedShareFile(displayName, mime)) {
        return@runCatching false
    }

    // Providers for MediaStore/library items and cloud documents do not all
    // implement openFileDescriptor consistently. openInputStream is the contract
    // used by the sender itself, so validate the same readable path here.
    resolver.openInputStream(uri)?.use { true } ?: false
}.getOrDefault(false)

private fun formatUltraBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}

private fun queueStatusLabel(status: String): String = when (status) {
    UltraShareQueueStore.STATUS_COMPLETED -> "اكتمل"
    UltraShareQueueStore.STATUS_SENDING -> "جارٍ الإرسال"
    UltraShareQueueStore.STATUS_FAILED -> "فشل — يمكن إعادة المحاولة"
    UltraShareQueueStore.STATUS_SKIPPED -> "تم التخطي"
    else -> "بانتظار الإرسال"
}
