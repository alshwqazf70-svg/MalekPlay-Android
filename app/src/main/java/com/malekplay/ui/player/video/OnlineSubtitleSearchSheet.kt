package com.malekplay.ui.player.video

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.network.OnlineSubtitle
import com.malekplay.network.SubtitleQueryCleaner
import com.malekplay.network.SubtitleResultPolicy
import com.malekplay.network.TvEpisodeGuide

internal enum class SubtitleResultFilter { ALL, AR, EN, SRT }
internal enum class SubtitleResultSort { BEST_MATCH, MOST_DOWNLOADED }

internal data class SubtitleCardActionState(
    val key: String,
    val normalizedLanguage: String,
    val isArabic: Boolean,
    val isDownloadingAndApplying: Boolean,
    val isAiTranslating: Boolean,
    val isPreviewingThis: Boolean,
    val isPreviewLoading: Boolean,
    val hasPreviewLines: Boolean,
    val buttonsEnabled: Boolean,
    val previewEnabled: Boolean,
    @StringRes val primaryLabelRes: Int,
    @StringRes val previewLabelRes: Int,
    @StringRes val aiLabelRes: Int?
)

internal fun filterAndSortOnlineSubtitles(
    results: List<OnlineSubtitle>,
    filter: SubtitleResultFilter,
    sort: SubtitleResultSort
): List<OnlineSubtitle> {
    val base = when (filter) {
        SubtitleResultFilter.ALL -> results
        SubtitleResultFilter.AR -> results.filter {
            SubtitleQueryCleaner.normalizeLanguage(it.language) == "ar"
        }
        SubtitleResultFilter.EN -> results.filter {
            SubtitleQueryCleaner.normalizeLanguage(it.language) == "en"
        }
        SubtitleResultFilter.SRT -> results.filter { it.format.equals("srt", ignoreCase = true) }
    }
    return when (sort) {
        SubtitleResultSort.BEST_MATCH -> base
        SubtitleResultSort.MOST_DOWNLOADED -> base.sortedByDescending { it.downloads }
    }
}

internal fun resolveSubtitleCardActionState(
    subtitle: OnlineSubtitle,
    loading: Boolean,
    downloadingKey: String?,
    previewKey: String?,
    previewLoading: Boolean,
    previewLines: List<String>
): SubtitleCardActionState {
    val key = subtitle.provider + ":" + subtitle.sourceId
    val normalizedLang = SubtitleQueryCleaner.normalizeLanguage(subtitle.language)
    val isArabic = normalizedLang == "ar"
    val isDownloadingAndApplying = loading && downloadingKey == key
    val isAiTranslating = loading && downloadingKey == "$key:ai"
    val isPreviewingThis = previewKey == key
    val isPreviewLoading = previewLoading && isPreviewingThis
    val hasPreviewLines = isPreviewingThis && previewLines.isNotEmpty()
    val primaryLabelRes = if (isDownloadingAndApplying) {
        R.string.subtitle_downloading_and_applying
    } else {
        R.string.subtitle_download_and_apply
    }
    val previewLabelRes = when {
        isPreviewLoading -> R.string.subtitle_preview_loading_button
        hasPreviewLines -> R.string.subtitle_hide_preview_button
        else -> R.string.subtitle_preview_button
    }
    val aiLabelRes = if (isArabic) {
        null
    } else if (isAiTranslating) {
        R.string.subtitle_downloading_and_translating
    } else {
        R.string.subtitle_download_and_translate_ai
    }
    return SubtitleCardActionState(
        key = key,
        normalizedLanguage = normalizedLang,
        isArabic = isArabic,
        isDownloadingAndApplying = isDownloadingAndApplying,
        isAiTranslating = isAiTranslating,
        isPreviewingThis = isPreviewingThis,
        isPreviewLoading = isPreviewLoading,
        hasPreviewLines = hasPreviewLines,
        buttonsEnabled = !loading,
        previewEnabled = !loading && !isPreviewLoading,
        primaryLabelRes = primaryLabelRes,
        previewLabelRes = previewLabelRes,
        aiLabelRes = aiLabelRes
    )
}

