package com.malekplay.ui.network

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val RadarNavy = Color(0xFF354365)
private val RadarBlue = Color(0xFF3568C6)
private val RadarCyan = Color(0xFF65D8F3)
private val RadarRed = Color(0xFFFF686F)

@Composable
fun UltraShareRadar(
    isScanning: Boolean,
    discoveredCount: Int,
    modifier: Modifier = Modifier,
    deviceName: String = android.os.Build.MODEL.orEmpty().ifBlank { "MalekPlay" },
    peerNames: List<String> = emptyList(),
    onPeerClick: (Int) -> Unit = {}
) {
    val transition = rememberInfiniteTransition(label = "ultra-share-radar")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "radar-sweep"
    )
    val peerSlots = listOf(Alignment.TopEnd, Alignment.BottomStart, Alignment.CenterEnd, Alignment.TopStart)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.49f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF40598F), RadarNavy.copy(alpha = 0.98f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
            drawCircle(color = RadarCyan.copy(alpha = 0.30f), radius = radius * 0.91f, center = center, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color = RadarBlue.copy(alpha = 0.98f), radius = radius * 0.56f, center = center)
            drawCircle(
                color = RadarCyan.copy(alpha = 0.10f),
                radius = radius * 0.34f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            if (isScanning) {
                rotate(rotation, center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to RadarCyan.copy(alpha = 0f),
                            0.76f to RadarCyan.copy(alpha = 0.025f),
                            1f to RadarCyan.copy(alpha = 0.28f)
                        ),
                        startAngle = -34f,
                        sweepAngle = 105f,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f)
                    )
                }
            }
            repeat(discoveredCount.coerceIn(0, 8)) { index ->
                val count = discoveredCount.coerceIn(1, 8)
                val angle = Math.toRadians(index * (360.0 / count) - 90.0)
                val orbit = radius * if (index % 2 == 0) 0.78f else 0.91f
                val point = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * orbit,
                    center.y + kotlin.math.sin(angle).toFloat() * orbit
                )
                drawCircle(RadarRed.copy(alpha = 0.20f), radius = 8.dp.toPx(), center = point)
                drawCircle(RadarRed, radius = 3.8.dp.toPx(), center = point)
                drawCircle(Color.White.copy(alpha = 0.72f), radius = 1.2.dp.toPx(), center = point)
            }
        }
        Card(
            modifier = Modifier.align(Alignment.Center).size(112.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FBFD))
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF52616B), modifier = Modifier.size(42.dp))
                Text(deviceName.take(16), color = Color(0xFF394363), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                Text("هذا جهازك", color = Color(0xFF64758C), textAlign = TextAlign.Center, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
        peerNames.take(4).forEachIndexed { index, name ->
            Card(
                onClick = { onPeerClick(index) },
                modifier = Modifier.align(peerSlots[index]).padding(8.dp).size(width = 104.dp, height = 76.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF5FA))
            ) {
                Column(
                    Modifier.fillMaxSize().padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF52616B), modifier = Modifier.size(30.dp))
                    Text(name.take(14), color = Color(0xFF394363), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                    Text("اضغط للاتصال", color = Color(0xFF64758C), textAlign = TextAlign.Center, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
