@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.malekplay.ui.network

import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Network
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.malekplay.core.ui.theme.MalekPlayColors
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.malekplay.R
import com.malekplay.core.ui.util.formatBytes
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.NetworkDiscoveryClient
import com.malekplay.network.DlnaContentDirectoryClient
import com.malekplay.network.DlnaEntry
import com.malekplay.network.DiscoveredNetworkDevice
import com.malekplay.network.NetworkDeviceKind
import com.malekplay.network.NetworkEntry
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProfileStore
import com.malekplay.network.copyKnownHostsBounded
import com.malekplay.network.normalizeNetworkHost
import com.malekplay.network.httpsNetworkEndpoint
import com.malekplay.network.NetworkProtocol
import com.malekplay.network.SmbClient
import com.malekplay.network.SmbPathPolicy
import com.malekplay.network.SftpClient
import com.malekplay.network.WebDavClient
import com.malekplay.player.NetworkPlaybackUriBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject


/** Builds a validated, credential-free Uri for the selected network file. */
private fun networkMediaUri(profile: NetworkProfile, remotePath: String): Uri {
    return when (profile.protocol) {
        NetworkProtocol.WEBDAV -> {
            val configuredEndpoint = Uri.parse(httpsNetworkEndpoint(profile.host, profile.port))
            val candidate = Uri.parse(remotePath)
            val candidatePort = if (candidate.port == -1) 443 else candidate.port
            require(
                candidate.scheme.equals("https", ignoreCase = true) &&
                    candidate.host.equals(configuredEndpoint.host, ignoreCase = true) &&
                    candidatePort == configuredEndpoint.port &&
                    candidate.userInfo == null &&
                    candidate.query == null &&
                    candidate.fragment == null
            ) { "رابط WebDAV لا يطابق الخادم المحفوظ" }
            candidate
        }
        NetworkProtocol.SMB, NetworkProtocol.SFTP -> {
            val fileName = remotePath.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("مسار ملف الشبكة فارغ")
            NetworkPlaybackUriBuilder.build(
                profile,
                NetworkEntry(name = fileName, path = remotePath, isDirectory = false)
            )
        }
    }
}

