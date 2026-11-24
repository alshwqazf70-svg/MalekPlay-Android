@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.malekplay.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.malekplay.BuildConfig
import com.malekplay.R
import com.malekplay.backup.MalekPlayBackupManager
import com.malekplay.core.ui.theme.AppThemeMode
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.theme.tintedSurface
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

private enum class SettingsCategory {
    ALL, PLAYBACK, APPEARANCE, GESTURES, AUDIO, SUBTITLES, FAMILY, BACKUP, VAULT, ABOUT
}

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

@UnstableApi
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKidsContent: () -> Unit = {},
    onKidsModeEnabled: () -> Unit = {},
    onOpenVault: () -> Unit = {}
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val backupViewModel: BackupViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerController by playerViewModel.controller.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by backupViewModel.message.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    val context = LocalContext.current
    val parentalControl = remember(context) { ParentalControlManager(context) }
    var kidsEnabled by remember(parentalControl) { mutableStateOf(parentalControl.isEnabled) }
    var kidsTimerLimit by remember(parentalControl) { mutableIntStateOf(parentalControl.sessionLimitMinutes) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.ALL) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { backupViewModel.exportTo(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { backupViewModel.restoreFrom(it) } }

    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000L)
            backupViewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
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
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = 0.20f),
                            colors.background,
                            colors.surface
                        )
                    )
                )
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Quick category filter pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsCategory.entries.forEach { cat ->
                    val selected = selectedCategory == cat
                    val bg = if (selected) colors.primary else colors.surfaceContainerHigh.copy(alpha = 0.85f)
                    val fg = if (selected) contrastingOnColor(colors.primary) else colors.onSurfaceVariant
                    Surface(
                        shape = CircleShape,
                        color = bg,
                        border = BorderStroke(
                            1.dp,
                            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = categoryLabel(cat),
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = fg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // -------------------------------------------------- playback
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.PLAYBACK) {
                    SettingsSection(
                        title = stringResource(R.string.settings_playback),
                        icon = Icons.Filled.PlayCircle
                    ) {
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
                }

                // ------------------------------------------------ appearance
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.APPEARANCE) {
                    SettingsSection(
                        title = stringResource(R.string.settings_appearance),
                        icon = Icons.Filled.Palette
                    ) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppThemeMode.entries.forEach { mode ->
                                val selected = settings.themeMode == mode
                                val tileBg = if (selected) colors.primary else colors.surfaceVariant.copy(alpha = 0.72f)
                                val tileInk = if (selected) contrastingOnColor(colors.primary) else colors.onSurface
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = tileBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.55f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { viewModel.update { it.copy(themeMode = mode) } }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = themeIcon(mode),
                                            contentDescription = null,
                                            tint = tileInk,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = themeLabel(mode),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tileInk,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        AppearancePreviewCard()
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.settings_accent),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MalekPlayColors.Accents.forEach { (id, color) ->
                                val selected = settings.accent == id
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .then(
                                            if (selected) {
                                                Modifier.border(2.5.dp, colors.onSurface, CircleShape)
                                            } else {
                                                Modifier.border(1.dp, colors.outlineVariant, CircleShape)
                                            }
                                        )
                                        .clickable { viewModel.update { it.copy(accent = id) } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = contrastingOnColor(color),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "لون الأحرف والنصوص",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            text = "اختر اللون الذي يريح عينيك. الأسود يفعّل خلفية فاتحة، والأبيض يفعّل خلفية داكنة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val textPalettes = listOf(
                            Triple("aurora", "الذهبي والبنفسجي", Color(0xFFC084FC)),
                            Triple("white", "أبيض", Color.White),
                            Triple("black", "أسود", Color(0xFF111111)),
                            Triple("gold", "ذهبي", Color(0xFFFFD166)),
                            Triple("purple", "بنفسجي", Color(0xFFC084FC)),
                            Triple("cyan", "سماوي", Color(0xFF64DCEB)),
                            Triple("mint", "نعناعي", Color(0xFF69D5A8)),
                            Triple("pink", "وردي", Color(0xFFFF8FB1))
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            textPalettes.forEach { (id, label, swatch) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(76.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (id == "aurora") Brush.linearGradient(
                                                    listOf(Color(0xFFFFD166), Color(0xFFC084FC))
                                                ) else Brush.linearGradient(listOf(swatch, swatch))
                                            )
                                            .then(
                                                if (settings.textColorMode == id) {
                                                    Modifier.border(3.dp, colors.primary, CircleShape)
                                                } else {
                                                    Modifier.border(1.dp, colors.outlineVariant, CircleShape)
                                                }
                                            )
                                            .clickable { viewModel.update { it.copy(textColorMode = id) } },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (settings.textColorMode == id) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = contrastingOnColor(swatch),
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurface,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SwitchRow(
                            title = stringResource(R.string.settings_dynamic_color),
                            summary = stringResource(R.string.settings_dynamic_color_summary),
                            checked = settings.dynamicColor,
                            onCheckedChange = { checked ->
                                viewModel.update { it.copy(dynamicColor = checked) }
                            }
                        )
                    }
                }

                // -------------------------------------------------- gestures
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.GESTURES) {
                    SettingsSection(
                        title = stringResource(R.string.settings_gestures),
                        icon = Icons.Filled.TouchApp
                    ) {
                        GestureRow(
                            title = stringResource(R.string.settings_gesture_swipe),
                            current = settings.gestureSwipeHorizontal,
                            onSelect = { action ->
                                viewModel.update { it.copy(gestureSwipeHorizontal = action) }
                            }
                        )
                        GestureRow(
                            title = stringResource(R.string.settings_gesture_vertical_left),
                            current = settings.gestureVerticalLeft,
                            onSelect = { action ->
                                viewModel.update { it.copy(gestureVerticalLeft = action) }
                            }
                        )
                        GestureRow(
                            title = stringResource(R.string.settings_gesture_vertical_right),
                            current = settings.gestureVerticalRight,
                            onSelect = { action ->
                                viewModel.update { it.copy(gestureVerticalRight = action) }
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
                }

                // ----------------------------------------------------- audio
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.AUDIO) {
                    SettingsSection(
                        title = stringResource(R.string.settings_audio),
                        icon = Icons.Filled.GraphicEq
                    ) {
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
                            range = 0f..12f,
                            steps = 11,
                            display = "${settings.loudnessGainDb} dB",
                            onValueChange = { value ->
                                viewModel.update { it.copy(loudnessGainDb = value.toInt()) }
                            }
                        )
                    }
                }

                // ------------------------------------------------- subtitles
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.SUBTITLES) {
                    SettingsSection(
                        title = stringResource(R.string.settings_subtitles),
                        icon = Icons.Filled.Subtitles
                    ) {
                        SliderRow(
                            title = stringResource(R.string.menu_subtitle_size),
                            value = settings.subtitleSizeSp.toFloat(),
                            range = 12f..48f,
                            steps = 35,
                            display = "${settings.subtitleSizeSp} sp",
                            onValueChange = { value ->
                                viewModel.update { it.copy(subtitleSizeSp = value.toInt()) }
                            }
                        )
                    }
                }

                // --------------------------------------------------- family
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.FAMILY) {
                    SettingsSection(
                        title = stringResource(R.string.settings_family),
                        icon = Icons.Filled.ChildCare
                    ) {
                        SwitchRow(
                            title = stringResource(R.string.settings_child_mode),
                            summary = stringResource(R.string.settings_child_mode_summary),
                            checked = kidsEnabled,
                            onCheckedChange = { showPinDialog = true }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.kids_timer_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0, 15, 30, 45, 60).forEach { minutes ->
                                val selected = kidsTimerLimit == minutes
                                val chipBg = if (selected) colors.primary else colors.surfaceVariant.copy(alpha = 0.72f)
                                val chipFg = if (selected) contrastingOnColor(colors.primary) else colors.onSurface
                                Surface(
                                    shape = CircleShape,
                                    color = chipBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            kidsTimerLimit = minutes
                                            parentalControl.setSessionLimitMinutes(minutes)
                                        }
                                ) {
                                    Text(
                                        text = if (minutes == 0) {
                                            stringResource(R.string.kids_timer_unlimited)
                                        } else {
                                            stringResource(R.string.kids_timer_minutes, minutes)
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = chipFg,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = onOpenKidsContent,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.ChildCare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.kids_manage_content),
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = CircleShape,
                                color = colors.primary
                            ) {
                                Text(
                                    text = stringResource(R.string.kids_approved_badge, parentalControl.allowedMediaIds.size),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contrastingOnColor(colors.primary),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (parentalControl.hasPin) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showChangePinDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.kids_change_pin))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.kids_manage_content_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // --------------------------------------------------- backup
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.BACKUP) {
                    SettingsSection(
                        title = stringResource(R.string.settings_backup),
                        icon = Icons.Filled.Backup
                    ) {
                        Text(
                            text = stringResource(R.string.settings_backup_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { exportLauncher.launch("malekplay-backup.json") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.settings_backup_export), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Filled.DownloadForOffline, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.settings_backup_import), maxLines = 1)
                            }
                        }
                        if (message != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val ok = message == BACKUP_OK
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (ok) colors.primaryContainer else colors.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (ok) stringResource(R.string.settings_done) else message.orEmpty(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) colors.onPrimaryContainer else colors.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // --------------------------------------------- private vault
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.VAULT) {
                    SettingsSection(
                        title = stringResource(R.string.vault_title),
                        icon = Icons.Filled.Lock
                    ) {
                        Text(
                            text = stringResource(R.string.vault_settings_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onOpenVault,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = contrastingOnColor(colors.primary)
                            )
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.vault_open_from_settings),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ---------------------------------------------------- about
                if (selectedCategory == SettingsCategory.ALL || selectedCategory == SettingsCategory.ABOUT) {
                    SettingsSection(
                        title = stringResource(R.string.settings_about),
                        icon = Icons.Filled.Info
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(shape = CircleShape, color = colors.primaryContainer) {
                                Text(
                                    text = "MalekPlayer",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showResetConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_reset_defaults))
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetToDefaults()
                        showResetConfirm = false
                        Toast.makeText(context, context.getString(R.string.settings_done), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.settings_reset_defaults))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.lib_cancel))
                }
            }
        )
    }

    if (showChangePinDialog) {
        ChangePinDialog(
            lockoutSeconds = parentalControl.remainingLockoutSeconds(),
            onDismiss = { showChangePinDialog = false },
            onConfirm = { currentPin, newPin ->
                val changed = runCatching {
                    parentalControl.changePin(currentPin, newPin)
                }.getOrDefault(false)
                if (changed) {
                    showChangePinDialog = false
                    Toast.makeText(context, context.getString(R.string.kids_pin_updated), Toast.LENGTH_SHORT).show()
                }
                changed
            }
        )
    }

    if (showPinDialog) {
        PinDialog(
            hasPin = parentalControl.hasPin,
            turningOff = kidsEnabled,
            lockoutSeconds = parentalControl.remainingLockoutSeconds(),
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
                    if (kidsEnabled) {
                        if (playerController != null) playerViewModel.stopForParentalMode()
                        onKidsModeEnabled()
                    }
                }
                applied
            }
        )
    }
}

