package com.malekplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.malekplay.data.preferences.PlayerSettings

@Composable
fun SettingsDialog(
    settings: PlayerSettings,
    onSettingsChange: ((PlayerSettings) -> PlayerSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات MalekPlay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = settings.resumePlayback,
                        onCheckedChange = { enabled ->
                            onSettingsChange { it.copy(resumePlayback = enabled) }
                        }
                    )
                    Text("استئناف التشغيل من آخر موضع")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = settings.rememberBrightness,
                        onCheckedChange = { enabled ->
                            onSettingsChange { it.copy(rememberBrightness = enabled) }
                        }
                    )
                    Text("تفعيل إيماءة السطوع")
                }
                Text("سرعة التشغيل الافتراضية: ${"%.2f".format(settings.defaultPlaybackSpeed)}×")
                Slider(
                    value = settings.defaultPlaybackSpeed,
                    onValueChange = { value ->
                        onSettingsChange { it.copy(defaultPlaybackSpeed = value) }
                    },
                    valueRange = 0.5f..2f
                )
                Text("حجم الترجمة: ${settings.subtitleSizeSp} نقطة")
                Slider(
                    value = settings.subtitleSizeSp.toFloat(),
                    onValueChange = { value ->
                        onSettingsChange { it.copy(subtitleSizeSp = value.toInt()) }
                    },
                    valueRange = 14f..36f,
                    steps = 10
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("تم") }
        }
    )
}