/** Snapshot of the directory currently being browsed. */
data class DlnaBrowseUiState(
    val device: DiscoveredNetworkDevice? = null,
    val objectId: String = "0",
    val parentId: String? = null,
    val entries: List<DlnaEntry> = emptyList(),
    val totalMatches: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
)

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

    private var browseJob: Job? = null
    private var dlnaBrowseJob: Job? = null
    private val dlnaClient = DlnaContentDirectoryClient()
    private val _dlnaBrowse = MutableStateFlow(DlnaBrowseUiState())
    val dlnaBrowse: StateFlow<DlnaBrowseUiState> = _dlnaBrowse.asStateFlow()
    private var discoveryJob: Job? = null
    private val discoveryClient = NetworkDiscoveryClient()
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredNetworkDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredNetworkDevice>> = _discoveredDevices.asStateFlow()
    private val _discoveryRunning = MutableStateFlow(false)
    val discoveryRunning: StateFlow<Boolean> = _discoveryRunning.asStateFlow()
    private val _discoveryMessage = MutableStateFlow<String?>(null)
    val discoveryMessage: StateFlow<String?> = _discoveryMessage.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            discoverDevices()
        }

        override fun onLost(network: Network) {
            val wifiStillAvailable = connectivityManager.allNetworks.any { candidate ->
                connectivityManager.getNetworkCapabilities(candidate)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (!wifiStillAvailable) {
                discoveryJob?.cancel()
                _discoveredDevices.value = emptyList()
                _discoveryMessage.value = "انقطع اتصال Wi-Fi؛ أعد الاتصال للبحث عن الأجهزة."
            }
        }
    }

    fun discoverDevices() {
        if (_discoveryRunning.value) return
        _discoveryRunning.value = true
        discoveryJob = viewModelScope.launch {
            _discoveryMessage.value = null
            try {
                val results = discoveryClient.discover(context)
                _discoveredDevices.value = results
                _discoveryMessage.value = if (results.isEmpty()) {
                    "لم نعثر على أجهزة تعلن خدمات مشاركة. تأكد من أن الأجهزة على شبكة Wi-Fi نفسها وأن مشاركة الوسائط مفعّلة."
                } else "اكتُشف " + results.size + " جهازاً أو خدمة على الشبكة المحلية."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _discoveryMessage.value = error.localizedMessage ?: "تعذر اكتشاف أجهزة الشبكة."
            } finally {
                _discoveryRunning.value = false
            }
        }
    }

    init {
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiCallback
            )
        }
        reload()
    }

    override fun onCleared() {
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        discoveryJob?.cancel()
        dlnaBrowseJob?.cancel()
        browseJob?.cancel()
        super.onCleared()
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
                    val cleanHost = normalizeNetworkHost(host)
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
                            temporary!!.outputStream().use { output ->
                                copyKnownHostsBounded(stream, output)
                            }
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
                }.onFailure { failure ->
                    runCatching { credentialVault.remove(id) }
                        .onFailure(failure::addSuppressed)
                    if (temporary?.exists() == true && temporary?.delete() != true) {
                        failure.addSuppressed(IllegalStateException("تعذر حذف الملف المؤقت لمفاتيح SFTP"))
                    }
                    if (destination?.exists() == true && destination?.delete() != true) {
                        failure.addSuppressed(IllegalStateException("تعذر حذف ملف مفاتيح SFTP غير المكتمل"))
                    }
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
            // The profile may already be removed even if deleting its auxiliary
            // known_hosts file failed; refresh the list without erasing that error.
            val refreshedProfiles = withContext(Dispatchers.IO) {
                runCatching { profileStore.load() }.getOrNull()
            }
            if (refreshedProfiles != null) _profiles.value = refreshedProfiles
            if (outcome.isSuccess && _browse.value.profileId == profile.id) {
                browseJob?.cancel()
                browseJob = null
                _browse.value = BrowseUiState()
            }
        }
    }

    fun browse(profile: NetworkProfile, path: String) {
        val target = path.ifBlank { "/" }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _browse.value = BrowseUiState(profileId = profile.id, path = target, loading = true)

            val credentials = try {
                withContext(Dispatchers.IO) { credentialVault.load(profile.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _browse.value = BrowseUiState(
                    profileId = profile.id,
                    path = target,
                    error = "تعذّر فك تشفير بيانات الدخول المحفوظة: ${error.localizedMessage ?: "خطأ غير معروف"}"
                )
                return@launch
            }

            if (credentials == null) {
                _browse.value = BrowseUiState(
                    profileId = profile.id,
                    path = target,
                    error = "بيانات اعتماد مصدر الشبكة غير محفوظة. أعد حفظ بيانات الدخول قبل الاتصال."
                )
                return@launch
            }

            val entries = try {
                when (profile.protocol) {
                    NetworkProtocol.WEBDAV -> webDavClient.list(
                        endpointFor(profile), target, credentials.first, credentials.second
                    )
                    NetworkProtocol.SMB -> {
                        val (shareName, remotePath) = SmbPathPolicy.resolve(profile.rootPath, target)
                        smbClient.list(
                            host = profile.host,
                            port = profile.port,
                            shareName = shareName,
                            path = remotePath,
                            username = credentials.first,
                            password = credentials.second
                        )
                    }
                    NetworkProtocol.SFTP -> sftpClient.list(
                        host = profile.host,
                        port = profile.port,
                        username = credentials.first,
                        password = credentials.second,
                        remotePath = target,
                        knownHostsFile = File(context.filesDir, "network/known_hosts/${profile.id}")
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _browse.value = BrowseUiState(
                    profileId = profile.id,
                    path = target,
                    error = error.localizedMessage ?: error.javaClass.simpleName
                )
                return@launch
            }

            _browse.value = BrowseUiState(
                profileId = profile.id,
                path = target,
                entries = entries.sortedWith(
                    compareByDescending<NetworkEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
            )
        }
    }

    fun browseDlna(
        device: DiscoveredNetworkDevice,
        objectId: String = "0",
        parentId: String? = null,
        startingIndex: Int = 0,
        append: Boolean = false
    ) {
        dlnaBrowseJob?.cancel()
        val previous = _dlnaBrowse.value
        _dlnaBrowse.value = if (append) previous.copy(loadingMore = true, error = null)
            else DlnaBrowseUiState(device = device, objectId = objectId, parentId = parentId, loading = true)
        dlnaBrowseJob = viewModelScope.launch {
            try {
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivity.activeNetwork
                val wifiNetwork = activeNetwork?.takeIf { candidate ->
                    connectivity.getNetworkCapabilities(candidate)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: connectivity.allNetworks.firstOrNull { candidate ->
                    connectivity.getNetworkCapabilities(candidate)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: throw IllegalStateException("اتصل بشبكة Wi-Fi نفسها التي يوجد عليها خادم الوسائط.")
                val page = dlnaClient.browse(device, wifiNetwork, objectId, startingIndex)
                _dlnaBrowse.value = DlnaBrowseUiState(
                    device = device,
                    objectId = objectId,
                    parentId = parentId,
                    entries = if (append) (previous.entries + page.entries).distinctBy { it.id } else page.entries,
                    totalMatches = page.totalMatches,
                    loadingMore = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _dlnaBrowse.value = if (append) {
                    previous.copy(loadingMore = false, error = error.localizedMessage ?: "تعذر تحميل المزيد")
                } else {
                    DlnaBrowseUiState(
                        device = device, objectId = objectId, parentId = parentId,
                        error = error.localizedMessage ?: "تعذر تصفح مكتبة DLNA"
                    )
                }
            }
        }
    }

    fun closeDlnaBrowse() {
        dlnaBrowseJob?.cancel()
        dlnaBrowseJob = null
        _dlnaBrowse.value = DlnaBrowseUiState()
    }

    fun closeBrowse() {
        browseJob?.cancel()
        browseJob = null
        _browse.value = BrowseUiState()
    }

    private fun endpointFor(profile: NetworkProfile): String =
        httpsNetworkEndpoint(profile.host, profile.port)

    companion object {
        /** Marker shown when the protocol has no browse implementation yet. */
        const val PROTOCOL_UNSUPPORTED = "unsupported-protocol"
    }
}

@Composable
fun NetworkScreen(
    onOpenMedia: (String) -> Unit,
    contentPadding: PaddingValues,
    onOpenUltraShare: () -> Unit
) {
    val viewModel: NetworkViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val sourceError by viewModel.sourceError.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val discoveryRunning by viewModel.discoveryRunning.collectAsStateWithLifecycle()
    val discoveryMessage by viewModel.discoveryMessage.collectAsStateWithLifecycle()
    val dlnaBrowse by viewModel.dlnaBrowse.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<DiscoveredNetworkDevice?>(null) }
    LaunchedEffect(viewModel) { viewModel.discoverDevices() }
    val activeProfile = profiles.firstOrNull { it.id == browse.profileId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF191323), MalekPlayColors.Background, Color(0xFF07060C))))
            .padding(contentPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.net_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MalekPlayColors.TextPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onOpenUltraShare,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.ultra_share_title), modifier = Modifier.padding(start = 5.dp), maxLines = 1)
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.net_add),
                    tint = MalekPlayColors.PurpleBright
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

        if (activeProfile == null && dlnaBrowse.device == null) {
            DiscoveryPanel(
                devices = discoveredDevices,
                running = discoveryRunning,
                message = discoveryMessage,
                onRefresh = viewModel::discoverDevices,
                onOpenMediaServer = { device -> viewModel.browseDlna(device) },
                onUseWindowsDevice = { device -> selectedDevice = device; showAddDialog = true }
            )
            SourcesList(
                profiles = profiles,
                onBrowse = { profile -> viewModel.browse(profile, profile.rootPath) },
                onRemove = { profile -> viewModel.removeSource(profile) },
                modifier = Modifier.weight(1f)
            )
        } else if (activeProfile != null) {
            BrowsePanel(
                profile = activeProfile,
                state = browse,
                onNavigate = { path -> viewModel.browse(activeProfile, path) },
                onRefresh = { viewModel.browse(activeProfile, browse.path) },
                onClose = { viewModel.closeBrowse() },
                onOpenMedia = onOpenMedia
            )
        } else {
            DlnaBrowsePanel(
                state = dlnaBrowse,
                onNavigate = { id -> viewModel.browseDlna(requireNotNull(dlnaBrowse.device), id, dlnaBrowse.objectId) },
                onParent = { viewModel.browseDlna(requireNotNull(dlnaBrowse.device), dlnaBrowse.parentId ?: "0") },
                onRefresh = { viewModel.browseDlna(requireNotNull(dlnaBrowse.device), dlnaBrowse.objectId, dlnaBrowse.parentId) },
                onLoadMore = { viewModel.browseDlna(requireNotNull(dlnaBrowse.device), dlnaBrowse.objectId, dlnaBrowse.parentId, dlnaBrowse.entries.size, append = true) },
                onClose = viewModel::closeDlnaBrowse,
                onOpenMedia = onOpenMedia
            )
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            initialHost = selectedDevice?.host,
            initialName = selectedDevice?.name,
            initialProtocol = selectedDevice?.let { if (it.kind == NetworkDeviceKind.WINDOWS_COMPUTER || it.services.any { service -> service.contains("_smb._tcp") }) NetworkProtocol.SMB else null },
            onDismiss = { showAddDialog = false; selectedDevice = null },
            onSave = { protocol, host, port, username, password, share, path, name, knownHostsUri ->
                showAddDialog = false
                selectedDevice = null
                viewModel.saveSource(protocol, host, port, username, password, share, path, name, knownHostsUri)
            }
        )
    }
}

