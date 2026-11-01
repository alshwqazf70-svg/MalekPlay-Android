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
import com.malekplay.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun NetworkSourcesDialog(onDismiss: () -> Unit) {
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
                val target = File(context.filesDir, "network_known_hosts")
                context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use(input::copyTo) }
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
                    .put("rootPath", p.rootPath).put("name", nameFor(p)))
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
                            "https://${profile.host}:${profile.port}",
                            path.ifBlank { profile.rootPath }, credentials.first, credentials.second
                        )
                        NetworkProtocol.SMB -> {
                            val parts = profile.rootPath.trim('/').split('/', limit = 2)
                            require(parts.firstOrNull().orEmpty().isNotBlank()) { "أدخل اسم مشاركة SMB في المسار، مثل media/" }
                            smb.list(profile.host, parts[0], path.ifBlank { parts.getOrNull(1).orEmpty() },
                                credentials.first, credentials.second)
                        }
                        NetworkProtocol.SFTP -> sftp.list(profile.host, profile.port, credentials.first,
                            credentials.second, path.ifBlank { profile.rootPath }, knownHosts
                                ?: File(context.filesDir, "network_known_hosts").takeIf { it.isFile }
                                ?: error("استورد ملف known_hosts أولاً"))
                    }
                }
                active = profile
                currentPath = path.ifBlank { profile.rootPath }
                message = "تم الاتصال. التصفح متاح؛ تشغيل ملفات الشبكة داخل المشغل لم يكتمل بعد."
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
                    OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true)
                    OutlinedTextField(rootPath, { rootPath = it }, label = { Text(if (protocol == NetworkProtocol.SMB) "المشاركة/المجلد (مثال: media/videos)" else "المسار الجذر") }, singleLine = true)
                    if (protocol == NetworkProtocol.SFTP) {
                        TextButton(onClick = { knownHostsPicker.launch(arrayOf("*/*")) }) { Text("استيراد ملف known_hosts") }
                    }
                    Button(enabled = host.isNotBlank() && port.toIntOrNull() in 1..65535 && password.isNotEmpty(),
                        onClick = {
                            val id = "net_" + java.util.UUID.randomUUID().toString().replace("-", "")
                            runCatching {
                                val profile = NetworkProfile(id, protocol, host.trim(), port.toInt(), username.trim(), rootPath.trim().ifBlank { "/" })
                                vault.save(id, username, password)
                                saveProfiles(profiles + profile)
                                name = ""; host = ""; username = ""; password = ""; rootPath = "/"
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
                                    } else {
                                        message = "تم العثور على الملف، لكن تشغيل SMB/SFTP/WebDAV من داخل المشغل يحتاج موصل بث آمن مخصصاً؛ لم يتم تفعيل تشغيل غير مكتمل."
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
                o.getString("host"), o.getInt("port"), o.optString("username"), o.optString("rootPath", "/")))
        }
    }
}.getOrDefault(emptyList())

private fun nameFor(profile: NetworkProfile): String =
    profile.host + " (" + profile.protocol.name + ")"
