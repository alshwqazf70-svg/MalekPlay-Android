@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.malekplay.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.NetworkDeviceKind
import com.malekplay.network.NetworkDiscoveryClient
import com.malekplay.network.TvCastProtocol
import com.malekplay.player.cast.WebCastRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DlnaDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (DiscoveredNetworkDevice) -> Unit,
    onStartWebCast: ((requirePin: Boolean) -> Unit)? = null,
    onStopWebCast: (() -> Unit)? = null,
    onToggleWebCastPin: ((Boolean) -> Unit)? = null,
    onRegenerateWebCastPin: (() -> Unit)? = null,
    initialTab: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveryClient = remember { NetworkDiscoveryClient() }
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    var refreshToken by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<DiscoveredNetworkDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var manualIp by remember { mutableStateOf("") }
    var probingManualIp by remember { mutableStateOf(false) }
    var manualIpError by remember { mutableStateOf<String?>(null) }

    val webCastState by WebCastRegistry.state.collectAsState()

    LaunchedEffect(refreshToken) {
        loading = true
        error = null
        devices = emptyList()
        try {
            val all = discoveryClient.discover(context)
            devices = all
                .filter { device ->
                    device.supportsDirectPlayback() ||
                        device.hasGoogleCastRoute() ||
                        device.isDiscoveredWithoutDirectPlayback() ||
                        device.kind == NetworkDeviceKind.MEDIA_RENDERER ||
                        device.castProtocols.isNotEmpty()
                }
                .distinctBy { it.host.lowercase() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "تعذر البحث عن أجهزة التلفزيون على الشبكة."
            devices = emptyList()
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("البث إلى التلفزيون والكمبيوتر") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("التلفزيون") },
                        icon = { Icon(Icons.Filled.Tv, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("الكمبيوتر والمتصفح") },
                        icon = { Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                if (selectedTab == 0) {
                    Text(
                        "يدعم DLNA وSamsung وLG وSony وAndroid TV وRoku وGoogle Cast على نفس شبكة Wi-Fi أو نقطة الاتصال.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MalekPlayColors.TextSecondary
                    )

                    // Native Google Cast / Chromecast route button inside the TV hub
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1E1630))
                            .border(1.dp, Color(0x44A855F7), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cast,
                            contentDescription = null,
                            tint = MalekPlayColors.PurpleBright,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Google Cast / Chromecast",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MalekPlayColors.TextPrimary
                            )
                            Text(
                                "لشاشات Android TV وChromecast",
                                style = MaterialTheme.typography.labelSmall,
                                color = MalekPlayColors.TextSecondary
                            )
                        }
                        MediaRouteButton(modifier = Modifier.size(40.dp))
                    }

                    if (loading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("جارٍ البحث عن أجهزة التلفزيون…")
                        }
                    } else if (error != null && devices.isEmpty()) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (devices.isEmpty()) {
                        Text(
                            "لم يعلن أي تلفزيون عن DLNA/Roku تلقائياً. يمكنك إدخال IP التلفزيون أدناه، أو استخدام تبويب «الكمبيوتر والمتصفح» لفتح الفيديو على متصفح التلفزيون الذكي!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MalekPlayColors.TextSecondary
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(devices, key = { it.id }) { device ->
                                val canDirectCast = device.supportsDirectPlayback()
                                val isGoogleCast = device.hasGoogleCastRoute()
                                val capabilityBadge = when {
                                    canDirectCast -> "جاهز للبث"
                                    isGoogleCast -> "زر Cast أعلاه"
                                    else -> "مكتشف • بدون بث مباشر"
                                }
                                val subtitleParts = listOfNotNull(
                                    device.host,
                                    device.manufacturer?.takeIf { it.isNotBlank() },
                                    device.modelName?.takeIf { it.isNotBlank() },
                                    protocolBadges(device),
                                    capabilityBadge
                                ).joinToString(" • ")

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (canDirectCast) Color(0xFF1A1426) else Color(0xFF14111D))
                                        .border(
                                            1.dp,
                                            if (canDirectCast) Color(0x55C084FC) else Color(0x22FFFFFF),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            if (canDirectCast) {
                                                onDeviceSelected(device)
                                            } else if (!isGoogleCast) {
                                                // Devices discovered only via AirPlay/DIAL or without AVTransport use Web Cast.
                                                selectedTab = 1
                                            } else {
                                                manualIpError = "لأجهزة Google Cast استخدم أيقونة Cast في الصندوق البنفسجي أعلاه."
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Tv,
                                        contentDescription = null,
                                        tint = if (canDirectCast) MalekPlayColors.PurpleBright else MalekPlayColors.TextSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MalekPlayColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = subtitleParts,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MalekPlayColors.TextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = when {
                                            canDirectCast -> "بث"
                                            isGoogleCast -> "Cast"
                                            else -> "المتصفح"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (canDirectCast) MalekPlayColors.PurpleBright else MalekPlayColors.TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Manual TV IP Probe fallback when router blocks SSDP multicast
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = {
                                    manualIp = it
                                    manualIpError = null
                                },
                                label = { Text("IP التلفزيون يدوياً") },
                                placeholder = { Text("192.168.1.50") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                enabled = !probingManualIp && manualIp.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        probingManualIp = true
                                        manualIpError = null
                                        try {
                                            val probed = discoveryClient.probeDeviceByIp(manualIp, context)
                                            if (probed == null) {
                                                manualIpError = "لم يستجب التلفزيون على منافذ DLNA/Roku. جرّب تبويب الكمبيوتر والمتصفح!"
                                            } else if (!probed.supportsDirectPlayback() && !probed.hasGoogleCastRoute()) {
                                                devices = (listOf(probed) + devices).distinctBy { it.host.lowercase() }
                                                manualIpError = "تم اكتشاف الجهاز (${probed.name}) لكنه لا يدعم DLNA AVTransport أو Roku ECP."
                                            } else {
                                                devices = (listOf(probed) + devices).distinctBy { it.host.lowercase() }
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (err: Exception) {
                                            manualIpError = err.message ?: "عنوان IP غير صالح."
                                        } finally {
                                            probingManualIp = false
                                        }
                                    }
                                }
                            ) {
                                if (probingManualIp) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        manualIpError?.let { msg ->
                            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    WebCastConsolePanel(
                        state = webCastState,
                        onStart = { requirePin ->
                            onStartWebCast?.invoke(requirePin) ?: runCatching {
                                WebCastRegistry.start(context, requirePin)
                            }
                        },
                        onStop = {
                            onStopWebCast?.invoke() ?: WebCastRegistry.stop()
                        },
                        onToggleRequirePin = { req ->
                            onToggleWebCastPin?.invoke(req) ?: WebCastRegistry.setRequirePin(req)
                        },
                        onRegeneratePin = {
                            onRegenerateWebCastPin?.invoke() ?: WebCastRegistry.regeneratePin()
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                TextButton(
                    onClick = { refreshToken++ },
                    enabled = !loading
                ) { Text("إعادة البحث") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

private fun protocolBadges(device: DiscoveredNetworkDevice): String? {
    val badges = buildList {
        if (TvCastProtocol.DLNA in device.castProtocols) add("DLNA")
        if (TvCastProtocol.ROKU_ECP in device.castProtocols) add("Roku")
        if (TvCastProtocol.CHROMECAST in device.castProtocols) add("Cast")
        if (TvCastProtocol.DIAL in device.castProtocols) add("SmartTV")
        if (TvCastProtocol.AIRPLAY in device.castProtocols) add("AirPlay")
    }
    return badges.takeIf { it.isNotEmpty() }?.joinToString("/")
}
