package com.malekplay.ui.player.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.malekplay.R
import com.malekplay.player.audio.LrcLine
import com.malekplay.player.audio.LrcParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private const val MAX_LRC_BYTES = 512 * 1024
private const val LYRICS_PREFERENCES = "synced_lyrics"
private const val LYRICS_TREE_URI = "lyrics_tree_uri"

/**
 * Displays timed lyrics for the current Media3 item.
 *
 * The user may grant one folder through SAF. The grant is persisted, and on
 * every track change the panel looks for an exact same-basename .lrc file in
 * that folder. Manual file selection remains available as a fallback.
 */
@Composable
fun SyncedLyricsPanel(
    mediaId: String,
    mediaTitle: String,
    positionMs: Long,
    isPlaying: Boolean,
    player: Player?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences(LYRICS_PREFERENCES, Context.MODE_PRIVATE)
    }
    var lyricsFolderUri by remember(context) {
        mutableStateOf(preferences.getString(LYRICS_TREE_URI, null))
    }
    var lines by remember(mediaId) { mutableStateOf<List<LrcLine>>(emptyList()) }
    var fileName by remember(mediaId) { mutableStateOf<String?>(null) }
    var loading by remember(mediaId) { mutableStateOf(false) }
    var failed by remember(mediaId) { mutableStateOf(false) }
    var notFound by remember(mediaId) { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var synchronizedPositionMs by remember(mediaId) { mutableStateOf(positionMs) }

    fun loadSelectedFile(uri: Uri, displayName: String? = null) {
        loadJob?.cancel()
        loading = true
        failed = false
        notFound = false
        loadJob = scope.launch {
            try {
                val text = readLrcText(context, uri)
                val parsed = withContext(Dispatchers.Default) { LrcParser.parse(text) }
                if (parsed.isEmpty()) throw IOException("No valid timestamped lyric lines")
                lines = parsed
                fileName = displayName
                    ?: withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lines = emptyList()
                fileName = null
                failed = true
            } finally {
                loading = false
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadSelectedFile(uri)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            preferences.edit().putString(LYRICS_TREE_URI, uri.toString()).apply()
            lyricsFolderUri = uri.toString()
        }
    }

    LaunchedEffect(mediaId, mediaTitle, lyricsFolderUri) {
        loadJob?.cancel()
        lines = emptyList()
        fileName = null
        failed = false
        notFound = false
        loading = false

        val folder = lyricsFolderUri?.let(Uri::parse) ?: return@LaunchedEffect
        loadJob = scope.launch {
            loading = true
            try {
                val match = withContext(Dispatchers.IO) {
                    findMatchingLrcInFolder(context, folder, mediaTitle)
                }
                if (match == null) {
                    notFound = true
                } else {
                    val text = readLrcText(context, match.first)
                    val parsed = withContext(Dispatchers.Default) { LrcParser.parse(text) }
                    if (parsed.isEmpty()) throw IOException("No valid timestamped lyric lines")
                    lines = parsed
                    fileName = match.second
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                // The user may revoke the SAF grant in Android settings.
                preferences.edit().remove(LYRICS_TREE_URI).apply()
                lyricsFolderUri = null
                failed = true
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(mediaId, isPlaying, player, lines.isNotEmpty()) {
        if (!isPlaying || player == null || lines.isEmpty()) {
            synchronizedPositionMs = positionMs
            return@LaunchedEffect
        }
        while (isActive) {
            synchronizedPositionMs = player.currentPosition.coerceAtLeast(0L)
            delay(200L)
        }
    }

    LaunchedEffect(positionMs, isPlaying) {
        if (!isPlaying) synchronizedPositionMs = positionMs
    }

    val activeIndex = remember(lines, synchronizedPositionMs) {
        findActiveLyricIndex(lines, synchronizedPositionMs)
    }
    val activeLine = lines.getOrNull(activeIndex)
    val upcomingIndex = if (activeIndex < 0) 0 else activeIndex + 1
    val upcomingLine = lines.getOrNull(upcomingIndex)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp, max = 110.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.lyrics_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = fileName.orEmpty(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !loading
                ) {
                    Text(
                        stringResource(R.string.lyrics_choose_folder),
                        maxLines = 1
                    )
                }
                TextButton(
                    onClick = { documentPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) },
                    enabled = !loading
                ) {
                    Text(
                        stringResource(
                            if (loading) R.string.lyrics_loading else R.string.lyrics_choose_file
                        ),
                        maxLines = 1
                    )
                }
            }

            when {
                loading -> Unit
                failed -> Text(
                    text = stringResource(R.string.lyrics_load_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                notFound -> Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                lines.isEmpty() -> Text(
                    text = stringResource(R.string.lyrics_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                else -> {
                    Text(
                        text = activeLine?.text ?: lines.first().text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (upcomingLine != null && upcomingIndex != activeIndex) {
                        Text(
                            text = upcomingLine.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private suspend fun findMatchingLrcInFolder(
    context: Context,
    treeUri: Uri,
    mediaTitle: String
): Pair<Uri, String>? = withContext(Dispatchers.IO) {
    val targetBaseName = normalizeLyricBaseName(mediaTitle)
    if (targetBaseName.isBlank()) return@withContext null

    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        treeDocumentId
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (idColumn < 0 || nameColumn < 0) return@use null

        while (cursor.moveToNext()) {
            val displayName = cursor.getString(nameColumn) ?: continue
            if (!displayName.endsWith(".lrc", ignoreCase = true)) continue
            if (normalizeLyricBaseName(displayName) != targetBaseName) continue
            val documentId = cursor.getString(idColumn) ?: continue
            return@withContext DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                documentId
            ) to displayName
        }
    }
    null
}

private fun normalizeLyricBaseName(value: String): String =
    value.substringAfterLast('/').substringBeforeLast('.', value.substringAfterLast('/'))
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

private fun findActiveLyricIndex(lines: List<LrcLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timestampMs <= positionMs) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}

private suspend fun readLrcText(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_LRC_BYTES) {
                    throw IOException("LRC file exceeds the 512 KiB safety limit")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Unable to open selected LRC file")

        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
            else -> String(bytes, StandardCharsets.UTF_8)
        }
    }

private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
}
