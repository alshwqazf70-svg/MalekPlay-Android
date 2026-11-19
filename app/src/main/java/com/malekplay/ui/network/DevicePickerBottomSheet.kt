package com.malekplay.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerBottomSheet(
    discoveredPeers: List<com.malekplay.network.UltraShareDiscovery.Peer>,
    wifiDirectPeers: List<com.malekplay.network.UltraShareWifiDirect.Peer>,
    activePeers: List<com.malekplay.network.UltraSharePeerSession>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onPeerSelected: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visibleDiscovered = discoveredPeers.filter {
        it.name.contains(search, ignoreCase = true) || it.host.contains(search, ignoreCase = true)
    }
    val visibleActive = activePeers.filter {
        it.name.contains(search, ignoreCase = true) || it.host.contains(search, ignoreCase = true)
    }
    val visibleWifiDirect = wifiDirectPeers.filter {
        it.name.contains(search, ignoreCase = true) || it.deviceAddress.contains(search, ignoreCase = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF17131F)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("اختر جهاز الاستقبال", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("تظهر هنا أجهزة الشبكة المحلية وWi-Fi Direct؛ يلزم توثيق آمن قبل نقل الملفات.", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onRefresh) {
                    if (isScanning) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        Text("إيقاف")
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث البحث")
                        Text("تحديث")
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("البحث عن جهاز") }
            )
            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    Text("جارٍ اكتشاف الأجهزة القريبة…", color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelMedium)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (visibleActive.isNotEmpty()) {
                    item { Text("أجهزة متصلة", color = Color(0xFF65D6A2), fontWeight = FontWeight.Bold) }
                    items(visibleActive, key = { "active:${it.host}" }) { peer ->
                        PeerChoiceRow(peer.name, peer.host, true) { onPeerSelected(peer.host) }
                    }
                }
                if (visibleWifiDirect.isNotEmpty()) {
                    item {
                        Text("أجهزة Wi-Fi Direct المكتشفة", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Text(
                            "هذه نتائج اكتشاف لاسلكي فقط، ولا تثبت أن الجهاز الآخر يشغّل MalekPlay. لا يمكن بدء النقل قبل إنشاء جلسة اقتران موثّقة.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    items(visibleWifiDirect, key = { "p2p:${it.deviceAddress.lowercase()}" }) { peer ->
                        PeerInfoRow(
                            name = peer.name,
                            detail = peer.status,
                            route = "Wi-Fi Direct • لم يتم توثيق MalekPlay"
                        )
                    }
                }
                if (visibleDiscovered.isNotEmpty()) {
                    item { Text("أجهزة الشبكة المحلية", color = MalekPlayColors.PurpleBright, fontWeight = FontWeight.Bold) }
                    items(visibleDiscovered, key = { "nearby:${it.peerId ?: it.host}" }) { peer ->
                        val route = peer.candidates.firstOrNull()?.transport?.toString().orEmpty()
                        PeerChoiceRow(peer.name, peer.host, false, route) { onPeerSelected(peer.host) }
                    }
                }
                if (visibleActive.isEmpty() && visibleDiscovered.isEmpty() && visibleWifiDirect.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (isScanning) "نبحث عن أجهزة MalekPlay عبر الشبكة المحلية وWi-Fi Direct…" else "لم نعثر على أجهزة بعد. فعّل Wi-Fi أو Wi-Fi Direct ثم حدّث البحث.",
                                modifier = Modifier.padding(16.dp), color = Color.LightGray
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("إغلاق") }
        }
    }
}

@Composable
private fun PeerChoiceRow(name: String, host: String, connected: Boolean, route: String = "", onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (connected) Color(0xFF16382D) else Color(0xFF21192D))
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(42.dp).background(
                    (if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright).copy(alpha = 0.16f), CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = if (connected) Color(0xFF65D6A2) else MalekPlayColors.PurpleBright)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                if (route.isNotBlank()) Text(route, color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = onClick) { Text(if (connected) "متابعة" else "اختيار") }
        }
    }
}


@Composable
private fun PeerInfoRow(name: String, detail: String, route: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(42.dp).background(
                    MalekPlayColors.PurpleBright.copy(alpha = 0.16f),
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleBright
                )
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(detail, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                Text(route, color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
