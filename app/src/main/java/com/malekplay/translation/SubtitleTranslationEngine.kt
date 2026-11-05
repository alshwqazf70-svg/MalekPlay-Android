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
class SubtitleTranslationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun translateSrtToArabic(source: Uri): Uri {
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
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
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
