package com.malekplay.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerBottomSheet(
    discoveredPeers: List<UltraShareDiscovery.Peer>,
    activePeers: List<com.malekplay.network.UltraSharePeerSession>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onConnect: (host: String, secret: String) -> Unit,
    onSendToApprovedPeer: (host: String) -> Unit // إذا كان متصلاً مسبقاً
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPeerHost by remember { mutableStateOf<String?>(null) }
    var secretCode by remember { mutableStateOf("") }
    var showPinInput by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // العنوان
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                Spacer(Modifier.width(8.dp))
                Text(
                    "اختر جهاز الاستقبال",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "تأكد من فتح تطبيق MalekPlay على الجهاز الآخر وبدء الاستقبال.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // حالة البحث
            if (isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MalekPlayColors.PurpleBright.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MalekPlayColors.PurpleBright, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("جاري البحث عن أجهزة...", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
            }

            // قائمة الأجهزة
            if (discoveredPeers.isEmpty() && activePeers.isEmpty() && !isScanning) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("لم يتم العثور على أجهزة. جرب تحديث البحث.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 300.dp) // تحديد الطول لتفادي خروج النافذة عن الشاشة
                ) {
                    // 1. الأجهزة المتصلة بالفعل
                    if (activePeers.isNotEmpty()) {
                        item {
                            Text("أجهزة متصلة:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF65D6A2), modifier = Modifier.padding(top = 8.dp))
                        }
                        items(activePeers) { peer ->
                            DeviceItem(
                                name = peer.name,
                                host = peer.host,
                                isConnected = true,
                                onClick = { onSendToApprovedPeer(peer.host) }
                            )
                        }
                    }

                    // 2. الأجهزة المكتشفة حديثاً
                    if (discoveredPeers.isNotEmpty()) {
                        item {
                            Text("أجهزة قريبة (تحتاج اتصال):", style = MaterialTheme.typography.labelMedium, color = MalekPlayColors.PurpleBright, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(discoveredPeers) { peer ->
                            DeviceItem(
                                name = peer.name,
                                host = peer.host,
                                isConnected = false,
                                onClick = {
                                    selectedPeerHost = peer.host
                                    showPinInput = true
                                }
                            )
                        }
                    }
                }
            }

            // حقل إدخال رمز الاقتران (يظهر فقط عند اختيار جهاز غير متصل)
            if (showPinInput && selectedPeerHost != null) {
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("أدخل رمز الاقتران (PIN) الظاهر على الجهاز الآخر:", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = secretCode,
                            onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() }) secretCode = it },
                            placeholder = { Text("مثال: 123456") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showPinInput = false; selectedPeerHost = null }) {
                                Text("إلغاء")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    selectedPeerHost?.let { host ->
                                        onConnect(host, secretCode)
                                    }
                                },
                                enabled = secretCode.length >= 4,
                                colors = ButtonDefaults.buttonColors(containerColor = MalekPlayColors.PurpleBright)
                            ) {
                                Text("اتصال وإرسال")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    name: String,
    host: String,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFF65D6A2).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // أيقونة الجهاز
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isConnected) Color(0xFF65D6A2).copy(alpha = 0.2f) else MalekPlayColors.PurpleBright.copy(alpha = 0.2f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (isConnected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            // اسم الجهاز وعنوان IP
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // زر الإجراء
            if (isConnected) {
                Text("إرسال", color = Color(0xFF65D6A2), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("اتصال")
                }
            }
        }
    }
}
