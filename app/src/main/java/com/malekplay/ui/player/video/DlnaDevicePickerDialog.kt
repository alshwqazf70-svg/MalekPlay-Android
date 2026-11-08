package com.malekplay.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.NetworkDeviceKind
import com.malekplay.network.NetworkDiscoveryClient

@Composable
fun DlnaDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (DiscoveredNetworkDevice) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<DiscoveredNetworkDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshToken) {
        loading = true
        error = null
        try {
            devices = NetworkDiscoveryClient().discover(context).filter { device ->
                device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
                    device.services.any { it.contains("MediaRenderer", ignoreCase = true) }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "تعذر البحث عن أجهزة التلفزيون على الشبكة."
            devices = emptyList()
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إرسال الفيديو إلى التلفزيون") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تأكد من اتصال الهاتف والتلفزيون بشبكة Wi-Fi نفسها.")
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text(" جارٍ البحث عن أجهزة DLNA…")
                    }
                } else if (error != null) {
                    Text(error.orEmpty())
                } else if (devices.isEmpty()) {
                    Text("لم نعثر على تلفزيون يعلن خدمة DLNA Renderer. يمكنك إعادة البحث بعد تفعيل مشاركة الوسائط على التلفزيون.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            TextButton(
                                onClick = { onDeviceSelected(device) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(device.name)
                                    Text(device.host, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { refreshToken++ },
                enabled = !loading
            ) { Text("إعادة البحث") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
