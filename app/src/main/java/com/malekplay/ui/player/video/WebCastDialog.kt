package com.malekplay.ui.player.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareQrCode
import com.malekplay.player.cast.WebCastRegistry
import com.malekplay.player.cast.WebCastUiState

/**
 * Reusable live-cast control surface for streaming to any PC or Smart TV browser
 * with QR code, 6-digit PIN protection, one-click link copying, and connected viewer status.
 */
@Composable
fun WebCastConsolePanel(
    state: WebCastUiState,
    onStart: (requirePin: Boolean) -> Unit,
    onStop: () -> Unit,
    onToggleRequirePin: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
    modifier: Modifier = Modifier,
    extraHeaderAction: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF231936), Color(0xFF151020))))
                .border(1.dp, Color(0x55A855F7), cardShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleBright,
                    modifier = Modifier.size(26.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "البث المباشر للكمبيوتر والمتصفح",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MalekPlayColors.TextPrimary
                    )
                    Text(
                        text = "شاهد الفيديو والترجمة وتحكّم بقائمة التشغيل من أي متصفح كمبيوتر أو تلفزيون ذكي دون تثبيت أي برنامج.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MalekPlayColors.TextSecondary
                    )
                }
            }

            extraHeaderAction?.invoke()

            if (!state.running) {
                state.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onStart(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("تشغيل البث المباشر للكمبيوتر")
                }
            } else {
                val qrTarget = state.quickConnectUrl ?: state.primaryUrl.orEmpty()
                val qrBitmap = remember(qrTarget) {
                    if (qrTarget.isNotBlank()) runCatching { UltraShareQrCode.createBitmap(qrTarget, 420) }.getOrNull()
                    else null
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "رمز QR للبث إلى الكمبيوتر",
                            modifier = Modifier
                                .size(176.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(6.dp)
                        )
                    }
                }

                // Clean URL + Copy
                state.primaryUrl?.let { url ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0E0B15))
                            .border(1.dp, Color(0x44C084FC), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("رابط الكمبيوتر / التلفزيون", style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.TextMuted)
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MalekPlayColors.PurpleBright,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { copyToClipboard(context, "رابط البث", url) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ الرابط", tint = MalekPlayColors.TextPrimary)
                        }
                        state.quickConnectUrl?.let { quick ->
                            IconButton(onClick = { shareQuickLink(context, quick) }) {
                                Icon(Icons.Filled.Share, contentDescription = "مشاركة رابط الدخول السريع", tint = MalekPlayColors.PurpleSoft)
                            }
                        }
                    }
                }

                // 6-digit PIN row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0E0B15))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("رمز الحماية (PIN)", style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.TextMuted)
                        Text(
                            text = if (state.requirePin) state.pinCode else "مفتوح للشبكة المحلية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = if (state.requirePin) MalekPlayColors.TextPrimary else Color(0xFF4ADE80)
                        )
                    }
                    if (state.requirePin) {
                        IconButton(onClick = onRegeneratePin) {
                            Icon(Icons.Filled.Refresh, contentDescription = "تغيير رمز PIN", tint = MalekPlayColors.TextSecondary)
                        }
                    }
                    Switch(
                        checked = state.requirePin,
                        onCheckedChange = onToggleRequirePin
                    )
                }

                // Quick-connect note
                state.quickConnectUrl?.let { quick ->
                    OutlinedButton(
                        onClick = { copyToClipboard(context, "رابط الدخول المباشر", quick) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("نسخ الرابط المباشر (بدون كتابة PIN)")
                    }
                }

                // Connected viewers & stream summary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x22000000))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val title = state.currentTitle.ifBlank { "اختر ملفاً من القائمة أو المشغل" }
                    Text(
                        text = "المحتوى الجاهز (${state.itemCount}): $title",
                        style = MaterialTheme.typography.labelMedium,
                        color = MalekPlayColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.viewers.isEmpty()) {
                        Text(
                            text = "في انتظار اتصال متصفح الكمبيوتر أو التلفزيون…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MalekPlayColors.TextSecondary
                        )
                    } else {
                        Text(
                            text = "الأجهزة المتصلة الآن (${state.viewers.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4ADE80),
                            fontWeight = FontWeight.Bold
                        )
                        state.viewers.forEach { viewer ->
                            Text(
                                text = "• ${viewer.label} (${viewer.host})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MalekPlayColors.TextSecondary
                            )
                        }
                    }
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("إيقاف البث إلى الكمبيوتر")
                }
            }
        }
    }
}

@Composable
fun WebCastDialog(
    onDismiss: () -> Unit,
    onPickFilesToShare: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val webState by WebCastRegistry.state.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("البث إلى الكمبيوتر والمتصفح") },
        text = {
            WebCastConsolePanel(
                state = webState,
                onStart = { requirePin ->
                    runCatching { WebCastRegistry.start(context, requirePin) }
                        .onFailure { Toast.makeText(context, it.message ?: "تعذر بدء البث", Toast.LENGTH_LONG).show() }
                },
                onStop = { WebCastRegistry.stop() },
                onToggleRequirePin = { WebCastRegistry.setRequirePin(it) },
                onRegeneratePin = { WebCastRegistry.regeneratePin() },
                extraHeaderAction = if (onPickFilesToShare != null) {
                    {
                        OutlinedButton(
                            onClick = onPickFilesToShare,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("اختيار فيديوهات أو صوتيات للبث للكمبيوتر")
                        }
                    }
                } else null
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "تم نسخ $label", Toast.LENGTH_SHORT).show()
}

private fun shareQuickLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "مشاركة رابط البث المباشر"))
    }
}
