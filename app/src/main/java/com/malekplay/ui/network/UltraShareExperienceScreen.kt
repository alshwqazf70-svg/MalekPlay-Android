package com.malekplay.ui.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.weight
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareQueueStore
import com.malekplay.network.UltraShareQrCode
import com.malekplay.network.UltraShareTransport
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
    var search by remember { mutableStateOf("") }
    var selectedUris by remember(initialUris) {
        mutableStateOf(initialUris.distinct().filter { isUltraShareMedia(context, it) })
    }

    fun handleQr(raw: String) {
        runCatching { UltraShareQrCode.parsePayload(raw) }
            .onSuccess { pairing ->
                host = pairing.host
                secret = pairing.pairingSecret
                viewModel.clearApprovedConnection()
                viewModel.requestConnection(pairing.host, pairing.pairingSecret)
                page = UltraSharePage.SEND
                Toast.makeText(context, "تمت قراءة الرمز وإرسال طلب الاقتران.", Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Toast.makeText(context, "رمز QR غير صالح أو لا يخص MalekPlay.", Toast.LENGTH_LONG).show()
            }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val accepted = picked.distinct().filter { isUltraShareMedia(context, it) }
        if (accepted.size != picked.distinct().size) {
            Toast.makeText(context, "يمكن مشاركة الفيديوهات والموسيقى والصور فقط.", Toast.LENGTH_LONG).show()
        }
        accepted.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        selectedUris = (selectedUris + accepted).distinct()
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
                    viewModel.clearApprovedConnection()
                    viewModel.requestConnection(pairing.host, pairing.pairingSecret)
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
        if (grants.values.all { it }) viewModel.scanWifiDirectPeers()
        else Toast.makeText(context, "يلزم إذن الأجهزة القريبة لاستخدام Wi-Fi Direct.", Toast.LENGTH_LONG).show()
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
    fun startHotspot() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startHotspot() else hotspotPermission.launch(missing.toTypedArray())
    }

    BackHandler(enabled = page != UltraSharePage.HOME) {
        page = UltraSharePage.HOME
    }

    LaunchedEffect(initialUris) {
        val allowed = initialUris.distinct().filter { isUltraShareMedia(context, it) }
        if (allowed.size != initialUris.distinct().size) {
            Toast.makeText(context, "تم تجاهل العناصر التي ليست فيديو أو موسيقى أو صوراً.", Toast.LENGTH_LONG).show()
        }
        val failedGrants = allowed.count { uri ->
            val resolver = context.contentResolver
            val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            !alreadyGranted && runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isFailure
        }
        if (failedGrants > 0) {
            Toast.makeText(context, "تعذر تثبيت إذن الوصول لبعض الملفات المشتركة؛ قد تحتاج إلى إعادة اختيارها.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!state.receiving) viewModel.startReceiving()
        if (!state.scanning) viewModel.scanNearbyDevices()
    }

    val qrPayload = state.localAddresses.firstOrNull()
        ?.takeIf { state.receiving && state.pairingSecret.isNotBlank() }
        ?.let { UltraShareQrCode.createReceiverPayload(it, state.pairingSecret) }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { runCatching { UltraShareQrCode.createBitmap(it) }.getOrNull() }
    }
    val visiblePeers = state.discoveredPeers.filter {
        it.name.contains(search, true) || it.host.contains(search, true)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF21152D), Color(0xFF0D0D12), Color(0xFF11101A))))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (page == UltraSharePage.HOME) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MalekPlayColors.TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text("MALEKPLAY • ULTRA SHARE", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                    Text("مشاركة الملفات", color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text("اختر طريقة المشاركة", color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(32.dp))
            }
            Box(Modifier.fillMaxWidth().height(226.dp), contentAlignment = Alignment.Center) {
                UltraShareRadar(
                    isScanning = state.scanning,
                    discoveredCount = state.discoveredPeers.size,
                    modifier = Modifier.size(218.dp)
                )
                Box(
                    Modifier.size(78.dp).background(Color(0xFF241531), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = "رادار الأجهزة القريبة", tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(38.dp))
                }
            }
            StatusPanel(
                title = when {
                    state.sending -> "جارٍ إرسال الملفات"
                    state.pendingConnection != null -> "طلب اتصال وارد"
                    state.activePeers.isNotEmpty() -> "جهاز متصل وجاهز"
                    state.scanning -> "البحث عن الأجهزة القريبة"
                    else -> "جاهز للمشاركة"
                },
                subtitle = state.discoveredPeers.size.toString() + " جهاز قريب • " + selectedUris.size + " ملف محدد",
                active = state.sending || state.activePeers.isNotEmpty()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Modifier.weight(1f), "إرسال", "فيديو • موسيقى • صور", { Icon(Icons.Filled.Send, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(30.dp)) }) {
                    page = UltraSharePage.SEND
                }
                ActionTile(Modifier.weight(1f), "استقبال", "إنشاء غرفة استقبال", { Icon(Icons.Filled.Download, null, tint = Color(0xFF65D6A2), modifier = Modifier.size(30.dp)) }) {
                    if (!state.receiving) viewModel.startReceiving()
                    page = UltraSharePage.RECEIVE
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Modifier.weight(1f), "الأجهزة القريبة", "الرادار والاتصال", { Icon(Icons.Filled.Wifi, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(28.dp)) }) {
                    if (!state.scanning) viewModel.scanNearbyDevices()
                    page = UltraSharePage.NEARBY
                }
                ActionTile(Modifier.weight(1f), "سجل النقل", "المرسل والمستلم", { Icon(Icons.Filled.History, null, tint = MalekPlayColors.Amber, modifier = Modifier.size(28.dp)) }) {
                    page = UltraSharePage.HISTORY
                }
            }
            ActionTile(Modifier.fillMaxWidth(), "الانضمام إلى مجموعة", "امسح رمز QR الذي يعرضه جهاز الاستقبال", { Icon(Icons.Filled.QrCode, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(28.dp)) }) {
                page = UltraSharePage.JOIN
            }
            ActionTile(Modifier.fillMaxWidth(), "غرفة النقل المباشر", "الأجهزة المتصلة وسرعة وتقدم كل ملف", { Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF65D6A2), modifier = Modifier.size(28.dp)) }) {
                page = UltraSharePage.TRANSFERS
            }
            state.pendingConnection?.let {
                IncomingRequestPanel(it, viewModel::acceptConnection, viewModel::rejectConnection)
            }
        } else {
            PageHeader(
                title = when (page) {
                    UltraSharePage.SEND -> "إرسال الوسائط"
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
                            if (!state.scanning) viewModel.scanNearbyDevices()
                        },
                        onOpenDevicePicker = {
                            showDevicePicker = true
                            if (!state.scanning) viewModel.scanNearbyDevices()
                        },
                        host = host,
                        onHostChange = { value ->
                            if (value.trim() != host) viewModel.clearApprovedConnection()
                            host = value.trim()
                        },
                        secret = secret,
                        onSecretChange = { value ->
                            viewModel.clearApprovedConnection()
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
                                        viewModel.clearApprovedConnection()
                                        viewModel.requestConnection(pairing.host, pairing.pairingSecret)
                                    }
                                    .onFailure { Toast.makeText(context, "بيانات الاقتران في الحافظة غير صالحة.", Toast.LENGTH_LONG).show() }
                            }
                        },
                        onRequestConnection = { viewModel.requestConnection(host, secret) },
                        onSendBatch = {
                            if (viewModel.sendBatch(selectedUris, host, secret)) selectedUris = emptyList()
                        }
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
                        onToggleReceiving = { if (state.receiving) viewModel.stopReceiving() else viewModel.startReceiving() },
                        onAcceptConnection = viewModel::acceptConnection,
                        onRejectConnection = viewModel::rejectConnection,
                        onOpenReceived = { uri, mimeType ->
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, "لا يوجد تطبيق مناسب لفتح الملف.", Toast.LENGTH_LONG).show() }
                        },
                        onExportReceived = { fileName -> exportLauncher.launch(fileName) }
                    )
                }
                UltraSharePage.JOIN -> {
                    InfoPanel("الانضمام الآمن", "افتح شاشة الاستقبال على الهاتف الآخر، ثم وجّه كاميرا هاتفك إلى رمز QR الظاهر هناك. لن يبدأ نقل الملفات قبل موافقة الطرفين.")
                    Button(onClick = ::scanQr, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Icon(Icons.Filled.QrCode, null)
                        Text("فتح ماسح رمز QR بالكاميرا")
                    }
                    OutlinedButton(onClick = { qrImagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
                        Text("قراءة QR من صورة محفوظة")
                    }
                    if (state.requestingConnection) {
                        StatusPanel("تم إرسال طلب الاتصال", "بانتظار موافقة جهاز الاستقبال", true)
                    }
                    state.error?.let { ErrorPanel(it) }
                    state.status?.let { InfoPanel("حالة الاقتران", it) }
                }

                UltraSharePage.NEARBY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.scanNearbyDevices() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Refresh, null); Text(if (state.scanning) "إيقاف البحث" else "بحث")
                        }
                        OutlinedButton(onClick = ::scanWifiDirect, modifier = Modifier.weight(1f)) { Text("Wi-Fi Direct") }
                    }
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("البحث عن جهاز") })
                    if (state.activePeers.isNotEmpty()) {
                        Text("الأجهزة المتصلة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                        state.activePeers.forEach { peer ->
                            InfoPanel(peer.name, peer.host + " • " + peer.role + "\n" + peer.status)
                            TextButton(onClick = { viewModel.disconnectPeer(peer.host) }) { Text("قطع الاتصال") }
                        }
                    }
                    Text("الأجهزة المكتشفة (" + visiblePeers.size + ")", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (visiblePeers.isEmpty()) InfoPanel("الرادار", if (state.scanning) "جارٍ البحث عن الأجهزة القريبة..." else "لا توجد أجهزة ظاهرة حالياً. تأكد من اتصال الهاتفين بالشبكة نفسها.")
                    visiblePeers.forEach { peer ->
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
                                        viewModel.clearApprovedConnection()
                                        scanQr()
                                    }
                                }, enabled = !state.requestingConnection && !state.sending) { Text("اتصال") }
                            }
                        }
                    }
                    state.wifiDirectPeers.forEach { peer ->
                        OutlinedButton(onClick = { viewModel.connectWifiDirectPeer(peer) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Wi-Fi Direct • " + peer.name + " • " + peer.status)
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
                                            onClick = { viewModel.retryFile(entry, host, secret) },
                                            enabled = !state.sending && host.isNotBlank() && state.approvedPeerHost == host && UltraShareTransport.isValidPairingSecret(secret)
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
                            onClick = { viewModel.resumePendingBatch(host, secret) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = host.isNotBlank() && state.approvedPeerHost == host && UltraShareTransport.isValidPairingSecret(secret)
                        ) { Text("استئناف الملفات المتبقية") }
                    }
                }

                UltraSharePage.HISTORY -> {
                    Text("الملفات المرسلة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    if (state.queueEntries.isEmpty()) InfoPanel("لا يوجد سجل إرسال", "ستظهر الملفات التي أرسلتها هنا.")
                    state.queueEntries.take(30).forEach { entry ->
                        InfoPanel(entry.displayName, queueStatusLabel(entry.status) + (entry.error?.let { "\n" + it } ?: ""))
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
                    viewModel.clearApprovedConnection()
                    scanQr()
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

private fun isUltraShareMedia(context: Context, uri: Uri): Boolean = runCatching {
    val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
    mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")
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
