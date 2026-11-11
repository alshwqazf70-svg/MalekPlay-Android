package com.malekplay.ui.network

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.scale
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareQueueStore
@Composable
fun UltraShareSessionRoom(
    state: UltraShareUiState,
    activeMode: String,
    localDeviceName: String,
    remoteHost: String,
    selectedMediaCount: Int
) {
    val isSendingRoom = activeMode == "send"
    val activePeer = state.activePeers.firstOrNull()
    val remoteName = activePeer?.name
        ?: state.pendingConnection?.peerName
        ?: when {
            state.requestingConnection -> "جارٍ التحقق من الجهاز"
            remoteHost.isNotBlank() -> remoteHost
            isSendingRoom -> "بانتظار جهاز الاستقبال"
            else -> "بانتظار جهاز الإرسال"
        }
    val connected = activePeer != null || state.approvedPeerHost != null
    val pairingPending = state.requestingConnection || state.pendingConnection != null
    val transferring = state.sending || state.sendProgress != null || state.receiveProgress != null
    val currentBatchEntries = if (state.queueTotal > 0) state.queueEntries.takeLast(state.queueTotal) else emptyList()
    val sendCompleted = !state.sending && state.queueTotal > 0 &&
        state.queueCompleted >= state.queueTotal && currentBatchEntries.size == state.queueTotal &&
        currentBatchEntries.all { it.status == UltraShareQueueStore.STATUS_COMPLETED }
    val receiveCompleted = !isSendingRoom && state.receivedFile != null && state.receiveProgress == null &&
        (state.status?.contains("استلام") == true || state.status?.contains("استُلم") == true || state.status?.contains("اكتملت دفعة") == true)
    val status = when {
        state.sending -> "يتم إرسال الوسائط إلى الجهاز الآخر"
        state.receiveProgress != null -> "يتم استقبال الوسائط من الجهاز الآخر"
        state.sendProgress != null -> "جارٍ نقل العنصر الحالي"
        state.pendingConnection != null -> "طلب اتصال وارد — بانتظار موافقتك"
        state.requestingConnection -> "جارٍ التحقق من الاقتران — ننتظر رد الجهاز الآخر"
        connected -> "اتصال موثّق — الجهازان جاهزان للنقل"
        !isSendingRoom && state.receiving -> "هذا الهاتف ظاهر وجاهز لاستقبال الوسائط"
        !isSendingRoom -> "ابدأ الاستقبال لعرض رمز الربط واستقبال الوسائط"
        selectedMediaCount > 0 -> "${selectedMediaCount} عنصر وسائط محدد — اختر جهاز الاستقبال"
        state.scanning -> "نبحث عن أجهزة MalekPlay القريبة"
        else -> "اختر الوسائط ثم اربط الجهازين"
    }
    val statusColor = when {
        transferring -> MalekPlayColors.PurpleBright
        connected -> Color(0xFF65D6A2)
        pairingPending -> Color(0xFFFFC66D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val senderName = if (isSendingRoom) localDeviceName else remoteName
    val remoteAddress = activePeer?.host ?: state.approvedPeerHost ?: remoteHost
    val receiverName = if (isSendingRoom) remoteName else localDeviceName
    val progress = if (isSendingRoom) state.sendProgress else state.receiveProgress
    val overallFraction = if (state.queueTotalBytes > 0L) {
        (state.queueTransferredBytes.toDouble() / state.queueTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("غرفة الاتصال والنقل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("هوية الطرفين وحالة الجلسة", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Box(
                    modifier = Modifier.background(statusColor.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        when {
                            transferring -> "نقل نشط"
                            connected -> "متصل"
                            pairingPending -> "اقتران"
                            else -> "استعداد"
                        },
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UltraShareIdentityTile(
                    modifier = Modifier.weight(1f),
                    name = senderName,
                    role = "المرسل",
                    isLocal = isSendingRoom,
                    isActive = connected || (transferring && isSendingRoom),
                    detail = if (isSendingRoom) "هذا الهاتف" else remoteAddress.takeIf { it.isNotBlank() }
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    UltraShareConnectionGlyph(
                        pairingPending = pairingPending,
                        connected = connected,
                        transferring = transferring
                    )
                    Text(
                        when {
                            transferring -> "نقل"
                            connected -> "آمن"
                            pairingPending -> "ربط"
                            else -> "قريب"
                        },
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                UltraShareIdentityTile(
                    modifier = Modifier.weight(1f),
                    name = receiverName,
                    role = "المستقبل",
                    isLocal = !isSendingRoom,
                    isActive = connected || (transferring && !isSendingRoom),
                    detail = if (isSendingRoom) remoteAddress.takeIf { it.isNotBlank() } else "هذا الهاتف"
                )
            }
            Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            // Show each peer independently instead of hiding concurrent sessions
            // behind the single headline peer identity above.
            val peerHosts = (state.activePeers.map { it.host } + state.peerTransfers.map { it.host })
                .distinct()
            if (peerHosts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "الأجهزة المشاركة (${peerHosts.size})",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    peerHosts.forEach { peerHost ->
                        val peer = state.activePeers.firstOrNull { it.host == peerHost }
                        val transfer = state.peerTransfers.firstOrNull { it.host == peerHost }
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        peer?.name ?: transfer?.fileName?.takeIf { it.isNotBlank() } ?: peerHost,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        peerHost,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    transfer?.status ?: peer?.status ?: "متصل",
                                    color = when {
                                        transfer?.status?.contains("فشل") == true -> MaterialTheme.colorScheme.error
                                        transfer?.status?.contains("يستقبل") == true -> MalekPlayColors.PurpleBright
                                        peer != null -> Color(0xFF65D6A2)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (transfer != null && transfer.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { transfer.fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (transfer.status.contains("فشل")) MaterialTheme.colorScheme.error else MalekPlayColors.PurpleBright
                                )
                                Text(
                                    "${transfer.fileName.ifBlank { "نقل الملفات" }} • ${(transfer.fraction * 100).toInt()}% • ${formatSessionTransferBytes(transfer.bytesPerSecond)}/s",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${formatSessionTransferBytes(transfer.transferredBytes)} / ${formatSessionTransferBytes(transfer.totalBytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            if (selectedMediaCount > 0 && isSendingRoom && !state.sending) {
                Text("${selectedMediaCount} عنصر وسائط جاهز للإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            progress?.let { current ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current.fileName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text("${(current.fraction * 100).toInt()}%", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.ExtraBold)
                    }
                    LinearProgressIndicator(progress = { current.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatTransferBytes(current.transferredBytes)} / ${formatTransferBytes(current.totalBytes)} • ${formatTransferBytes(current.bytesPerSecond)}/s",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (overallFraction != null && isSendingRoom) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinearProgressIndicator(progress = { overallFraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "تقدم الدفعة: ${state.queueCompleted} / ${state.queueTotal} • ${formatTransferBytes(state.queueTransferredBytes)} / ${formatTransferBytes(state.queueTotalBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (isSendingRoom && state.queueEntries.isNotEmpty()) {
                val sessionItems = currentBatchEntries.ifEmpty { state.queueEntries.takeLast(5) }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("عناصر جلسة النقل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    sessionItems.take(5).forEach { entry ->
                        val itemColor = when (entry.status) {
                            UltraShareQueueStore.STATUS_COMPLETED -> Color(0xFF65D6A2)
                            UltraShareQueueStore.STATUS_FAILED -> MaterialTheme.colorScheme.error
                            UltraShareQueueStore.STATUS_SENDING -> MalekPlayColors.PurpleBright
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val itemLabel = when (entry.status) {
                            UltraShareQueueStore.STATUS_COMPLETED -> "اكتمل"
                            UltraShareQueueStore.STATUS_FAILED -> "فشل — يمكن إعادة المحاولة"
                            UltraShareQueueStore.STATUS_SENDING -> "جارٍ الإرسال"
                            UltraShareQueueStore.STATUS_SKIPPED -> "تم التخطي"
                            else -> "بانتظار الإرسال"
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(itemColor, androidx.compose.foundation.shape.CircleShape))
                                Text(entry.displayName, modifier = Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(itemLabel, color = itemColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            if (entry.status == UltraShareQueueStore.STATUS_SENDING && state.sendProgress?.fileName == entry.displayName) {
                                val itemProgress = state.sendProgress!!
                                LinearProgressIndicator(progress = { itemProgress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(
                                    "${(itemProgress.fraction * 100).toInt()}% • ${formatTransferBytes(itemProgress.transferredBytes)} / ${formatTransferBytes(itemProgress.totalBytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (sessionItems.size > 5) {
                        Text("و ${sessionItems.size - 5} عناصر أخرى في الطابور", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (state.queueEntries.isNotEmpty() && isSendingRoom) {
                val summaryEntries = currentBatchEntries.ifEmpty { state.queueEntries }
                val completed = summaryEntries.count { it.status == UltraShareQueueStore.STATUS_COMPLETED }
                val failed = summaryEntries.count { it.status == UltraShareQueueStore.STATUS_FAILED }
                val waiting = summaryEntries.count {
                    it.status == UltraShareQueueStore.STATUS_PENDING || it.status == UltraShareQueueStore.STATUS_SENDING
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UltraShareQueueCount("مكتمل $completed", Color(0xFF65D6A2), Modifier.weight(1f))
                    UltraShareQueueCount("جارٍ/منتظر $waiting", MalekPlayColors.PurpleBright, Modifier.weight(1f))
                    UltraShareQueueCount("فشل $failed", MaterialTheme.colorScheme.error, Modifier.weight(1f))
                }
            }
            if (sendCompleted && isSendingRoom) {
                UltraShareCompletionPanel(
                    title = "اكتمل إرسال الوسائط",
                    subtitle = "اكتمل نقل ${state.queueCompleted} من ${state.queueTotal} عنصر بنجاح.",
                    detail = "يمكنك الآن إرسال دفعة أخرى أو العودة إلى مكتبة الوسائط."
                )
            }
            if (receiveCompleted) {
                val received = state.receivedFile!!
                UltraShareCompletionPanel(
                    title = "اكتمل استقبال الوسائط",
                    subtitle = received.fileName,
                    detail = formatTransferBytes(received.sizeBytes) + " • " +
                        if (received.publishedUri != null) "حُفظ في التنزيلات / MalekPlay / Received" else "حُفظ داخل مساحة التطبيق"
                )
            }
        }
    }
}

@Composable
private fun UltraShareConnectionGlyph(
    pairingPending: Boolean,
    connected: Boolean,
    transferring: Boolean
) {
    if (pairingPending || transferring) {
        val transition = rememberInfiniteTransition(label = "ultraSharePairingPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ultraSharePairingScale"
        )
        Box(
            modifier = Modifier.size(48.dp).scale(pulse)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MalekPlayColors.PurpleBright.copy(alpha = 0.36f),
                            MalekPlayColors.PurpleDeep.copy(alpha = 0.12f)
                        )
                    ),
                    androidx.compose.foundation.shape.CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.fillMaxSize(), color = MalekPlayColors.PurpleBright, strokeWidth = 2.dp)
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(23.dp))
        }
    } else {
        Box(
            modifier = Modifier.size(48.dp).background(
                Brush.radialGradient(
                    listOf(
                        (if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright).copy(alpha = 0.28f),
                        MalekPlayColors.PurpleDeep.copy(alpha = 0.12f)
                    )
                ),
                androidx.compose.foundation.shape.CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright,
                modifier = Modifier.size(23.dp)
            )
        }
    }
    Text(
        when {
            transferring -> "نقل"
            connected -> "آمن"
            pairingPending -> "ربط"
            else -> "قريب"
        },
        color = when {
            transferring -> MalekPlayColors.PurpleBright
            connected -> Color(0xFF65D6A2)
            pairingPending -> Color(0xFFFFC66D)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun UltraShareIdentityTile(
    modifier: Modifier = Modifier,
    name: String,
    role: String,
    isLocal: Boolean,
    isActive: Boolean,
    detail: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(
                    Brush.linearGradient(
                        listOf(
                            MalekPlayColors.PurpleBright.copy(alpha = 0.30f),
                            if (isActive) Color(0xFF65D6A2).copy(alpha = 0.22f) else MalekPlayColors.PurpleDeep.copy(alpha = 0.20f)
                        )
                    ),
                    androidx.compose.foundation.shape.CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(name.firstOrNull()?.uppercase() ?: "M", color = if (isActive) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                detail ?: if (isLocal) "هذا الهاتف" else if (isActive) "متصل" else "بعيد",
                color = if (isActive && !isLocal) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UltraShareQueueCount(label: String, tint: Color, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier.background(tint.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 5.dp, vertical = 7.dp),
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun UltraShareCompletionPanel(title: String, subtitle: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF164A38))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier.size(66.dp).background(
                    Color(0xFF83E6B7).copy(alpha = 0.16f),
                    androidx.compose.foundation.shape.CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF83E6B7),
                    modifier = Modifier.size(42.dp)
                )
            }
            Text("100% • اكتملت العملية", color = Color(0xFFB6E8D2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(subtitle, color = Color(0xFFE2FFF1), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(detail, color = Color(0xFFB6E8D2), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

internal fun formatTransferBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = -1
    do {
        amount /= 1024.0
        index++
    } while (amount >= 1024.0 && index < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", amount, units[index])
}


private fun formatSessionTransferBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    for (unit in units) {
        amount /= 1024.0
        if (amount < 1024.0 || unit == units.last()) {
            return String.format(java.util.Locale.US, "%.1f %s", amount, unit)
        }
    }
    return "$value B"
}
