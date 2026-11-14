package com.malekplay.network

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        @Volatile var uri: Uri? = null
    )

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MalekPlay-Diagnostics").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<String, SessionLog>()
    @Volatile private var appContext: Context? = null
    @Volatile private var currentSession: SessionLog? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun beginSession(role: String, trigger: String = "share_started"): String {
        val safeRole = role.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '_' }.take(16).ifBlank { "UNKNOWN" }
        val session = SessionLog(UUID.randomUUID().toString(), safeRole)
        sessions[session.id] = session
        currentSession = session

        // The executor serializes creation before every event appended for this session.
        writer.execute {
            runCatching {
                val context = appContext ?: return@runCatching
                val uri = createPublicLog(context, safeRole, session.id)
                session.uri = uri
                eventNow(
                    context, uri, "SESSION", "STARTED",
                    JSONObject().put("role", safeRole)
                        .put("trigger", safeValue(trigger))
                        .put("sessionId", session.id)
                )
                Log.i(TAG, "Diagnostic session started: " + session.id + " (" + safeRole + ")")
            }.onFailure { Log.e(TAG, "Unable to create public diagnostic log", it) }
        }
        return session.id
    }

    fun event(stage: String, name: String, details: Map<String, Any?> = emptyMap()) {
        // Android's org.json implementation is a stub in plain JVM unit tests.
        // Diagnostics must never become the reason a protocol test fails.
        if (appContext == null) return
        val session = currentSession ?: sessions[beginSession("UNKNOWN", "automatic")]
            ?: return
        val payload = JSONObject()
            .put("timestamp", timestamp())
            .put("sessionId", session.id)
            .put("role", session.role)
            .put("stage", safeValue(stage))
            .put("event", safeValue(name))
        details.forEach { (key, value) ->
            if (value != null) payload.put(safeKey(key), safeValueForKey(key, value.toString()))
        }
        append(session, payload)
    }

    fun failure(stage: String, failure: Throwable, contextMessage: String? = null) {
        if (appContext == null) return
        val session = currentSession ?: sessions[beginSession("UNKNOWN", "automatic_error")]
            ?: return
        val root = rootCause(failure)
        val frame = sequenceOf(failure, root)
            .flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("com.malekplay.") }
        val payload = JSONObject()
            .put("timestamp", timestamp())
            .put("sessionId", session.id)
            .put("role", session.role)
            .put("stage", safeValue(stage))
            .put("event", "ERROR")
            .put("exception", failure.javaClass.name)
            .put("rootException", root.javaClass.name)
            .put("message", safeValue(contextMessage ?: failure.message.orEmpty()))
            .put("rootMessage", safeValue(root.message.orEmpty()))
            .put("sourceClass", frame?.className.orEmpty())
            .put("sourceMethod", frame?.methodName.orEmpty())
            .put("sourceLine", frame?.lineNumber ?: -1)
            .put("stackTrace", safeValue(failure.stackTraceToString(), MAX_STACK_CHARS))
        append(session, payload)
        Log.e(TAG, "[" + stage + "] " + failure.javaClass.simpleName + ": " + failure.message, failure)
    }

    fun endSession(result: String = "finished") {
        val session = currentSession ?: return
        if (appContext != null) {
            val payload = JSONObject()
                .put("timestamp", timestamp())
                .put("sessionId", session.id)
                .put("role", session.role)
                .put("stage", "SESSION")
                .put("event", "ENDED")
                .put("result", safeValue(result))
            append(session, payload)
        }
        if (currentSession === session) currentSession = null
    }

    private fun append(session: SessionLog, payload: JSONObject) {
        val context = appContext ?: return
        writer.execute {
            val target = session.uri ?: return@execute
            writeLine(context, target, payload)
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

    private fun writeLine(context: Context, uri: Uri, payload: JSONObject) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wa")?.use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { stream ->
                    stream.write(payload.toString())
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

    private fun eventNow(context: Context, uri: Uri, stage: String, name: String, details: JSONObject) {
        val payload = JSONObject().put("timestamp", timestamp()).put("stage", stage).put("event", name)
        val keys = details.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            payload.put(key, details.opt(key))
        }
        writeLine(context, uri, payload)
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