// ----------------------------------------------------------------- sections

@Composable
private fun AppearancePreviewCard() {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .border(1.dp, primary.copy(alpha = 0.35f), shape)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MalekPlayer Cinema UI",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.home_library_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(primary, secondary)))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "4K HDR",
                        style = MaterialTheme.typography.labelSmall,
                        color = contrastingOnColor(primary)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(primary, secondary)))
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val deepIconBg = tintedSurface(colors.primaryContainer, colors.primary, 0.16f)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .border(1.dp, colors.primary.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = colors.surfaceContainer.copy(alpha = 0.94f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(deepIconBg)
                        .border(1.dp, colors.primary.copy(alpha = 0.30f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
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
            .clip(RoundedCornerShape(12.dp))
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
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = display,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
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
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f))
            ) {
                Text(
                    text = gestureLabel(current),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureAction.entries.forEach { action ->
                    val selected = action == current
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = gestureLabel(action),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) colors.primary else colors.onSurface
                            )
                        },
                        trailingIcon = {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            }
                        },
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
    lockoutSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var confirmationPin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

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
                if (!hasPin) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmationPin,
                        onValueChange = {
                            confirmationPin = it.filter(Char::isDigit).take(8)
                            mismatch = false
                        },
                        label = { Text("تأكيد رمز الوالد") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                }
                if (mismatch) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "رمز التأكيد غير مطابق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (lockoutSeconds > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.kids_pin_lockout, lockoutSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (wrong) {
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
                    if (!hasPin && pin != confirmationPin) {
                        mismatch = true
                        return@TextButton
                    }
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

