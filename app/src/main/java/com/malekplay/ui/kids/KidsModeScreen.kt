package com.malekplay.ui.kids

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.MediaItem
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
    var showParentDialog by remember { mutableStateOf(false) }
    var parentPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val approved = remember(media, selected, enabled) {
        media.map { it.media }.filter { it.id in selected && (it.isVideo || it.isAudio) }
            .sortedBy { it.title.lowercase() }
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
                                manager.setAllowedMediaIds(selected)
                                onBack()
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
                    if (enabled) approved.size else selected.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
            )
            if (enabled) {
                if (approved.isEmpty()) {
                    Text(stringResource(R.string.kids_no_approved_media), modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(approved, key = { it.id }) { item ->
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
                    Text(stringResource(R.string.kids_no_media_found), modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(media, key = { it.media.id }) { libraryItem ->
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
