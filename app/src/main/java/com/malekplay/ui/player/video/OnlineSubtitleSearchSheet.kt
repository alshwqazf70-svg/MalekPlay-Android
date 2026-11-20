package com.malekplay.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.malekplay.network.OnlineSubtitle
import com.malekplay.network.SubtitleQueryCleaner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleSearchSheet(
    query: String, onQueryChange: (String) -> Unit,
    language: String, onLanguageChange: (String) -> Unit,
    season: String, onSeasonChange: (String) -> Unit,
    episode: String, onEpisodeChange: (String) -> Unit,
    results: List<OnlineSubtitle>, loading: Boolean, error: String?,
    onSearch: () -> Unit, onDownload: (OnlineSubtitle) -> Unit,
    onSaveCredentials: (String, String, String) -> Unit,
    onSaveProviderKeys: (String, String) -> Unit, onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var credentialsExpanded by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var subdlKey by remember { mutableStateOf("") }
    var websiteKey by remember { mutableStateOf("") }
    var downloadingKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loading) { if (!loading) downloadingKey = null }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
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
                    OutlinedTextField(season, { onSeasonChange(it.filter(Char::isDigit).take(2)) },
                        Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(50),
                        label = { Text("الموسم") })
                    OutlinedTextField(episode, { onEpisodeChange(it.filter(Char::isDigit).take(3)) },
                        Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(50),
                        label = { Text("الحلقة") })
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("لغة الترجمة", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(language == "ar,en", { onLanguageChange("ar,en") },
                            label = { Text("العربية + English") })
                        FilterChip(language == "ar", { onLanguageChange("ar") },
                            label = { Text("العربية فقط") })
                        FilterChip(language == "en", { onLanguageChange("en") },
                            label = { Text("English") })
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
                    Text("البحث المجاني لا يحتاج إلى بيانات. تُحفظ المفاتيح مشفّرة محليًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("مفتاح OpenSubtitles API") })
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("اسم مستخدم OpenSubtitles") })
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("كلمة مرور OpenSubtitles") })
                    Button(onClick = { onSaveCredentials(apiKey.trim(), username.trim(), password) },
                        enabled = apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ بيانات OpenSubtitles") }
                    OutlinedTextField(subdlKey, { subdlKey = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("مفتاح SubDL المجاني") })
                    OutlinedTextField(websiteKey, { websiteKey = it }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("مفتاح subtitles.website (Bearer)") })
                    Button(onClick = { onSaveProviderKeys(subdlKey.trim(), websiteKey.trim()) },
                        enabled = subdlKey.isNotBlank() || websiteKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ مفاتيح المزودين") }
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
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(results, key = { it.provider + ":" + it.sourceId }) { subtitle ->
                        val key = subtitle.provider + ":" + subtitle.sourceId
                        val isDownloading = loading && downloadingKey == key
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${subtitle.downloads} تنزيلات",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.weight(1f))
                                    Button(onClick = { downloadingKey = key; onDownload(subtitle) },
                                        enabled = !loading || isDownloading,
                                        shape = RoundedCornerShape(12.dp)) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.size(7.dp))
                                        }
                                        Text(if (isDownloading) "جارٍ التطبيق…" else "تحميل وتطبيق")
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
    "opensubtitles" -> "OpenSubtitles"
    else -> provider
}
