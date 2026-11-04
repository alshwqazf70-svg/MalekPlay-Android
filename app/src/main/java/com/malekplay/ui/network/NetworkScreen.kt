@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.malekplay.ui.network

import android.net.Uri
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.util.formatBytes
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProfileStore
import com.malekplay.network.NetworkProtocol
import com.malekplay.network.SmbClient
import com.malekplay.network.SftpClient
import com.malekplay.network.WebDavClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject


/** Builds credential-free media Uris resolved against the saved profile in PlaybackService. */
private fun networkMediaUri(profile: NetworkProfile, remotePath: String): Uri {
    val segments = remotePath.split('/').filter { it.isNotBlank() }
    return when (profile.protocol) {
        NetworkProtocol.WEBDAV -> {
            val host = profile.host.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            val candidate = Uri.parse(remotePath)
            val configuredEndpoint = Uri.parse("https://$host:${profile.port}")
            val candidatePort = if (candidate.port == -1) 443 else candidate.port
            if (candidate.scheme.equals("https", ignoreCase = true) &&
                candidate.host.equals(configuredEndpoint.host, ignoreCase = true) &&
                candidatePort == configuredEndpoint.port &&
                candidate.userInfo == null
            ) {
                candidate
            } else {
                Uri.parse("https://$host:${profile.port}").buildUpon().apply {
                    segments.forEach(::appendPath)
                }.build()
            }
        }
        NetworkProtocol.SMB -> Uri.Builder().scheme("maleksmb").authority(profile.id).apply {
            segments.forEach(::appendPath)
        }.build()
        NetworkProtocol.SFTP -> Uri.Builder().scheme("maleksftp").authority(profile.id).apply {
            segments.forEach(::appendPath)
        }.build()
    }
}

