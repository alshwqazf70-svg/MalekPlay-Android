package com.malekplay.ui.network

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors

private val ShareWhite = Color(0xFFFDFEFF)
private val ShareCyan = Color(0xFF3D72D4)

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
    onExportReceived: (String) -> Unit,
    onExtractReceivedFolder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (state.receiving) "جاري انتظار انضمام الأصدقاء…" else "إنشاء مجموعة",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        if (state.receiving) {
            if (state.activePeers.isNotEmpty()) {
                val connectedNames = state.activePeers.joinToString("، ") { it.name }
                ShareInfoCard(
                    title = "تم الاتصال بنجاح",
                    body = "متصل الآن مع: $connectedNames\nرمز QR مخفي لأن الاقتران اكتمل. يمكنك إرسال واستقبال ملفات إضافية دون إعادة الاتصال."
                )
            } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = ShareWhite)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "اطلب من صديقك مسح رمز QR؛ سيحاول MalekPlay إنشاء الاتصال المباشر تلقائياً",
                        color = Color(0xFF30394C),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFFF1F5FC), RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("اسم المجموعة", color = Color(0xFF69758B), style = MaterialTheme.typography.labelMedium)
                        Text(
                            android.os.Build.MODEL.orEmpty().ifBlank { "MalekPlay Android" },
                            color = Color(0xFF30394C),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("كلمة المرور", color = Color(0xFF69758B), style = MaterialTheme.typography.labelMedium)
                        Text(
                            state.pairingSecret.ifBlank { "جارٍ إنشاء رمز الاقتران…" },
                            color = Color(0xFF3568C6),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "رمز QR للانضمام إلى مجموعة MalekPlay",
                                modifier = Modifier.size(290.dp)
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = ShareCyan)
                                Text("جارٍ تجهيز رمز QR…", color = Color(0xFF59677E))
                            }
                        }
                    }
                    Text(
                        "شغّل Wi-Fi وفعّل أذونات الأجهزة القريبة/الموقع عند طلبها؛ لا يلزم الانضمام يدوياً إلى شبكة Wi-Fi",
                        color = Color(0xFF46536A),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            ShareInfoCard(
                title = "حالة المجموعة",
                body = if (state.pendingConnection != null) {
                    "طلب اتصال من ${state.pendingConnection.peerName} قيد التحقق."
                } else {
                    "المجموعة جاهزة لاقتران جهاز آخر؛ يبدأ الاتصال تلقائياً بعد التحقق من QR أو الرمز."
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = Color(0xFFC084FC), modifier = Modifier.size(26.dp))
                Text(
                    "يحاول MalekPlay الاتصال عبر Wi-Fi Direct تلقائياً. إذا لم يدعمه أحد الجهازين، يمكن استخدام شبكة محلية أو نقطة اتصال كمسار بديل.",
                    color = Color(0xFFE4E9F5),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                "عناوين الشبكة: ${state.localAddresses.joinToString(" • ").ifBlank { "جارٍ تجهيز عنوان الشبكة..." }}",
                color = Color(0xFFD9E2F4),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onCopyQr,
                modifier = Modifier.fillMaxWidth(),
                enabled = !qrPayload.isNullOrBlank()
            ) {
                Icon(Icons.Filled.QrCode, contentDescription = null)
                Text("نسخ بيانات QR", modifier = Modifier.padding(start = 8.dp))
            }
            }
        } else {
            ShareInfoCard(
                title = "الاستقبال متوقف",
                body = "ابدأ الاستقبال لإنشاء مجموعة ورمز اتصال"
            )
        }

        if (state.hotspotActive) {
            ShareInfoCard(
                title = "شبكة MalekPlay",
                body = "اسم الشبكة: ${state.hotspotSsid.orEmpty()}\nكلمة المرور: ${state.hotspotPassword ?: "غير متاحة"}"
            )
            OutlinedButton(onClick = onStopHotspot, modifier = Modifier.fillMaxWidth()) {
                Text("إيقاف نقطة الاتصال")
            }
        } else {
            OutlinedButton(
                onClick = onStartHotspot,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.hotspotStarting
            ) {
                if (state.hotspotStarting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Wifi, contentDescription = null)
                Text("إنشاء نقطة اتصال محلية", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(onClick = onToggleReceiving, modifier = Modifier.fillMaxWidth(), colors = ultraShareButtonColors()) {
            Text(if (state.receiving) "إيقاف الاستقبال" else "بدء الاستقبال")
        }

        state.pendingConnection?.let { request ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF53678D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("طلب اتصال وارد", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(request.peerName + " • " + request.peerAddress, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAcceptConnection, modifier = Modifier.weight(1f), colors = ultraShareButtonColors()) { Text("قبول") }
                        OutlinedButton(onClick = onRejectConnection, modifier = Modifier.weight(1f)) { Text("رفض") }
                    }
                }
            }
        }
        state.receivedFile?.let { received ->
            ShareInfoCard("آخر ملف مستلم", received.fileName + "\n" + formatTransferBytes(received.sizeBytes))
            received.publishedUri?.let { uri ->
                OutlinedButton(onClick = { onOpenReceived(uri, received.mimeType) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("فتح الملف المستلم", modifier = Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = { onExportReceived(received.fileName) }, modifier = Modifier.fillMaxWidth()) {
                Text("حفظ نسخة في مكان آخر")
            }
            if (received.mimeType.equals("application/zip", ignoreCase = true) ||
                received.fileName.endsWith(".zip", ignoreCase = true)
            ) {
                OutlinedButton(onClick = onExtractReceivedFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Text("استخراج المجلد إلى مكان تختاره", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ShareInfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF465477))
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = ShareCyan, fontWeight = FontWeight.Bold)
            Text(body, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}


/** Keeps primary-action labels readable even when an action is temporarily disabled. */
@Composable
internal fun ultraShareButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF9B5DE5),
    contentColor = Color.White,
    disabledContainerColor = Color(0xFF56386F),
    disabledContentColor = Color(0xFFF7EEFF)
)
