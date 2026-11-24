package com.malekplay.ui.player.video

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.network.OnlineSubtitle
import com.malekplay.network.SubtitleQueryCleaner
import com.malekplay.network.SubtitleResultPolicy
import com.malekplay.network.TvEpisodeGuide

private enum class ResultFilter { ALL, AR, EN, SRT }
private enum class ResultSort { BEST_MATCH, MOST_DOWNLOADED }

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
    var resultFilter by remember { mutableStateOf(ResultFilter.ALL) }
    var resultSort by remember { mutableStateOf(ResultSort.BEST_MATCH) }
    val availableSeasons = SubtitleResultPolicy.availableSeasons(results)
    val selectedSeasonNumber = season.toIntOrNull()
    val availableEpisodes = selectedSeasonNumber?.let {
        SubtitleResultPolicy.availableEpisodes(results, it)
    }.orEmpty()

    val filteredResults = remember(results, resultFilter, resultSort) {
        val base = when (resultFilter) {
            ResultFilter.ALL -> results
            ResultFilter.AR -> results.filter { SubtitleQueryCleaner.normalizeLanguage(it.language) == "ar" }
            ResultFilter.EN -> results.filter { SubtitleQueryCleaner.normalizeLanguage(it.language) == "en" }
            ResultFilter.SRT -> results.filter { it.format.equals("srt", ignoreCase = true) }
        }
        when (resultSort) {
            ResultSort.BEST_MATCH -> base
            ResultSort.MOST_DOWNLOADED -> base.sortedByDescending { it.downloads }
        }
    }

    LaunchedEffect(loading) { if (!loading) downloadingKey = null }
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

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
                            "البحث عن ترجمة عبر الإنترنت",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(shape = CircleShape, color = colors.secondaryContainer) {
                            Text(
                                "بحث ذكي · معاينة فورية · ترجمة بالذكاء الاصطناعي",
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = colors.onSecondaryContainer,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    label = { Text("اسم الفيلم أو المسلسل") },
                    placeholder = { Text("اكتب اسم العمل") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotEmpty()) {
                                TextButton(onClick = { onQueryChange("") }) { Text("مسح") }
                            }
                            IconButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                                Icon(Icons.Default.Search, contentDescription = "بحث")
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
                        label = { Text("الموسم") }
                    )
                    OutlinedTextField(
                        episode,
                        { onEpisodeChange(SubtitleQueryCleaner.normalizeDigits(it.filter(Char::isDigit).take(3))) },
                        Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        label = { Text("الحلقة") }
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
                    Text(if (episodeGuide == null) "استعراض مواسم وحلقات المسلسل" else "تحديث دليل الحلقات")
                }

                if (episodeGuide != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            "دليل الحلقات: ${episodeGuide.showName}" +
                                (episodeGuide.showYear?.let { " ($it)" } ?: ""),
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
                                "مصدر البيانات: TVmaze",
                                modifier = Modifier.clickable { uriHandler.openUri("https://www.tvmaze.com") },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                            Text(
                                "الرخصة: CC BY-SA",
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
                                    label = { Text("الموسم ${guideSeason.number}") },
                                    colors = chipColors
                                )
                            }
                        }
                        if (selectedSeasonNumber != null) {
                            val guideSeason = episodeGuide.seasons.firstOrNull {
                                it.number == selectedSeasonNumber
                            }
                            if (guideSeason != null) {
                                Text("حلقات الموسم ${guideSeason.number}", style = MaterialTheme.typography.labelLarge)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    guideSeason.episodes.forEach { guideEpisode ->
                                        FilterChip(
                                            selected = episode == guideEpisode.toString(),
                                            onClick = { onEpisodeChange(guideEpisode.toString()) },
                                            label = { Text("الحلقة $guideEpisode") },
                                            colors = chipColors
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    "هذا الموسم غير موجود في دليل المسلسل. يمكنك إدخال رقمه وحلقته يدويًا.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                "اختر موسمًا لعرض جميع الحلقات المسجلة له في الدليل.",
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
                            Text("المواسم التي ظهرت في نتائج الترجمات", style = MaterialTheme.typography.labelLarge)
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
                                        label = { Text("الموسم $availableSeason") },
                                        colors = chipColors
                                    )
                                }
                            }
                            if (selectedSeasonNumber != null) {
                                if (availableEpisodes.isNotEmpty()) {
                                    Text("الحلقات التي حددتها المصادر للترجمات", style = MaterialTheme.typography.labelLarge)
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableEpisodes.forEach { availableEpisode ->
                                            FilterChip(
                                                selected = episode == availableEpisode.toString(),
                                                onClick = { onEpisodeChange(availableEpisode.toString()) },
                                                label = { Text("الحلقة $availableEpisode") },
                                                colors = chipColors
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "لا توجد أرقام حلقات مؤكدة في نتائج الترجمات لهذا الموسم. استخدم دليل الحلقات أو أدخل الرقم يدويًا.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    "يمكنك تحميل دليل المسلسل لعرض الحلقات حتى إن لم تتوفر لها ترجمات بعد.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    } else if (results.isNotEmpty()) {
                        Text(
                            "لم توفر النتائج أرقام مواسم أو حلقات مؤكدة. يمكنك تحميل دليل المسلسل أو إدخالها يدويًا دون تخمين.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("لغة الترجمة", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = language == "ar,en",
                            onClick = { onLanguageChange("ar,en") },
                            label = { Text("العربية + English") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = language == "ar",
                            onClick = { onLanguageChange("ar") },
                            label = { Text("العربية فقط") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = language == "en",
                            onClick = { onLanguageChange("en") },
                            label = { Text("English") },
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
                        Text("جارٍ البحث عن أفضل الترجمات…")
                    } else {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("بحث عن الترجمات")
                    }
                }

                TextButton(onClick = { credentialsExpanded = !credentialsExpanded }) {
                    Text(
                        if (credentialsExpanded) "إخفاء إعدادات المفاتيح الاختيارية"
                        else "إعدادات API الاختيارية ومفاتيح المزودين"
                    )
                }

                if (credentialsExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { uriHandler.openUri("https://subdl.com/developers") }) {
                            Text("الحصول على مفتاح SubDL")
                        }
                        TextButton(onClick = { uriHandler.openUri("https://subtitles.website") }) {
                            Text("المصدر العام الاحتياطي")
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasSubdlKey) {
                            ResultBadge("SubDL محفوظ ✓", colors.primaryContainer, colors.onPrimaryContainer)
                        }
                        if (hasWebsiteKey) {
                            ResultBadge("Website محفوظ ✓", colors.secondaryContainer, colors.onSecondaryContainer)
                        }
                    }
                    OutlinedTextField(
                        subdlKey,
                        { subdlKey = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("مفتاح SubDL") }
                    )
                    OutlinedTextField(
                        websiteKey,
                        { websiteKey = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("مفتاح subtitles.website (Bearer)") }
                    )
                    Button(
                        onClick = {
                            onSaveProviderKeys(subdlKey.trim(), websiteKey.trim())
                            subdlKey = ""
                            websiteKey = ""
                        },
                        enabled = subdlKey.isNotBlank() || websiteKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("حفظ مفاتيح المزودين") }
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
                                    "تنزيل الترجمة وتحويلها إلى العربية",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = onCancelTranslation) { Text("إلغاء") }
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
                                    "$translationCompletedCues / $translationTotalCues مقطع"
                                } else {
                                    "جارٍ تنزيل الملف أو تجهيز نموذج اللغة…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSecondaryContainer
                            )
                        }
                    }
                }

                if (error != null) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.errorContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                error,
                                color = colors.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }
                } else if (!loading && results.isEmpty()) {
                    Text(
                        "أدخل اسم العمل واضغط بحث لعرض الترجمات المتاحة من المزودين.",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (results.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "النتائج",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${filteredResults.size} / ${results.size} ترجمة",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = resultFilter == ResultFilter.ALL,
                            onClick = { resultFilter = ResultFilter.ALL },
                            label = { Text("الكل") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = resultFilter == ResultFilter.AR,
                            onClick = { resultFilter = ResultFilter.AR },
                            label = { Text("العربية") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = resultFilter == ResultFilter.EN,
                            onClick = { resultFilter = ResultFilter.EN },
                            label = { Text("English") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = resultFilter == ResultFilter.SRT,
                            onClick = { resultFilter = ResultFilter.SRT },
                            label = { Text("SRT") },
                            colors = chipColors
                        )
                        FilterChip(
                            selected = resultSort == ResultSort.MOST_DOWNLOADED,
                            onClick = {
                                resultSort = if (resultSort == ResultSort.BEST_MATCH) {
                                    ResultSort.MOST_DOWNLOADED
                                } else {
                                    ResultSort.BEST_MATCH
                                }
                            },
                            leadingIcon = if (resultSort == ResultSort.MOST_DOWNLOADED) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text("الأكثر تحميلاً") },
                            colors = chipColors
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filteredResults.forEachIndexed { index, subtitle ->
                        val key = subtitle.provider + ":" + subtitle.sourceId
                        val isDownloading = loading && downloadingKey == key
                        val isAiTranslating = loading && downloadingKey == "$key:ai"
                        val isArabic = SubtitleQueryCleaner.normalizeLanguage(subtitle.language) == "ar"
                        val isBestMatch = index == 0 && resultSort == ResultSort.BEST_MATCH
                        val isPreviewingThis = previewKey == key

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
                                                    "أفضل تطابق",
                                                    color = selectedChipInk,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    ResultBadge(
                                        if (isArabic) "AR" else "EN",
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

                                val cleanRelease = SubtitleQueryCleaner.clean(subtitle.release).title
                                    .ifBlank { subtitle.release }
                                Text(
                                    cleanRelease,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )

                                val episodeContext = buildList {
                                    subtitle.seasonNumber?.let { add("الموسم $it") }
                                    subtitle.episodeNumber?.let { add("الحلقة $it") }
                                }.joinToString("  •  ")
                                if (episodeContext.isNotBlank()) {
                                    Text(
                                        "نطاق البحث: $episodeContext",
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
                                        "${subtitle.downloads} تنزيلات",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                    OutlinedButton(
                                        onClick = { onPreviewSubtitle(subtitle) },
                                        enabled = !loading && !(previewLoading && isPreviewingThis),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        if (previewLoading && isPreviewingThis) {
                                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(6.dp))
                                        } else {
                                            Icon(Icons.Filled.Visibility, null, Modifier.size(15.dp))
                                            Spacer(Modifier.size(5.dp))
                                        }
                                        Text(
                                            if (isPreviewingThis && previewLines.isNotEmpty()) "إخفاء" else "معاينة"
                                        )
                                    }
                                    Button(
                                        onClick = { downloadingKey = key; onDownload(subtitle) },
                                        enabled = !loading,
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
                                        Text(if (isDownloading) "جارٍ التطبيق…" else "تطبيق")
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
                                                "معاينة النص",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            when {
                                                previewLoading -> Text(
                                                    "جارٍ جلب أول الأسطر…",
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

                                if (!isArabic) {
                                    TextButton(
                                        onClick = {
                                            downloadingKey = "$key:ai"
                                            onDownloadAndTranslate(subtitle)
                                        },
                                        enabled = !loading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (isAiTranslating) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(7.dp))
                                        } else {
                                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp))
                                            Spacer(Modifier.size(6.dp))
                                        }
                                        Text(
                                            if (isAiTranslating) "جارٍ التنزيل والترجمة…"
                                            else "تنزيل وترجمة للعربية (AI)"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (loading && downloadingKey != null) {
                    Text(
                        "جارٍ تنزيل ملف الترجمة وتطبيقه على الفيديو…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
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

private fun providerLabel(provider: String): String = when (provider) {
    "subtitleSearchEngine" -> "subtitles.website"
    "subdl" -> "SubDL"
    "opensubtitles" -> "OpenSubtitles (قديم)"
    "subf2m" -> "Subf2m"
    else -> provider
}