/** Snapshot of the directory currently being browsed. */
data class BrowseUiState(
    val profileId: String? = null,
    val path: String = "/",
    val entries: List<NetworkEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val profileStore: NetworkProfileStore,
    private val credentialVault: NetworkCredentialVault,
    private val webDavClient: WebDavClient,
    private val smbClient: SmbClient,
    private val sftpClient: SftpClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<NetworkProfile>>(emptyList())
    val profiles: StateFlow<List<NetworkProfile>> = _profiles.asStateFlow()

    private val _browse = MutableStateFlow(BrowseUiState())
    val browse: StateFlow<BrowseUiState> = _browse.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { profileStore.load() } }
            result.onSuccess {
                _profiles.value = it
                _sourceError.value = null
            }.onFailure {
                _sourceError.value = "تعذرت قراءة مصادر الشبكة المحفوظة: ${it.localizedMessage ?: "خطأ غير معروف"}"
            }
        }
    }

    fun saveSource(
        protocol: NetworkProtocol,
        host: String,
        port: Int,
        username: String,
        password: String,
        share: String,
        path: String,
        displayName: String,
        knownHostsUri: Uri? = null
    ) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val outcome = withContext(Dispatchers.IO) {
                var destination: File? = null
                var temporary: File? = null
                runCatching {
                    val cleanHost = host.trim()
                    require(cleanHost.isNotBlank()) { "يجب إدخال اسم الخادم" }
                    require(port in 1..65535) { "رقم المنفذ غير صالح" }
                    val cleanPath = path.trim().trim('/')
                    val root = when (protocol) {
                        NetworkProtocol.SMB -> {
                            require(share.isNotBlank()) { "يجب إدخال اسم مشاركة SMB" }
                            listOf(share.trim().trim('/'), cleanPath)
                                .filter { it.isNotBlank() }
                                .joinToString(separator = "/", prefix = "/")
                        }
                        else -> if (cleanPath.isBlank()) "/" else "/$cleanPath"
                    }
                    val profile = NetworkProfile(
                        id = id,
                        protocol = protocol,
                        host = cleanHost,
                        port = port,
                        username = username.trim(),
                        rootPath = root,
                        displayName = displayName.trim().ifBlank { cleanHost }
                    )
                    if (protocol == NetworkProtocol.SFTP) {
                        val sourceUri = requireNotNull(knownHostsUri) {
                            "اختر ملف known_hosts موثوقاً قبل حفظ مصدر SFTP"
                        }
                        val directory = File(context.filesDir, "network/known_hosts")
                        check(directory.exists() || directory.mkdirs()) { "تعذر إنشاء مجلد مفاتيح SFTP" }
                        destination = File(directory, id)
                        temporary = File(directory, "$id.tmp")
                        val input = context.contentResolver.openInputStream(sourceUri)
                            ?: error("تعذر قراءة ملف known_hosts")
                        input.use { stream ->
                            temporary!!.outputStream().use { output -> stream.copyTo(output) }
                        }
                        require(temporary!!.length() in 1..1_000_000L) {
                            "ملف known_hosts فارغ أو أكبر من الحد المسموح"
                        }
                        check(temporary!!.renameTo(destination!!)) {
                            "تعذر حفظ ملف مفاتيح SFTP"
                        }
                        temporary = null
                    }
                    credentialVault.save(id, username.trim(), password)
                    profileStore.save(profile)
                }.onFailure {
                    runCatching { credentialVault.remove(id) }
                    temporary?.delete()
                    destination?.delete()
                }
            }
            _sourceError.value = outcome.exceptionOrNull()?.localizedMessage
            if (outcome.isSuccess) reload()
        }
    }

    fun removeSource(profile: NetworkProfile) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    profileStore.remove(profile.id)
                    val knownHosts = File(context.filesDir, "network/known_hosts/" + profile.id)
                    check(!knownHosts.exists() || knownHosts.delete()) {
                        "تم حذف المصدر لكن تعذر حذف ملف مفاتيح SFTP"
                    }
                }
            }
            _sourceError.value = outcome.exceptionOrNull()?.localizedMessage
            if (outcome.isSuccess) {
                if (_browse.value.profileId == profile.id) _browse.value = BrowseUiState()
                reload()
            }
        }
    }

    fun browse(profile: NetworkProfile, path: String) {
        val target = path.ifBlank { "/" }
        viewModelScope.launch {
            _browse.value = BrowseUiState(profileId = profile.id, path = target, loading = true)
            val credentialsResult = withContext(Dispatchers.IO) {
                runCatching { credentialVault.load(profile.id) }
            }
            if (credentialsResult.isFailure) {
                _browse.value = BrowseUiState(
                    profileId = profile.id,
                    path = target,
                    error = "تعذّر فك تشفير بيانات الدخول المحفوظة. أعد إدخال بيانات اعتماد هذا المصدر ثم حاول مجدداً."
                )
                return@launch
            }
            val credentials = credentialsResult.getOrNull()
            val username = credentials?.first?.takeIf { it.isNotBlank() } ?: profile.username
            val password = credentials?.second.orEmpty()
            val result = runCatching {
                when (profile.protocol) {
                    NetworkProtocol.WEBDAV -> webDavClient.list(
                        endpointFor(profile), target, username, password
                    )
                    NetworkProtocol.SMB -> {
                        val segments = target.trim('/').split('/').filter { it.isNotBlank() }
                        val shareName = segments.firstOrNull() ?: error("مسار مشاركة SMB غير صالح")
                        val remotePath = segments.drop(1).joinToString("/")
                        smbClient.list(
                            host = profile.host,
                            port = profile.port,
                            shareName = shareName,
                            path = remotePath,
                            username = username,
                            password = password
                        )
                    }
                    NetworkProtocol.SFTP -> sftpClient.list(
                        host = profile.host,
                        port = profile.port,
                        username = username,
                        password = password,
                        remotePath = target,
                        knownHostsFile = File(context.filesDir, "network/known_hosts/${profile.id}")
                    )
                }
            }
            _browse.value = result.fold(
                onSuccess = { entries ->
                    BrowseUiState(
                        profileId = profile.id,
                        path = target,
                        entries = entries.sortedWith(
                            compareByDescending<NetworkEntry> { it.isDirectory }
                                .thenBy { it.name.lowercase() }
                        )
                    )
                },
                onFailure = { throwable ->
                    BrowseUiState(
                        profileId = profile.id,
                        path = target,
                        error = throwable.localizedMessage ?: throwable.javaClass.simpleName
                    )
                }
            )
        }
    }

    fun closeBrowse() {
        _browse.value = BrowseUiState()
    }

    private fun endpointFor(profile: NetworkProfile): String {
        val host = profile.host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        return "https://$host:${profile.port}"
    }

    companion object {
        /** Marker shown when the protocol has no browse implementation yet. */
        const val PROTOCOL_UNSUPPORTED = "unsupported-protocol"
    }
}

@Composable
fun NetworkScreen(
    onOpenMedia: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val viewModel: NetworkViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == browse.profileId }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.net_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.net_add)
                )
            }
        }

        sourceError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (activeProfile == null) {
            SourcesList(
                profiles = profiles,
                onBrowse = { profile -> viewModel.browse(profile, profile.rootPath) },
                onRemove = { profile -> viewModel.removeSource(profile) }
            )
        } else {
            BrowsePanel(
                profile = activeProfile,
                state = browse,
                onNavigate = { path -> viewModel.browse(activeProfile, path) },
                onRefresh = { viewModel.browse(activeProfile, browse.path) },
                onClose = { viewModel.closeBrowse() },
                onOpenMedia = onOpenMedia
            )
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onSave = { protocol, host, port, username, password, share, path, name, knownHostsUri ->
                showAddDialog = false
                viewModel.saveSource(protocol, host, port, username, password, share, path, name, knownHostsUri)
            }
        )
    }
}

