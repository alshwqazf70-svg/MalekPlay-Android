package com.malekplay.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malekplay.R
import com.malekplay.core.ui.util.DecoderInfo
import com.malekplay.core.ui.util.formatBitrate
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatSpeed
import com.malekplay.domain.model.EqualizerPreset
import com.malekplay.domain.model.EQUALIZER_PRESETS
import com.malekplay.domain.model.PlayerSettings
import com.malekplay.ui.player.PlaybackStats
import com.malekplay.ui.player.TrackDescriptor

// ------------------------------------------------------------------- speed

@Composable
fun SpeedPickerDialog(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(current) { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_speed)) },
        text = {
            Column {
                Text(
                    formatSpeed(value),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.25f..4f,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        TextButton(onClick = { value = preset }) {
                            Text(formatSpeed(preset), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(value); onDismiss() }) {
                Text(stringResource(R.string.common_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } }
    )
}

// -------------------------------------------------------------- sleep timer

@Composable
fun SleepTimerDialog(
    active: Boolean,
    onMinutes: (Int) -> Unit,
    onEndOfEpisode: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMinutes(minutes); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.player_sleep_minutes, minutes))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEndOfEpisode(); onDismiss() }
                        .padding(vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.player_sleep_end_of_episode))
                }
                if (active) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCancel(); onDismiss() }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.player_sleep_cancel),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } }
    )
}

// ------------------------------------------------------------------ tracks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPickerSheet(
    title: String,
    tracks: List<TrackDescriptor>,
    disabled: Boolean,
    onSelect: (TrackDescriptor) -> Unit,
    onSetDisabled: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (tracks.isEmpty()) {
                Text(
                    stringResource(R.string.common_unknown),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(tracks, key = { "${it.groupIndex}_${it.trackIndex}" }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(track); onDismiss() }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = track.selected, onClick = { onSelect(track); onDismiss() })
                            Spacer(Modifier.width(8.dp))
                            Text(track.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            TextButton(
                onClick = { onSetDisabled(true); onDismiss() },
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(stringResource(R.string.player_subtitle_off))
            }
        }
    }
}

// ------------------------------------------------------------------- stats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(stats: PlaybackStats, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.player_stats),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            val hardware = DecoderInfo.isHardwareAccelerated(stats.videoMimeType)
            StatsRow(stringResource(R.string.stats_decoder), stats.videoCodec +
                if (hardware) "  •  ${stringResource(R.string.player_hw)}" else "")
            StatsRow(stringResource(R.string.stats_resolution),
                if (stats.width > 0) "${stats.width}×${stats.height}" else "—")
            StatsRow(stringResource(R.string.stats_fps),
                if (stats.frameRate > 0f) "%.2f fps".format(stats.frameRate) else "—")
            StatsRow(stringResource(R.string.stats_video), stats.videoCodec)
            StatsRow(stringResource(R.string.stats_bitrate), formatBitrate(stats.bitrate))
            StatsRow(stringResource(R.string.stats_audio), buildString {
                append(stats.audioCodec)
                if (stats.audioChannels > 0) append("  •  ${stats.audioChannels}ch")
                if (stats.audioSampleRate > 0) append("  •  ${stats.audioSampleRate} Hz")
            })
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------- subtitle style

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleSheet(
    settings: PlayerSettings,
    onChange: (PlayerSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var local by remember(settings) { mutableStateOf(settings) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.menu_subtitle_style),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(stringResource(R.string.menu_subtitle_size) + ": ${local.subtitleSizeSp} sp")
            Slider(
                value = local.subtitleSizeSp.toFloat(),
                onValueChange = { local = local.copy(subtitleSizeSp = it.toInt()) },
                valueRange = 12f..40f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.menu_subtitle_position) + ": ${local.subtitleBottomPadding}")
            Slider(
                value = local.subtitleBottomPadding.toFloat(),
                onValueChange = { local = local.copy(subtitleBottomPadding = it.toInt()) },
                valueRange = 0f..30f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.menu_subtitle_bg), modifier = Modifier.weight(1f))
                Switch(
                    checked = local.subtitleBackground,
                    onCheckedChange = { local = local.copy(subtitleBackground = it) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bold", modifier = Modifier.weight(1f))
                Switch(
                    checked = local.subtitleBold,
                    onCheckedChange = { local = local.copy(subtitleBold = it) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.menu_subtitle_color))
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    0xFFFFFFFFL to Color.White,
                    0xFFFFEB3BL to Color.Yellow,
                    0xFF4DD0E1L to Color.Cyan,
                    0xFF81C784L to Color(0xFF81C784),
                    0xFFFF80ABL to Color(0xFFFF80AB)
                ).forEach { (argb, color) ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(color, CircleShape)
                            .border(
                                2.dp,
                                if (local.subtitleColor == argb) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                CircleShape
                            )
                            .clickable { local = local.copy(subtitleColor = argb) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onChange(local); onDismiss() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_apply)) }
        }
    }
}

// ---------------------------------------------------------------- equalizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    available: Boolean,
    bandCount: Int,
    levelRange: Pair<Int, Int>,
    presetIndex: Int,
    bands: List<Int>,
    bassBoost: Int,
    virtualizer: Int,
    loudness: Int,
    onPreset: (EqualizerPreset) -> Unit,
    onBand: (Int, Int) -> Unit,
    onBass: (Int) -> Unit,
    onVirtualizer: (Int) -> Unit,
    onLoudness: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.player_equalizer),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (!available || bandCount <= 0) {
                Text(
                    "Audio effects are not available for this output.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EQUALIZER_PRESETS.forEach { preset ->
                        Surface(
                            modifier = Modifier.clickable { onPreset(preset) },
                            shape = RoundedCornerShape(50),
                            color = if (presetIndex == preset.ordinal) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                preset.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 11.sp,
                                color = if (presetIndex == preset.ordinal) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Horizontal band sliders: simpler and more thumb-friendly than a
                // rotated track, and avoids fighting the sheet's scroll gestures.
                repeat(bandCount) { index ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Band ${index + 1}", fontSize = 11.sp)
                            Text("${bands.getOrElse(index) { 0 }}", fontSize = 11.sp)
                        }
                        Slider(
                            value = bands.getOrElse(index) { 0 }.toFloat(),
                            onValueChange = { onBand(index, it.toInt()) },
                            valueRange = levelRange.first.toFloat()..levelRange.second.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            EffectSlider("Bass boost", bassBoost.toFloat(), 0f..1000f) { onBass(it.toInt()) }
            EffectSlider("Virtualizer", virtualizer.toFloat(), 0f..1000f) { onVirtualizer(it.toInt()) }
            EffectSlider("Loudness (dB)", loudness.toFloat(), 0f..30f) { onLoudness(it.toInt()) }
        }
    }
}

@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp)
            Text(value.toInt().toString(), fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}
