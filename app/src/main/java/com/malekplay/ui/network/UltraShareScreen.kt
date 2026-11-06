package com.malekplay.ui.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.theme.MalekPlayColors
import com.malekplay.network.UltraShareTransport
import com.malekplay.network.UltraShareQrCode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class UltraShareUiState(
    val pairingSecret: String = "",
    val localAddresses: List<String> = emptyList(),
    val receiving: Boolean = false,
    val sending: Boolean = false,
    val progress: UltraShareTransport.Progress? = null,
    val receivedFile: UltraShareTransport.ReceivedFile? = null,
    val status: String? = null,
    val error: String? = null
)

@HiltViewModel
class UltraShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(UltraShareUiState())
    val state = _state.asStateFlow()
    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    fun startReceiving() {
        if (receiveJob != null) return
        val secret = UltraShareTransport.createPairingSecret()
        _state.value = UltraShareUiState(
            pairingSecret = secret,
            localAddresses = findLocalIpv4Addresses(),
            receiving = true,
            status = context.getString(R.string.ultra_share_waiting)
        )
        receiveJob = viewModelScope.launch {
            try {
                val received = UltraShareTransport.receiveOnce(
                    pairingSecret = secret,
                    destinationDirectory = UltraShareTransport.defaultReceiveDirectory(context),
                    onProgress = { progress -> _state.value = _state.value.copy(progress = progress) }
                )
                _state.value = _state.value.copy(
                    receiving = false,
                    progress = null,
                    receivedFile = received,
                    status = context.getString(R.string.ultra_share_received, received.fileName),
                    error = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    receiving = false,
                    error = failure.localizedMessage ?: context.getString(R.string.ultra_share_transfer_failed),
                    status = null
                )
            } finally {
                receiveJob = null
                _state.value = _state.value.copy(receiving = false)
            }
        }
    }

    fun stopReceiving() {
        receiveJob?.cancel()
        _state.value = _state.value.copy(
            status = context.getString(R.string.ultra_share_stopped),
            progress = null
        )
    }

    fun send(uri: Uri, host: String, secret: String) {
        if (sendJob != null) return
        sendJob = viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null, status = null, progress = null)
            try {
                val metadata = withContext(Dispatchers.IO) { readDocumentMetadata(context, uri) }
                UltraShareTransport.send(
                    resolver = context.contentResolver,
                    uri = uri,
                    host = host,
                    pairingSecret = secret,
                    fileName = metadata.first,
                    mimeType = metadata.second,
                    sizeBytes = metadata.third,
                    onProgress = { progress -> _state.value = _state.value.copy(progress = progress) }
                )
                _state.value = _state.value.copy(
                    sending = false,
                    progress = null,
                    status = context.getString(R.string.ultra_share_sent, metadata.first),
                    error = null
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    sending = false,
                    error = failure.localizedMessage ?: context.getString(R.string.ultra_share_transfer_failed)
                )
            } finally {
                sendJob = null
                _state.value = _state.value.copy(sending = false)
            }
        }
    }

    fun exportReceived(destination: Uri) {
        val received = _state.value.receivedFile ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination, "w")
                        ?: error("تعذر فتح وجهة الحفظ.")
                    received.file.inputStream().use { input ->
                        output.use { target -> input.copyTo(target, UltraShareTransport.CHUNK_BYTES) }
                    }
                }
                _state.value = _state.value.copy(
                    status = context.getString(R.string.ultra_share_exported, received.fileName),
                    error = null
                )
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.localizedMessage)
            }
        }
    }

    fun cancelSending() {
        sendJob?.cancel()
        _state.value = _state.value.copy(
            progress = null,
            status = context.getString(R.string.ultra_share_send_cancelled)
        )
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, status = null)
    }

    override fun onCleared() {
        receiveJob?.cancel()
        sendJob?.cancel()
        super.onCleared()
    }

    private fun findLocalIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback &&
                    (name.startsWith("wlan") || name.startsWith("swlan") ||
                        name.startsWith("ap") || name.startsWith("eth"))
            }
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun readDocumentMetadata(context: Context, uri: Uri): Triple<String, String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        require(size > 0L) { "تعذر معرفة حجم الملف؛ اختر ملفاً من مدير الملفات يدعم عرض الحجم." }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(name, mime, size)
    }
}

