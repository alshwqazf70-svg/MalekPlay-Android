package com.malekplay.ui.network

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.contrastingOnColor

@Composable
fun ReceiveScreen(
    state: UltraShareUiState,
    qrBitmap: Bitmap?,
    qrPayload: String?,
    onCopyQr: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onToggleReceiving: () -> Unit,
    onOpenTransfers: () -> Unit,
    onSendToGroup: () -> Unit,
    onOpenReceived: (Uri, String) -> Unit,
    onExportReceived: (String) -> Unit
) {
    var showAdvancedPairing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val connectedNames = (
        state.activePeers.map { it.name } +
            state.approvedSenderPeerHosts.filterNot { peerHost -> state.activePeers.any { it.host == peerHost } }
        ).distinct()
    val hasConnectedPeers = connectedNames.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Connected status card (when peers joined)
        if (hasConnectedPeers) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.45f), RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = colors.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "تم الاقتران بنجاح (${connectedNames.size})",
                            color = colors.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "الأجهزة المتصلة: ${connectedNames.joinToString("، ")}\nالقبول التلقائي مفعّل — الإرسال والاستقبال متاحان فوراً.",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenTransfers,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ultraShareButtonColors()
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("غرفة النقل", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(
                            onClick = onSendToGroup,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إرسال للمجموعة", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (state.receiving) {
            // Hero QR card
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.5.dp,
                        Brush.linearGradient(listOf(colors.primary, colors.secondary)),
                        RoundedCornerShape(26.dp)
                    ),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.QrCode, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                            Text(
                                text = android.os.Build.MODEL.orEmpty().ifBlank { "MalekPlayer Android" },
                                color = colors.onPrimaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "امسح رمز QR من الهاتف الآخر",
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "بمجرد المسح يتم الاقتران وقبول الإرسال والاستقبال تلقائياً دون أي تعقيد",
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // High-contrast pure white frame for effortless camera recognition
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White)
                            .border(2.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "رمز QR للانضمام إلى شبكة MalekPlayer",
                                modifier = Modifier.size(250.dp)
                            )
                        } else {
                            Column(
                                modifier = Modifier.size(220.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = colors.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = when {
                                        !state.controlChannelReady -> "جارٍ تشغيل قناة الاقتران الآمنة…"
                                        state.hotspotStarting -> "جارٍ تجهيز نقطة الاتصال المحلية…"
                                        state.invitationId.isNotBlank() -> "جارٍ تجهيز رمز QR المباشر…"
                                        !state.lanFallbackReady && !state.hotspotActive -> "جارٍ فحص أسرع مسار اتصال…"
                                        else -> "جارٍ توليد رمز QR…"
                                    },
                                    color = Color(0xFF475569),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    state.hotspotSsid?.takeIf { it.isNotBlank() }?.let { ssid ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Filled.Wifi, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "الشبكة: $ssid",
                                    color = colors.onSurface,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Advanced / manual fallback details (collapsed by default)
                    TextButton(onClick = { showAdvancedPairing = !showAdvancedPairing }) {
                        Text(if (showAdvancedPairing) "إخفاء التفاصيل اليدوية" else "خيارات الاتصال اليدوي / نسخ QR")
                        Icon(
                            imageVector = if (showAdvancedPairing) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (showAdvancedPairing) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceContainerLowest)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "رمز الاقتران اليدوي: ${state.pairingSecret.ifBlank { "—" }}",
                                color = colors.primary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val displayedAddresses = (state.localAddresses + listOfNotNull(state.wifiDirectLocalAddress)).distinct()
                            Text(
                                text = "عناوين الشبكة: " + displayedAddresses.joinToString(" • ").ifBlank {
                                    if (state.invitationId.isNotBlank()) "Wi-Fi Direct جاهز" else "جارٍ التحديد..."
                                },
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = onCopyQr,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !qrPayload.isNullOrBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("نسخ بيانات الدعوة")
                            }
                        }
                    }
                }
            }
        } else {
            ShareInfoCard(
                title = "الغرفة متوقفة",
                body = "اضغط «بدء الغرفة وعرض QR» ليتمكن الهاتف الآخر من المسح والاتصال فوراً."
            )
        }

        // Hotspot toggle & Room toggle buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.hotspotActive) {
                OutlinedButton(
                    onClick = onStopHotspot,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("إيقاف نقطة الاتصال", maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = onStartHotspot,
                    modifier = Modifier.weight(1f),
                    enabled = !state.hotspotStarting,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.hotspotStarting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نقطة اتصال محلية", maxLines = 1)
                }
            }

            Button(
                onClick = onToggleReceiving,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ultraShareButtonColors()
            ) {
                Text(
                    text = if (state.receiving) "إيقاف الغرفة" else "بدء الغرفة وعرض QR",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        if (state.hotspotActive) {
            ShareInfoCard(
                title = "نقطة اتصال MalekPlayer نشطة",
                body = "اسم الشبكة: ${state.hotspotSsid.orEmpty()}\nكلمة المرور: ${state.hotspotPassword ?: "غير متاحة"}"
            )
        }

        state.receivedFile?.let { received ->
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("آخر ملف مستلم", color = colors.primary, fontWeight = FontWeight.Bold)
                    Text(
                        "${received.fileName} • ${formatTransferBytes(received.sizeBytes)}",
                        color = colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        received.publishedUri?.let { uri ->
                            FilledTonalButton(
                                onClick = { onOpenReceived(uri, received.mimeType) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فتح الملف")
                            }
                        }
                        OutlinedButton(
                            onClick = { onExportReceived(received.fileName) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("حفظ نسخة")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareInfoCard(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = colors.primary, fontWeight = FontWeight.Bold)
            Text(body, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Keeps primary-action labels high-contrast across every selected theme accent. */
@Composable
internal fun ultraShareButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = contrastingOnColor(MaterialTheme.colorScheme.primary),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)
