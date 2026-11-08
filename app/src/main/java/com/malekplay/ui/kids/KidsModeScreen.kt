package com.malekplay.ui.kids

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.malekplay.R
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun KidsModeScreen(
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val manager = remember(context) { ParentalControlManager(context) }
    val enabled = manager.isEnabled
    val playerController by playerViewModel.controller.collectAsStateWithLifecycle()

    // A previous media session may outlive the Activity. Always clear it when Kids Mode opens.
    LaunchedEffect(enabled, playerController) {
        if (enabled && playerController != null) playerViewModel.stopForParentalMode()
    }
    val media by viewModel.allMedia.collectAsStateWithLifecycle()
    var selected by remember(manager) { mutableStateOf(manager.allowedMediaIds) }
    var searchQuery by remember { mutableStateOf("") }
    var mediaFilter by remember { mutableStateOf("all") }
    var showParentDialog by remember { mutableStateOf(false) }
    var parentPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    // Keep sorting/filtering of a very large family library off the main thread.
    // Sorting is performed only when the underlying library changes, not per keystroke.
    val sortedMedia by produceState(
        initialValue = emptyList<LibraryItem>(), media
    ) {
        value = withContext(Dispatchers.Default) {
            media.sortedBy { it.media.title.lowercase() }
        }
    }
    val validMediaIds by produceState(
        initialValue = emptySet<String>(), media
    ) {
        value = withContext(Dispatchers.Default) {
            media.asSequence().map { it.media }
                .filter { it.isVideo || it.isAudio }
                .map { it.id }.toSet()
        }
    }
    // Selection changes must not trigger a full-library refilter while the parent
    // is choosing approvals; the allowlist matters only after Kids Mode is enabled.
    val activeAllowlist = if (enabled) selected else emptySet()
    val filteredMedia by produceState(
        initialValue = emptyList<LibraryItem>(),
        sortedMedia, activeAllowlist, enabled, searchQuery, mediaFilter
    ) {
        value = withContext(Dispatchers.Default) {
            val query = searchQuery.trim()
            sortedMedia.filter { entry ->
                val item = entry.media
                val typeMatches = when (mediaFilter) {
                    "video" -> item.isVideo
                    "audio" -> item.isAudio
                    else -> true
                }
                val approvalMatches = !enabled || item.id in activeAllowlist
                val queryMatches = query.isBlank() ||
                    item.displayTitle.contains(query, ignoreCase = true) ||
                    item.folderName.contains(query, ignoreCase = true) ||
                    item.artist.orEmpty().contains(query, ignoreCase = true) ||
                    item.album.orEmpty().contains(query, ignoreCase = true)
                typeMatches && approvalMatches && queryMatches && (item.isVideo || item.isAudio)
            }
        }
    }

    // A child cannot navigate back into the parent area using the system back button.
    BackHandler(enabled = enabled) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (enabled) R.string.kids_library_title else R.string.kids_choose_title)) },
                navigationIcon = {
                    if (!enabled) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    } else {
                        Icon(Icons.Filled.ChildCare, contentDescription = null, modifier = Modifier.padding(start = 16.dp))
                    }
                },
                actions = {
                    if (enabled) {
                        TextButton(onClick = { showParentDialog = true }) {
                            Text(stringResource(R.string.kids_parent_access))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                saveError = !runCatching {
                                    manager.setAllowedMediaIds(selected.intersect(validMediaIds))
                                }.isSuccess
                                if (!saveError) onBack()
                            }
                        ) { Text(stringResource(R.string.kids_save_selection)) }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (enabled) R.string.kids_library_summary else R.string.kids_choose_summary,
                    if (enabled) filteredMedia.size else selected.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ابحث في مكتبة الأطفال") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mediaFilter == "all",
                    onClick = { mediaFilter = "all" },
                    label = { Text("الكل") }
                )
                FilterChip(
                    selected = mediaFilter == "video",
                    onClick = { mediaFilter = "video" },
                    label = { Text("الفيديو") }
                )
                FilterChip(
                    selected = mediaFilter == "audio",
                    onClick = { mediaFilter = "audio" },
                    label = { Text("الصوت") }
                )
            }
            if (saveError) Text(stringResource(R.string.kids_save_error), color = MaterialTheme.colorScheme.error)
            if (enabled) {
                if (filteredMedia.isEmpty()) {
                    Text(
                        if (searchQuery.isNotBlank()) "لا توجد نتائج تطابق البحث."
                        else stringResource(R.string.kids_no_approved_media),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filteredMedia, key = { it.media.id }) { entry ->
                            val item = entry.media
                            Row(
                                Modifier.fillMaxWidth().clickable { onPlay(item) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ChildCare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(item.displayTitle, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(if (item.isVideo) R.string.kids_video_label else R.string.kids_audio_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (media.isEmpty()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.kids_no_media_found))
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.kids_refresh_library))
                        }
                    }
                } else if (filteredMedia.isEmpty()) {
                    Text("لا توجد وسائط تطابق البحث أو نوع التصفية.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filteredMedia, key = { it.media.id }) { libraryItem ->
                            val item = libraryItem.media
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + item.id else selected - item.id
                                    }
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(item.displayTitle, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(if (item.isVideo) R.string.kids_video_label else R.string.kids_audio_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            KidsModeHelpCard(enabled = enabled)
        }
    }

    if (showParentDialog) {
        AlertDialog(
            onDismissRequest = { showParentDialog = false; parentPin = ""; pinError = false },
            title = { Text(stringResource(R.string.kids_parent_access)) },
            text = {
                Column {
                    Text(stringResource(R.string.kids_parent_pin_prompt))
                    OutlinedTextField(
                        value = parentPin,
                        onValueChange = { parentPin = it.filter(Char::isDigit).take(8); pinError = false },
                        label = { Text(stringResource(R.string.kids_pin_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    if (pinError) Text(stringResource(R.string.kids_pin_wrong), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manager.disableAfterPin(parentPin)) {
                        showParentDialog = false
                        parentPin = ""
                        onBack()
                    } else pinError = true
                }) { Text(stringResource(R.string.kids_parent_exit)) }
            },
            dismissButton = {
                TextButton(onClick = { showParentDialog = false; parentPin = "" }) {
                    Text(stringResource(R.string.lib_cancel))
                }
            }
        )
    }
}


@Composable
private fun KidsModeHelpCard(enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("دليل وضع الأطفال", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء الشرح" else "اضغط لعرض طريقة الإعداد والاستخدام")
            }
            if (expanded) {
                if (!enabled) {
                    Text("1. ابحث عن الفيديوهات أو المقاطع الصوتية باستخدام خانة البحث.\n2. استخدم «الكل» أو «الفيديو» أو «الصوت» لتصفية المكتبة.\n3. ضع علامة بجانب كل عنصر تريد السماح للطفل بمشاهدته أو سماعه.\n4. اضغط «حفظ الاختيار» أعلى الشاشة؛ ستُحفظ العناصر المحددة ويُفعّل وضع الأطفال.")
                } else {
                    Text("1. سيظهر للطفل فقط المحتوى الذي سمح به ولي الأمر.\n2. يمكن البحث والتبديل بين «الكل» و«الفيديو» و«الصوت».\n3. اضغط على العنصر لتشغيله.\n4. للخروج إلى مساحة الوالدين اضغط «دخول الوالدين» وأدخل رمز PIN ثم اختر «خروج وضع الأطفال».")
                }
                Text("تنبيه مهم: هذا الوضع يقيّد المحتوى داخل MalekPlay فقط، ولا يحوّل الهاتف إلى وضع قفل كامل؛ يمكن للطفل استخدام أزرار النظام أو فتح تطبيقات أخرى خارج MalekPlay. احتفظ برمز PIN ولا تشاركه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