@Composable
fun UltraShareScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: UltraShareViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val qrPayload = state.localAddresses.firstOrNull()?.takeIf { state.receiving }?.let { address ->
        UltraShareQrCode.createPayload(address, UltraShareTransport.DEFAULT_PORT, state.pairingSecret)
    }
    val qrBitmap = remember(qrPayload) {
        qrPayload?.let { payload -> runCatching { UltraShareQrCode.createBitmap(payload) }.getOrNull() }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.send(uri, host.trim(), secret.trim())
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.exportReceived(uri) }
    val scanQrImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        UltraShareQrCode.parsePayload(
                            UltraShareQrCode.decodeImage(context.contentResolver, uri)
                        )
                    }
                }
                result.onSuccess { pairing ->
                    host = pairing.host
                    secret = pairing.pairingSecret
                    Toast.makeText(context, R.string.ultra_share_scan_success, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        it.localizedMessage ?: context.getString(R.string.ultra_share_scan_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF20122F), MalekPlayColors.Background, Color(0xFF07060C))))
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MalekPlayColors.TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ultra_share_title), style = MaterialTheme.typography.headlineSmall, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.ultra_share_subtitle), style = MaterialTheme.typography.bodySmall, color = MalekPlayColors.TextSecondary)
            }
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(28.dp))
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_receive), color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.ultra_share_receive_hint), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (state.receiving) {
                    Text(stringResource(R.string.ultra_share_receiver_address), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    if (state.localAddresses.isEmpty()) {
                        Text(stringResource(R.string.ultra_share_no_address), color = MaterialTheme.colorScheme.error)
                    } else {
                        state.localAddresses.forEach { address ->
                            Text(address, color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            stringResource(R.string.ultra_share_port, UltraShareTransport.DEFAULT_PORT),
                            color = MalekPlayColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(stringResource(R.string.ultra_share_pairing_code), color = MalekPlayColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Text(state.pairingSecret.chunked(4).joinToString(" "), color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(stringResource(R.string.ultra_share_code_warning), color = MalekPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.ultra_share_qr_description),
                            modifier = Modifier
                                .size(220.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Text(
                            stringResource(R.string.ultra_share_qr_hint),
                            color = MalekPlayColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Button(
                    onClick = { if (state.receiving) viewModel.stopReceiving() else viewModel.startReceiving() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending
                ) {
                    if (state.receiving) Text(stringResource(R.string.ultra_share_stop_receiving))
                    else Text(stringResource(R.string.ultra_share_start_receiving))
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF21192D)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Send, contentDescription = null, tint = MalekPlayColors.PurpleBright)
                    Text(stringResource(R.string.ultra_share_send), color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ultra_share_host)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.filter { char -> char.isLetterOrDigit() }.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ultra_share_pairing_code)) },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { scanQrImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving
                ) {
                    Icon(Icons.Filled.QrCode, contentDescription = null)
                    Text(stringResource(R.string.ultra_share_scan_qr), modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.sending && !state.receiving &&
                        host.isNotBlank() && UltraShareTransport.isValidPairingSecret(secret)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Text(stringResource(R.string.ultra_share_choose_send), modifier = Modifier.padding(start = 8.dp))
                }
                if (state.sending) {
                    OutlinedButton(onClick = viewModel::cancelSending, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ultra_share_cancel_send))
                    }
                }
            }
        }

        state.progress?.let { progress ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17131F)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress.fileName, color = MalekPlayColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(progress.fraction * 100).toInt()}%  •  ${formatTransferBytes(progress.transferredBytes)} / ${formatTransferBytes(progress.totalBytes)}  •  ${formatTransferBytes(progress.bytesPerSecond)}/s",
                        color = MalekPlayColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        state.receivedFile?.let { received ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18291F)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF66D99A))
                        Text(stringResource(R.string.ultra_share_receive_complete), color = MalekPlayColors.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(received.fileName, color = MalekPlayColors.TextPrimary)
                    Text(formatTransferBytes(received.sizeBytes), color = MalekPlayColors.TextSecondary)
                    OutlinedButton(onClick = { exportFile.launch(received.fileName) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.ultra_share_save_to_device), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        state.status?.let { Text(it, color = MalekPlayColors.PurpleBright, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::clearMessages) { Text(stringResource(R.string.common_dismiss)) }
        }
        Text(
            stringResource(R.string.ultra_share_lan_note),
            color = MalekPlayColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun formatTransferBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = -1
    do {
        amount /= 1024.0
        index++
    } while (amount >= 1024.0 && index < units.lastIndex)
    return String.format(java.util.Locale.US, "%.1f %s", amount, units[index])
}