@Composable
private fun ChangePinDialog(
    lockoutSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Boolean
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.kids_change_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it.filter(Char::isDigit).take(8); errorMsg = null },
                    label = { Text(stringResource(R.string.kids_current_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter(Char::isDigit).take(8); errorMsg = null },
                    label = { Text(stringResource(R.string.kids_new_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter(Char::isDigit).take(8); errorMsg = null },
                    label = { Text("تأكيد رمز الوالد") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                if (lockoutSeconds > 0) {
                    Text(
                        text = stringResource(R.string.kids_pin_lockout, lockoutSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (errorMsg != null) {
                    Text(
                        text = errorMsg.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newPin.length < 4) {
                        errorMsg = "يجب أن يتكون الرمز من 4 إلى 8 أرقام"
                        return@TextButton
                    }
                    if (newPin != confirmPin) {
                        errorMsg = "رمز التأكيد غير مطابق."
                        return@TextButton
                    }
                    if (!onConfirm(currentPin, newPin)) {
                        errorMsg = "الرمز الحالي غير صحيح أو مقفل مؤقتاً"
                    }
                }
            ) {
                Text(stringResource(R.string.kids_save_selection))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lib_cancel))
            }
        }
    )
}

// ------------------------------------------------------------------- labels

@Composable
private fun categoryLabel(cat: SettingsCategory): String = when (cat) {
    SettingsCategory.ALL -> stringResource(R.string.settings_section_all)
    SettingsCategory.PLAYBACK -> stringResource(R.string.settings_playback)
    SettingsCategory.APPEARANCE -> stringResource(R.string.settings_appearance)
    SettingsCategory.GESTURES -> stringResource(R.string.settings_gestures)
    SettingsCategory.AUDIO -> stringResource(R.string.settings_audio)
    SettingsCategory.SUBTITLES -> stringResource(R.string.settings_subtitles)
    SettingsCategory.FAMILY -> stringResource(R.string.settings_family)
    SettingsCategory.BACKUP -> stringResource(R.string.settings_backup)
    SettingsCategory.VAULT -> stringResource(R.string.vault_title)
    SettingsCategory.ABOUT -> stringResource(R.string.settings_about)
}

private fun themeIcon(mode: AppThemeMode): ImageVector = when (mode) {
    AppThemeMode.LIGHT -> Icons.Filled.LightMode
    AppThemeMode.DARK -> Icons.Filled.DarkMode
    AppThemeMode.AMOLED -> Icons.Filled.Contrast
    AppThemeMode.SYSTEM -> Icons.Filled.AutoAwesome
}

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
