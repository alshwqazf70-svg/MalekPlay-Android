package com.malekplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistPickerViewModel @Inject constructor(
    private val repository: PlaylistRepository
) : ViewModel() {

    val playlists = repository.observePlaylists()

    fun toggle(playlistId: String, mediaId: String, present: Boolean) {
        viewModelScope.launch {
            if (present) repository.removeMedia(playlistId, mediaId)
            else repository.addMedia(playlistId, listOf(mediaId))
        }
    }

    fun create(name: String, mediaId: String) {
        viewModelScope.launch {
            val id = runCatching { repository.create(name) }.getOrNull() ?: return@launch
            repository.addMedia(id, listOf(mediaId))
        }
    }
}

/** Lets the user add one file to any number of playlists, or create a new one. */
@Composable
fun PlaylistPickerDialog(
    mediaId: String,
    onDismiss: () -> Unit,
    viewModel: PlaylistPickerViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_add_to_playlist)) },
        text = {
            Column {
                if (playlists.isEmpty() && !showCreate) {
                    Text(
                        stringResource(R.string.lib_no_playlists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                playlists.forEach { playlist ->
                    val present = playlist.items.any { it.mediaId == mediaId }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${playlist.playlist.name} (${playlist.items.size})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Checkbox(
                            checked = present,
                            onCheckedChange = {
                                viewModel.toggle(playlist.playlist.playlistId, mediaId, present)
                            }
                        )
                    }
                }
                if (showCreate) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.lib_playlist_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showCreate) {
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.create(newName.trim(), mediaId)
                            newName = ""
                            showCreate = false
                        }
                    }) { Text(stringResource(R.string.lib_create)) }
                } else {
                    TextButton(onClick = { showCreate = true }) {
                        Text(stringResource(R.string.lib_new_playlist))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) }
            }
        }
    )
}
