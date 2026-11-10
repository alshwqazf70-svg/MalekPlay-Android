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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.malekplay.core.ui.theme.MalekPlayColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHomeScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // بدء البحث تلقائياً عند فتح الشاشة (مثل Zapya)
    LaunchedEffect(Unit) {
        if (!state.scanning) viewModel.scanNearbyDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("النقل السريع", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "السجل")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.3f))

            // 1. الرادار (قلب Zapya)
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                ZapyaStyleRadar(isScanning = state.scanning)
                // أيقونة الهاتف في المنتصف
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(3.dp, MalekPlayColors.PurpleBright.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(40.dp))
                        Text("هذا الهاتف", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 2. حالة البحث
            Text(
                text = if (state.scanning) "جاري البحث عن أجهزة قريبة..." else "جاهز للاتصال",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(0.3f))

            // 3. الأزرار الضخمة (إرسال/استقبال)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onNavigateToSend,
                    modifier = Modifier.weight(1f).height(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("إرسال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onNavigateToReceive,
                    modifier = Modifier.weight(1f).height(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MalekPlayColors.PurpleBright)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("استقبال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ZapyaStyleRadar(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        // رسم الدوائر
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.1f), radius, center, style = Stroke(2.dp.toPx()))
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.15f), radius * 0.66f, center, style = Stroke(2.dp.toPx()))
        drawCircle(MalekPlayColors.PurpleBright.copy(alpha = 0.2f), radius * 0.33f, center, style = Stroke(2.dp.toPx()))
        if (isScanning) {
            rotate(rotation, center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(MalekPlayColors.PurpleBright.copy(alpha = 0f), MalekPlayColors.PurpleBright.copy(alpha = 0.4f))
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
