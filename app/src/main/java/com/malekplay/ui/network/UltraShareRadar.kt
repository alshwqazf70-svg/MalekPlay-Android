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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors

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
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "radar-sweep"
    )
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.47f
            val ringStroke = 1.4.dp.toPx()
            listOf(1f, 0.72f, 0.43f).forEachIndexed { index, factor ->
                drawCircle(
                    color = MalekPlayColors.PurpleBright.copy(alpha = 0.16f + index * 0.035f),
                    radius = radius * factor,
                    center = center,
                    style = Stroke(width = ringStroke)
                )
            }
            drawCircle(
                color = MalekPlayColors.PurpleBright.copy(alpha = 0.08f),
                radius = radius * 0.16f,
                center = center
            )
            if (isScanning) {
                rotate(rotation, center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to MalekPlayColors.PurpleBright.copy(alpha = 0f),
                            0.72f to MalekPlayColors.PurpleBright.copy(alpha = 0.03f),
                            1f to MalekPlayColors.PurpleBright.copy(alpha = 0.42f)
                        ),
                        startAngle = -38f,
                        sweepAngle = 112f,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f)
                    )
                }
            }
            val count = discoveredCount.coerceIn(0, 8)
            repeat(count) { index ->
                val angle = Math.toRadians((index * (360.0 / maxOf(count, 1)) - 90.0))
                val dotRadius = radius * (0.55f + (index % 3) * 0.12f)
                val point = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * dotRadius,
                    center.y + kotlin.math.sin(angle).toFloat() * dotRadius
                )
                drawCircle(
                    color = if (isScanning) MalekPlayColors.PurpleBright else MalekPlayColors.PurpleBright.copy(alpha = 0.7f),
                    radius = 4.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = MalekPlayColors.PurpleBright.copy(alpha = 0.22f),
                    radius = 8.dp.toPx(),
                    center = point
                )
            }
        }
    }
}
