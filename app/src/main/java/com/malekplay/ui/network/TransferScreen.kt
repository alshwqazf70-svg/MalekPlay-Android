// المسار المقترح: app/src/main/java/com/malekplay/ui/network/TransferScreen.kt
package com.malekplay.ui.network

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.malekplay.core.ui.theme.MalekPlayColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // تشغيل البحث تلقائياً عند فتح الشاشة
    LaunchedEffect(Unit) {
        if (!state.scanning) viewModel.scanNearbyDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("MalekPlay Transfer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("النقل السريع", style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.PurpleBright)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "إعدادات")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.5f))

            // 1. الرادار (في المنتصف)
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                ZapyaRadar(isScanning = state.scanning)

                // أيقونة الهاتف في المنتصف
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(3.dp, MalekPlayColors.PurpleBright.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MalekPlayColors.PurpleBright,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "هذا الهاتف",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.5f))

            // 2. حالة البحث (مثل Zapya)
            Text(
                text = if (state.scanning) "جاري البحث عن أجهزة قريبة..." else "جاهز للاتصال",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            // 3. الأزرار الضخمة (إرسال / استقبال) مثل Zapya
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // زر الإرسال
                Button(
                    onClick = onNavigateToSend,
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("إرسال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }

                // زر الاستقبال
                OutlinedButton(
                    onClick = onNavigateToReceive,
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("استقبال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4. شريط سفلي مصغر (سجل النقل، إلخ)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onNavigateToHistory) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("سجل النقل", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * رادار متحرك احترافي (مثل Zapya)
 */
@Composable
fun ZapyaRadar(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        // رسم 3 دوائر متحدة المركز
        drawCircle(
            color = MalekPlayColors.PurpleBright.copy(alpha = 0.1f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = MalekPlayColors.PurpleBright.copy(alpha = 0.15f),
            radius = radius * 0.66f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = MalekPlayColors.PurpleBright.copy(alpha = 0.2f),
            radius = radius * 0.33f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        if (isScanning) {
            // رسم شعاع الرادار الدوار
            rotate(rotation, center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            MalekPlayColors.PurpleBright.copy(alpha = 0f),
                            MalekPlayColors.PurpleBright.copy(alpha = 0.4f)
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2)
                )
            }
        }
    }
}
