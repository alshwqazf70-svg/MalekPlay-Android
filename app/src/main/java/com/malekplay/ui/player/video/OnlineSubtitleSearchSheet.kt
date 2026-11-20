package com.malekplay.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.network.OnlineSubtitle

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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("البحث عن ترجمة عبر الإنترنت", style = MaterialTheme.typography.titleLarge)
                    Text("ابحث باسم العمل أو الحلقة، ثم حمّل الترجمة مباشرة",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, enabled = !loading) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
                }
            }
            OutlinedTextField(query, onQueryChange, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("اسم الفيلم أو المسلسل") },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("مسح") }
                        IconButton(onClick = onSearch, enabled = query.isNotBlank() && !loading) {
                            Icon(Icons.Default.Search, contentDescription = "بحث")
                        }
                    }
                })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(season, { onSeasonChange(it.filter(Char::isDigit).take(2)) },
                    Modifier.weight(1f), singleLine = true, label = { Text("الموسم") })
                OutlinedTextField(episode, { onEpisodeChange(it.filter(Char::isDigit).take(3)) },
                    Modifier.weight(1f), singleLine = true, label = { Text("الحلقة") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(language == "ar,en", { onLanguageChange("ar,en") }, label = { Text("العربية + English") })
                FilterChip(language == "ar", { onLanguageChange("ar") }, label = { Text("العربية فقط") })
                FilterChip(language == "en", { onLanguageChange("en") }, label = { Text("English") })
            }
            Button(onClick = onSearch, enabled = query.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
                if (loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                else Text("بحث عن الترجمات")
            }
            TextButton(onClick = { credentialsExpanded = !credentialsExpanded }) {
                Text(if (credentialsExpanded) "إخفاء إعدادات OpenSubtitles الاختيارية" else "إعدادات API الاختيارية · OpenSubtitles")
            }
            if (credentialsExpanded) {
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("مفتاح OpenSubtitles API") })
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("اسم مستخدم OpenSubtitles") })
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("كلمة المرور") })
                Button(onClick = { onSaveCredentials(apiKey, username, password) },
                    enabled = apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("حفظ بيانات OpenSubtitles بأمان") }
                OutlinedTextField(subdlKey, { subdlKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("مفتاح SubDL المجاني (اختياري)") })
                OutlinedTextField(websiteKey, { websiteKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("مفتاح subtitles.website (اختياري)") })
                Button(onClick = { onSaveProviderKeys(subdlKey, websiteKey) },
                    enabled = subdlKey.isNotBlank() || websiteKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("حفظ مفاتيح المزودين") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!loading && results.isEmpty() && error == null) {
                Text("اكتب العنوان واضغط بحث لعرض الترجمات المتاحة.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.provider + ":" + it.sourceId }) { subtitle ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (subtitle.language.startsWith("ar", true)) "AR" else "EN",
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(subtitle.format.uppercase(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.weight(1f))
                                Text(subtitle.provider, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(subtitle.release.ifBlank { "ترجمة " + subtitle.format.uppercase() },
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(subtitle.downloads.toString() + " تنزيلات", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Button(onClick = { onDownload(subtitle) }, enabled = !loading) { Text("تحميل وتطبيق") }
                            }
                        }
                    }
                }
            }
            if (loading) Text("جارٍ البحث أو تنزيل الترجمة…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
