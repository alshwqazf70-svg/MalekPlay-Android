package com.malekplay.ui.network

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors

@Composable
fun ReceiveScreen(
    state: UltraShareUiState,
    qrBitmap: Bitmap?,
    qrPayload: String?,
    onCopyQr: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onToggleReceiving: () -> Unit,
    onAcceptConnection: () -> Unit,
    onRejectConnection: () -> Unit,
    onOpenReceived: (Uri, String) -> Unit,
    onExportReceived: (String) -> Unit
) {
    ReceiveInfoCard(
        title = if (state.receiving) "هاتفك جاهز للاستقبال" else "الاستقبال متوقف",
        body = if (state.receiving) "امسح رمز QR من هاتف الإرسال" else "ابدأ الاستقبال لإنشاء رمز الاتصال",
        active = state.receiving
    )
    if (state.receiving) {
        ReceiveInfoCard("رمز الاقتران", state.pairingSecret.chunked(3).joinToString(" "))
        ReceiveInfoCard("عناوين الشبكة", state.localAddresses.joinToString("\n").ifBlank { "جارٍ تجهيز عنوان الشبكة..." })
        qrBitmap?.let { bitmap ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(bitmap.asImageBitmap(), contentDescription = "رمز QR للاتصال", modifier = Modifier.size(220.dp))
                    Text("امسح الرمز من هاتف الإرسال", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
        OutlinedButton(onClick = onCopyQr, modifier = Modifier.fillMaxWidth(), enabled = !qrPayload.isNullOrBlank()) {
            Icon(Icons.Filled.QrCode, contentDescription = null)
            Text("نسخ بيانات QR", modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (state.hotspotActive) {
        ReceiveInfoCard("شبكة MalekPlay", "اسم الشبكة: ${state.hotspotSsid.orEmpty()}\nكلمة المرور: ${state.hotspotPassword ?: "غير متاحة"}")
        OutlinedButton(onClick = onStopHotspot, modifier = Modifier.fillMaxWidth()) { Text("إيقاف نقطة الاتصال") }
    } else {
        OutlinedButton(onClick = onStartHotspot, modifier = Modifier.fillMaxWidth(), enabled = !state.hotspotStarting) {
            if (state.hotspotStarting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Wifi, contentDescription = null)
            Text("إنشاء نقطة اتصال محلية", modifier = Modifier.padding(start = 8.dp))
        }
    }
    Button(onClick = onToggleReceiving, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.receiving) "إيقاف الاستقبال" else "بدء الاستقبال")
    }
    state.pendingConnection?.let { request ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("طلب اتصال وارد", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
                Text(request.peerName + " • " + request.peerAddress, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAcceptConnection, modifier = Modifier.weight(1f)) { Text("قبول") }
                    OutlinedButton(onClick = onRejectConnection, modifier = Modifier.weight(1f)) { Text("رفض") }
                }
            }
        }
    }
    state.receivedFile?.let { received ->
        ReceiveInfoCard("آخر ملف مستلم", received.fileName + "\n" + formatTransferBytes(received.sizeBytes))
        received.publishedUri?.let { uri ->
            OutlinedButton(onClick = { onOpenReceived(uri, received.mimeType) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text("فتح الملف المستلم", modifier = Modifier.padding(start = 8.dp))
            }
        }
        OutlinedButton(onClick = { onExportReceived(received.fileName) }, modifier = Modifier.fillMaxWidth()) {
            Text("حفظ نسخة في مكان آخر")
        }
    }
}

@Composable
private fun ReceiveInfoCard(title: String, body: String, active: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF17372D) else Color(0xFF21192D))
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (active) Color(0xFF65D6A2) else MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(body, color = if (active) Color(0xFFD8F7E8) else MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
