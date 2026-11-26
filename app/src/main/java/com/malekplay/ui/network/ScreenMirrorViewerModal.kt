package com.malekplay.ui.network

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.AndroidScreenMirrorDecoder
import com.malekplay.network.IncomingMirrorOffer
import com.malekplay.network.ScreenMirrorViewerClient
import com.malekplay.network.UltraShareDiscovery
import com.malekplay.network.UltraShareForegroundService
import com.malekplay.network.UltraShareScreenMirrorProtocol

@Composable
fun ScreenMirrorViewerModal(
    offer: IncomingMirrorOffer,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var audioMuted by remember { mutableStateOf(false) }
    var decoderError by remember { mutableStateOf<String?>(null) }

    val localViewerId = remember(context) {
        UltraShareScreenMirrorProtocol.canonicalId(UltraShareDiscovery.localPeerId(context)).orEmpty()
    }

    val decoder = remember(offer.sessionId) {
        AndroidScreenMirrorDecoder(
            onVideoDimensionsChanged = { _, _, _ -> },
            onRequestKeyframe = {
                UltraShareForegroundService.sendMirrorViewerAction(
                    context = context,
                    hostAddress = offer.hostAddress,
                    sessionId = offer.sessionId,
                    action = UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR
                )
            },
            onDecoderError = { msg -> decoderError = msg }
        )
    }

    val viewerClient = remember(offer.sessionId, offer.hostAddress, offer.streamPort) {
        ScreenMirrorViewerClient(
            context = context,
            hostAddress = offer.hostAddress,
            streamPort = offer.streamPort,
            sessionId = offer.sessionId,
            hostPeerId = offer.hostPeerId,
            localViewerPeerId = localViewerId,
            streamKey = offer.streamKey,
            frameSink = { frame -> decoder.handleFrame(frame) },
            onRequestKeyframe = {
                UltraShareForegroundService.sendMirrorViewerAction(
                    context = context,
                    hostAddress = offer.hostAddress,
                    sessionId = offer.sessionId,
                    action = UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR
                )
            }
        )
    }

    DisposableEffect(viewerClient, decoder) {
        viewerClient.start()
        onDispose {
            viewerClient.close()
            decoder.close()
        }
    }

    val status by viewerClient.status.collectAsState()
    val videoWidth = (if (status.width > 0) status.width else offer.width).coerceAtLeast(16)
    val videoHeight = (if (status.height > 0) status.height else offer.height).coerceAtLeast(16)
    val videoRatio = (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.35f, 3.0f)

    Dialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07050A))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { controlsVisible = !controlsVisible },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = if (videoRatio >= 1f) {
                    Modifier.fillMaxWidth().aspectRatio(videoRatio)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(videoRatio)
                },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewCtx ->
                        SurfaceView(viewCtx).apply {
                            keepScreenOn = true
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    decoder.attachSurface(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    decoder.attachSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    decoder.attachSurface(null)
                                }
                            })
                        }
                    }
                )
            }

            // Center state overlay (connecting / reconnecting / locked screen / error)
            val centerMessage: Pair<String, String?>? = when {
                decoderError != null -> "تعذر عرض البث" to decoderError
                status.phase == ScreenMirrorViewerClient.ConnectionPhase.ERROR ->
                    "انقطع بث الشاشة" to (status.errorMessage ?: "تعذر الاتصال بجهاز المنشئ.")
                status.phase == ScreenMirrorViewerClient.ConnectionPhase.SCREEN_PROTECTED ->
                    "شاشة المضيف مقفلة أو محمية حالياً" to "يعود العرض تلقائياً عند فتح الشاشة أو مغادرة المحتوى المحمي."
                status.phase == ScreenMirrorViewerClient.ConnectionPhase.CONNECTING ->
                    "جارٍ الاتصال بالشاشة الجماعية..." to "${offer.hostName} • ${offer.width}×${offer.height}"
                status.phase == ScreenMirrorViewerClient.ConnectionPhase.RECONNECTING ->
                    "إعادة الاتصال التلقائي..." to "محاولة ${status.reconnectAttempt}"
                else -> null
            }

            if (centerMessage != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xCC191223),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (status.phase == ScreenMirrorViewerClient.ConnectionPhase.SCREEN_PROTECTED) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = Color(0xFFFFC66D),
                                modifier = Modifier.size(34.dp)
                            )
                        } else if (status.phase == ScreenMirrorViewerClient.ConnectionPhase.CONNECTING ||
                            status.phase == ScreenMirrorViewerClient.ConnectionPhase.RECONNECTING
                        ) {
                            CircularProgressIndicator(
                                color = MalekPlayColors.PurpleBright,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Text(
                            centerMessage.first,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        centerMessage.second?.let { sub ->
                            Text(
                                sub,
                                color = Color(0xFFD5CBE2),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                        if (status.phase == ScreenMirrorViewerClient.ConnectionPhase.ERROR || decoderError != null) {
                            Button(
                                onClick = onLeave,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MalekPlayColors.PurpleBright,
                                    contentColor = Color(0xFF190F22)
                                )
                            ) {
                                Text("العودة للغرفة", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xDD110B18), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (status.phase == ScreenMirrorViewerClient.ConnectionPhase.STREAMING) {
                                            Color(0xFF65D6A2)
                                        } else {
                                            Color(0xFFFFC66D)
                                        },
                                        CircleShape
                                    )
                            )
                            Text(
                                "الشاشة الجماعية • ${offer.hostName}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val qualityLabel = buildString {
                            append("${videoWidth}×${videoHeight}")
                            if (status.estimatedFps > 0) append(" • ${status.estimatedFps} fps")
                            if (status.bitrateKbps > 0) append(" • ${status.bitrateKbps} kbps")
                            append(if (offer.audioEnabled) " • صوت + صورة" else " • فيديو فقط")
                        }
                        Text(
                            qualityLabel,
                            color = Color(0xFFD5CBE2),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (offer.audioEnabled) {
                        IconButton(
                            onClick = {
                                val nextMuted = !audioMuted
                                audioMuted = nextMuted
                                decoder.setMuted(nextMuted)
                            }
                        ) {
                            Icon(
                                if (audioMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = if (audioMuted) "تشغيل الصوت" else "كتم الصوت",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            UltraShareForegroundService.sendMirrorViewerAction(
                                context = context,
                                hostAddress = offer.hostAddress,
                                sessionId = offer.sessionId,
                                action = UltraShareScreenMirrorProtocol.ViewerAction.REQUEST_IDR
                            )
                        }
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "تحديث الإطار",
                            tint = Color.White
                        )
                    }

                    OutlinedButton(
                        onClick = onLeave,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB4A9))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("مغادرة", modifier = Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
