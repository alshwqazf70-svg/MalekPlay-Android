package com.malekplay.translation

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads language models on demand and translates SRT subtitle cues locally.
 * The first model download requires an internet connection; translation thereafter is on-device.
 */
class SubtitleTranslationEngine(private val context: Context) {
    suspend fun translateSrtToArabic(source: Uri): Uri {
        val input = context.contentResolver.openInputStream(source)
            ?: error("تعذر فتح ملف الترجمة")
        val sourceBytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= 5_000_000) { "حجم ملف الترجمة يجب ألا يتجاوز 5 ميجابايت" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val sourceText = SrtSubtitleParser.decode(sourceBytes)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            val translated = SrtSubtitleParser.splitBlocks(sourceText)
                .map { block ->
                    val lines = block.split('\\n')
                    val timeIndex = SrtSubtitleParser.timingLineIndex(lines)
                    if (timeIndex < 0 || timeIndex + 1 >= lines.size) {
                        block
                    } else {
                        val textStart = timeIndex + 1
                        val subtitleText = lines.drop(textStart).joinToString("\\n")
                        if (subtitleText.isBlank()) {
                            block
                        } else {
                            val (maskedText, markup) = SrtSubtitleParser.maskMarkup(subtitleText)
                            val translatedText = translator.translate(maskedText).awaitResult()
                            val restoredText = SrtSubtitleParser.restoreMarkup(translatedText, markup)
                            (lines.take(textStart) + restoredText.split('\\n')).joinToString("\\n")
                        }
                    }
                }
                .joinToString("\\n\\n")
            val output = File.createTempFile("malekplay-ar-", ".srt", context.cacheDir)
            output.writeText(translated, StandardCharsets.UTF_8)
            Uri.fromFile(output)
        } finally {
            translator.close()
        }
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
