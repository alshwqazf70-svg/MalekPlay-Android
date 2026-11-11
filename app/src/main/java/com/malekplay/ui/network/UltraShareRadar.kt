package com.malekplay.ui.network

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

private val RadarNavy = Color(0xFF394667)
private val RadarBlue = Color(0xFF3568C6)
private val RadarCyan = Color(0xFF65D8F3)
private val RadarRed = Color(0xFFFF686F)

@Composable
fun UltraShareRadar(
    isScanning: Boolean,
    discoveredCount: Int,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "ultra-share-radar")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "radar-sweep"
    )

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.49f

            // Zapya-inspired layered blue radar field rather than thin neon rings.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF40598F), RadarNavy.copy(alpha = 0.98f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
            drawCircle(
                color = RadarBlue.copy(alpha = 0.98f),
                radius = radius * 0.56f,
                center = center
            )
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

            val count = discoveredCount.coerceIn(0, 8)
            repeat(count) { index ->
                val angle = Math.toRadians((index * (360.0 / maxOf(count, 1)) - 90.0))
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
    }
}
