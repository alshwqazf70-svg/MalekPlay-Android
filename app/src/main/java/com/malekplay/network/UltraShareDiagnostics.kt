package com.malekplay.network

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** JSONL diagnostics exported to Downloads for Termux on Android 10+. */
object UltraShareDiagnostics {
    private const val TAG = "MalekPlayDiagnostics"
    private const val DIRECTORY = "Download/MalekPlayDiagnostics/"
    private const val FILE_PREFIX = "ultrashare-"
    private const val MAX_SESSIONS = 3
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MalekPlay-Diagnostics").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var currentUri: Uri? = null
    @Volatile private var currentSessionId: String? = null
    @Volatile private var currentRole = "UNKNOWN"
    private val closed = AtomicBoolean(true)

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun beginSession(role: String, trigger: String = "share_started"): String {
        val safeRole = role.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '_' }.take(16).ifBlank { "UNKNOWN" }
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            closed.set(false)
            currentSessionId = id
            currentRole = safeRole
            currentUri = null
        }
        writer.execute {
            runCatching {
                val context = appContext ?: return@runCatching
                val uri = createPublicLog(context, safeRole, id)
                if (currentSessionId == id) currentUri = uri
                eventNow(context, uri, "SESSION", "STARTED",
                    JSONObject().put("role", safeRole).put("trigger", safeValue(trigger)).put("sessionId", id))
                Log.i(TAG, "Diagnostic session started: " + id + " (" + safeRole + ")")
            }.onFailure { Log.e(TAG, "Unable to create public diagnostic log", it) }
        }
        return id
    }

    fun event(stage: String, name: String, details: Map<String, Any?> = emptyMap()) {
        val id = currentSessionId ?: beginSession(currentRole, "automatic")
        val payload = JSONObject().put("timestamp", timestamp()).put("sessionId", id)
            .put("role", currentRole).put("stage", safeValue(stage)).put("event", safeValue(name))
        details.forEach { (key, value) ->
            if (value != null) payload.put(safeKey(key), safeValue(value.toString()))
        }
        append(payload)
    }

    fun failure(stage: String, failure: Throwable, contextMessage: String? = null) {
        val id = currentSessionId ?: beginSession(currentRole, "automatic_error")
        val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.malekplay.") }
        val payload = JSONObject().put("timestamp", timestamp()).put("sessionId", id)
            .put("role", currentRole).put("stage", safeValue(stage)).put("event", "ERROR")
            .put("exception", failure.javaClass.name)
            .put("message", safeValue(contextMessage ?: failure.message.orEmpty()))
            .put("sourceClass", frame?.className.orEmpty())
            .put("sourceMethod", frame?.methodName.orEmpty())
            .put("sourceLine", frame?.lineNumber ?: -1)
            .put("stackTrace", safeValue(failure.stackTraceToString()).take(12_000))
        append(payload)
        Log.e(TAG, "[" + stage + "] " + failure.javaClass.simpleName + ": " + failure.message, failure)
    }

    fun endSession(result: String = "finished") {
        val id = currentSessionId ?: return
        event("SESSION", "ENDED", mapOf("result" to result))
        synchronized(this) {
            if (currentSessionId == id) {
                currentSessionId = null
                currentUri = null
                closed.set(true)
            }
        }
    }

    private fun append(payload: JSONObject) {
        val context = appContext ?: return
        val uri = currentUri
        writer.execute {
            val target = uri ?: currentUri ?: return@execute
            writeLine(context, target, payload)
        }
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
        val files = mutableListOf<Pair<Uri, Long>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
            arrayOf(DIRECTORY, FILE_PREFIX + "%"),
            MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                files += uri to cursor.getLong(dateColumn)
            }
        }
        files.drop(MAX_SESSIONS).forEach { item ->
            runCatching { context.contentResolver.delete(item.first, null, null) }
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

    private fun safeValue(value: String): String {
        val redacted = value
            .replace(Regex("(?i)(password|passwd|secret|token|pairing[_ -]?code|session[_ -]?key)(\\s*[=:]\\s*)[^\\s,;]+"), "$1$2[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "Bearer [REDACTED]")
        return redacted.take(2_000)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
