package com.malekplay.ui.network

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.malekplay.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun NetworkSourcesDialog(onDismiss: () -> Unit, onPlayNetworkMedia: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE) }
    val vault = remember(context) { NetworkCredentialVault(context) }
    val webDav = remember { WebDavClient() }
    val smb = remember { SmbClient() }
    val sftp = remember { SftpClient() }
    val scope = rememberCoroutineScope()
    var protocol by remember { mutableStateOf(NetworkProtocol.WEBDAV) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("/") }
    var profiles by remember { mutableStateOf(loadProfiles(prefs.getString("profiles", "[]") ?: "[]")) }
    var active by remember { mutableStateOf<NetworkProfile?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NetworkEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var knownHosts by remember { mutableStateOf<File?>(null) }

    val knownHostsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val target = File(context.filesDir, "network_known_hosts_import")
                val temporary = File(context.filesDir, "network_known_hosts_import.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 1024L * 1024L) { "ملف known_hosts أكبر من الحد المسموح (1 ميجابايت)" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("تعذر فتح ملف known_hosts")
                require(temporary.isFile && temporary.length() > 0L) { "ملف known_hosts فارغ" }
                if (target.exists()) require(target.delete()) { "تعذر استبدال ملف known_hosts السابق" }
                require(temporary.renameTo(target)) { "تعذر حفظ ملف known_hosts المستورد" }
                knownHosts = target
                message = "تم استيراد ملف known_hosts"
            }.onFailure { message = "تعذر استيراد الملف: ${it.localizedMessage}" }
        }
    }

    fun saveProfiles(next: List<NetworkProfile>) {
        profiles = next
        val json = JSONArray().apply {
            next.forEach { p ->
                put(JSONObject().put("id", p.id).put("protocol", p.protocol.name)
                    .put("host", p.host).put("port", p.port).put("username", p.username)
                    .put("rootPath", p.rootPath).put("displayName", p.displayName))
            }
        }
        prefs.edit().putString("profiles", json.toString()).apply()
    }

    fun connect(profile: NetworkProfile, path: String) {
        scope.launch {
            busy = true
            message = "جارٍ الاتصال بالمصدر..."
            try {
                val credentials = vault.load(profile.id) ?: error("بيانات الدخول غير موجودة؛ احذف المصدر وأعد إضافته")
                entries = withContext(Dispatchers.IO) {
                    when (profile.protocol) {
                        NetworkProtocol.WEBDAV -> webDav.list(
                            httpsNetworkEndpoint(profile.host, profile.port),
                            path.ifBlank { profile.rootPath }, credentials.first, credentials.second
                        )
                        NetworkProtocol.SMB -> {
                            val (shareName, relativePath) = SmbPathPolicy.resolve(profile.rootPath, path)
                            smb.list(
                                profile.host,
                                profile.port,
                                shareName,
                                relativePath,
                                credentials.first,
                                credentials.second
                            )
                        }
                        NetworkProtocol.SFTP -> {
                            val trustedHosts = File(context.filesDir, "network/known_hosts/${profile.id}")
                            require(trustedHosts.isFile && trustedHosts.length() > 0L) {
                                "ملف known_hosts غير محفوظ لهذا المصدر؛ احذف المصدر وأعد إضافته بعد استيراد الملف"
                            }
                            sftp.list(profile.host, profile.port, credentials.first,
                                credentials.second, path.ifBlank { profile.rootPath }, trustedHosts)
                        }
                    }
                }
                active = profile
                currentPath = path.ifBlank { profile.rootPath }
                message = "تم الاتصال بالمصدر. اختر ملف WebDAV لتشغيله مباشرة."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "تعذر الاتصال: ${error.localizedMessage ?: "خطأ غير معروف"}"
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مصادر الشبكة") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("WebDAV • SMB • SFTP", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showAdd = !showAdd }) { Text(if (showAdd) "إغلاق الإضافة" else "إضافة مصدر") }
                }
                if (showAdd) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        NetworkProtocol.values().forEach { item ->
                            FilterChip(selected = protocol == item, onClick = {
                                protocol = item
                                port = when (item) { NetworkProtocol.WEBDAV -> "443"; NetworkProtocol.SMB -> "445"; NetworkProtocol.SFTP -> "22" }
                            }, label = { Text(item.name) })
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("اسم المصدر") }, singleLine = true)
                    OutlinedTextField(host, { host = it }, label = { Text("اسم الخادم أو عنوان IP") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("المنفذ") }, singleLine = true)
                    OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(rootPath, { rootPath = it }, label = { Text(if (protocol == NetworkProtocol.SMB) "المشاركة/المجلد (مثال: media/videos)" else "المسار الجذر") }, singleLine = true)
                    if (protocol == NetworkProtocol.SFTP) {
                        TextButton(onClick = { knownHostsPicker.launch(arrayOf("*/*")) }) { Text("استيراد ملف known_hosts") }
                    }
                    Button(enabled = host.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true) && password.isNotEmpty(),
                        onClick = {
                            val id = "net_" + java.util.UUID.randomUUID().toString().replace("-", "")
                            runCatching {
                                val profile = NetworkProfile(id, protocol, normalizeNetworkHost(host), port.toInt(), username.trim(), rootPath.trim().ifBlank { "/" }, name.trim().ifBlank { host.trim() })
                                if (protocol == NetworkProtocol.SFTP) {
                                    val importedHosts = knownHosts ?: error("استورد ملف known_hosts الموثوق أولاً")
                                    require(importedHosts.isFile && importedHosts.length() > 0L) { "ملف known_hosts فارغ" }
                                    val profileHosts = File(context.filesDir, "network/known_hosts/$id")
                                    profileHosts.parentFile?.mkdirs()
                                    importedHosts.copyTo(profileHosts, overwrite = true)
                                }
                                vault.save(id, username, password)
                                saveProfiles(profiles + profile)
                                name = ""; host = ""; username = ""; password = ""; rootPath = "/"
                                knownHosts = null
                                showAdd = false
                                message = "تم حفظ المصدر وبيانات الدخول مشفرة"
                            }.onFailure { message = it.localizedMessage ?: "تعذر حفظ المصدر" }
                        }) { Text("حفظ المصدر") }
                }
                if (profiles.isNotEmpty()) {
                    Text("المصادر المحفوظة", style = MaterialTheme.typography.titleSmall)
                    profiles.forEach { profile ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nameFor(profile), style = MaterialTheme.typography.bodyMedium)
                                Text("${profile.protocol} • ${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = !busy, onClick = { connect(profile, profile.rootPath) }) { Text("اتصال") }
                            TextButton(onClick = {
                                saveProfiles(profiles.filterNot { it.id == profile.id })
                                vault.remove(profile.id)
                                File(context.filesDir, "network/known_hosts/${profile.id}").delete()
                                if (active?.id == profile.id) { active = null; entries = emptyList() }
                            }) { Text("حذف") }
                        }
                    }
                }
                active?.let { profile ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("محتويات ${nameFor(profile)}", style = MaterialTheme.typography.titleSmall)
                        TextButton(enabled = !busy, onClick = {
                            val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                            connect(profile, if (parent.isBlank()) profile.rootPath else parent)
                        }) { Text("رجوع") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(entries) { entry ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (entry.isDirectory) {
                                        val next = when (profile.protocol) {
                                            NetworkProtocol.WEBDAV -> runCatching { java.net.URL(entry.path).path }.getOrDefault(entry.path)
                                            else -> entry.path
                                        }
                                        connect(profile, next)
                                    } else if (profile.protocol == NetworkProtocol.WEBDAV) {
                                        val mediaUri = runCatching { Uri.parse(entry.path) }.getOrNull()
                                        if (mediaUri == null || mediaUri.scheme != "https" ||
                                            !mediaUri.host.equals(profile.host, true) ||
                                            (if (mediaUri.port == -1) 443 else mediaUri.port) != profile.port) {
                                            message = "رابط الملف لا يطابق خادم WebDAV المحفوظ؛ تم رفض تشغيله."
                                        } else {
                                            message = "جارٍ فتح ${entry.name} في المشغل..."
                                            onPlayNetworkMedia(mediaUri, entry.name)
                                        }
                                    } else {
                                        val mediaUri = runCatching {
                                            com.malekplay.player.NetworkPlaybackUriBuilder.build(profile, entry)
                                        }.getOrNull()
                                        if (mediaUri == null) {
                                            message = "تعذر تجهيز مسار تشغيل الشبكة؛ تحقق من المشاركة والمسار."
                                        } else {
                                            message = "جارٍ فتح ${entry.name} في المشغل..."
                                            onPlayNetworkMedia(mediaUri, entry.name)
                                        }
                                    }
                                }
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text((if (entry.isDirectory) "📁 " else "▶ ") + entry.name)
                                    if (!entry.isDirectory && entry.sizeBytes > 0) Text("${entry.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

private fun loadProfiles(raw: String): List<NetworkProfile> = runCatching {
    val array = JSONArray(raw)
    buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(NetworkProfile(o.getString("id"), NetworkProtocol.valueOf(o.getString("protocol")),
                normalizeNetworkHost(o.getString("host")), o.getInt("port"), o.optString("username"), o.optString("rootPath", "/"), o.optString("displayName").ifBlank { o.optString("name", o.getString("host")) } ))
        }
    }
}.getOrDefault(emptyList())

private fun nameFor(profile: NetworkProfile): String =
    profile.displayName
