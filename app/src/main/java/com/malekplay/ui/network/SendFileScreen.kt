package com.malekplay.ui.network

import com.malekplay.network.UltraShareCore
import com.malekplay.network.UltraShareForegroundService
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.malekplay.core.ui.theme.MalekPlayColors

@Composable
fun SendFileScreen(
    state: UltraShareUiState,
    selectedUris: List<Uri>,
    onSelectedUrisChange: (List<Uri>) -> Unit,
    libraryFiles: List<MediaFile>,
    libraryLoading: Boolean,
    libraryError: String?,
    onLoadPhoneMedia: (MediaType) -> Unit,
    onPickMedia: (Array<String>) -> Unit,
    onScanQr: () -> Unit,
    onShowNearby: () -> Unit,
    onOpenDevicePicker: () -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    secret: String,
    onSecretChange: (String) -> Unit,
    onPastePairingDetails: () -> Unit,
    onRequestConnection: () -> Unit,
    onSendBatch: (String) -> Unit,
    onSendToAllPeers: () -> Unit,
    onResumeAllPeers: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    // Selecting a discovered peer opens the secure pairing fields immediately.
    var showManualPairing by remember(host) { mutableStateOf(host.isNotBlank() && host !in state.approvedSenderPeerHosts) }
    // Incoming authenticated pairing may approve a peer before this screen has a local host.
    val sendHost = host.ifBlank { state.approvedPeerHost ?: state.approvedSenderPeerHosts.firstOrNull().orEmpty() }
    val selectedPeerBusy = sendHost.isNotBlank() &&
        UltraShareForegroundService.isSendingToPeer(sendHost)
    val availablePeerCount = state.approvedSenderPeerHosts.count { peerHost ->
        !UltraShareForegroundService.isSendingToPeer(peerHost)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.approvedSenderPeerCount > 0 || state.sending || state.receiveProgress != null) {
            OutlinedButton(onClick = onOpenTransfers, modifier = Modifier.fillMaxWidth()) {
                Text("فتح غرفة النقل")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.VIDEO) }, modifier = Modifier.weight(1f), enabled = true) {
                Icon(Icons.Filled.Movie, null); Text("فيديو")
            }
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.AUDIO) }, modifier = Modifier.weight(1f), enabled = true) {
                Icon(Icons.Filled.FolderOpen, null); Text("موسيقى")
            }
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.IMAGE) }, modifier = Modifier.weight(1f), enabled = true) {
                Icon(Icons.Filled.Image, null); Text("صور")
            }
        }
        OutlinedButton(
            onClick = { onPickMedia(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = true
        ) {
            Text("اختيار ملفات من الهاتف (كل الأنواع المسموحة)")
        }
        if (libraryLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.padding(1.dp), strokeWidth = 2.dp)
                Text("جارٍ فهرسة الوسائط…", color = MalekPlayColors.TextSecondary)
            }
        }
        libraryError?.let { ErrorPanel(it) }
        if (libraryFiles.isNotEmpty()) {
            Text("مكتبة الهاتف • ${libraryFiles.size} عنصر", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(libraryFiles, key = { it.id }) { file ->
                    UltraShareMediaCard(file, file.uri in selectedUris, enabled = true) {
                        onSelectedUrisChange(if (file.uri in selectedUris) selectedUris - file.uri else selectedUris + file.uri)
                    }
                }
            }
        }
        InfoPanel("قائمة الإرسال", selectedUris.size.toString() + " عنصر محدد")
        if (selectedUris.isNotEmpty()) {
            OutlinedButton(onClick = { onSelectedUrisChange(emptyList()) }, modifier = Modifier.fillMaxWidth(), enabled = true) { Text("إفراغ القائمة") }
        }
        // Keep pairing/discovery available during another peer's transfer so
        // the user can add a second receiver without interrupting the first.
        Button(colors = ultraShareButtonColors(), onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode, null)
            Text(if (state.approvedSenderPeerCount == 0) "مسح QR لجهاز الاستقبال" else "إضافة جهاز آخر عبر QR")
        }
        OutlinedButton(onClick = onShowNearby, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.approvedSenderPeerCount == 0) "عرض الأجهزة القريبة" else "البحث عن جهاز آخر")
        }
        OutlinedButton(onClick = onOpenDevicePicker, modifier = Modifier.fillMaxWidth()) {
            Text("اختيار جهاز من نافذة الأجهزة القريبة")
        }
        if (sendHost.isNotBlank()) {
            InfoPanel("الجهاز المحدد", sendHost + " • " + when {
                sendHost in state.approvedSenderPeerHosts -> "متصل وموثّق"
                state.requestingConnection -> "جارٍ إكمال الاقتران"
                else -> "بانتظار الاقتران"
            })
        }
        OutlinedButton(onClick = { showManualPairing = !showManualPairing }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    showManualPairing -> "إخفاء بيانات الاقتران"
                    state.approvedSenderPeerCount == 0 -> "إظهار بيانات الاقتران"
                    else -> "إضافة جهاز آخر باستخدام PIN"
                }
            )
        }
        if (showManualPairing) {
            InfoPanel("إكمال الاتصال الآمن", "تم اختيار الجهاز. أدخل رمز الاقتران ذي 9 أرقام الظاهر على شاشة الاستقبال، أو امسح رمز QR الخاص به.")
            OutlinedTextField(value = host, onValueChange = onHostChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("عنوان الجهاز") })
            OutlinedTextField(value = secret, onValueChange = onSecretChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("رمز الاقتران (9 أرقام)") })
            OutlinedButton(onClick = onPastePairingDetails, modifier = Modifier.fillMaxWidth()) { Text("لصق بيانات الاقتران") }
            Button(colors = ultraShareButtonColors(),
                onClick = onRequestConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret) && !state.requestingConnection
            ) { Text("طلب اتصال يدوي") }
        }
        if (selectedUris.isNotEmpty()) {
            Button(colors = ultraShareButtonColors(),
                onClick = { onSendBatch(sendHost) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.approvedSenderPeerCount > 0 && sendHost.isNotBlank() && !selectedPeerBusy
            ) {
                Text(
                    if (selectedPeerBusy) "جارٍ الإرسال إلى هذا الجهاز"
                    else "إرسال ${selectedUris.size} ملف الآن"
                )
            }
            if (state.approvedSenderPeerCount > 1) {
                OutlinedButton(
                    onClick = onSendToAllPeers,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = availablePeerCount > 0
                ) {
                    Text("إرسال إلى الأجهزة المتاحة (${availablePeerCount})")
                }
            }
        }
            if (state.peerDeliveryEntries.any {
                    it.peerHost in state.approvedSenderPeerHosts &&
                        (it.status == com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_PENDING ||
                            it.status == com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_FAILED)
                }) {
                OutlinedButton(
                    onClick = onResumeAllPeers,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = availablePeerCount > 0
                ) {
                    Text("استئناف التسليمات غير المكتملة دون تكرار الملفات الناجحة")
                }
            }
        state.error?.let { ErrorPanel(it) }
        state.status?.let { InfoPanel("الحالة", it) }
    }
}

@Composable
private fun UltraShareMediaCard(file: MediaFile, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.width(148.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF38204F) else Color(0xFF21192D))
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (file.type == MediaType.AUDIO) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().height(104.dp),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.FolderOpen, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.padding(8.dp)) }
            } else {
                AsyncImage(model = file.thumbnailUrl ?: file.uri, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(104.dp))
            }
            Text(file.name, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Text(file.size, color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(if (selected) "محدد ✓" else "اضغط للتحديد", color = if (selected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall)
        }
    }
}
