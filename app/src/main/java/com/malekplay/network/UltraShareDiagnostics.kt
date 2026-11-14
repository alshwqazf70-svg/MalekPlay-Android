package com.malekplay.network

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** JSONL diagnostics exported to Downloads for Termux on Android 10+. */
object UltraShareDiagnostics {
    private const val TAG = "MalekPlayDiagnostics"
    private const val DIRECTORY = "Download/MalekPlayDiagnostics/"
    private const val FILE_PREFIX = "ultrashare-"
    private const val MAX_SESSIONS = 3
    private const val MAX_DETAIL_CHARS = 2_000
    private const val MAX_STACK_CHARS = 12_000

    internal fun <T> filesToDelete(newestFirst: List<T>): List<T> = newestFirst.drop(MAX_SESSIONS)

    private data class SessionLog(
        val id: String,
        val role: String,
        val key: String,
        val createdAtMillis: Long = System.currentTimeMillis(),
        @Volatile var uri: Uri? = null
    )

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MalekPlay-Diagnostics").apply { isDaemon = true }
    }
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionLog>()
    private val sessionLock = Any()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        beginSession("SERVICE", "process_started", "process")
    }

    /**
     * Creates or returns the diagnostic log for a logical connection.
     * sessionKey must be a non-secret stable key (for example an approved peer host).
     */
    fun beginSession(
        role: String,
        trigger: String = "share_started",
        sessionKey: String? = null
    ): String {
        val safeRole = role.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '_' }.take(16).ifBlank { "UNKNOWN" }
        val key = normalizeSessionKey(sessionKey ?: "session:" + UUID.randomUUID())
        synchronized(sessionLock) {
            sessions[key]?.let { return it.id }
            val session = SessionLog(UUID.randomUUID().toString(), safeRole, key)
            sessions[key] = session
            while (sessions.size > MAX_SESSIONS + 1) {
                sessions.values.filter { it.key != "process" }
                    .minByOrNull { it.createdAtMillis }
                    ?.let { expired ->
                        if (sessions.remove(expired.key, expired)) {
                            append(expired, linkedMapOf(
                                "timestamp" to timestamp(),
                                "sessionId" to expired.id,
                                "role" to expired.role,
                                "stage" to "SESSION",
                                "event" to "EVICTED",
                                "result" to "diagnostic_session_limit"
                            ))
                        }
                    } ?: break
            }
            // The single writer guarantees the file exists before this session's queued events.
            writer.execute {
                runCatching {
                    val context = appContext ?: return@runCatching
                    val uri = createPublicLog(context, safeRole, session.id)
                    session.uri = uri
                    writeLine(
                        context, uri,
                        jsonObject(linkedMapOf(
                            "timestamp" to timestamp(),
                            "sessionId" to session.id,
                            "role" to safeRole,
                            "stage" to "SESSION",
                            "event" to "STARTED",
                            "trigger" to safeValue(trigger)
                        ))
                    )
                    Log.i(TAG, "Diagnostic session started: " + session.id + " (" + safeRole + ")")
                }.onFailure { Log.e(TAG, "Unable to create public diagnostic log", it) }
            }
            return session.id
        }
    }

    fun event(stage: String, name: String, details: Map<String, Any?> = emptyMap()) {
        if (appContext == null) return
        val key = details["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
            ?.let { normalizeSessionKey("id:" + it) }
            ?: details["peerHost"]?.toString()?.takeIf { it.isNotBlank() }
                ?.let { normalizeSessionKey("peer:" + it) }
            ?: details["host"]?.toString()?.takeIf { it.isNotBlank() }
                ?.let { normalizeSessionKey("peer:" + it) }
            ?: "process"
        val session = sessionFor(key, if (key == "process") "SERVICE" else "PEER") ?: return
        val payload = linkedMapOf<String, Any?>(
            "timestamp" to timestamp(),
            "sessionId" to session.id,
            "role" to session.role,
            "stage" to safeValue(stage),
            "event" to safeValue(name)
        )
        details.forEach { (detailKey, value) ->
            if (value != null && !detailKey.equals("sessionId", true)) {
                payload[safeKey(detailKey)] = safeValueForKey(detailKey, value.toString())
            }
        }
        append(session, payload)
        if (name in setOf("V2_PEER_SESSION_CLOSED", "SESSION_CLOSED") ||
            (stage == "SESSION" && name == "ENDED")
        ) {
            endSessionForKey(key, "peer_session_closed")
        }
    }

    fun failure(stage: String, failure: Throwable, contextMessage: String? = null) {
        if (appContext == null) return
        val peer = contextMessage?.let {
            Regex("(?i)(?:peerHost|host)\\s*=\\s*([^;,\\s]+)").find(it)?.groupValues?.getOrNull(1)
        }
        val key = peer?.let { normalizeSessionKey("peer:" + it) } ?: "process"
        val session = sessionFor(key, if (key == "process") "SERVICE" else "PEER") ?: return
        val root = rootCause(failure)
        val frame = sequenceOf(failure, root)
            .flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("com.malekplay.") }
        append(session, linkedMapOf(
            "timestamp" to timestamp(),
            "sessionId" to session.id,
            "role" to session.role,
            "stage" to safeValue(stage),
            "event" to "ERROR",
            "exception" to failure.javaClass.name,
            "rootException" to root.javaClass.name,
            "message" to safeValue(contextMessage ?: failure.message.orEmpty()),
            "rootMessage" to safeValue(root.message.orEmpty()),
            "sourceClass" to frame?.className.orEmpty(),
            "sourceMethod" to frame?.methodName.orEmpty(),
            "sourceLine" to (frame?.lineNumber ?: -1),
            "stackTrace" to safeValue(failure.stackTraceToString(), MAX_STACK_CHARS)
        ))
        Log.e(TAG, "[" + stage + "] " + failure.javaClass.simpleName + ": " + safeValue(failure.message.orEmpty()))
    }

    fun endSession(result: String = "finished") {
        endSessionForKey("process", result)
    }

    fun endPeerSession(peerHost: String, result: String = "finished") {
        endSessionForKey(normalizeSessionKey("peer:" + peerHost), result)
    }

    private fun sessionFor(key: String, role: String): SessionLog? =
        sessions[key] ?: runCatching {
            beginSession(role, "automatic", key)
            sessions[key]
        }.getOrNull()

    private fun endSessionForKey(key: String, result: String) {
        val session = sessions.remove(key) ?: return
        append(session, linkedMapOf(
            "timestamp" to timestamp(),
            "sessionId" to session.id,
            "role" to session.role,
            "stage" to "SESSION",
            "event" to "ENDED",
            "result" to safeValue(result)
        ))
    }

    private fun normalizeSessionKey(value: String): String {
        if (value == "process") return value
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return "key:" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun append(session: SessionLog, payload: Map<String, Any?>) {
        val context = appContext ?: return
        val encoded = jsonObject(payload)
        writer.execute {
            val target = session.uri ?: return@execute
            writeLine(context, target, encoded)
        }
    }

    private fun rootCause(failure: Throwable): Throwable {
        var current = failure
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        while (current.cause != null && visited.add(current)) {
            current = current.cause ?: break
        }
        return current
    }

    internal fun jsonObject(values: Map<String, Any?>): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            jsonString(key) + ":" + jsonValue(value)
        }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Double -> if (value.isFinite()) value.toString() else "null"
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }

    private fun writeLine(context: Context, uri: Uri, payload: String) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wa")?.use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { stream ->
                    stream.write(payload)
                    stream.write("\n")
                    stream.flush()
                }
            } ?: error("Unable to open diagnostic output stream")
        }.onFailure { Log.e(TAG, "Diagnostic event write failed", it) }
    }

    @SuppressLint("NewApi")
    private fun createPublicLog(context: Context, role: String, id: String): Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Public Termux-readable diagnostic export requires Android 10 or newer"
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_PREFIX + stamp + "-" + role + "-" + id.take(8) + ".jsonl")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val created = resolver.insert(collection, values) ?: error("MediaStore refused to create diagnostic file")
        pruneOldSessions(context, collection)
        return created
    }

    private fun pruneOldSessions(context: Context, collection: Uri) {
        val files = mutableListOf<Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
            arrayOf(DIRECTORY, FILE_PREFIX + "%"),
            MediaStore.MediaColumns.DATE_ADDED + " DESC, " + MediaStore.MediaColumns._ID + " DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                files += Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
            }
        }
        filesToDelete(files).forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "Unable to prune old diagnostic file", it) }
        }
    }

    private fun safeKey(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' }.take(48).ifBlank { "detail" }

    private fun safeValueForKey(key: String, value: String): String {
        if (Regex("(?i).*(password|passwd|secret|token|pairing[_ -]?code|session[_ -]?key|authorization|credential).*").matches(key)) {
            return "[REDACTED]"
        }
        return safeValue(value)
    }

    private fun safeValue(value: String, maxChars: Int = MAX_DETAIL_CHARS): String {
        val redacted = value
            .replace(Regex("(?i)(password|passwd|secret|token|pairing[_ -]?code|session[_ -]?key)(\\s*[=:]\\s*)[^\\s,;]+"), "$1$2[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer [REDACTED]")
        return redacted.take(maxChars)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
}