package com.malekplay.translation

import android.content.Context
import android.net.Uri
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads language models on demand and translates SRT subtitle cues locally.
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
        sourceLanguageCode: String = TranslateLanguage.ENGLISH
    ): Uri {
        val extension = source.lastPathSegment.orEmpty()
            .substringAfterLast('.', "").lowercase()
        require(extension == "srt" || extension == "vtt") {
            "الترجمة المحلية تدعم ملفات SRT وVTT فقط حالياً"
        }
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
        val sourceLanguage = validateTranslationSourceLanguage(
            sourceLanguageCode,
            TranslateLanguage.getAllLanguages()
        )
        require(sourceLanguage != TranslateLanguage.ARABIC) {
            "لغة المصدر هي العربية بالفعل"
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            val translatedCues = mutableListOf<String>()
            for (cue in SrtSubtitleParser.splitBlocks(sourceText)) {
                currentCoroutineContext().ensureActive()
                val lines = cue.split('\n')
                val timeIndex = SrtSubtitleParser.timingLineIndex(lines)
                if (timeIndex < 0 || timeIndex + 1 >= lines.size) {
                    translatedCues += cue
                    continue
                }
                val textStart = timeIndex + 1
                val subtitleText = lines.drop(textStart).joinToString("\n")
                if (subtitleText.isBlank()) {
                    translatedCues += cue
                    continue
                }
                val (maskedText, markup) = SrtSubtitleParser.maskMarkup(subtitleText)
                val translatedText = translator.translate(maskedText).awaitResult()
                currentCoroutineContext().ensureActive()
                val restoredText = SrtSubtitleParser.restoreMarkup(translatedText, markup)
                translatedCues += (lines.take(textStart) + restoredText.split('\n')).joinToString("\n")
            }
            val translated = translatedCues.joinToString("\n\n")
            val output = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                TranslatedSubtitleCache.pruneExpired(context.cacheDir)
                File.createTempFile("malekplay-ar-", ".srt", context.cacheDir).also {
                    it.writeText(translated, StandardCharsets.UTF_8)
                }
            }
            Uri.fromFile(output)
        } finally {
            translator.close()
        }
    }

    private companion object {
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
