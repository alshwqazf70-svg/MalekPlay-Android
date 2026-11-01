package com.malekplay.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.malekplay.data.preferences.PlayerSettings
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.backup.BackupViewModel

@Composable
fun SettingsDialog(
    settings: PlayerSettings,
    onSettingsChange: ((PlayerSettings) -> PlayerSettings) -> Unit,
    onChildModeChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val parental = remember(context) { ParentalControlManager(context) }
    var childModeEnabled by remember { mutableStateOf(parental.isEnabled) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val backupViewModel: BackupViewModel = hiltViewModel()
    val backupMessage by backupViewModel.message.collectAsState()
    val backupBusy by backupViewModel.busy.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) backupViewModel.export(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) backupViewModel.restore(uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات MalekPlay") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!childModeEnabled) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.resumePlayback,
                        onCheckedChange = { enabled -> onSettingsChange { it.copy(resumePlayback = enabled) } }
                    )
                    Text("استئناف التشغيل من آخر موضع")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.rememberBrightness,
                        onCheckedChange = { enabled -> onSettingsChange { it.copy(rememberBrightness = enabled) } }
                    )
                    Text("تفعيل إيماءة السطوع")
                }
                Text("سرعة التشغيل الافتراضية: ${"%.2f".format(settings.defaultPlaybackSpeed)}×")
                Slider(
                    value = settings.defaultPlaybackSpeed,
                    onValueChange = { value -> onSettingsChange { it.copy(defaultPlaybackSpeed = value) } },
                    valueRange = 0.5f..2f
                )
                Text("مظهر التطبيق", style = MaterialTheme.typography.titleSmall)
                listOf("light" to "فاتح", "dark" to "داكن", "system" to "حسب إعدادات الجهاز").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.theme == value,
                            onClick = { onSettingsChange { it.copy(theme = value) } }
                        )
                        Text(label)
                    }
                }
                Text("حجم الترجمة: ${settings.subtitleSizeSp} نقطة")
                Slider(
                    value = settings.subtitleSizeSp.toFloat(),
                    onValueChange = { value -> onSettingsChange { it.copy(subtitleSizeSp = value.toInt()) } },
                    valueRange = 14f..36f,
                    steps = 10
                )
                }
                Text("العائلة والخصوصية", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (childModeEnabled) "وضع الأطفال مفعّل" else "وضع الأطفال متوقف")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        pinInput = ""
                        pinError = null
                        showPinDialog = true
                    }) { Text(if (childModeEnabled) "إيقاف" else "تفعيل") }
                }
                Text("يطلب وضع الأطفال رمز PIN قبل تعطيله. الرمز من 4 إلى 8 أرقام.")
                if (!childModeEnabled) {
                    Text("النسخ الاحتياطي", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !backupBusy, onClick = { exportLauncher.launch("malekplay-backup.json") }) {
                            Text("تصدير")
                        }
                        Button(enabled = !backupBusy, onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*"))
                        }) { Text("استعادة") }
                    }
                    backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("تشمل النسخة الإعدادات والمفضلة وقوائم التشغيل، ولا تنسخ ملفات الوسائط أو قاعدة البيانات الحية.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } }
    )

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = {
                Text(
                    if (childModeEnabled) "إيقاف وضع الأطفال"
                    else if (parental.hasPin) "تفعيل وضع الأطفال"
                    else "إنشاء رمز وضع الأطفال"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { value ->
                            if (value.length <= 8 && value.all { it in '0'..'9' }) pinInput = value
                        },
                        label = { Text("رمز PIN") },
                        singleLine = true
                    )
                    pinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (!parental.hasPin) parental.setPin(pinInput)
                        val success = if (childModeEnabled) parental.disableAfterPin(pinInput)
                        else parental.enableAfterPin(pinInput)
                        if (success) {
                            childModeEnabled = parental.isEnabled
                            onChildModeChanged(childModeEnabled)
                            showPinDialog = false
                        } else pinError = "الرمز غير صحيح أو أن المحاولات مقفلة مؤقتاً"
                    } catch (error: Exception) {
                        pinError = error.localizedMessage ?: "تعذر حفظ الرمز"
                    }
                }) { Text("تأكيد") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("إلغاء") } }
        )
    }
}
