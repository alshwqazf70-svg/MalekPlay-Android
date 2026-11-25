package com.malekplay.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads language models on demand and translates SRT, WebVTT, ASS and SSA cues locally.
 * The first model download requires an internet connection; translation thereafter is on-device.
 */
/** Validates a source language against the language models supported by ML Kit. */
internal fun validateTranslationSourceLanguage(
    languageCode: String,
    supportedLanguageCodes: Collection<String>
): String {
    val normalized = languageCode.trim().lowercase(java.util.Locale.ROOT)
    require(normalized.isNotBlank() && normalized in supportedLanguageCodes) {
        "لغة المصدر غير مدعومة في الترجمة المحلية"
    }
    return normalized
}


/**
 * Removes only expired files created by subtitle translation. The seven-day
 * grace period avoids disrupting subtitles that may still be referenced by UI.
 */
internal object TranslatedSubtitleCache {
    private const val PREFIX = "malekplay-ar-"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000

    fun pruneExpired(cacheDir: File, nowMs: Long = System.currentTimeMillis()) {
        val expiry = nowMs - MAX_AGE_MS
        cacheDir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile && file.name.startsWith(PREFIX) &&
                    file.name.endsWith(".srt") && file.lastModified() < expiry
            }
            ?.forEach { file -> runCatching { file.delete() } }
    }
}

class SubtitleTranslationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun translateSrtToArabic(
        source: Uri,
        sourceLanguageCode: String = TranslateLanguage.ENGLISH,
        onProgress: (completedCues: Int, totalCues: Int) -> Unit = { _, _ -> }
    ): Uri {
        val sourceBytes = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(source)
                ?: error("تعذر فتح ملف الترجمة")
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val single = stream.read()
                        if (single < 0) break
                        total += 1
                        require(total <= MAX_SUBTITLE_BYTES) { "حجم ملف الترجمة يجب ألا يتجاوز 5 ميجابايت" }
                        output.write(single)
                        continue
                    }
                    total += count
                    require(total <= MAX_SUBTITLE_BYTES) { "حجم ملف الترجمة يجب ألا يتجاوز 5 ميجابايت" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val sourceText = SrtSubtitleParser.decode(sourceBytes)
        val extension = resolveSubtitleFormat(source, sourceText)
            ?: error("صيغة ملف الترجمة غير معروفة. استخدم SRT أو VTT أو ASS أو SSA")
        val sourceLanguage = validateTranslationSourceLanguage(
            sourceLanguageCode,
            TranslateLanguage.getAllLanguages()
        )
        require(sourceLanguage != TranslateLanguage.ARABIC) {
            "لغة المصدر هي العربية بالفعل"
        }
        val cacheDigest = MessageDigest.getInstance("SHA-256").run {
            update(sourceLanguage.toByteArray(StandardCharsets.UTF_8))
            update(byteArrayOf(0))
            digest(sourceBytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }
        val cacheFile = File(context.cacheDir, "malekplay-ar-$cacheDigest.srt")
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            cacheFile.setLastModified(System.currentTimeMillis())
            onProgress(1, 1)
            return Uri.fromFile(cacheFile)
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator = Translation.getClient(options)
        return try {
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(
                    "تعذر تنزيل نموذج اللغة. يلزم اتصال بالإنترنت لتنزيل نموذج اللغة لأول مرة.",
                    error
                )
            }
            data class CueToTranslate(
                val originalIndex: Int,
                val lines: List<String>,
                val textStart: Int,
                val maskedText: String,
                val markup: List<String>
            )
            val translatedCues = SrtSubtitleParser.splitBlocks(sourceText, extension).toMutableList()
            val pending = translatedCues.mapIndexedNotNull { index, cue ->
                val lines = cue.split('\n')
                val timeIndex = SrtSubtitleParser.timingLineIndex(lines)
                if (timeIndex < 0 || timeIndex + 1 >= lines.size) return@mapIndexedNotNull null
                val textStart = timeIndex + 1
                val subtitleText = lines.drop(textStart).joinToString("\n")
                if (subtitleText.isBlank()) return@mapIndexedNotNull null
                val mergedDialogue = SubtitleTranslationPolicy.mergeWrappedDialogueLines(subtitleText)
                val (maskedText, markup) = SrtSubtitleParser.maskMarkup(mergedDialogue)
                CueToTranslate(index, lines, textStart, maskedText, markup)
            }

            require(pending.isNotEmpty()) { "لم يتم العثور على حوارات قابلة للترجمة في ملف الترجمة" }
            onProgress(0, pending.size)
            var completedCues = 0
            // Translate neighbouring cues together for dialogue context. If ML Kit changes
            // a boundary token, fall back to independent cue translation without losing timing.
            for (chunk in SubtitleTranslationPolicy.chunkCueTexts(pending.map { it.maskedText })) {
                currentCoroutineContext().ensureActive()
                val batch = chunk.map(pending::get)
                val separators = (0 until (batch.size - 1)).map { position ->
                    SubtitleTranslationPolicy.separator(chunk.first(), position)
                }
                val request = buildString {
                    batch.forEachIndexed { index, cue ->
                        if (index > 0) append("\n").append(separators[index - 1]).append("\n")
                        append(cue.maskedText)
                    }
                }
                val batchResult = if (batch.size == 1) {
                    listOf(translator.translate(request).awaitResult())
                } else {
                    val translatedBatch = translator.translate(request).awaitResult()
                    SubtitleTranslationPolicy.splitBatchTranslation(translatedBatch, separators)
                        ?: batch.map { cue ->
                            currentCoroutineContext().ensureActive()
                            translator.translate(cue.maskedText).awaitResult()
                        }
                }
                batch.forEachIndexed { index, cue ->
                    currentCoroutineContext().ensureActive()
                    val restoredText = SrtSubtitleParser.restoreMarkup(batchResult[index], cue.markup)
                    translatedCues[cue.originalIndex] =
                        (cue.lines.take(cue.textStart) + restoredText.split('\n')).joinToString("\n")
                }
                completedCues += batch.size
                onProgress(completedCues.coerceAtMost(pending.size), pending.size)
            }
            val translated = translatedCues.joinToString("\n\n")
            val output = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                TranslatedSubtitleCache.pruneExpired(context.cacheDir)
                val temporary = File.createTempFile("malekplay-ar-", ".part", context.cacheDir)
                try {
                    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                    temporary.writeBytes(utf8Bom + translated.toByteArray(StandardCharsets.UTF_8))
                    // Same-directory rename is atomic on the app filesystem. Do not
                    // delete an existing cache entry first: another playback job may
                    // have completed the identical translation concurrently.
                    if (!temporary.renameTo(cacheFile)) {
                        check(cacheFile.isFile && cacheFile.length() > 0L) {
                            "تعذر حفظ الترجمة في الذاكرة المؤقتة"
                        }
                    }
                    cacheFile
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            Uri.fromFile(output)
        } finally {
            translator.close()
        }
    }


    private fun resolveSubtitleFormat(source: Uri, text: String): String? {
        SrtSubtitleParser.detectFormat(text)?.let { return it }
        val displayName = runCatching {
            context.contentResolver.query(
                source,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        }.getOrNull()
        val namedExtension = displayName.orEmpty()
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in SUPPORTED_FORMATS }
            ?: source.lastPathSegment.orEmpty()
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it in SUPPORTED_FORMATS }
        if (namedExtension != null) return namedExtension

        val mimeExtension = when (runCatching { context.contentResolver.getType(source) }.getOrNull()
            ?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "text/vtt" -> "vtt"
            "application/x-subrip", "application/srt" -> "srt"
            "text/x-ssa", "application/x-ass", "text/x-ass" -> "ass"
            else -> null
        }
        if (mimeExtension != null) return mimeExtension

        val normalized = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            normalized.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            normalized.contains("[Events]", ignoreCase = true) &&
                normalized.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) } -> "ass"
            normalized.lineSequence().any { SrtSubtitleParser.timingLineIndex(listOf(it)) == 0 } -> "srt"
            else -> null
        }
    }

    private companion object {
        val SUPPORTED_FORMATS = setOf("srt", "vtt", "ass", "ssa")
        const val MAX_SUBTITLE_BYTES = 5_000_000
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }
}