@StringRes
internal fun resolveSubtitleFooterStatusRes(
    loading: Boolean,
    downloadingKey: String?,
    translationLoading: Boolean
): Int? {
    if (!loading || downloadingKey == null) return null
    return if (translationLoading || downloadingKey.endsWith(":ai")) {
        R.string.subtitle_downloading_and_translating
    } else {
        R.string.subtitle_downloading_and_applying_status
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleSearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    season: String,
    onSeasonChange: (String) -> Unit,
    episode: String,
    onEpisodeChange: (String) -> Unit,
    results: List<OnlineSubtitle>,
    loading: Boolean,
    error: String?,
    episodeGuide: TvEpisodeGuide?,
    episodeGuideLoading: Boolean,
    episodeGuideError: String?,
    onLoadEpisodeGuide: () -> Unit,
    translationLoading: Boolean,
    translationCompletedCues: Int,
    translationTotalCues: Int,
    translationError: String? = null,
    onSearch: () -> Unit,
    onDownload: (OnlineSubtitle) -> Unit,
    onDownloadAndTranslate: (OnlineSubtitle) -> Unit,
    onCancelTranslation: () -> Unit,
    onSaveProviderKeys: (String, String) -> Unit,
    hasSubdlKey: Boolean = false,
    hasWebsiteKey: Boolean = false,
    previewKey: String? = null,
    previewLines: List<String> = emptyList(),
    previewLoading: Boolean = false,
    previewError: String? = null,
    onPreviewSubtitle: (OnlineSubtitle) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    var credentialsExpanded by remember { mutableStateOf(false) }
    var subdlKey by remember { mutableStateOf("") }
    var websiteKey by remember { mutableStateOf("") }
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    var autoSearched by remember { mutableStateOf(false) }
    var resultFilter by remember { mutableStateOf(SubtitleResultFilter.ALL) }
    var resultSort by remember { mutableStateOf(SubtitleResultSort.BEST_MATCH) }
    val availableSeasons = remember(results) {
        SubtitleResultPolicy.availableSeasons(results)
    }
    val selectedSeasonNumber = season.toIntOrNull()
    val availableEpisodes = remember(results, selectedSeasonNumber) {
        selectedSeasonNumber?.let {
            SubtitleResultPolicy.availableEpisodes(results, it)
        }.orEmpty()
    }

    val filteredResults = remember(results, resultFilter, resultSort) {
        filterAndSortOnlineSubtitles(results, resultFilter, resultSort)
    }

    LaunchedEffect(loading, translationLoading) {
        if (!loading && !translationLoading) downloadingKey = null
    }
    LaunchedEffect(Unit) {
        if (!autoSearched && query.isNotBlank() && results.isEmpty() && !loading && error == null) {
            autoSearched = true
            onSearch()
        }
    }

    val selectedChipInk = contrastingOnColor(colors.primary)
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = colors.primary,
        selectedLabelColor = selectedChipInk,
        selectedLeadingIconColor = selectedChipInk,
        selectedTrailingIconColor = selectedChipInk
    )
    val activeError = error ?: translationError

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.player_search_subtitle_online),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(shape = CircleShape, color = colors.secondaryContainer) {
                        Text(
                            stringResource(R.string.subtitle_sheet_highlights),
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = colors.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.subtitle_close_search)
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text(stringResource(R.string.subtitle_search_title)) },
                placeholder = { Text(stringResource(R.string.subtitle_search_placeholder)) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            TextButton(onClick = { onQueryChange("") }) {
                                Text(stringResource(R.string.subtitle_clear_query))
                            }
                        }
                        IconButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.subtitle_search)
                            )
                        }
                    }
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    season,
                    { onSeasonChange(SubtitleQueryCleaner.normalizeDigits(it.filter(Char::isDigit).take(2))) },
                    Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    label = { Text(stringResource(R.string.subtitle_season)) }
                )
                OutlinedTextField(
                    episode,
                    { onEpisodeChange(SubtitleQueryCleaner.normalizeDigits(it.filter(Char::isDigit).take(3))) },
                    Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    label = { Text(stringResource(R.string.subtitle_episode)) }
                )
            }

            Button(
                onClick = onLoadEpisodeGuide,
                enabled = query.isNotBlank() && !episodeGuideLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (episodeGuideLoading) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    stringResource(
                        if (episodeGuide == null) R.string.subtitle_episode_guide_load
                        else R.string.subtitle_episode_guide_refresh
                    )
                )
            }

            if (episodeGuide != null) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val showLabel = episodeGuide.showName +
                        (episodeGuide.showYear?.let { " ($it)" } ?: "")
                    Text(
                        stringResource(R.string.subtitle_episode_guide_title, showLabel),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (episodeGuideError != null) {
                        Text(
                            episodeGuideError,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.subtitle_episode_guide_source),
                            modifier = Modifier.clickable { uriHandler.openUri("https://www.tvmaze.com") },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary
                        )
                        Text(
                            stringResource(R.string.subtitle_episode_guide_license),
                            modifier = Modifier.clickable {
                                uriHandler.openUri("https://www.tvmaze.com/api")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        episodeGuide.seasons.forEach { guideSeason ->
                            FilterChip(
                                selected = season == guideSeason.number.toString(),
                                onClick = {
                                    onSeasonChange(guideSeason.number.toString())
                                    onEpisodeChange("")
                                },
                                label = { Text(stringResource(R.string.subtitle_season_chip, guideSeason.number)) },
                                colors = chipColors
                            )
                        }
                    }
                    if (selectedSeasonNumber != null) {
                        val guideSeason = episodeGuide.seasons.firstOrNull {
                            it.number == selectedSeasonNumber
                        }
                        if (guideSeason != null) {
                            Text(
                                stringResource(R.string.subtitle_season_episodes_title, guideSeason.number),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                guideSeason.episodes.forEach { guideEpisode ->
                                    FilterChip(
                                        selected = episode == guideEpisode.toString(),
                                        onClick = { onEpisodeChange(guideEpisode.toString()) },
                                        label = { Text(stringResource(R.string.subtitle_episode_chip, guideEpisode)) },
                                        colors = chipColors
                                    )
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.subtitle_guide_season_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.subtitle_guide_select_season_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                if (episodeGuideError != null) {
                    Text(
                        episodeGuideError,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (availableSeasons.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.subtitle_detected_seasons_title),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableSeasons.forEach { availableSeason ->
                                FilterChip(
                                    selected = season == availableSeason.toString(),
                                    onClick = {
                                        onSeasonChange(availableSeason.toString())
                                        onEpisodeChange("")
                                    },
                                    label = { Text(stringResource(R.string.subtitle_season_chip, availableSeason)) },
                                    colors = chipColors
                                )
                            }
                        }
                        if (selectedSeasonNumber != null) {
                            if (availableEpisodes.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.subtitle_detected_episodes_title),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    availableEpisodes.forEach { availableEpisode ->
                                        FilterChip(
                                            selected = episode == availableEpisode.toString(),
                                            onClick = { onEpisodeChange(availableEpisode.toString()) },
                                            label = { Text(stringResource(R.string.subtitle_episode_chip, availableEpisode)) },
                                            colors = chipColors
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    stringResource(R.string.subtitle_detected_episodes_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.subtitle_detected_select_season_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else if (results.isNotEmpty()) {
                    Text(
                        stringResource(R.string.subtitle_no_season_episode_tags_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.subtitle_language_label), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = language == "ar,en",
                        onClick = { onLanguageChange("ar,en") },
                        label = { Text(stringResource(R.string.subtitle_language_ar_en)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = language == "ar",
                        onClick = { onLanguageChange("ar") },
                        label = { Text(stringResource(R.string.subtitle_language_ar_only)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = language == "en",
                        onClick = { onLanguageChange("en") },
                        label = { Text(stringResource(R.string.subtitle_language_en_only)) },
                        colors = chipColors
                    )
                }
            }

            Button(
                onClick = onSearch,
                enabled = query.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (loading && downloadingKey == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = selectedChipInk)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.subtitle_searching_progress))
                } else {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.subtitle_search_button))
                }
            }

            TextButton(onClick = { credentialsExpanded = !credentialsExpanded }) {
                Text(
                    stringResource(
                        if (credentialsExpanded) R.string.subtitle_keys_hide
                        else R.string.subtitle_keys_show
                    )
                )
            }

            if (credentialsExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { uriHandler.openUri("https://subdl.com/developers") }) {
                        Text(stringResource(R.string.subtitle_get_subdl_key))
                    }
                    TextButton(onClick = { uriHandler.openUri("https://subtitles.website") }) {
                        Text(stringResource(R.string.subtitle_public_fallback_source))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasSubdlKey) {
                        ResultBadge(
                            stringResource(R.string.subtitle_subdl_saved_badge),
                            colors.primaryContainer,
                            colors.onPrimaryContainer
                        )
                    }
                    if (hasWebsiteKey) {
                        ResultBadge(
                            stringResource(R.string.subtitle_website_saved_badge),
                            colors.secondaryContainer,
                            colors.onSecondaryContainer
                        )
                    }
                }
                OutlinedTextField(
                    subdlKey,
                    { subdlKey = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.subtitle_subdl_key_label)) }
                )
                OutlinedTextField(
                    websiteKey,
                    { websiteKey = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.subtitle_website_key_label)) }
                )
                Button(
                    onClick = {
                        onSaveProviderKeys(subdlKey.trim(), websiteKey.trim())
                        subdlKey = ""
                        websiteKey = ""
                    },
                    enabled = subdlKey.isNotBlank() || websiteKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.subtitle_save_provider_keys))
                }
            }

            if (translationLoading) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.subtitle_ai_progress_title),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = {
                                downloadingKey = null
                                onCancelTranslation()
                            }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                        val fraction = if (translationTotalCues > 0) {
                            translationCompletedCues.toFloat() / translationTotalCues
                        } else 0f
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (translationTotalCues > 0) {
                                stringResource(
                                    R.string.subtitle_translation_cues_progress,
                                    translationCompletedCues,
                                    translationTotalCues
                                )
                            } else {
                                stringResource(R.string.subtitle_ai_preparing_status)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSecondaryContainer
                        )
                    }
                }
            }

            if (activeError != null) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.errorContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            activeError,
                            color = colors.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                            Text(stringResource(R.string.subtitle_retry))
                        }
                    }
                }
            } else if (!loading && results.isEmpty()) {
                Text(
                    stringResource(R.string.subtitle_search_prompt_hint),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (results.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.subtitle_results_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.subtitle_results_count, filteredResults.size, results.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = resultFilter == SubtitleResultFilter.ALL,
                        onClick = { resultFilter = SubtitleResultFilter.ALL },
                        label = { Text(stringResource(R.string.subtitle_filter_all)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = resultFilter == SubtitleResultFilter.AR,
                        onClick = { resultFilter = SubtitleResultFilter.AR },
                        label = { Text(stringResource(R.string.subtitle_filter_arabic)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = resultFilter == SubtitleResultFilter.EN,
                        onClick = { resultFilter = SubtitleResultFilter.EN },
                        label = { Text(stringResource(R.string.subtitle_filter_english)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = resultFilter == SubtitleResultFilter.SRT,
                        onClick = { resultFilter = SubtitleResultFilter.SRT },
                        label = { Text(stringResource(R.string.subtitle_filter_srt)) },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = resultSort == SubtitleResultSort.MOST_DOWNLOADED,
                        onClick = {
                            resultSort = if (resultSort == SubtitleResultSort.BEST_MATCH) {
                                SubtitleResultSort.MOST_DOWNLOADED
                            } else {
                                SubtitleResultSort.BEST_MATCH
                            }
                        },
                        leadingIcon = if (resultSort == SubtitleResultSort.MOST_DOWNLOADED) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text(stringResource(R.string.subtitle_sort_most_downloaded)) },
                        colors = chipColors
                    )
                }

                if (filteredResults.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.subtitle_filter_no_matches),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        TextButton(onClick = { resultFilter = SubtitleResultFilter.ALL }) {
                            Text(stringResource(R.string.subtitle_filter_show_all))
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredResults.forEachIndexed { index, subtitle ->
                    val cardState = resolveSubtitleCardActionState(
                        subtitle = subtitle,
                        loading = loading,
                        downloadingKey = downloadingKey,
                        previewKey = previewKey,
                        previewLoading = previewLoading,
                        previewLines = previewLines
                    )
                    val key = cardState.key
                    val isDownloading = cardState.isDownloadingAndApplying
                    val isAiTranslating = cardState.isAiTranslating
                    val isBestMatch = index == 0 && resultSort == SubtitleResultSort.BEST_MATCH
                    val isPreviewingThis = cardState.isPreviewingThis
                    val isPreviewLoading = cardState.isPreviewLoading

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isBestMatch) colors.primary.copy(alpha = 0.55f)
                            else colors.outlineVariant.copy(alpha = 0.45f)
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isBestMatch) colors.surfaceContainerHigh else colors.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isBestMatch) {
                                    Surface(
                                        shape = CircleShape,
                                        color = colors.primary
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = selectedChipInk,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                stringResource(R.string.subtitle_best_match),
                                                color = selectedChipInk,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                ResultBadge(
                                    when (cardState.normalizedLanguage) {
                                        "ar" -> stringResource(R.string.subtitle_lang_badge_ar)
                                        "en" -> stringResource(R.string.subtitle_lang_badge_en)
                                        "" -> stringResource(R.string.subtitle_lang_badge_en)
                                        else -> cardState.normalizedLanguage.uppercase()
                                    },
                                    colors.primaryContainer,
                                    colors.onPrimaryContainer
                                )
                                ResultBadge(
                                    subtitle.format.uppercase(),
                                    colors.tertiaryContainer,
                                    colors.onTertiaryContainer
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    providerLabel(subtitle.provider),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            val cleanRelease = remember(subtitle.release) {
                                SubtitleQueryCleaner.clean(subtitle.release).title
                                    .ifBlank { subtitle.release }
                            }
                            Text(
                                cleanRelease,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            val episodeContext = buildList {
                                subtitle.seasonNumber?.let {
                                    add(stringResource(R.string.subtitle_season_chip, it))
                                }
                                subtitle.episodeNumber?.let {
                                    add(stringResource(R.string.subtitle_episode_chip, it))
                                }
                            }.joinToString("  •  ")
                            if (episodeContext.isNotBlank()) {
                                Text(
                                    stringResource(R.string.subtitle_search_scope, episodeContext),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.subtitle_downloads_count,
                                        subtitle.downloads,
                                        subtitle.downloads
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { onPreviewSubtitle(subtitle) },
                                    enabled = cardState.previewEnabled,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isPreviewLoading) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.size(6.dp))
                                    } else {
                                        Icon(Icons.Filled.Visibility, null, Modifier.size(15.dp))
                                        Spacer(Modifier.size(5.dp))
                                    }
                                    Text(stringResource(cardState.previewLabelRes))
                                }
                                Button(
                                    onClick = { downloadingKey = key; onDownload(subtitle) },
                                    enabled = cardState.buttonsEnabled,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isDownloading) {
                                        CircularProgressIndicator(
                                            Modifier.size(15.dp),
                                            strokeWidth = 2.dp,
                                            color = selectedChipInk
                                        )
                                        Spacer(Modifier.size(6.dp))
                                    } else {
                                        Icon(Icons.Filled.Download, null, Modifier.size(15.dp))
                                        Spacer(Modifier.size(5.dp))
                                    }
                                    Text(stringResource(cardState.primaryLabelRes))
                                }
                            }

                            AnimatedVisibility(visible = isPreviewingThis && (previewLoading || previewError != null || previewLines.isNotEmpty())) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surface.copy(alpha = 0.88f),
                                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.subtitle_preview_header),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        when {
                                            previewLoading -> Text(
                                                stringResource(R.string.subtitle_preview_fetching_lines),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant
                                            )
                                            previewError != null -> Text(
                                                previewError,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.error
                                            )
                                            else -> previewLines.forEach { line ->
                                                Text(
                                                    line,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            cardState.aiLabelRes?.let { aiLabelRes ->
                                TextButton(
                                    onClick = {
                                        downloadingKey = "$key:ai"
                                        onDownloadAndTranslate(subtitle)
                                    },
                                    enabled = cardState.buttonsEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isAiTranslating) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.size(7.dp))
                                    } else {
                                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp))
                                        Spacer(Modifier.size(6.dp))
                                    }
                                    Text(stringResource(aiLabelRes))
                                }
                            }
                        }
                    }
                }
            }

            resolveSubtitleFooterStatusRes(
                loading = loading,
                downloadingKey = downloadingKey,
                translationLoading = translationLoading
            )?.let { footerRes ->
                Text(
                    stringResource(footerRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ResultBadge(
    label: String,
    background: Color,
    foreground: Color
) {
    Surface(shape = CircleShape, color = background) {
        Text(
            label,
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun providerLabel(provider: String): String = when (provider) {
    "subtitleSearchEngine" -> stringResource(R.string.subtitle_provider_website)
    "subdl" -> stringResource(R.string.subtitle_provider_subdl)
    "opensubtitles" -> stringResource(R.string.subtitle_provider_opensubtitles_legacy)
    "subf2m" -> stringResource(R.string.subtitle_provider_subf2m)
    else -> provider
}
