package com.malekplay.translation

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * محرك ترجمة الـ SRT الذكي وفائق السرعة عبر ML Kit محلياً على الجهاز
 */
class SubtitleTranslationEngine(private val context: Context) {

    suspend fun translateSrtToArabic(
        source: Uri,
        onProgress: (Int) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        cleanOldTempSubtitles()

        // 1. قراءة الملف مع اكتشاف الترميز الذكي
        val rawBytes = readSourceBytes(source)
        val sourceText = decodeSmartText(rawBytes)

        // 2. إعداد مترجم ML Kit
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val translator = Translation.getClient(options)

        try {
            // تنزيل النموذج إذا لم يكن موجوداً
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()

            // 3. تحليل كتل الترجمة (SRT Blocks)
            val blocks = sourceText.split(Regex("\\r?\\n\\s*\\r?\\n")).filter { it.isNotBlank() }
            if (blocks.isEmpty()) return@withContext source

            // 4. تقسيم الترجمة إلى دفعات متوازية (Parallel Batches) لسرعة خارقة
            val batchSize = 15 // معالجة 15 كتلة في كل دفعة متزامنة
            val chunks = blocks.chunked(batchSize)
            var processedCount = 0

            val translatedBlocks = mutableListOf<String>()

            for (chunk in chunks) {
                val chunkResults = coroutineScope {
                    chunk.map { block ->
                        async {
                            translateSingleBlock(block, translator)
                        }
                    }.awaitAll()
                }
                translatedBlocks.addAll(chunkResults)
                processedCount += chunk.size
                val percent = ((processedCount.toFloat() / blocks.size) * 100).toInt().coerceIn(0, 100)
                onProgress(percent)
            }

            // 5. حفظ الملف المترجم بترميز UTF-8 سليم
            val outputContent = translatedBlocks.joinToString("\n\n")
            val output = File.createTempFile("malekplay-ar-", ".srt", context.cacheDir)
            output.writeText(outputContent, StandardCharsets.UTF_8)
            Uri.fromFile(output)
        } finally {
            translator.close()
        }
    }

    private suspend fun translateSingleBlock(block: String, translator: Translator): String {
        val lines = block.split(Regex("\\r?\\n")).toMutableList()
        val timeIndex = lines.indexOfFirst { it.contains("-->") }

        if (timeIndex >= 0 && timeIndex + 1 < lines.size) {
            val textStart = timeIndex + 1
            val subtitleText = lines.drop(textStart).joinToString(" ").trim()

            if (subtitleText.isNotBlank()) {
                val translated = runCatching {
                    translator.translate(subtitleText).awaitResult()
                }.getOrDefault(subtitleText) // في حال فشل سطر واحد لا يفشل الفيلم بالكامل

                return (lines.take(textStart) + translated).joinToString("\n")
            }
        }
        return block
    }

    private fun readSourceBytes(source: Uri): ByteArray {
        val input: InputStream = context.contentResolver.openInputStream(source)
            ?: error("تعذر فتح ملف الترجمة")
        return input.use { it.readBytes() }
    }

    /**
     * كشف الترميز التلقائي (UTF-8, Windows-1256, UTF-16) لمنع تلف الحروف
     */
    private fun decodeSmartText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val utf8Text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        if (!utf8Text.contains("")) {
            return utf8Text
        }
        // تجربة ترميز اللغة العربية القديم Windows-1256
        return runCatching {
            String(bytes, Charset.forName("windows-1256"))
        }.getOrDefault(utf8Text)
    }

    /**
     * تنظيف ملفات الترجمة المؤقتة القديمة للحفاظ على ذاكرة الهاتف
     */
    private fun cleanOldTempSubtitles() {
        runCatching {
            val cacheFiles = context.cacheDir.listFiles { _, name -> name.startsWith("malekplay-ar-") && name.endsWith(".srt") }
            val now = System.currentTimeMillis()
            cacheFiles?.forEach { file ->
                if (now - file.lastModified() > 24 * 60 * 60 * 1000L) { // أقدم من 24 ساعة
                    file.delete()
                }
            }
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
