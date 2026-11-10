package com.malekplay.ui.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareBatchTransport
import com.malekplay.network.UltraShareQrCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onBack: () -> Unit,
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // تشغيل الاستقبال تلقائياً عند فتح الشاشة (مثل Zapya)
    LaunchedEffect(Unit) {
        if (!state.receiving) {
            viewModel.startReceiving()
        }
    }

    // توليد رمز الـ QR
    val qrPayload = state.localAddresses.firstOrNull()?.takeIf { state.receiving }?.let { address ->
        UltraShareQrCode.createPayload(address, UltraShareBatchTransport.DEFAULT_PORT, state.pairingSecret)
    }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { payload -> runCatching { UltraShareQrCode.createBitmap(payload) }.getOrNull() }
    }

    // أذونات إنشاء نقطة اتصال محلية (Hotspot)
    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startHotspot()
        } else {
            Toast.makeText(context, "يلزم منح إذن الأجهزة القريبة لإنشاء شبكة.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestLocalHotspot() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.startHotspot()
        else hotspotPermissionLauncher.launch(missing.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("استقبال الملفات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // 1. حالة الاتصال (مثل Zapya)
            Row(
                modifier = Modifier
                    .background(
                        if (state.receiving) Color(0xFF65D6A2).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (state.receiving) Color(0xFF65D6A2) else Color.Gray, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.receiving) "متصل وجاهز للاستقبال" else "الاستقبال متوقف",
                    color = if (state.receiving) Color(0xFF65D6A2) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.weight(0.5f))

            // 2. رمز الـ QR الضخم
            if (state.receiving && qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "امسح الرمز من هاتف الإرسال للاتصال",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // شكل جمالي عندما يكون الاستقبال متوقفاً
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                        .border(2.dp, MalekPlayColors.PurpleBright.copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = MalekPlayColors.PurpleBright.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "اضغط على زر البدء أدناه لعرض رمز الـ QR",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(24.dp))

            // 3. معلومات الاتصال (PIN، عنوان IP)
            if (state.receiving) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("رمز الاقتران (PIN)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.pairingSecret.chunked(3).joinToString(" "),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = MalekPlayColors.PurpleBright,
                            letterSpacing = 4.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                        Spacer(Modifier.height(12.dp))
                        
                        Text("عنوان IP المحلي", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        if (state.localAddresses.isEmpty()) {
                            Text("جاري جلب العنوان...", color = MaterialTheme.colorScheme.error)
                        } else {
                            state.localAddresses.forEach { address ->
                                Text(address, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        
                        // زر لنسخ بيانات الاتصال
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val textToCopy = "IP: ${state.localAddresses.firstOrNull() ?: ""}\nPIN: ${state.pairingSecret}"
                                clipboard?.setPrimaryClip(ClipData.newPlainText("MalekPlay Pair", textToCopy))
                                Toast.makeText(context, "تم نسخ بيانات الاتصال", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("نسخ البيانات")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 4. زر إنشاء نقطة اتصال (إذا لم يوجد راوتر مشترك)
                if (!state.hotspotActive && !state.hotspotStarting) {
                    OutlinedButton(
                        onClick = ::requestLocalHotspot,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.WifiTethering, null)
                        Spacer(Modifier.width(8.dp))
                        Text("إنشاء شبكة Wi-Fi خاصة (Hotspot)")
                    }
                } else if (state.hotspotActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF65D6A2).copy(alpha = 0.1f))
                    ) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("شبكة MalekPlay المحلية جاهزة", fontWeight = FontWeight.Bold, color = Color(0xFF65D6A2))
                            Text("SSID: ${state.hotspotSsid ?: "غير معروف"}", style = MaterialTheme.typography.bodySmall)
                            Text("Password: ${state.hotspotPassword ?: "بدون"}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::stopHotspot) { Text("إيقاف الشبكة", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 5. زر البدء / الإيقاف الكبير
            Button(
                onClick = { 
                    if (state.receiving) viewModel.stopReceiving() 
                    else viewModel.startReceiving() 
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.receiving) MaterialTheme.colorScheme.error else MalekPlayColors.PurpleBright
                )
            ) {
                Icon(
                    if (state.receiving) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.receiving) "إيقاف الاستقبال" else "بدء الاستقبال",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
