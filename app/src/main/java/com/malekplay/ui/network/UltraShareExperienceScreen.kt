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
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraSharePeerDeliveryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraSharePlatformCompatibility
import com.malekplay.network.UltraShareNetworkRouting
import com.malekplay.network.UltraShareCore
import com.malekplay.network.UltraShareScreenMirrorPolicy
import com.malekplay.player.cast.CastMediaHttpServer
import com.malekplay.player.cast.WebCastItem
import com.malekplay.player.cast.WebCastRegistry
import com.malekplay.ui.player.video.WebCastDialog
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
    onInitialUrisConsumed: () -> Unit = {},
    initialOpenScreenMirror: Boolean = false,
    onScreenMirrorEntryConsumed: () -> Unit = {}
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
    var page by remember {
        mutableStateOf(if (initialOpenScreenMirror) UltraSharePage.TRANSFERS else UltraSharePage.HOME)
    }
    LaunchedEffect(initialOpenScreenMirror) {
        if (initialOpenScreenMirror) {
            page = UltraSharePage.TRANSFERS
            onScreenMirrorEntryConsumed()
        }
    }
    var shakeToConnectArmed by remember { mutableStateOf(false) }
    var triggerShakeScan by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showWebCastDialog by remember { mutableStateOf(false) }
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
    var autoSentInitialUriKey by remember { mutableStateOf<String?>(null) }
    var qrAutoSendArmed by remember { mutableStateOf(false) }

    fun handleQr(raw: String) {
        runCatching { UltraShareQrCode.parsePayload(raw) }
            .onSuccess { pairing ->
                if (state.requestingConnection || pendingQrPairing != null) {
                    viewModel.cancelPendingPairingAttempt()
                }
                host = pairing.host
                secret = pairing.pairingSecret
                qrAutoSendArmed = selectedUris.isNotEmpty()
                pendingQrPairing = pairing
                page = UltraSharePage.SEND
                Toast.makeText(context, "تمت قراءة الدعوة؛ جارٍ الاقتران التلقائي الفوري...", Toast.LENGTH_SHORT).show()
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
    val screenMirrorCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            viewModel.startHostScreenMirroring(result.resultCode, data)
        } else {
            Toast.makeText(context, "تم إلغاء إذن مشاركة الشاشة.", Toast.LENGTH_SHORT).show()
        }
    }
    fun launchMediaProjectionConsent() {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? android.media.projection.MediaProjectionManager
        if (mgr == null) {
            Toast.makeText(context, "ميزة مشاركة الشاشة غير مدعومة على هذا الجهاز.", Toast.LENGTH_LONG).show()
            return
        }
        screenMirrorCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }
    val mirrorAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.setMirrorAudioMode(UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE)
            Toast.makeText(
                context,
                "لم يُمنح إذن الصوت؛ ستبدأ الشاشة الجماعية بالفيديو فقط دون توقف.",
                Toast.LENGTH_SHORT
            ).show()
        }
        launchMediaProjectionConsent()
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
                    if (state.requestingConnection || pendingQrPairing != null) {
                        viewModel.cancelPendingPairingAttempt()
                    }
                    host = pairing.host
                    secret = pairing.pairingSecret
                    qrAutoSendArmed = selectedUris.isNotEmpty()
                    pendingQrPairing = pairing
                    page = UltraSharePage.SEND
                    Toast.makeText(context, "تمت قراءة الدعوة؛ جارٍ الاقتران التلقائي الفوري...", Toast.LENGTH_SHORT).show()
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

    // Holds the operation to resume after the user enables Location in system Settings
    // (API 23–32) or grants the runtime permission. Decouples the pending QR / receiver / scan
    // state from system prerequisite resolution so returning from Settings resumes safely
    // without losing the scanned QR or session, and without re-opening Settings in a loop.
    var pendingWifiDirectAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var wifiPermissionPromptInFlight by remember { mutableStateOf(false) }
    val wifiPermissionLauncherRef = remember {
        arrayOf<androidx.activity.result.ActivityResultLauncher<Array<String>>?>(null)
    }

    fun isWifiDirectLocationServiceEnabled(): Boolean =
        UltraSharePlatformCompatibility.isLocationServiceEnabled(context)

    /** Resume the pending Wi-Fi Direct operation after prerequisites are satisfied. */
    fun runPendingWifiDirectAction() {
        val pairing = pendingQrPairing
        if (pairing != null) {
            pendingQrPairing = null
            // Route every QR through the single policy owner. In particular, a
            // P2P room may also carry its group SSID/PSK; those credentials are
            // fallback data and must not bypass WifiP2pManager negotiation.
            viewModel.joinUnifiedInvitation(pairing)
        } else if (pendingReceiverStart) {
            pendingReceiverStart = false
            viewModel.startReceiving()
        } else if (pendingInvitationAdvertisement) {
            pendingInvitationAdvertisement = false
            viewModel.advertiseCurrentWifiDirectInvitation()
        } else viewModel.scanWifiDirectPeers()
    }

    /** Handle a denied permission or cancelled Location Settings without losing available fallbacks. */
    fun runPendingWifiDirectFallback(reasonOverride: String? = null) {
        val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(context)
        val reasonToast = reasonOverride ?: UltraSharePlatformCompatibility.messageFor(prereq)
        val pairing = pendingQrPairing
        val wasAdvertisingInvitation = pendingInvitationAdvertisement
        pendingQrPairing = null
        pendingInvitationAdvertisement = false
        if (pairing != null) {
            val hasWifiCredentials = !pairing.hotspotSsid.isNullOrBlank() &&
                !pairing.hotspotPassword.isNullOrBlank()
            val lanFallbacks = UltraShareNetworkRouting.findNonP2pLanFallbackHosts(context, pairing.hosts)
                .ifEmpty {
                    pairing.hosts.filter {
                        UltraShareCore.isValidPrivateLanHost(it) &&
                            !UltraShareNetworkRouting.isWifiDirectGroupHost(it) &&
                            !UltraShareNetworkRouting.isLocalAddress(context, it)
                    }
                }
            val preferHotspotDirect = hasWifiCredentials &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (UltraShareNetworkRouting.hasCaptivePortalWifi(context) || lanFallbacks.isEmpty())
            if (preferHotspotDirect) {
                Toast.makeText(
                    context,
                    "تعذّر استخدام Wi-Fi Direct؛ سيتم الانضمام مباشرةً عبر شبكة MalekPlay.",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.connectHotspotInvitation(pairing, allowWifiDirectFallback = false)
            } else if (lanFallbacks.isNotEmpty()) {
                Toast.makeText(
                    context,
                    "تعذّر استخدام Wi-Fi Direct؛ سيتم الاتصال تلقائياً عبر الشبكة المحلية (LAN).",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.requestConnection(
                    lanFallbacks.first(),
                    pairing.pairingSecret,
                    alternateHosts = lanFallbacks.drop(1),
                    port = pairing.port,
                    onConnectionFailed = { rejected, _ ->
                        if (!rejected && hasWifiCredentials && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            viewModel.connectHotspotInvitation(pairing, allowWifiDirectFallback = false)
                        }
                    }
                )
            } else if (hasWifiCredentials && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                viewModel.connectHotspotInvitation(pairing, allowWifiDirectFallback = false)
            } else {
                val msg = reasonToast.ifBlank {
                    UltraSharePlatformCompatibility.securityExceptionMessage(
                        context,
                        "للانضمام إلى شبكة MalekPlayer"
                    )
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else if (pendingReceiverStart) {
            pendingReceiverStart = false
            // Prerequisite denial disables only the Wi-Fi Direct route; the ViewModel
            // falls back cleanly to LocalOnlyHotspot or same-LAN reception.
            viewModel.startReceiving()
            if (reasonToast.isNotBlank()) {
                Toast.makeText(context, reasonToast, Toast.LENGTH_LONG).show()
            }
        } else if (wasAdvertisingInvitation) {
            viewModel.disableWifiDirectInvitation()
            if (reasonToast.isNotBlank()) {
                Toast.makeText(context, reasonToast, Toast.LENGTH_LONG).show()
            }
        } else if (reasonToast.isNotBlank()) {
            Toast.makeText(context, reasonToast, Toast.LENGTH_LONG).show()
        }
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingWifiDirectAction
        pendingWifiDirectAction = null
        val missing = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        val result = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = missing.isEmpty(),
            locationServiceEnabled = isWifiDirectLocationServiceEnabled()
        )
        when (result) {
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK -> {
                if (action != null) action() else runPendingWifiDirectAction()
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_PERMISSION_MISSING,
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.NEARBY_PERMISSION_MISSING -> {
                // Location service was enabled in Settings; now request the runtime permission once.
                pendingWifiDirectAction = action
                if (!wifiPermissionPromptInFlight && missing.isNotEmpty()) {
                    wifiPermissionPromptInFlight = true
                    wifiPermissionLauncherRef[0]?.launch(missing.toTypedArray())
                }
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED -> {
                // User returned from Settings without enabling Location: do not loop back into Settings.
                runPendingWifiDirectFallback(
                    "لا تزال خدمة الموقع (Location) متوقفة في إعدادات الهاتف؛ لم يكتمل الاتصال عبر Wi-Fi Direct."
                )
            }
        }
    }

    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        wifiPermissionPromptInFlight = false
        val action = pendingWifiDirectAction
        pendingWifiDirectAction = null
        val granted = grants.isNotEmpty() && grants.values.all { it }
        val result = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            locationServiceEnabled = isWifiDirectLocationServiceEnabled()
        )
        when (result) {
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK -> {
                if (action != null) action() else runPendingWifiDirectAction()
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED -> {
                pendingWifiDirectAction = action
                Toast.makeText(
                    context,
                    UltraSharePlatformCompatibility.messageFor(result),
                    Toast.LENGTH_LONG
                ).show()
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> runPendingWifiDirectFallback()
        }
    }
    wifiPermissionLauncherRef[0] = wifiPermission

    /**
     * Single gate for every Wi-Fi Direct operation. Keeps the three concerns separate:
     *   1. App runtime permission (`ACCESS_FINE_LOCATION` on API 23–32, `NEARBY_WIFI_DEVICES` on 33+).
     *   2. Location service enabled in system Settings (API 23–32 only).
     *   3. Accurate SDK-specific Arabic messaging without duplicate permission prompts.
     */
    fun ensureWifiDirectReadyThen(action: () -> Unit) {
        val missing = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        val result = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = missing.isEmpty(),
            locationServiceEnabled = isWifiDirectLocationServiceEnabled()
        )
        when (result) {
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK -> {
                pendingWifiDirectAction = null
                action()
            }
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED -> {
                pendingWifiDirectAction = action
                Toast.makeText(
                    context,
                    UltraSharePlatformCompatibility.messageFor(result),
                    Toast.LENGTH_LONG
                ).show()
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> {
                pendingWifiDirectAction = action
                if (!wifiPermissionPromptInFlight && missing.isNotEmpty()) {
                    wifiPermissionPromptInFlight = true
                    wifiPermission.launch(missing.toTypedArray())
                }
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
        if (wifiGranted) {
            if (isWifiDirectLocationServiceEnabled()) {
                viewModel.scanWifiDirectPeers()
            } else {
                ensureWifiDirectReadyThen { viewModel.scanWifiDirectPeers() }
            }
        }
        if (bluetoothGranted) viewModel.scanBluetoothPeers()
        if (!wifiGranted && !bluetoothGranted) {
            Toast.makeText(context, "لم يتم منح أذونات البحث المطلوبة.", Toast.LENGTH_LONG).show()
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val scanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true &&
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else grants.values.all { it }
        if (scanGranted) viewModel.scanBluetoothPeers()
        else Toast.makeText(context, "يلزم منح إذن Bluetooth لاكتشاف الأجهزة.", Toast.LENGTH_LONG).show()
    }
    val hotspotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants.isNotEmpty() && grants.values.all { it }
        val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            locationServiceEnabled = isWifiDirectLocationServiceEnabled()
        )
        when (prereq) {
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK -> viewModel.startHotspot()
            UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED -> {
                pendingWifiDirectAction = { viewModel.startHotspot() }
                Toast.makeText(
                    context,
                    UltraSharePlatformCompatibility.messageFor(prereq),
                    Toast.LENGTH_LONG
                ).show()
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> Toast.makeText(
                context,
                UltraSharePlatformCompatibility.securityExceptionMessage(context, "لإنشاء شبكة محلية"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun scanQr() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    fun scanWifiDirect() {
        // Route through the single prerequisite gate so the location service (API 23–32)
        // is checked before any permission request and the scanned QR/session is kept.
        ensureWifiDirectReadyThen { runPendingWifiDirectAction() }
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
                if (isWifiDirectLocationServiceEnabled()) {
                    viewModel.scanWifiDirectPeers()
                } else {
                    ensureWifiDirectReadyThen { viewModel.scanWifiDirectPeers() }
                }
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
        val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(context)
        if (prereq == UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK) {
            viewModel.startHotspot()
        } else if (prereq == UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.LOCATION_SERVICE_DISABLED) {
            ensureWifiDirectReadyThen { viewModel.startHotspot() }
        } else {
            val missing = UltraSharePlatformCompatibility.wifiDirectRuntimePermissions(Build.VERSION.SDK_INT)
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) hotspotPermission.launch(missing.toTypedArray())
        }
    }

    fun startReceivingGroup() {
        if (state.receiving) return
        val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(context)
        if (prereq == UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK) {
            // Wi-Fi Direct is the primary no-router transport. The ViewModel starts
            // the receiver listeners, creates the P2P group and advertises its QR;
            // LocalOnlyHotspot starts only after a confirmed P2P cleanup on failure.
            viewModel.startReceiving()
        } else {
            pendingReceiverStart = true
            ensureWifiDirectReadyThen { runPendingWifiDirectAction() }
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

    // Media selected in Movies, Series, Music or Library arrives through initialUris.
    // If a peer is already authenticated, start the transfer directly; otherwise keep
    // the selection and automatically start it when the room becomes ready.
    LaunchedEffect(
        initialUris,
        state.activePeers,
        state.approvedSenderPeerHosts,
        state.approvedPeerHost,
        state.sending
    ) {
        val requested = initialUris.distinct().filter { isUltraShareAllowedFile(context, it) }
        if (requested.isEmpty()) {
            autoSentInitialUriKey = null
            return@LaunchedEffect
        }
        val peerReady = state.activePeers.isNotEmpty() ||
            state.approvedSenderPeerHosts.isNotEmpty() ||
            !state.approvedPeerHost.isNullOrBlank()
        if (!peerReady || state.sending) return@LaunchedEffect

        val requestKey = requested.map(Uri::toString).sorted().joinToString("|")
        if (requestKey == autoSentInitialUriKey) return@LaunchedEffect

        val readable = requested.filter { uri ->
            ensureUltraShareReadableUri(context, uri)
        }
        if (readable.isNotEmpty() && viewModel.sendBatchToApprovedPeers(readable)) {
            autoSentInitialUriKey = requestKey
            selectedUris = selectedUris.filterNot { it in requested }
            onInitialUrisConsumed()
            Toast.makeText(
                context,
                if (readable.size == 1) "بدأ إرسال الملف مباشرة إلى الجهاز المتصل."
                else "بدأ إرسال ${readable.size} ملفات مباشرة إلى الأجهزة المتصلة.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(pendingQrPairing) {
        val pairing = pendingQrPairing ?: return@LaunchedEffect
        viewModel.rememberInvitationPeer(pairing.invitationId, pairing.pairingSecret)

        val hasWifiCredentials = !pairing.hotspotSsid.isNullOrBlank() &&
            !pairing.hotspotPassword.isNullOrBlank()
        // Wi-Fi Direct discovery requires the runtime permission on API 23+,
        // while legacy WifiManager.addNetwork needs it on API < 29.
        // Android 10+ WifiNetworkSpecifier uses a system consent sheet and does
        // not require NEARBY_WIFI_DEVICES when joining a hotspot-only QR.
        val needsRuntimePermission =
            !pairing.invitationId.isNullOrBlank() ||
                (hasWifiCredentials && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        if (needsRuntimePermission) {
            val prereq = UltraSharePlatformCompatibility.evaluateWifiDirectPrerequisite(context)
            if (prereq != UltraSharePlatformCompatibility.WifiDirectPrerequisiteResult.OK) {
                // Re-check both permission AND Location service (API 23–32) and preserve
                // pendingQrPairing until resolved so returning from Settings resumes cleanly.
                ensureWifiDirectReadyThen { runPendingWifiDirectAction() }
                return@LaunchedEffect
            }
        }

        pendingQrPairing = null
        // The ViewModel owns QR route selection: P2P invitations are negotiated
        // through DNS-SD/WifiP2pManager first; only LAN/hotspot invitations use
        // their fast host probe or WifiNetworkSpecifier path.
        viewModel.joinUnifiedInvitation(pairing)
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
        state.wifiDirectHost?.takeIf { it.isNotBlank() }?.let { connectedHost ->
            host = connectedHost
            secret = ""
            page = UltraSharePage.SEND
        }
    }

    // UltraShareViewModel is the single source of truth for receiverQrPayload:
    // it withholds P2P QR codes until DNS-SD advertisement succeeds and keeps v6
    // mode/host priority consistent across Wi-Fi Direct, Hotspot, and LAN fallbacks.
    val qrPayload = state.receiverQrPayload.takeIf { state.receiving }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { runCatching { UltraShareQrCode.createBitmap(it) }.getOrNull() }
    }
    val visiblePeers = state.discoveredPeers.filter {
        it.name.contains(search, true) || it.host.contains(search, true)
    }
    val hasConnectedPeer = state.activePeers.isNotEmpty() ||
        state.approvedSenderPeerHosts.isNotEmpty() || !state.approvedPeerHost.isNullOrBlank()
    val colors = MaterialTheme.colorScheme
    fun sendSelectedUrisToRoom() {
        if (selectedUris.isEmpty()) return
        if (viewModel.sendBatchToApprovedPeers(selectedUris)) {
            selectedUris = emptyList()
            qrAutoSendArmed = false
            onInitialUrisConsumed()
        }
    }

    // Automatically send user-selected files as soon as QR pairing completes.
    LaunchedEffect(
        qrAutoSendArmed,
        state.approvedSenderPeerHosts,
        state.approvedPeerHost,
        state.sending,
        selectedUris
    ) {
        if (!qrAutoSendArmed || selectedUris.isEmpty() || state.sending) return@LaunchedEffect
        val senderReady = state.approvedSenderPeerHosts.isNotEmpty() || !state.approvedPeerHost.isNullOrBlank()
        if (!senderReady) return@LaunchedEffect
        if (viewModel.sendBatchToApprovedPeers(selectedUris)) {
            qrAutoSendArmed = false
            selectedUris = emptyList()
            onInitialUrisConsumed()
            page = UltraSharePage.TRANSFERS
            Toast.makeText(context, "تم الاقتران وبدأ إرسال الملفات مباشرة!", Toast.LENGTH_SHORT).show()
        }
    }

    val connectedDevicesCount = (state.activePeers.map { it.host } + state.approvedSenderPeerHosts).distinct().size
    val nearbyDevicesCount = state.discoveredPeers.size + state.wifiDirectPeers.size +
        state.bluetoothPeers.size + state.blePeers.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.primaryContainer.copy(alpha = 0.24f),
                        colors.background,
                        colors.surface
                    )
                )
            )
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Quick navigation pills across all UltraShare pages
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                UltraSharePage.HOME to "الرئيسية",
                UltraSharePage.RECEIVE to "استقبال (QR)",
                UltraSharePage.SEND to if (selectedUris.isNotEmpty()) "إرسال (${selectedUris.size})" else "إرسال",
                UltraSharePage.TRANSFERS to if (connectedDevicesCount > 0) "غرفة النقل ($connectedDevicesCount)" else "غرفة النقل",
                UltraSharePage.NEARBY to if (nearbyDevicesCount > 0) "القريبة ($nearbyDevicesCount)" else "القريبة",
                UltraSharePage.HISTORY to "السجل"
            ).forEach { (targetPage, label) ->
                val isSelected = page == targetPage || (page == UltraSharePage.JOIN && targetPage == UltraSharePage.SEND)
                val bg = if (isSelected) colors.primary else colors.surfaceContainerHigh.copy(alpha = 0.85f)
                val fg = if (isSelected) contrastingOnColor(colors.primary) else colors.onSurfaceVariant
                Surface(
                    shape = CircleShape,
                    color = bg,
                    border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            if (targetPage == UltraSharePage.RECEIVE && !state.receiving) {
                                startReceivingGroup()
                            }
                            page = targetPage
                        }
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        if (hasConnectedPeer && page != UltraSharePage.TRANSFERS) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF16A34A).copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.40f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "متصل بـ ${connectedDevicesCount.coerceAtLeast(1)} جهاز • جاهز للنقل المباشر",
                        color = colors.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { page = UltraSharePage.TRANSFERS }) {
                        Text("الغرفة", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            viewModel.stopHostScreenMirroring()
                            viewModel.leaveMirrorViewer()
                            (state.activePeers.map { it.host } + state.approvedSenderPeerHosts)
                                .distinct()
                                .forEach { viewModel.disconnectPeer(it) }
                            if (state.receiving) stopReceivingGroup()
                        }
                    ) {
                        Text("فصل", color = colors.error)
                    }
                }
            }
        }

        if (selectedUris.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.primaryContainer.copy(alpha = 0.45f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hasConnectedPeer) "متصل وجاهز — ${selectedUris.size} ملف بانتظار الإرسال"
                        else "تم تحديد ${selectedUris.size} ملف — امسح QR للإرسال التلقائي",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    if (hasConnectedPeer) {
                        Button(
                            onClick = ::sendSelectedUrisToRoom,
                            enabled = !state.sending,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ultraShareButtonColors()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إرسال المختار الآن", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { scanQr() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ultraShareButtonColors()
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("مسح QR وإرسال فوراً", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        if (page == UltraSharePage.HOME) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = colors.onSurface)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "المشاركة الفورية",
                        color = colors.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "اقتران مباشر بمسح QR وإرسال واستقبال بدون تعقيد",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                FilledTonalButton(
                    onClick = { page = UltraSharePage.JOIN; scanQr() },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "مسح رمز QR", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("مسح QR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Primary circular actions: Create Room (QR) vs Join Room (Scan QR)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                ZapyaPrimaryAction(
                    modifier = Modifier.weight(1f),
                    title = "إنشاء غرفة (QR)",
                    subtitle = "اعرض الرمز للاستقبال والإرسال الفوري",
                    icon = { Icon(Icons.Filled.QrCode, null, tint = colors.primary, modifier = Modifier.size(54.dp)) }
                ) {
                    startReceivingGroup()
                    page = UltraSharePage.RECEIVE
                }
                ZapyaPrimaryAction(
                    modifier = Modifier.weight(1f),
                    title = "انضمام بمسح QR",
                    subtitle = "امسح الرمز للاتصال والبدء مباشرة",
                    icon = { Icon(Icons.Filled.QrCodeScanner, null, tint = colors.primary, modifier = Modifier.size(54.dp)) }
                ) {
                    page = UltraSharePage.JOIN
                    scanQr()
                }
            }

            // Fast shortcuts row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = { page = UltraSharePage.SEND },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("اختيار ملفات", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { page = UltraSharePage.NEARBY; scanAllNearby() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("الأجهزة ($nearbyDevicesCount)", fontWeight = FontWeight.SemiBold)
                }
            }

            OutlinedButton(
                onClick = {
                    if (selectedUris.isNotEmpty()) {
                        val items = selectedUris.mapIndexed { idx, uri ->
                            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            } ?: uri.lastPathSegment ?: "Media ${idx + 1}"
                            val mime = context.contentResolver.getType(uri)
                                ?: CastMediaHttpServer.inferMimeFromUriOrName(uri, name)
                            WebCastItem(
                                id = "us-$idx-${uri.toString().hashCode().toUInt()}",
                                title = name,
                                uri = uri,
                                mimeType = mime
                            )
                        }
                        WebCastRegistry.updateQueue(items, 0)
                        if (!WebCastRegistry.state.value.running) {
                            runCatching { WebCastRegistry.start(context, requirePin = true) }
                        }
                    }
                    showWebCastDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("البث المباشر إلى متصفح الكمبيوتر (بدون تثبيت)", fontWeight = FontWeight.SemiBold)
            }

            StatusPanel(
                title = when {
                    state.sending -> "جارٍ إرسال الملفات..."
                    hasConnectedPeer -> "متصل وجاهز للإرسال والاستقبال"
                    state.receiving -> "الغرفة مفتوحة — بانتظار المسح"
                    state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning -> "جارٍ البحث عن الأجهزة القريبة..."
                    else -> "جاهز للمشاركة الفورية"
                },
                subtitle = "$connectedDevicesCount متصل • $nearbyDevicesCount جهاز قريب • ${selectedUris.size} ملف محدد",
                active = state.sending || hasConnectedPeer || state.receiving
            )

            if (hasConnectedPeer || state.sending || state.receiveProgress != null) {
                Button(
                    onClick = { page = UltraSharePage.TRANSFERS },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ultraShareButtonColors()
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("فتح غرفة النقل المباشر", fontWeight = FontWeight.Bold)
                }
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { advancedOptionsExpanded = !advancedOptionsExpanded }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "خيارات إضافية (مسح من صورة / اتصال يدوي / تشخيص)",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (advancedOptionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant
                        )
                    }
                    if (advancedOptionsExpanded) {
                        OutlinedButton(
                            onClick = { qrImagePicker.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.QrCode, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("مسح QR من صورة محفوظة")
                        }
                        OutlinedButton(
                            onClick = { page = UltraSharePage.HISTORY },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("سجل الإرسال والاستقبال")
                        }
                    }
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
                        onCancelPairing = { viewModel.cancelPendingPairingAttempt() },
                        onSendBatch = { targetHost ->
                            if (viewModel.sendBatch(selectedUris, targetHost)) {
                                selectedUris = emptyList()
                                qrAutoSendArmed = false
                                onInitialUrisConsumed()
                            }
                        },
                        onSendToAllPeers = {
                            if (viewModel.sendBatchToApprovedPeers(selectedUris)) {
                                selectedUris = emptyList()
                                qrAutoSendArmed = false
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
                    val colors = MaterialTheme.colorScheme
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(88.dp)
                                    .background(colors.primaryContainer, CircleShape)
                                    .border(1.5.dp, colors.primary.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, null, tint = colors.primary, modifier = Modifier.size(44.dp))
                            }
                            Text(
                                "انضمام فوري عبر QR",
                                color = colors.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                "امسح رمز QR الظاهر على هاتف الاستقبال؛ يتم الاقتران وقبول الإرسال والاستقبال تلقائياً دون أي خطوات إضافية.",
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(
                                colors = ultraShareButtonColors(),
                                onClick = ::scanQr,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("مسح رمز QR بالكاميرا", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { qrImagePicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
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
                            viewModel.leaveSessionRoom()
                            page = UltraSharePage.HOME
                        },
                        onToggleMirrorAudio = viewModel::setMirrorAudioRequested,
                        onSelectMirrorAudioMode = viewModel::setMirrorAudioMode,
                        onStartScreenMirror = {
                            if (state.mirrorAudioMode != UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE &&
                                !UltraShareScreenMirrorPolicy.hasRecordAudioRuntimePermission(context)
                            ) {
                                mirrorAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                launchMediaProjectionConsent()
                            }
                        },
                        onStopScreenMirror = viewModel::stopHostScreenMirroring,
                        onJoinScreenMirror = viewModel::joinMirrorViewer,
                        onCreateMirrorGroup = {
                            if (!state.receiving) startReceivingGroup()
                            page = UltraSharePage.RECEIVE
                        },
                        onJoinMirrorGroup = {
                            page = UltraSharePage.JOIN
                            scanQr()
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
    state.watchingMirrorSession?.let { activeOffer ->
        ScreenMirrorViewerModal(
            offer = activeOffer,
            onLeave = viewModel::leaveMirrorViewer
        )
    }
    if (showWebCastDialog) {
        WebCastDialog(
            onDismiss = { showWebCastDialog = false },
            onPickFilesToShare = { mediaPicker.launch(arrayOf("*/*")) }
        )
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
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = colors.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ElevatedCard(
            modifier = Modifier
                .size(144.dp)
                .border(1.5.dp, colors.primary.copy(alpha = 0.35f), CircleShape),
            onClick = onClick,
            shape = CircleShape,
            colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.primaryContainer.copy(alpha = 0.78f),
                                colors.surfaceContainer
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(106.dp)
                        .background(colors.surfaceContainerLowest.copy(alpha = 0.85f), CircleShape)
                        .border(1.dp, colors.primary.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { icon() }
            }
        }
        Text(
            title,
            color = colors.primary,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            subtitle,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            minLines = 2
        )
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
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (active) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (active) Color(0xFF22C55E).copy(alpha = 0.4f) else colors.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(18.dp)
            )
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (active) Color(0xFF22C55E) else colors.primary, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = colors.onSurface, fontWeight = FontWeight.Bold)
                Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun InfoPanel(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.onSurface, fontWeight = FontWeight.Bold)
            Text(body, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
