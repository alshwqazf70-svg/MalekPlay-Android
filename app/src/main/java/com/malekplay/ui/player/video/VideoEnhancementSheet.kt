package com.malekplay.ui.player.video

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.malekplay.R
import com.malekplay.player.effects.VideoEnhancementProfile
import com.malekplay.ui.player.VideoEnhancementDiagnostics
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEnhancementSheet(
    upscalingEnabled: Boolean,
    noiseReductionEnabled: Boolean,
    vibranceEnabled: Boolean,
    smartAiEnabled: Boolean,
    smartAiModeLabel: String,
    smartAiFallbackReason: String?,
    requestedProfile: VideoEnhancementProfile,
    effectiveProfile: VideoEnhancementProfile,
    downgradeReason: String?,
    customMode: Boolean,
    bypassed: Boolean,
    diagnostics: VideoEnhancementDiagnostics,
    onDismiss: () -> Unit,
    onToggleMasterEnhancement: (Boolean) -> Unit,
    onToggleSmartAi: (Boolean) -> Unit,
    onToggleUpscaling: (Boolean) -> Unit,
    onToggleNoiseReduction: (Boolean) -> Unit,
    onToggleVibrance: (Boolean) -> Unit,
    onSelectProfile: (VideoEnhancementProfile) -> Unit,
    onApplyCustomTuning: (denoise: Float, detail: Float, contrast: Float, vibrance: Float) -> Unit,
    onComparisonBypassChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val anyEnabled = upscalingEnabled || noiseReductionEnabled || vibranceEnabled || smartAiEnabled
    var showFineTuning by remember(customMode) { mutableStateOf(customMode) }
    var detailDraft by remember(customMode, requestedProfile, anyEnabled) {
        mutableFloatStateOf(diagnostics.detailStrength.coerceIn(0f, 1.20f))
    }
    var denoiseDraft by remember(customMode, requestedProfile, anyEnabled) {
        mutableFloatStateOf(diagnostics.denoiseStrength.coerceIn(0f, 0.60f))
    }
    var contrastDraft by remember(customMode, requestedProfile, anyEnabled) {
        mutableFloatStateOf(diagnostics.contrastBoost.coerceIn(0f, 0.22f))
    }
    var vibranceDraft by remember(customMode, requestedProfile, anyEnabled) {
        mutableFloatStateOf(diagnostics.vibranceStrength.coerceIn(0f, 0.35f))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.menu_video_enhancement),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = smartAiModeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = anyEnabled,
                    onCheckedChange = onToggleMasterEnhancement
                )
            }

            if (anyEnabled) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (bypassed) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onComparisonBypassChange(true)
                                waitForUpOrCancellation()
                                onComparisonBypassChange(false)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Compare,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (bypassed) {
                                stringResource(R.string.player_video_enhancement_comparing_original)
                            } else {
                                stringResource(R.string.player_video_enhancement_hold_compare)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            val statusWarning = smartAiFallbackReason ?: downgradeReason?.let {
                stringResource(R.string.player_video_enhancement_capped_notice, it)
            }
            if (statusWarning != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = statusWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Smart AI Toggle Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (smartAiEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = if (smartAiEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.menu_google_ai_enhancement),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.player_video_enhancement_ai_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = smartAiEnabled,
                        onCheckedChange = onToggleSmartAi
                    )
                }
            }

            // Quality Presets
            Text(
                text = stringResource(R.string.menu_video_quality_profile),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    VideoEnhancementProfile.ECONOMY to R.string.video_quality_economy,
                    VideoEnhancementProfile.BALANCED to R.string.video_quality_balanced,
                    VideoEnhancementProfile.ULTRA to R.string.video_quality_ultra
                ).forEach { (profile, labelRes) ->
                    val selected = !customMode && requestedProfile == profile
                    FilterChip(
                        selected = selected,
                        onClick = {
                            showFineTuning = false
                            onSelectProfile(profile)
                        },
                        label = { Text(stringResource(labelRes)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (effectiveProfile != requestedProfile && !customMode && anyEnabled) {
                Text(
                    text = stringResource(
                        R.string.player_video_enhancement_effective_profile,
                        when (effectiveProfile) {
                            VideoEnhancementProfile.ECONOMY -> stringResource(R.string.video_quality_economy)
                            VideoEnhancementProfile.BALANCED -> stringResource(R.string.video_quality_balanced)
                            VideoEnhancementProfile.ULTRA -> stringResource(R.string.video_quality_ultra)
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Individual Stages
            EnhancementToggleRow(
                title = stringResource(R.string.menu_video_upscaling),
                subtitle = stringResource(R.string.player_video_enhancement_upscale_desc),
                checked = upscalingEnabled,
                onCheckedChange = onToggleUpscaling
            )
            EnhancementToggleRow(
                title = stringResource(R.string.menu_video_noise_reduction),
                subtitle = stringResource(R.string.player_video_enhancement_denoise_desc),
                checked = noiseReductionEnabled,
                onCheckedChange = onToggleNoiseReduction
            )
            EnhancementToggleRow(
                title = stringResource(R.string.player_video_enhancement_vibrance),
                subtitle = stringResource(R.string.player_video_enhancement_vibrance_desc),
                checked = vibranceEnabled,
                onCheckedChange = onToggleVibrance
            )

            // Fine Tuning Expandable Section
            TextButton(
                onClick = { showFineTuning = !showFineTuning },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (showFineTuning) {
                        stringResource(R.string.player_video_enhancement_hide_tuning)
                    } else {
                        stringResource(R.string.player_video_enhancement_show_tuning)
                    }
                )
            }

            if (showFineTuning) {
                EnhancementSliderRow(
                    label = stringResource(R.string.player_video_enhancement_detail_slider),
                    value = detailDraft,
                    valueRange = 0f..1.20f,
                    percentScale = 100f / 1.20f,
                    onValueChange = {
                        detailDraft = it
                        onApplyCustomTuning(denoiseDraft, it, contrastDraft, vibranceDraft)
                    }
                )
                EnhancementSliderRow(
                    label = stringResource(R.string.player_video_enhancement_denoise_slider),
                    value = denoiseDraft,
                    valueRange = 0f..0.60f,
                    percentScale = 100f / 0.60f,
                    onValueChange = {
                        denoiseDraft = it
                        onApplyCustomTuning(it, detailDraft, contrastDraft, vibranceDraft)
                    }
                )
                EnhancementSliderRow(
                    label = stringResource(R.string.player_video_enhancement_contrast_slider),
                    value = contrastDraft,
                    valueRange = 0f..0.22f,
                    percentScale = 100f / 0.22f,
                    onValueChange = {
                        contrastDraft = it
                        onApplyCustomTuning(denoiseDraft, detailDraft, it, vibranceDraft)
                    }
                )
                EnhancementSliderRow(
                    label = stringResource(R.string.player_video_enhancement_vibrance_slider),
                    value = vibranceDraft,
                    valueRange = 0f..0.35f,
                    percentScale = 100f / 0.35f,
                    onValueChange = {
                        vibranceDraft = it
                        onApplyCustomTuning(denoiseDraft, detailDraft, contrastDraft, it)
                    }
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.player_video_enhancement_manual_only_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.menu_video_enhancement_diagnostics))
            }
        }
    }
}

@Composable
private fun EnhancementToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EnhancementSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    percentScale: Float,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(value * percentScale).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