@Composable
private fun SourcesList(
    profiles: List<NetworkProfile>,
    onBrowse: (NetworkProfile) -> Unit,
    onRemove: (NetworkProfile) -> Unit
) {
    if (profiles.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.net_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = profiles, key = { it.id }) { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.protocol.name} • ${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { onBrowse(profile) }) {
                    Text(text = stringResource(R.string.net_browse))
                }
                IconButton(onClick = { onRemove(profile) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.lib_delete)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowsePanel(
    profile: NetworkProfile,
    state: BrowseUiState,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onOpenMedia: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.lib_refresh)
                )
            }
        }

        if (state.path.trim('/').isNotEmpty()) {
            TextButton(
                onClick = { onNavigate(parentPath(state.path)) },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "..")
            }
        }

        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                val rawError = state.error ?: ""
                val message = stringResource(R.string.net_error, rawError)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onRefresh) {
                        Text(text = stringResource(R.string.common_retry))
                    }
                }
            }

            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.net_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(items = state.entries, key = { it.path }) { entry ->
                        NetworkEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    onNavigate(relativePath(entry.path))
                                } else {
                                    onOpenMedia(networkMediaUri(profile, entry.path).toString())
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkEntryRow(
    entry: NetworkEntry,
    onClick: () -> Unit
) {
    val isMedia = isMediaFile(entry.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Filled.Folder
                isMedia -> Icons.Filled.Movie
                else -> Icons.Filled.MusicNote
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory) {
                Text(
                    text = formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onSave: (
        protocol: NetworkProtocol,
        host: String,
        port: Int,
        username: String,
        password: String,
        share: String,
        path: String,
        displayName: String,
        knownHostsUri: Uri?
    ) -> Unit
) {
    val protocols = NetworkProtocol.entries.toList()
    var protocolIndex by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var knownHostsUri by remember { mutableStateOf<Uri?>(null) }
    val knownHostsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { knownHostsUri = it }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.net_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.net_protocol),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    protocols.forEachIndexed { index, item ->
                        if (index == protocolIndex) {
                            Button(
                                onClick = {
                                    protocolIndex = index
                                    port = defaultPort(item).toString()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = item.name, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    protocolIndex = index
                                    port = defaultPort(item).toString()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = item.name, maxLines = 1)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(text = stringResource(R.string.net_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(text = stringResource(R.string.net_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(text = stringResource(R.string.net_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = stringResource(R.string.net_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (protocols[protocolIndex] == NetworkProtocol.SFTP) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { knownHostsPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(knownHostsUri?.lastPathSegment ?: "اختيار ملف known_hosts موثوق")
                    }
                    Text(
                        text = "يتم نسخ المفتاح إلى مساحة التطبيق الخاصة، ولا يتم تعطيل التحقق من هوية الخادم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (protocols[protocolIndex] == NetworkProtocol.SMB) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = share,
                        onValueChange = { share = it },
                        label = { Text(text = stringResource(R.string.net_share)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(text = stringResource(R.string.net_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(text = stringResource(R.string.net_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.net_https_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && (protocols[protocolIndex] != NetworkProtocol.SFTP || knownHostsUri != null),
                onClick = {
                    onSave(
                        protocols[protocolIndex],
                        host,
                        port.toIntOrNull() ?: defaultPort(protocols[protocolIndex]),
                        username,
                        password,
                        share,
                        path,
                        displayName,
                        knownHostsUri
                    )
                }
            ) {
                Text(text = stringResource(R.string.net_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.lib_cancel))
            }
        }
    )
}

private fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
    NetworkProtocol.WEBDAV -> 443
    NetworkProtocol.SMB -> 445
    NetworkProtocol.SFTP -> 22
}

private fun parentPath(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    val parent = trimmed.substringBeforeLast('/')
    return if (parent.isBlank()) "/" else parent
}

private fun relativePath(url: String): String =
    runCatching { Uri.parse(url).path }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "/"

private val MEDIA_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "ts", "flv",
    "mpg", "mpeg", "wmv", "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma"
)

private fun isMediaFile(name: String): Boolean =
    name.substringAfterLast('.').lowercase() in MEDIA_EXTENSIONS
