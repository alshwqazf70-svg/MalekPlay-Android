package com.malekplay.ui.network

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.malekplay.network.UltraShareCore
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraSharePeerDeliveryStore

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
    onCancelPairing: () -> Unit = {},
    onSendBatch: (String) -> Unit,
    onSendToAllPeers: () -> Unit,
    onResumeAllPeers: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showManualPairing by remember { mutableStateOf(false) }
    val sendHost = host.ifBlank { state.approvedPeerHost ?: state.approvedSenderPeerHosts.firstOrNull().orEmpty() }
    val isConnected = state.approvedSenderPeerCount > 0 && sendHost.isNotBlank()
    val selectedPeerBusy = sendHost.isNotBlank() && UltraShareForegroundService.isSendingToPeer(sendHost)
    val availablePeerCount = state.approvedSenderPeerHosts.count { peerHost ->
        !UltraShareForegroundService.isSendingToPeer(peerHost)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Live connection / pairing card at the top
        if (isConnected) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = colors.primaryContainer.copy(alpha = 0.55f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                        Text(
                            text = if (state.approvedSenderPeerCount > 1) {
                                "متصل بـ ${state.approvedSenderPeerCount} أجهزة — جاهز للإرسال"
                            } else {
                                "متصل بجهاز $sendHost — جاهز للإرسال"
                            },
                            color = colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onOpenTransfers,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("غرفة النقل")
                        }
                        OutlinedButton(
                            onClick = onScanQr,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("إضافة جهاز QR")
                        }
                    }
                }
            }
        } else if (state.requestingConnection) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = colors.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "جارٍ الاقتران التلقائي...",
                            color = colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = state.status ?: "يتم الاتصال بالجهاز فوراً دون حاجة لتأكيد إضافي.",
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onCancelPairing) {
                        Text("إلغاء")
                    }
                }
            }
        } else {
            // Instant QR connect CTA
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "الاتصال السريع بجهاز الاستقبال",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "امسح رمز QR من هاتف الاستقبال ليتم الاتصال والإرسال تلقائياً دون تعقيد.",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        colors = ultraShareButtonColors(),
                        onClick = onScanQr,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مسح رمز QR للاتصال والإرسال", fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onShowNearby,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("الأجهزة القريبة", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onOpenDevicePicker,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("قائمة الأجهزة", maxLines = 1)
                        }
                    }
                }
            }
        }

        // 2. File selection section
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "اختر الملفات للإرسال",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Surface(shape = CircleShape, color = colors.primaryContainer) {
                        Text(
                            text = "${selectedUris.size} محدد",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onLoadPhoneMedia(MediaType.VIDEO) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("فيديو", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { onLoadPhoneMedia(MediaType.AUDIO) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("موسيقى", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { onLoadPhoneMedia(MediaType.IMAGE) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("صور", maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = { onPickMedia(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("اختيار ملفات من مدير الملفات")
                }

                if (libraryLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("جارٍ تحميل ملفات الهاتف…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                libraryError?.let { ErrorPanel(it) }

                if (libraryFiles.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(libraryFiles, key = { it.id }) { file ->
                            UltraShareMediaCard(
                                file = file,
                                selected = file.uri in selectedUris,
                                onClick = {
                                    onSelectedUrisChange(
                                        if (file.uri in selectedUris) selectedUris - file.uri
                                        else selectedUris + file.uri
                                    )
                                }
                            )
                        }
                    }
                }

                if (selectedUris.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSelectedUrisChange(emptyList()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("إفراغ القائمة")
                        }
                    }
                }
            }
        }

        // 3. Primary Send buttons when files are picked
        if (selectedUris.isNotEmpty()) {
            Button(
                colors = ultraShareButtonColors(),
                onClick = {
                    if (isConnected) onSendBatch(sendHost) else onScanQr()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !selectedPeerBusy
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.AutoMirrored.Filled.Send else Icons.Filled.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        selectedPeerBusy -> "جارٍ الإرسال إلى هذا الجهاز..."
                        isConnected -> "إرسال ${selectedUris.size} ملف فوراً"
                        else -> "امسح QR لإرسال ${selectedUris.size} ملف فوراً"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (state.approvedSenderPeerCount > 1) {
                OutlinedButton(
                    onClick = onSendToAllPeers,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = availablePeerCount > 0,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("إرسال إلى جميع الأجهزة المتصلة ($availablePeerCount)")
                }
            }
        }

        if (state.peerDeliveryEntries.any {
                it.peerHost in state.approvedSenderPeerHosts &&
                    (it.status == UltraSharePeerDeliveryStore.STATUS_PENDING ||
                        it.status == UltraSharePeerDeliveryStore.STATUS_FAILED)
            }
        ) {
            OutlinedButton(
                onClick = onResumeAllPeers,
                modifier = Modifier.fillMaxWidth(),
                enabled = availablePeerCount > 0,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("استئناف التسليمات غير المكتملة")
            }
        }

        // 4. Optional manual pairing (collapsed by default)
        TextButton(
            onClick = { showManualPairing = !showManualPairing },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (showManualPairing) "إخفاء الاتصال اليدوي" else "اتصال يدوي (IP ورمز الاقتران)")
            Icon(
                imageVector = if (showManualPairing) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        if (showManualPairing) {
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("عنوان IP للجهاز") }
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = onSecretChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("رمز الاقتران (9 أرقام)") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPastePairingDetails,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("لصق QR")
                        }
                        Button(
                            colors = ultraShareButtonColors(),
                            onClick = onRequestConnection,
                            modifier = Modifier.weight(1f),
                            enabled = host.isNotBlank() && UltraShareCore.isValidPairingSecret(secret) && !state.requestingConnection,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("اتصال")
                        }
                    }
                }
            }
        }

        state.error?.let { ErrorPanel(it) }
        state.status?.let { InfoPanel("حالة الاتصال", it) }
    }
}

@Composable
private fun UltraShareMediaCard(
    file: MediaFile,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .width(142.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) colors.primaryContainer.copy(alpha = 0.6f) else colors.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
            ) {
                if (file.type == MediaType.AUDIO) {
                    Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = colors.primary, modifier = Modifier.size(34.dp))
                    }
                } else {
                    AsyncImage(
                        model = file.thumbnailUrl ?: file.uri,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
                if (selected) {
                    Surface(
                        shape = CircleShape,
                        color = colors.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(
                text = file.name,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file.size,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