@Composable
private fun DiscoveryPanel(
    devices: List<DiscoveredNetworkDevice>,
    running: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onOpenMediaServer: (DiscoveredNetworkDevice) -> Unit,
    onUseWindowsDevice: (DiscoveredNetworkDevice) -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF302044), Color(0xFF171321), Color(0xFF111019))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x99C084FC), Color(0x22FFFFFF), Color(0x667E22CE))), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Storage, contentDescription = null, tint = MalekPlayColors.PurpleBright, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.net_discovery_title), style = MaterialTheme.typography.titleMedium, color = MalekPlayColors.TextPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(stringResource(R.string.net_discovery_subtitle), style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.TextSecondary)
                Text(stringResource(R.string.net_discovery_privacy), style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onRefresh, enabled = !running, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (running) R.string.net_discovery_searching else R.string.net_discovery_search))
            }
        }
        if (running) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.net_discovery_scanning), style = MaterialTheme.typography.bodySmall, color = MalekPlayColors.TextSecondary)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MalekPlayColors.TextSecondary) }
        if (devices.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(items = devices, key = { it.id }) { device ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.045f))
                        .then(if (device.kind == NetworkDeviceKind.MEDIA_SERVER) Modifier.clickable { onOpenMediaServer(device) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (device.kind) {
                                NetworkDeviceKind.WINDOWS_COMPUTER -> Icons.Filled.Storage
                                NetworkDeviceKind.MEDIA_SERVER, NetworkDeviceKind.MEDIA_RENDERER -> Icons.Filled.Movie
                                NetworkDeviceKind.NETWORK_DEVICE -> Icons.Filled.Folder
                            },
                            contentDescription = null, tint = MalekPlayColors.PurpleSoft, modifier = Modifier.size(25.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium, color = MalekPlayColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(device.host + " • " + (device.services.firstOrNull { it.startsWith("_") } ?: device.services.drop(1).firstOrNull().orEmpty()), style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (device.kind == NetworkDeviceKind.WINDOWS_COMPUTER || device.services.any { it.contains("_smb._tcp") }) {
                            TextButton(onClick = { onUseWindowsDevice(device) }) {
                                Text(stringResource(R.string.net_discovery_configure_smb))
                            }
                        } else when (device.kind) {
                            NetworkDeviceKind.MEDIA_SERVER -> TextButton(onClick = { onOpenMediaServer(device) }) {
                                Text(stringResource(R.string.net_discovery_open))
                            }
                            else -> Text(stringResource(R.string.net_discovery_detected), style = MaterialTheme.typography.labelSmall, color = MalekPlayColors.PurpleSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DlnaBrowsePanel(
    state: DlnaBrowseUiState,
    onNavigate: (String) -> Unit,
    onParent: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    onOpenMedia: (String) -> Unit
) {
    val device = state.device ?: return
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = MalekPlayColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.host + " • DLNA", style = MaterialTheme.typography.bodySmall, color = MalekPlayColors.TextSecondary)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lib_refresh))
            }
        }
        if (state.objectId != "0") {
            TextButton(onClick = onParent, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.net_dlna_parent))
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.entries.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.common_retry)) }
            }
            state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.net_dlna_empty), color = MalekPlayColors.TextSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(items = state.entries, key = { it.id }) { entry ->
                    val entryShape = RoundedCornerShape(17.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .clip(entryShape)
                            .background(Brush.horizontalGradient(listOf(Color(0xFF241A32), Color(0xFF17131F), Color(0xFF111019))))
                            .border(1.dp, Brush.linearGradient(listOf(Color(0x66C084FC), Color(0x18FFFFFF), Color(0x557E22CE))), entryShape)
                            .clickable {
                                if (entry.isContainer) onNavigate(entry.id)
                                else entry.mediaUrl?.let { mediaUrl ->
                                    val mediaUri = Uri.parse(mediaUrl)
                                    val localScheme = if (mediaUri.scheme.equals("https", true)) {
                                        "malekdlna-https"
                                    } else {
                                        "malekdlna-http"
                                    }
                                    onOpenMedia(mediaUri.buildUpon().scheme(localScheme).build().toString())
                                }
                            }
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when {
                                entry.isContainer -> Icons.Filled.Folder
                                entry.mimeType?.startsWith("audio/", ignoreCase = true) == true -> Icons.Filled.MusicNote
                                else -> Icons.Filled.Movie
                            },
                            contentDescription = null,
                            tint = MalekPlayColors.PurpleBright,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, color = MalekPlayColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (entry.isContainer) stringResource(R.string.net_dlna_folder) else entry.mimeType ?: stringResource(R.string.net_dlna_file),
                                color = MalekPlayColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!entry.isContainer && entry.sizeBytes > 0L) {
                            Text(formatBytes(entry.sizeBytes), color = MalekPlayColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                state.error?.takeIf { state.entries.isNotEmpty() }?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
                if (state.entries.size < state.totalMatches) {
                    item {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.net_dlna_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesList(
    profiles: List<NetworkProfile>,
    onBrowse: (NetworkProfile) -> Unit,
    onRemove: (NetworkProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    if (profiles.isEmpty()) {
        val emptyShape = RoundedCornerShape(26.dp)
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp)
                .clip(emptyShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF21192D), Color(0xFF17131F), Color(0xFF100E16))))
                .border(1.dp, Brush.linearGradient(listOf(Color(0x88C084FC), Color(0x22FFFFFF), Color(0x667E22CE))), emptyShape)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MalekPlayColors.PurpleDeep.copy(alpha = 0.28f))
                    .border(1.dp, MalekPlayColors.NeonPurple.copy(alpha = 0.38f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleSoft,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.net_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MalekPlayColors.TextPrimary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = profiles, key = { it.id }) { profile ->
            val rowShape = RoundedCornerShape(18.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(rowShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF241A32), Color(0xFF17131F), Color(0xFF121019))))
                    .border(1.dp, Brush.linearGradient(listOf(Color(0x77C084FC), Color(0x22FFFFFF), Color(0x557E22CE))), rowShape)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MalekPlayColors.PurpleBright,
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
    initialHost: String? = null,
    initialName: String? = null,
    initialProtocol: NetworkProtocol? = null,
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
    var protocolIndex by remember(initialProtocol) { mutableIntStateOf(protocols.indexOf(initialProtocol).takeIf { it >= 0 } ?: 0) }
    var host by remember(initialHost) { mutableStateOf(initialHost.orEmpty()) }
    var port by remember(initialProtocol) { mutableStateOf(when (initialProtocol) { NetworkProtocol.SMB -> "445"; NetworkProtocol.SFTP -> "22"; else -> "443" }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var displayName by remember(initialName) { mutableStateOf(initialName.orEmpty()) }
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
                    Text(
                        text = stringResource(R.string.net_smb_share_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
