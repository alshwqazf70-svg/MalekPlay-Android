package com.malekplay.translation

import androidx.annotation.WorkerThread
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToLong

/**
 * Offline Vosk adapter for decoded 16 kHz, mono, signed 16-bit little-endian PCM.
 *
 * Media containers must be decoded/resampled before calling this adapter. Keeping that
 * boundary explicit prevents accidentally feeding compressed AAC/MP3 bytes to the recognizer.
 * The caller owns the InputStream and must invoke this method on a worker thread.
 */
class VoskOfflineTranscriber {

    @WorkerThread
    fun transcribePcm16kMono(
        modelDirectory: File,
        pcmInput: InputStream
    ): String {
        require(modelDirectory.isDirectory) { "نموذج التعرف الصوتي غير مثبت" }
        require(File(modelDirectory, "am/final.mdl").isFile) {
            "ملفات النموذج الصوتي ناقصة أو تالفة"
        }

        val model = Model(modelDirectory.absolutePath)
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
            try {
                recognizer.setWords(true)
                val words = mutableListOf<RecognizedWord>()
                val buffer = ByteArray(BUFFER_BYTES)
                var carry = 0

                while (true) {
                    var read = pcmInput.read(buffer, carry, buffer.size - carry)
                    if (read < 0) break
                    if (read == 0) {
                        val single = pcmInput.read()
                        if (single < 0) break
                        buffer[carry] = single.toByte()
                        read = 1
                    }

                    val available = carry + read
                    val alignedBytes = available and -2
                    if (alignedBytes > 0 && recognizer.acceptWaveForm(buffer, alignedBytes)) {
                        appendWords(recognizer.result, words)
                    }

                    carry = available - alignedBytes
                    if (carry == 1) buffer[0] = buffer[alignedBytes]
                }

                if (carry != 0) {
                    throw IOException("تدفق الصوت يحتوي على عينة PCM غير مكتملة")
                }
                appendWords(recognizer.finalResult, words)
                return SrtSubtitleWriter.fromWords(words)
            } finally {
                recognizer.close()
            }
        } finally {
            model.close()
        }
    }

    private fun appendWords(json: String, destination: MutableList<RecognizedWord>) {
        val result = JSONObject(json).optJSONArray("result") ?: return
        for (index in 0 until result.length()) {
            val item = result.optJSONObject(index) ?: continue
            val text = item.optString("word").trim()
            val startSeconds = item.optDouble("start", Double.NaN)
            val endSeconds = item.optDouble("end", Double.NaN)
            if (text.isEmpty() || !startSeconds.isFinite() || !endSeconds.isFinite()) continue
            if (startSeconds < 0.0 || endSeconds <= startSeconds) continue
            val startMs = (startSeconds * 1_000.0).roundToLong()
            val endMs = (endSeconds * 1_000.0).roundToLong()
            if (endMs > startMs) destination += RecognizedWord(text, startMs, endMs)
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BUFFER_BYTES = 8_192
    }
}
