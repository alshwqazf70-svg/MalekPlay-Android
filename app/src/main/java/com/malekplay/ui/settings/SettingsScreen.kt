@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.malekplay.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.malekplay.BuildConfig
import com.malekplay.R
import com.malekplay.backup.MalekPlayBackupManager
import com.malekplay.core.ui.theme.AppThemeMode
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.util.formatSpeed
import com.malekplay.domain.model.GestureAction
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.player.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val BACKUP_OK = "ok"

/** Runs the backup export / restore off the main thread and reports the result. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: MalekPlayBackupManager
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _message.value = runCatching { backupManager.exportTo(uri) }
                .fold(
                    onSuccess = { BACKUP_OK },
                    onFailure = { it.localizedMessage ?: it.javaClass.simpleName }
                )
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            _message.value = runCatching { backupManager.restoreFrom(uri) }
                .fold(
                    onSuccess = { BACKUP_OK },
                    onFailure = { it.localizedMessage ?: it.javaClass.simpleName }
                )
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKidsContent: () -> Unit = {},
    onKidsModeEnabled: () -> Unit = {}
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val backupViewModel: BackupViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerController by playerViewModel.controller.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by backupViewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val parentalControl = remember(context) { ParentalControlManager(context) }
    var kidsEnabled by remember(parentalControl) { mutableStateOf(parentalControl.isEnabled) }
    var showPinDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { backupViewModel.exportTo(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { backupViewModel.restoreFrom(it) } }

    LaunchedEffect(kidsEnabled, playerController) {
        if (kidsEnabled && playerController != null) playerViewModel.stopForParentalMode()
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000L)
            backupViewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ------------------------------------------------------ playback
            SettingsSection(title = stringResource(R.string.settings_playback)) {
                SwitchRow(
                    title = stringResource(R.string.settings_resume),
                    summary = stringResource(R.string.settings_resume_summary),
                    checked = settings.resumePlayback,
                    onCheckedChange = { checked ->
                        viewModel.update { it.copy(resumePlayback = checked) }
                    }
                )
                SliderRow(
                    title = stringResource(R.string.settings_speed_default),
                    value = settings.defaultSpeed,
                    range = 0.5f..2f,
                    steps = 5,
                    display = formatSpeed(settings.defaultSpeed),
                    onValueChange = { viewModel.update { s -> s.copy(defaultSpeed = it) } }
                )
                SliderRow(
                    title = stringResource(R.string.settings_skip),
                    value = settings.skipSeconds.toFloat(),
                    range = 5f..60f,
                    steps = 10,
                    display = stringResource(R.string.settings_skip_seconds, settings.skipSeconds),
                    onValueChange = { value ->
                        viewModel.update { s -> s.copy(skipSeconds = value.toInt()) }
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.settings_autoplay),
                    checked = settings.autoplayNext,
                    onCheckedChange = { checked ->
                        viewModel.update { it.copy(autoplayNext = checked) }
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.settings_background),
                    checked = settings.backgroundPlayback,
                    onCheckedChange = { checked ->
                        viewModel.update { it.copy(backgroundPlayback = checked) }
                    }
                )
            }

            // ---------------------------------------------------- appearance
            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.update { it.copy(themeMode = mode) } }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.update { it.copy(themeMode = mode) } }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = themeLabel(mode), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_accent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MalekPlayColors.Accents.forEach { (id, color) ->
                        val selected = settings.accent == id
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { viewModel.update { it.copy(accent = id) } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    summary = stringResource(R.string.settings_dynamic_color_summary),
                    checked = settings.dynamicColor,
                    onCheckedChange = { checked ->
                        viewModel.update { it.copy(dynamicColor = checked) }
                    }
                )
            }

            // ------------------------------------------------------ gestures
            SettingsSection(title = stringResource(R.string.settings_gestures)) {
                GestureRow(
                    title = stringResource(R.string.settings_gesture_swipe),
                    current = settings.gestureSwipeHorizontal,
                    onSelect = { action ->
                        viewModel.update { it.copy(gestureSwipeHorizontal = action) }
                    }
                )
                GestureRow(
                    title = stringResource(R.string.settings_gesture_double_tap),
                    current = settings.gestureDoubleTap,
                    onSelect = { action ->
                        viewModel.update { it.copy(gestureDoubleTap = action) }
                    }
                )
                GestureRow(
                    title = stringResource(R.string.settings_gesture_long_press),
                    current = settings.gestureLongPress,
                    onSelect = { action ->
                        viewModel.update { it.copy(gestureLongPress = action) }
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.settings_brightness_gesture),
                    summary = stringResource(R.string.settings_brightness_gesture_summary),
                    checked = settings.rememberBrightness,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(rememberBrightness = enabled) }
                    }
                )
            }

            // --------------------------------------------------------- audio
            SettingsSection(title = stringResource(R.string.settings_audio)) {
                SliderRow(
                    title = stringResource(R.string.settings_bass_boost),
                    value = settings.bassBoostStrength.toFloat(),
                    range = 0f..1000f,
                    steps = 9,
                    display = settings.bassBoostStrength.toString(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(bassBoostStrength = value.toInt()) }
                    }
                )
                SliderRow(
                    title = stringResource(R.string.settings_virtualizer),
                    value = settings.virtualizerStrength.toFloat(),
                    range = 0f..1000f,
                    steps = 9,
                    display = settings.virtualizerStrength.toString(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(virtualizerStrength = value.toInt()) }
                    }
                )
                SliderRow(
                    title = stringResource(R.string.settings_loudness),
                    value = settings.loudnessGainDb.toFloat(),
                    range = 0f..30f,
                    steps = 29,
                    display = settings.loudnessGainDb.toString(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(loudnessGainDb = value.toInt()) }
                    }
                )
            }

            // ----------------------------------------------------- subtitles
            SettingsSection(title = stringResource(R.string.settings_subtitles)) {
                SliderRow(
                    title = stringResource(R.string.menu_subtitle_size),
                    value = settings.subtitleSizeSp.toFloat(),
                    range = 12f..48f,
                    steps = 35,
                    display = settings.subtitleSizeSp.toString(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(subtitleSizeSp = value.toInt()) }
                    }
                )
            }

            // ------------------------------------------------------- family
            SettingsSection(title = stringResource(R.string.settings_family)) {
                SwitchRow(
                    title = stringResource(R.string.settings_child_mode),
                    summary = stringResource(R.string.settings_child_mode_summary),
                    checked = kidsEnabled,
                    onCheckedChange = { showPinDialog = true }
                )
                OutlinedButton(
                    onClick = onOpenKidsContent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.kids_manage_content))
                }
                Text(
                    text = stringResource(R.string.kids_manage_content_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ------------------------------------------------------- backup
            SettingsSection(title = stringResource(R.string.settings_backup)) {
                Text(
                    text = stringResource(R.string.settings_backup_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("malekplay-backup.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_backup_export))
                    }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_backup_import))
                    }
                }
                if (message != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (message == BACKUP_OK) {
                            stringResource(R.string.settings_done)
                        } else {
                            message ?: ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // -------------------------------------------------------- about
            SettingsSection(title = stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (showPinDialog) {
        PinDialog(
            hasPin = parentalControl.hasPin,
            turningOff = kidsEnabled,
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                val applied = runCatching {
                    if (!parentalControl.hasPin) {
                        parentalControl.setPin(pin)
                        parentalControl.enableAfterPin(pin)
                    } else if (kidsEnabled) {
                        parentalControl.disableAfterPin(pin)
                    } else {
                        parentalControl.enableAfterPin(pin)
                    }
                }.getOrDefault(false)
                if (applied) {
                    kidsEnabled = parentalControl.isEnabled
                    showPinDialog = false
                    if (kidsEnabled) onKidsModeEnabled()
                }
                applied
            }
        )
    }
}

// ----------------------------------------------------------------- sections

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp)),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun GestureRow(
    title: String,
    current: GestureAction,
    onSelect: (GestureAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            Text(
                text = gestureLabel(current),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(text = gestureLabel(action)) },
                        onClick = {
                            expanded = false
                            onSelect(action)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDialog(
    hasPin: Boolean,
    turningOff: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.kids_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (hasPin) R.string.kids_pin_hint else R.string.kids_create_pin
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(8)
                        wrong = false
                    },
                    label = { Text(text = stringResource(R.string.kids_pin_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                if (wrong) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.kids_pin_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onConfirm(pin)) wrong = true
                }
            ) {
                Text(
                    text = stringResource(
                        if (turningOff) R.string.kids_disable else R.string.kids_enable
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.lib_cancel))
            }
        }
    )
}

// ------------------------------------------------------------------- labels

@Composable
private fun themeLabel(mode: AppThemeMode): String = stringResource(
    when (mode) {
        AppThemeMode.LIGHT -> R.string.settings_theme_light
        AppThemeMode.DARK -> R.string.settings_theme_dark
        AppThemeMode.AMOLED -> R.string.settings_theme_amoled
        AppThemeMode.SYSTEM -> R.string.settings_theme_system
    }
)

@Composable
private fun gestureLabel(action: GestureAction): String = stringResource(
    when (action) {
        GestureAction.NONE -> R.string.settings_gesture_action_none
        GestureAction.SEEK -> R.string.settings_gesture_action_seek
        GestureAction.VOLUME -> R.string.settings_gesture_action_volume
        GestureAction.BRIGHTNESS -> R.string.settings_gesture_action_brightness
        GestureAction.PLAY_PAUSE -> R.string.settings_gesture_action_play_pause
        GestureAction.SPEED_BOOST -> R.string.settings_gesture_action_speed
    }
)
