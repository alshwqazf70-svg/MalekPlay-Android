package com.malekplay.ui.player.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.malekplay.network.OnlineSubtitle
import com.malekplay.network.SubtitleQueryCleaner
import com.malekplay.network.SubtitleResultPolicy
import com.malekplay.network.TvEpisodeGuide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleSearchSheet(
    query: String, onQueryChange: (String) -> Unit,
    language: String, onLanguageChange: (String) -> Unit,
    season: String, onSeasonChange: (String) -> Unit,
    episode: String, onEpisodeChange: (String) -> Unit,
    results: List<OnlineSubtitle>, loading: Boolean, error: String?,
    episodeGuide: TvEpisodeGuide?, episodeGuideLoading: Boolean, episodeGuideError: String?,
    onLoadEpisodeGuide: () -> Unit,
    translationLoading: Boolean, translationCompletedCues: Int, translationTotalCues: Int,
    onSearch: () -> Unit, onDownload: (OnlineSubtitle) -> Unit,
    onDownloadAndTranslate: (OnlineSubtitle) -> Unit, onCancelTranslation: () -> Unit,
    onSaveProviderKeys: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    var credentialsExpanded by remember { mutableStateOf(false) }
    var subdlKey by remember { mutableStateOf("") }
    var websiteKey by remember { mutableStateOf("") }
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    val availableSeasons = SubtitleResultPolicy.availableSeasons(results)
    val selectedSeasonNumber = season.toIntOrNull()
    val availableEpisodes = selectedSeasonNumber?.let {
        SubtitleResultPolicy.availableEpisodes(results, it)
    }.orEmpty()

    LaunchedEffect(loading) { if (!loading) downloadingKey = null }
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("البحث عن ترجمة عبر الإنترنت", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("بحث ذكي · عدة مزودين", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    label = { Text("اسم الفيلم أو المسلسل") }, placeholder = { Text("اكتب اسم العمل") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("مسح") }
                            IconButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                                Icon(Icons.Default.Search, contentDescription = "بحث")
                            }
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(season, { onSeasonChange(SubtitleQueryCleaner.normalizeDigits(it.filter(Char::isDigit).take(2))) },
                        Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(50),
                        label = { Text("الموسم") })
                    OutlinedTextField(episode, { onEpisodeChange(SubtitleQueryCleaner.normalizeDigits(it.filter(Char::isDigit).take(3))) },
                        Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(50),
                        label = { Text("الحلقة") })
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
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "مصدر البيانات: TVmaze",
                                modifier = Modifier.clickable { uriHandler.openUri("https://www.tvmaze.com") },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "الرخصة: CC BY-SA",
                                modifier = Modifier.clickable {
                                    uriHandler.openUri("https://www.tvmaze.com/api")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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
                                    label = { Text("الموسم ${guideSeason.number}") }
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
                                            label = { Text("الحلقة $guideEpisode") }
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    "هذا الموسم غير موجود في دليل المسلسل. يمكنك إدخال رقمه وحلقته يدويًا.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                "اختر موسمًا لعرض جميع الحلقات المسجلة له في الدليل.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    if (episodeGuideError != null) {
                        Text(
                            episodeGuideError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        label = { Text("الموسم $availableSeason") }
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
                                                label = { Text("الحلقة $availableEpisode") }
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "لا توجد أرقام حلقات مؤكدة في نتائج الترجمات لهذا الموسم. استخدم دليل الحلقات أو أدخل الرقم يدويًا.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    "يمكنك تحميل دليل المسلسل لعرض الحلقات حتى إن لم تتوفر لها ترجمات بعد.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (results.isNotEmpty()) {
                        Text(
                            "لم توفر النتائج أرقام مواسم أو حلقات مؤكدة. يمكنك تحميل دليل المسلسل أو إدخالها يدويًا دون تخمين.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("لغة الترجمة", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val chipColors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black,
                            selectedTrailingIconColor = Color.Black
                        )
                        FilterChip(language == "ar,en", { onLanguageChange("ar,en") },
                            label = { Text("العربية + English") }, colors = chipColors)
                        FilterChip(language == "ar", { onLanguageChange("ar") },
                            label = { Text("العربية فقط") }, colors = chipColors)
                        FilterChip(language == "en", { onLanguageChange("en") },
                            label = { Text("English") }, colors = chipColors)
                    }
                }
                Button(onClick = onSearch, enabled = query.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    if (loading && downloadingKey == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("بحث عن الترجمات")
                    }
                }
                TextButton(onClick = { credentialsExpanded = !credentialsExpanded }) {
                    Text(if (credentialsExpanded) "إخفاء إعدادات المفاتيح الاختيارية"
                        else "إعدادات API الاختيارية ومفاتيح المزودين")
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
                    OutlinedTextField(subdlKey, { subdlKey = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("مفتاح SubDL") })
                    OutlinedTextField(websiteKey, { websiteKey = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("مفتاح subtitles.website (Bearer)") })
                    Button(onClick = { onSaveProviderKeys(subdlKey.trim(), websiteKey.trim()) },
                        enabled = subdlKey.isNotBlank() || websiteKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ مفاتيح المزودين") }
                }
                if (translationLoading) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
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
                                TextButton(onClick = onCancelTranslation) {
                                    Text("إلغاء")
                                }
                            }
                            val fraction = if (translationTotalCues > 0) {
                                translationCompletedCues.toFloat() / translationTotalCues
                            } else 0f
                            LinearProgressIndicator(
                                progress = fraction.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                if (translationTotalCues > 0) {
                                    "$translationCompletedCues / $translationTotalCues مقطع"
                                } else {
                                    "جارٍ تنزيل الملف أو تجهيز نموذج اللغة…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                if (error != null) {
                    Card(shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }
                } else if (!loading && results.isEmpty()) {
                    Text("أدخل اسم العمل واضغط بحث لعرض الترجمات المتاحة من المزودين.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (results.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("النتائج", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${results.size} ترجمة", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    results.forEach { subtitle ->
                        val key = subtitle.provider + ":" + subtitle.sourceId
                        val isDownloading = loading && downloadingKey == key
                        val isAiTranslating = loading && downloadingKey == "$key:ai"
                        val isArabic = SubtitleQueryCleaner.normalizeLanguage(subtitle.language) == "ar"
                        Card(shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    ResultBadge(
                                        if (SubtitleQueryCleaner.normalizeLanguage(subtitle.language) == "ar") "AR" else "EN",
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer)
                                    ResultBadge(subtitle.format.uppercase(),
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        MaterialTheme.colorScheme.onTertiaryContainer)
                                    Spacer(Modifier.weight(1f))
                                    Text(providerLabel(subtitle.provider),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val cleanRelease = SubtitleQueryCleaner.clean(subtitle.release).title
                                    .ifBlank { subtitle.release }
                                Text(cleanRelease, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold)
                                val episodeContext = buildList {
                                    subtitle.seasonNumber?.let { add("الموسم $it") }
                                    subtitle.episodeNumber?.let { add("الحلقة $it") }
                                }.joinToString("  •  ")
                                if (episodeContext.isNotBlank()) {
                                    Text(
                                        "نطاق البحث: $episodeContext",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${subtitle.downloads} تنزيلات",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    Button(onClick = { downloadingKey = key; onDownload(subtitle) },
                                        enabled = !loading,
                                        shape = RoundedCornerShape(12.dp)) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(7.dp))
                                        }
                                        Text(if (isDownloading) "جارٍ التطبيق…" else "تحميل وتطبيق")
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
                    Text("جارٍ تنزيل ملف الترجمة وتطبيقه على الفيديو…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ResultBadge(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color
) {
    Surface(shape = CircleShape, color = background) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = foreground, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold)
    }
}

private fun providerLabel(provider: String): String = when (provider) {
    "subtitleSearchEngine" -> "subtitles.website"
    "subdl" -> "SubDL"
    "opensubtitles" -> "OpenSubtitles (قديم)"
    "subf2m" -> "Subf2m"
    else -> provider
}
