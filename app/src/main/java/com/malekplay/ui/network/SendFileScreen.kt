package com.malekplay.ui.network

import com.malekplay.network.UltraShareTransport
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
    onPickFolder: () -> Unit,
    onScanQr: () -> Unit,
    onShowNearby: () -> Unit,
    onOpenDevicePicker: () -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    secret: String,
    onSecretChange: (String) -> Unit,
    onPastePairingDetails: () -> Unit,
    onRequestConnection: () -> Unit,
    onSendBatch: () -> Unit
) {
    // Selecting a discovered peer opens the secure pairing fields immediately.
    var showManualPairing by remember(host) { mutableStateOf(host.isNotBlank() && state.approvedPeerHost != host) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.VIDEO) }, modifier = Modifier.weight(1f), enabled = !state.sending) {
                Icon(Icons.Filled.Movie, null); Text("فيديو")
            }
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.AUDIO) }, modifier = Modifier.weight(1f), enabled = !state.sending) {
                Icon(Icons.Filled.FolderOpen, null); Text("موسيقى")
            }
            OutlinedButton(onClick = { onLoadPhoneMedia(MediaType.IMAGE) }, modifier = Modifier.weight(1f), enabled = !state.sending) {
                Icon(Icons.Filled.Image, null); Text("صور")
            }
        }
        OutlinedButton(onClick = { onPickMedia(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
            Text("اختيار جميع أنواع الملفات")
        }
        OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
            Icon(Icons.Filled.FolderOpen, null)
            Text("إرسال مجلد كامل مع الحفاظ على بنيته")
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
                    UltraShareMediaCard(file, file.uri in selectedUris) {
                        onSelectedUrisChange(if (file.uri in selectedUris) selectedUris - file.uri else selectedUris + file.uri)
                    }
                }
            }
        }
        InfoPanel("قائمة الإرسال", selectedUris.size.toString() + " عنصر محدد")
        if (selectedUris.isNotEmpty()) {
            OutlinedButton(onClick = { onSelectedUrisChange(emptyList()) }, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) { Text("إفراغ القائمة") }
        }
        Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) {
            Icon(Icons.Filled.QrCode, null); Text("مسح QR لجهاز الاستقبال")
        }
        OutlinedButton(onClick = onShowNearby, modifier = Modifier.fillMaxWidth()) { Text("عرض الأجهزة القريبة") }
        OutlinedButton(onClick = onOpenDevicePicker, modifier = Modifier.fillMaxWidth(), enabled = !state.sending) { Text("اختيار جهاز من نافذة الأجهزة القريبة") }
        if (host.isNotBlank()) {
            InfoPanel("الجهاز المحدد", host + " • " + when {
                state.approvedPeerHost == host -> "متصل وموثّق"
                state.requestingConnection -> "بانتظار الموافقة"
                else -> "بانتظار الاقتران"
            })
        }
        OutlinedButton(onClick = { showManualPairing = !showManualPairing }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showManualPairing) "إخفاء بيانات الاقتران" else "إظهار بيانات الاقتران")
        }
        if (showManualPairing) {
            InfoPanel("إكمال الاتصال الآمن", "تم اختيار الجهاز. أدخل رمز الاقتران ذي 9 أرقام الظاهر على شاشة الاستقبال، أو امسح رمز QR الخاص به.")
            OutlinedTextField(value = host, onValueChange = onHostChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("عنوان الجهاز") })
            OutlinedTextField(value = secret, onValueChange = onSecretChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("رمز الاقتران (9 أرقام)") })
            OutlinedButton(onClick = onPastePairingDetails, modifier = Modifier.fillMaxWidth()) { Text("لصق بيانات الاقتران") }
            Button(
                onClick = onRequestConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret) && !state.requestingConnection
            ) { Text("طلب اتصال يدوي") }
        }
        if (selectedUris.isNotEmpty()) {
            Button(
                onClick = onSendBatch,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.sending && host.isNotBlank() && state.approvedPeerHost == host && UltraShareTransport.isValidPairingSecret(secret)
            ) { Text("إرسال ${selectedUris.size} ملف الآن") }
        }
        state.error?.let { ErrorPanel(it) }
        state.status?.let { InfoPanel("الحالة", it) }
    }
}

@Composable
private fun UltraShareMediaCard(file: MediaFile, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
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
