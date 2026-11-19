package com.malekplay.ui.network

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareQueueStore
@Composable
fun UltraShareSessionRoom(
    state: UltraShareUiState,
    activeMode: String,
    localDeviceName: String,
    remoteHost: String,
    selectedMediaCount: Int,
    onPauseSending: () -> Unit,
    onResumeSending: () -> Unit,
    onCancelSending: () -> Unit,
    onPickAndSendFiles: () -> Unit,
    onShowGroupQr: () -> Unit,
    onBackHome: () -> Unit
) {
    val isSendingRoom = activeMode == "send"
    // Receiver-side approvals live in activePeers; sender-side approvals are
    // tracked separately because the sender owns an outbound MPC2 client session.
    // Treat both as the same authenticated connection state in the shared UI.
    val connectedPeerHosts = (
        state.activePeers.map { it.host } + state.approvedSenderPeerHosts
    ).distinct()
    val activePeerCount = connectedPeerHosts.size
    val activePeer = state.activePeers.firstOrNull()
    val senderPeerHost = state.approvedSenderPeerHosts.firstOrNull()
    val remoteName = when {
        activePeerCount > 1 -> "$activePeerCount أجهزة متصلة"
        activePeer != null -> activePeer.name
        state.pendingConnection != null -> state.pendingConnection.peerName
        state.requestingConnection -> "جارٍ التحقق من الجهاز"
        !senderPeerHost.isNullOrBlank() -> senderPeerHost
        remoteHost.isNotBlank() -> remoteHost
        isSendingRoom -> "بانتظار جهاز الاستقبال"
        else -> "بانتظار جهاز الإرسال"
    }
    val connected = activePeerCount > 0 || state.approvedPeerHost != null
    val pairingPending = state.requestingConnection || state.pendingConnection != null
    val transferring = state.sending || state.sendProgress != null || state.receiveProgress != null
    val sendingPaused = !state.sending && state.queueTotal > 0 && (
        state.status?.contains("أُوقف الطابور مؤقتاً") == true ||
            state.status?.contains("أُوقف الإرسال الجماعي") == true
        )
    val currentBatchEntries = if (state.queueTotal > 0) state.queueEntries.takeLast(state.queueTotal) else emptyList()
    val sendCompleted = !state.sending && state.queueTotal > 0 &&
        state.queueCompleted >= state.queueTotal && currentBatchEntries.size == state.queueTotal &&
        currentBatchEntries.all { it.status == UltraShareQueueStore.STATUS_COMPLETED }
    val receiveCompleted = state.receivedFile != null && state.receiveProgress == null &&
        (state.status?.contains("استلام") == true || state.status?.contains("استُلم") == true || state.status?.contains("اكتملت دفعة") == true)
    val status = when {
        state.sending -> if (activePeerCount > 1) "يتم إرسال الملفات إلى $activePeerCount أجهزة" else "يتم إرسال الملفات إلى الجهاز الآخر"
        state.receiveProgress != null -> if (activePeerCount > 1) "يتم استقبال الملفات من $activePeerCount أجهزة" else "يتم استقبال الملفات من الجهاز الآخر"
        state.sendProgress != null -> "جارٍ نقل العنصر الحالي"
        state.pendingConnection != null -> "طلب اتصال وارد — بانتظار موافقتك"
        state.requestingConnection -> "جارٍ التحقق من الاقتران — ننتظر رد الجهاز الآخر"
        connected -> if (activePeerCount > 1) "اتصال موثّق — $activePeerCount أجهزة جاهزة للنقل" else "اتصال موثّق — الجهازان جاهزان للنقل"
        !isSendingRoom && state.receiving -> "هذا الهاتف ظاهر وجاهز لاستقبال الملفات"
        !isSendingRoom -> "ابدأ الاستقبال لعرض رمز الربط واستقبال الملفات"
        selectedMediaCount > 0 -> "${selectedMediaCount} ملف محدد — اختر جهاز الاستقبال"
        state.scanning || state.wifiDirectScanning || state.bluetoothScanning || state.bleScanning ->
            "نبحث عن أجهزة MalekPlay القريبة"
        else -> "اختر الملفات ثم اربط الجهازين"
    }
    val statusColor = when {
        transferring -> MalekPlayColors.PurpleBright
        connected -> Color(0xFF65D6A2)
        pairingPending -> Color(0xFFFFC66D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val senderName = if (isSendingRoom) localDeviceName else remoteName
    val remoteAddress = if (activePeerCount > 1) "جلسة متعددة الأجهزة" else activePeer?.host ?: senderPeerHost ?: state.approvedPeerHost ?: remoteHost
    val receiverName = if (isSendingRoom) remoteName else localDeviceName
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
                    Text("غرفة جماعية ثنائية الاتجاه • كل جهاز يرسل ويستقبل بالتوازي", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Button(onClick = onPickAndSendFiles, modifier = Modifier.weight(1f)) { Text("إرسال ملفات") }
                OutlinedButton(onClick = onShowGroupQr, modifier = Modifier.weight(1f)) { Text("رمز المجموعة") }
                OutlinedButton(onClick = onBackHome, modifier = Modifier.weight(1f)) { Text("الرئيسية") }
            }
            if (isSendingRoom && (state.sending || sendingPaused)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.sending) {
                        OutlinedButton(
                            onClick = onPauseSending,
                            modifier = Modifier.weight(1f)
                        ) { Text("إيقاف مؤقت") }
                    } else {
                        Button(
                            onClick = onResumeSending,
                            modifier = Modifier.weight(1f),
                            enabled = state.approvedSenderPeerHosts.isNotEmpty() ||
                                !state.approvedPeerHost.isNullOrBlank()
                        ) { Text("استئناف المتبقي") }
                    }
                    OutlinedButton(
                        onClick = onCancelSending,
                        modifier = Modifier.weight(1f)
                    ) { Text("إلغاء الإرسال") }
                }
            }
            // Show each peer independently instead of hiding concurrent sessions
            // behind the single headline peer identity above.
            val reconnectablePeerHosts = (
                state.reconnectablePeers.map { it.host } +
                    state.reconnectableSenderPeerHosts
                ).toSet()
            val peerHosts = (
                state.activePeers.map { it.host } +
                    state.approvedSenderPeerHosts +
                    state.peerTransfers.map { it.host } +
                    reconnectablePeerHosts
                ).distinct()
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
                        // Keep delivery history attached to the authenticated device
                        // when its IP changes; host is only a display/routing attribute.
                        val peerFiles = state.peerDeliveryEntries.filter {
                            if (peer != null) it.peerSessionId == peer.sessionId
                            else it.peerHost == peerHost
                        }
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
                                        peer?.name ?: if (peerHost == senderPeerHost) "الجهاز المتصل" else "جهاز مشارك",
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
                                    transfer?.status ?: peer?.status ?: if (peerHost in reconnectablePeerHosts) "انقطع الاتصال" else "متصل",
                                    color = when {
                                        transfer?.status?.contains("فشل") == true -> MaterialTheme.colorScheme.error
                                        transfer?.status?.contains("يستقبل") == true -> MalekPlayColors.PurpleBright
                                        peer != null -> Color(0xFF65D6A2)
                                        peerHost in reconnectablePeerHosts -> Color(0xFFFFC66D)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (peerFiles.isNotEmpty()) {
                                val deliveredCount = peerFiles.count {
                                    it.status == com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_COMPLETED
                                }
                                val failedCount = peerFiles.count {
                                    it.status == com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_FAILED
                                }
                                val waitingCount = peerFiles.size - deliveredCount - failedCount
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    UltraShareQueueCount("مستلم $deliveredCount", Color(0xFF65D6A2), Modifier.weight(1f))
                                    UltraShareQueueCount("قيد الانتظار $waitingCount", MalekPlayColors.PurpleBright, Modifier.weight(1f))
                                    UltraShareQueueCount("فشل $failedCount", MaterialTheme.colorScheme.error, Modifier.weight(1f))
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        "نتائج الملفات لهذا الجهاز (" + peerFiles.size + ")",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    peerFiles.take(6).forEach { delivery ->
                                        val deliveryColor = when (delivery.status) {
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_COMPLETED -> Color(0xFF65D6A2)
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_FAILED -> MaterialTheme.colorScheme.error
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_SENDING -> MalekPlayColors.PurpleBright
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        val deliveryLabel = when (delivery.status) {
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_COMPLETED -> "تم الاستلام"
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_FAILED -> "فشل"
                                            com.malekplay.network.UltraSharePeerDeliveryStore.STATUS_SENDING -> "جارٍ الإرسال"
                                            else -> "بانتظار الإرسال"
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(delivery.displayName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(deliveryLabel, color = deliveryColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (delivery.updatedAtMillis > 0L) {
                                                Text(
                                                    "آخر تحديث " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(delivery.updatedAtMillis)),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            delivery.error?.takeIf { it.isNotBlank() }?.let { reason ->
                                                Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    if (peerFiles.size > 6) {
                                        Text("و " + (peerFiles.size - 6) + " ملفات أخرى", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
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
                                val remainingBytes = (transfer.totalBytes - transfer.transferredBytes).coerceAtLeast(0L)
                                if (transfer.bytesPerSecond > 0L && remainingBytes > 0L) {
                                    Text(
                                        "الوقت المتبقي التقريبي: ${formatSessionDuration(remainingBytes / transfer.bytesPerSecond)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (selectedMediaCount > 0 && isSendingRoom && !state.sending) {
                Text("${selectedMediaCount} ملف جاهز للإرسال", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            state.sendProgress?.let { current ->
                UltraShareDirectionProgress(
                    title = "الإرسال إلى الأجهزة",
                    fileName = current.fileName,
                    fraction = current.fraction,
                    transferredBytes = current.transferredBytes,
                    totalBytes = current.totalBytes,
                    bytesPerSecond = current.bytesPerSecond,
                    tint = MalekPlayColors.PurpleBright
                )
            }
            state.receiveProgress?.let { current ->
                UltraShareDirectionProgress(
                    title = "الاستقبال من الأجهزة",
                    fileName = current.fileName,
                    fraction = current.fraction,
                    transferredBytes = current.transferredBytes,
                    totalBytes = current.totalBytes,
                    bytesPerSecond = current.bytesPerSecond,
                    tint = Color(0xFF65D6A2)
                )
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
                                val lowerName = entry.displayName.lowercase()
                                val isImage = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                                    lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                                    lowerName.endsWith(".gif") || lowerName.endsWith(".bmp")
                                if (isImage) {
                                    AsyncImage(
                                        model = entry.parsedUri,
                                        contentDescription = entry.displayName,
                                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(7.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(42.dp).background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(7.dp)
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            when {
                                                lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                                                    lowerName.endsWith(".avi") || lowerName.endsWith(".mov") -> "🎬"
                                                lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                                                    lowerName.endsWith(".flac") || lowerName.endsWith(".m4a") -> "🎵"
                                                else -> "📄"
                                            }
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(itemLabel, color = itemColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
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
            if (sendCompleted) {
                UltraShareCompletionPanel(
                    title = "اكتمل إرسال الملفات",
                    subtitle = "اكتمل نقل ${state.queueCompleted} من ${state.queueTotal} عنصر بنجاح.",
                    detail = "يمكنك الآن إرسال دفعة أخرى أو العودة إلى قائمة الملفات."
                )
            }
            if (receiveCompleted) {
                val received = state.receivedFile!!
                UltraShareCompletionPanel(
                    title = "اكتمل استقبال الملفات",
                    subtitle = received.fileName,
                    detail = formatTransferBytes(received.sizeBytes) + " • " +
                        if (received.publishedUri != null) "حُفظ في التنزيلات / MalekPlay / Received" else "حُفظ داخل مساحة التطبيق"
                )
            }
        }
    }
}

@Composable
private fun UltraShareDirectionProgress(
    title: String,
    fileName: String,
    fraction: Float,
    transferredBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
    tint: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fileName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Text("${(fraction * 100).toInt()}%", color = tint, fontWeight = FontWeight.ExtraBold)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = tint
        )
        Text(
            "${formatTransferBytes(transferredBytes)} / ${formatTransferBytes(totalBytes)} • ${formatTransferBytes(bytesPerSecond)}/s",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        val remainingBytes = (totalBytes - transferredBytes).coerceAtLeast(0L)
        if (bytesPerSecond > 0L && remainingBytes > 0L) {
            Text(
                "الوقت المتبقي التقريبي: ${formatSessionDuration(remainingBytes / bytesPerSecond)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
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


private fun formatSessionDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val remainder = safe % 60L
    return when {
        hours > 0L -> "${hours}س ${minutes}د"
        minutes > 0L -> "${minutes}د ${remainder}ث"
        else -> "${remainder}ث"
    }
}

private fun formatSessionTransferBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unitIndex = -1
    while (amount >= 1024.0 && unitIndex < units.lastIndex) {
        amount /= 1024.0
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", amount, units[unitIndex])
}
