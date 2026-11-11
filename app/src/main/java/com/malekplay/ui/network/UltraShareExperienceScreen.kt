package com.malekplay.ui.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareFolderArchive
import com.malekplay.network.UltraSharePeerDeliveryStore
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class UltraSharePage { HOME, SEND, RECEIVE, JOIN, NEARBY, TRANSFERS, HISTORY }

@Composable
fun UltraShareExperienceScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList()
) {
    val context = LocalContext.current
    val viewModel: UltraShareViewModel = hiltViewModel()
    val mediaPickerViewModel: MediaPickerViewModel = hiltViewModel()
    val libraryFiles by mediaPickerViewModel.mediaFiles.collectAsState()
    val libraryLoading by mediaPickerViewModel.isLoading.collectAsState()
    val libraryError by mediaPickerViewModel.error.collectAsState()
    var pendingMediaType by remember { mutableStateOf(MediaType.VIDEO) }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(UltraSharePage.HOME) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var pendingQrPairing by remember { mutableStateOf<UltraShareQrCode.PairingDetails?>(null) }
    var pendingInvitationAdvertisement by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var pendingFolderArchive by remember { mutableStateOf<UltraShareTransport.ReceivedFile?>(null) }
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
                Toast.makeText(context, "رمز QR غير صالح أو لا يخص MalekPlay.", Toast.LENGTH_LONG).show()
            }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val distinctPicked = picked.distinct()
        val accepted = ArrayList<Uri>(distinctPicked.size)
        var rejectedCount = 0
        distinctPicked.forEach { uri ->
            val resolver = context.contentResolver
            val alreadyPersisted = resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            val hasDurableReadAccess = alreadyPersisted || runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                resolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }.getOrDefault(false)
            if (hasDurableReadAccess && isUltraShareAllowedFile(context, uri)) {
                accepted += uri
            } else {
                rejectedCount++
            }
        }
        if (rejectedCount > 0) {
            Toast.makeText(
                context,
                "لم تتم إضافة $rejectedCount ملفاً؛ تعذر تثبيت إذن قراءة دائم أو قراءة بيانات الملف. أعد اختياره من مدير الملفات.",
                Toast.LENGTH_LONG
            ).show()
        }
        selectedUris = (selectedUris + accepted).distinct()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            scope.launch {
                val resolver = context.contentResolver
                val alreadyPersisted = resolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission
                }
                val hasDurableReadAccess = alreadyPersisted || runCatching {
                    resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    resolver.persistedUriPermissions.any {
                        it.uri == treeUri && it.isReadPermission
                    }
                }.getOrDefault(false)
                if (!hasDurableReadAccess) {
                    Toast.makeText(
                        context,
                        "تعذر حفظ إذن قراءة المجلد. اختره مرة أخرى من مدير الملفات.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    try {
                        Toast.makeText(context, "جارٍ تجهيز المجلد مع الحفاظ على ترتيب ملفاته...", Toast.LENGTH_SHORT).show()
                        val archive = withContext(Dispatchers.IO) {
                            UltraShareFolderArchive.create(context, treeUri)
                        }
                        val archiveUri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            archive.file
                        )
                        check(isUltraShareAllowedFile(context, archiveUri)) {
                            "تعذر فتح أرشيف المجلد بعد إنشائه."
                        }
                        selectedUris = (selectedUris + archiveUri).distinct()
                        Toast.makeText(
                            context,
                            "تم تجهيز المجلد: ${archive.includedFiles} ملف و${archive.includedDirectories} مجلد. أصبح جاهزاً للإرسال.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Toast.makeText(
                            context,
                            failure.localizedMessage ?: "تعذر تجهيز المجلد للمشاركة.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    val extractionFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destinationUri ->
        val archive = pendingFolderArchive
        pendingFolderArchive = null
        if (destinationUri != null && archive != null) {
            scope.launch {
                val resolver = context.contentResolver
                val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val alreadyPersisted = resolver.persistedUriPermissions.any {
                    it.uri == destinationUri && it.isReadPermission && it.isWritePermission
                }
                val hasDurableAccess = alreadyPersisted || runCatching {
                    resolver.takePersistableUriPermission(destinationUri, permissionFlags)
                    resolver.persistedUriPermissions.any {
                        it.uri == destinationUri && it.isReadPermission && it.isWritePermission
                    }
                }.getOrDefault(false)
                if (!hasDurableAccess) {
                    Toast.makeText(
                        context,
                        "تعذر حفظ صلاحية الكتابة إلى مجلد الوجهة. اختر مجلداً آخر.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    try {
                        Toast.makeText(context, "جارٍ استخراج المجلد والتحقق من مسارات الملفات...", Toast.LENGTH_SHORT).show()
                        val result = withContext(Dispatchers.IO) {
                            UltraShareFolderArchive.extract(context, archive.file, destinationUri)
                        }
                        Toast.makeText(
                            context,
                            "اكتمل استخراج المجلد: ${result.extractedFiles} ملف و${result.createdDirectories} مجلد.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Toast.makeText(
                            context,
                            failure.localizedMessage ?: "تعذر استخراج أرشيف المجلد.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) viewModel.exportReceived(uri)
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(::handleQr)
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
                if (!pairing.invitationId.isNullOrBlank()) {
                    viewModel.joinWifiDirectInvitation(
                        pairing.invitationId,
                        pairing.pairingSecret,
                        pairing.hosts
                    )
                } else if (pairing.host.isNotBlank()) {
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts)
                }
            } else if (pendingInvitationAdvertisement) {
                pendingInvitationAdvertisement = false
                viewModel.advertiseCurrentWifiDirectInvitation()
            } else viewModel.scanWifiDirectPeers()
        } else {
            pendingQrPairing = null
            pendingInvitationAdvertisement = false
            Toast.makeText(context, "يلزم إذن الأجهزة القريبة لاستخدام Wi-Fi Direct.", Toast.LENGTH_LONG).show()
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
        qrScanner.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("امسح رمز QR الخاص بجهاز MalekPlay")
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }
    fun scanWifiDirect() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.scanWifiDirectPeers() else wifiPermission.launch(missing.toTypedArray())
    }
    fun scanBluetooth() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
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
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
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
    fun startHotspot() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startHotspot() else hotspotPermission.launch(missing.toTypedArray())
    }

    fun startReceivingGroup() {
        if (!state.receiving) viewModel.startReceiving()
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pendingInvitationAdvertisement = false
            viewModel.advertiseCurrentWifiDirectInvitation()
        } else {
            // Preserve the requested action across the Android permission dialog.
            // Without this flag the callback falls through to peer scanning and never
            // creates the receiver's P2P group or advertises its QR invitation.
            pendingInvitationAdvertisement = true
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
            val resolver = context.contentResolver
            val alreadyGranted = resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            val hasDurableReadAccess = alreadyGranted || runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                resolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }.getOrDefault(false)
            if (hasDurableReadAccess && isUltraShareAllowedFile(context, uri)) {
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
        if (pairing.invitationId.isNullOrBlank()) {
            pendingQrPairing = null
            if (pairing.host.isNotBlank()) {
                viewModel.requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts)
            }
        } else {
            val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val missing = required.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                wifiPermission.launch(missing.toTypedArray())
            } else {
                pendingQrPairing = null
                viewModel.joinWifiDirectInvitation(
                    pairing.invitationId,
                    pairing.pairingSecret,
                    pairing.hosts
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!state.receiving) viewModel.startReceiving()
        if (!state.scanning) viewModel.scanNearbyDevices()
    }

    // If secure pairing succeeds through an alternate interface, keep the UI and
    // subsequent file-transfer target aligned with the address that actually paired.
    LaunchedEffect(state.approvedPeerHost) {
        state.approvedPeerHost?.let { host = it }
    }
    LaunchedEffect(state.wifiDirectHost) {
        state.wifiDirectHost?.let { connectedHost ->
            host = connectedHost
            secret = ""
            page = UltraSharePage.SEND
        }
    }

    val qrPayload = state.localAddresses
        .takeIf { state.receiving && state.pairingSecret.isNotBlank() }
        ?.let {
            UltraShareQrCode.createReceiverPayload(
                hosts = it,
                pairingSecret = state.pairingSecret,
                invitationId = state.invitationId
            )
        }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { runCatching { UltraShareQrCode.createBitmap(it) }.getOrNull() }
    }
    val visiblePeers = state.discoveredPeers.filter {
        it.name.contains(search, true) || it.host.contains(search, true)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF171021), Color(0xFF100D18), Color(0xFF0D0D12))))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                IconButton(onClick = { page = UltraSharePage.JOIN }) {
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
                    scanAllNearby()
                    page = UltraSharePage.NEARBY
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "تحريك الهاتف",
                        icon = { Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = { Toast.makeText(context, "اكتشاف الأجهزة بالحركة غير متاح بعد؛ استخدم البحث القريب أو QR.", Toast.LENGTH_LONG).show() }
                    )
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "المشاركة عبر QR",
                        icon = { Icon(Icons.Filled.QrCode, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = { page = UltraSharePage.JOIN }
                    )
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "نسخ إلى هاتف آخر",
                        icon = { Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = { page = UltraSharePage.SEND }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "ادعُ الأصدقاء",
                        icon = { Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "انضم إليّ عبر MalekPlay لمشاركة الملفات محلياً.")
                            runCatching { context.startActivity(Intent.createChooser(share, "دعوة الأصدقاء")) }
                                .onFailure { Toast.makeText(context, "تعذر فتح قائمة المشاركة.", Toast.LENGTH_SHORT).show() }
                        }
                    )
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "إعدادات Wi-Fi",
                        icon = { Icon(Icons.Filled.Wifi, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = { scanWifiDirect(); page = UltraSharePage.NEARBY }
                    )
                    ZapyaShortcut(
                        modifier = Modifier.weight(1f),
                        title = "مساعدة",
                        icon = { Icon(Icons.Filled.History, null, tint = Color.White, modifier = Modifier.size(27.dp)) },
                        onClick = { page = UltraSharePage.HISTORY }
                    )
                }
            }
            StatusPanel(
                title = when {
                    state.sending -> "جارٍ إرسال الملفات"
                    state.pendingConnection != null -> "طلب اتصال وارد"
                    state.activePeers.isNotEmpty() -> "جهاز متصل وجاهز"
                    state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning -> "البحث عن الأجهزة القريبة"
                    else -> "جاهز للمشاركة"
                },
                subtitle = "${state.discoveredPeers.size + state.wifiDirectPeers.size + state.bluetoothPeers.size + state.blePeers.size} جهاز قريب • ${selectedUris.size} ملف محدد",
                active = state.sending || state.activePeers.isNotEmpty()
            )
            UltraShareHelpCard()
            state.pendingConnection?.let {
                IncomingRequestPanel(it, viewModel::acceptConnection, viewModel::rejectConnection)
            }
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
                        onPickFolder = { folderPicker.launch(null) },
                        onScanQr = ::scanQr,
                        onShowNearby = {
                            page = UltraSharePage.NEARBY
                            if (!state.scanning) viewModel.scanNearbyDevices()
                        },
                        onOpenDevicePicker = {
                            showDevicePicker = true
                            if (!state.scanning) viewModel.scanNearbyDevices()
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
                                        viewModel.requestConnection(pairing.host, pairing.pairingSecret, alternateHosts = pairing.hosts)
                                    }
                                    .onFailure { Toast.makeText(context, "بيانات الاقتران في الحافظة غير صالحة.", Toast.LENGTH_LONG).show() }
                            }
                        },
                        onRequestConnection = { viewModel.requestConnection(host, secret) },
                        onSendBatch = {
                            if (viewModel.sendBatch(selectedUris, host)) selectedUris = emptyList()
                        },
                        onSendToAllPeers = {
                            if (viewModel.sendBatchToApprovedPeers(selectedUris)) selectedUris = emptyList()
                        },
                        onResumeAllPeers = viewModel::resumePendingForAllPeers
                    )
                }
                UltraSharePage.RECEIVE -> {
                    ReceiveScreen(
                        state = state,
                        qrBitmap = qrBitmap,
                        qrPayload = qrPayload,
                        onCopyQr = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("MalekPlay pairing", qrPayload.orEmpty()))
                            Toast.makeText(context, "تم نسخ بيانات الاقتران.", Toast.LENGTH_SHORT).show()
                        },
                        onStartHotspot = ::startHotspot,
                        onStopHotspot = viewModel::stopHotspot,
                        onToggleReceiving = { if (state.receiving) stopReceivingGroup() else startReceivingGroup() },
                        onAcceptConnection = viewModel::acceptConnection,
                        onRejectConnection = viewModel::rejectConnection,
                        onOpenReceived = { uri, mimeType ->
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, "لا يوجد تطبيق مناسب لفتح الملف.", Toast.LENGTH_LONG).show() }
                        },
                        onExportReceived = { fileName -> exportLauncher.launch(fileName) },
                        onExtractReceivedFolder = {
                            pendingFolderArchive = state.receivedFile
                            extractionFolderPicker.launch(null)
                        }
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
                                "امسح رمز QR الظاهر على هاتف المضيف، أو ابحث عن الأجهزة القريبة. سيطلب MalekPlay موافقة المضيف قبل بدء الاتصال والنقل.",
                                color = Color(0xFFB7C5E7),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(onClick = ::scanQr, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                                Icon(Icons.Filled.QrCode, null)
                                Text("مسح رمز QR بالكاميرا", modifier = Modifier.padding(start = 8.dp))
                            }
                            OutlinedButton(
                                onClick = { qrImagePicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.sending
                            ) { Text("قراءة QR من صورة محفوظة") }
                            OutlinedButton(
                                onClick = {
                                    scanAllNearby()
                                    page = UltraSharePage.NEARBY
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.sending
                            ) {
                                Icon(Icons.Filled.Wifi, null)
                                Text("البحث عن الأجهزة القريبة", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (state.requestingConnection) {
                        StatusPanel("تم إرسال طلب الاتصال", "بانتظار موافقة جهاز الاستقبال", true)
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
                            onClick = ::scanAllNearby,
                            modifier = Modifier.weight(1f),
                            enabled = !state.sending
                        ) {
                            Icon(Icons.Filled.Refresh, null)
                            Text(if (state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning) "إيقاف البحث" else "بحث شامل")
                        }
                        OutlinedButton(
                            onClick = ::scanQr,
                            modifier = Modifier.weight(1f),
                            enabled = !state.sending
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
                    OutlinedButton(onClick = ::scanBluetooth, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
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
                                if (state.approvedPeerHost != peer.host || !UltraShareTransport.isValidPairingSecret(secret)) {
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
                                    TextButton(onClick = { viewModel.reconnectPeer(peer.host) }, enabled = !state.requestingConnection && !state.sending) {
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
                                Button(onClick = {
                                    val sessionReady = state.approvedPeerHost == peer.host && UltraShareTransport.isValidPairingSecret(secret)
                                    host = peer.host
                                    page = UltraSharePage.SEND
                                    if (!sessionReady) {
                                        secret = ""
                                    }
                                }, enabled = !state.requestingConnection && !state.sending) { Text("اتصال") }
                            }
                        }
                    }
                    if (state.wifiDirectPeers.isNotEmpty()) {
                        Text("أجهزة Wi-Fi Direct (${state.wifiDirectPeers.size})", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.wifiDirectPeers.filter {
                            it.name.contains(search, true) || it.deviceAddress.contains(search, true)
                        }.forEach { peer ->
                            OutlinedButton(
                                onClick = { viewModel.connectWifiDirectPeer(peer) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.requestingConnection && !state.sending
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
                        activeMode = if (state.sending || host.isNotBlank() || selectedUris.isNotEmpty()) "send" else "receive",
                        localDeviceName = Build.MODEL?.take(40)?.ifBlank { "MalekPlay Android" } ?: "MalekPlay Android",
                        remoteHost = host.ifBlank { state.activePeers.firstOrNull()?.host.orEmpty() },
                        selectedMediaCount = selectedUris.size
                    )
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
                    if (state.peerTransfers.isEmpty() && state.sendProgress == null) InfoPanel("لا توجد عمليات نقل نشطة", "ستظهر هنا سرعة النقل وتقدم كل جهاز عند بدء المشاركة.")
                    if (state.sending) {
                        OutlinedButton(onClick = viewModel::pauseSending, modifier = Modifier.fillMaxWidth()) { Text("إيقاف مؤقت") }
                        OutlinedButton(onClick = viewModel::cancelSending, modifier = Modifier.fillMaxWidth()) { Text("إلغاء الإرسال") }
                    }
                    state.queueEntries.take(10).forEach { entry ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(entry.displayName, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                                Text(queueStatusLabel(entry.status), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                entry.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (entry.status == UltraShareQueueStore.STATUS_PENDING || entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                        TextButton(onClick = { viewModel.skipQueuedFile(entry) }) { Text("تخطّي") }
                                    }
                                    if (entry.status == UltraShareQueueStore.STATUS_FAILED) {
                                        TextButton(
                                            onClick = { viewModel.retryFile(entry, host) },
                                            enabled = !state.sending && host.isNotBlank() && host in state.approvedSenderPeerHosts
                                        ) { Text("إعادة المحاولة") }
                                    }
                                }
                            }
                        }
                    }
                    if (!state.sending && state.queueEntries.any {
                            it.status == UltraShareQueueStore.STATUS_PENDING || it.status == UltraShareQueueStore.STATUS_FAILED
                        }) {
                        OutlinedButton(
                            onClick = { viewModel.resumePendingBatch(host) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = host.isNotBlank() && host in state.approvedSenderPeerHosts
                        ) { Text("استئناف الملفات المتبقية") }
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
                        InfoPanel(entry.fileName, formatUltraBytes(entry.sizeBytes) + " • " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.receivedAtEpochMs)))
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
            if (page != UltraSharePage.RECEIVE && state.pendingConnection != null) {
                IncomingRequestPanel(state.pendingConnection!!, viewModel::acceptConnection, viewModel::rejectConnection)
            }
            state.error?.takeIf { page != UltraSharePage.SEND && page != UltraSharePage.NEARBY }?.let { ErrorPanel(it) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showDevicePicker) {
        DevicePickerBottomSheet(
            discoveredPeers = state.discoveredPeers,
            activePeers = state.activePeers,
            isScanning = state.scanning,
            onDismiss = { showDevicePicker = false },
            onRefresh = viewModel::scanNearbyDevices,
            onPeerSelected = { selectedHost ->
                showDevicePicker = false
                host = selectedHost
                page = UltraSharePage.SEND
                val sessionReady = state.approvedPeerHost == selectedHost && UltraShareTransport.isValidPairingSecret(secret)
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

@Composable
private fun IncomingRequestPanel(
    request: com.malekplay.network.UltraShareControlChannel.Request,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("طلب اتصال وارد", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
            Text(request.peerName + " • " + request.peerAddress, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("قبول") }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("رفض") }
            }
        }
    }
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
    if (!UltraShareTransport.isAllowedShareFile(displayName, mime)) {
        return@runCatching false
    }

    // A syntactically valid MIME type does not prove that a provider still grants
    // access to the content. Open the descriptor before accepting the URI.
    resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
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
