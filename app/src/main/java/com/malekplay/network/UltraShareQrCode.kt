package com.malekplay.network

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/** QR payloads are versioned and contain only the local endpoint and one-session secret. */
object UltraShareQrCode {
    private const val SCHEMA = "malekplay"
    private const val AUTHORITY = "ultra-share"
    private const val VERSION = "1"
    private const val MAX_DECODE_DIMENSION = 2048

    fun createPayload(host: String, port: Int, pairingSecret: String): String {
        require(port in 1..65535) { "منفذ الاقتران غير صالح." }
        require(UltraShareTransport.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران غير صالح."
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", VERSION)
            .appendQueryParameter("host", host)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("secret", pairingSecret)
            .build()
            .toString()
    }

    data class PairingDetails(
        val host: String,
        val port: Int,
        val pairingSecret: String
    )

    fun parsePayload(payload: String): PairingDetails {
        val uri = Uri.parse(payload)
        require(uri.scheme == SCHEMA && uri.authority == AUTHORITY) {
            "رمز QR ليس رمز اقتران MalekPlay Ultra Share."
        }
        require(uri.getQueryParameter("v") == VERSION) {
            "إصدار رمز الاقتران غير مدعوم."
        }
        val host = requireNotNull(uri.getQueryParameter("host")) { "عنوان الجهاز غير موجود في رمز QR." }
        val port = requireNotNull(uri.getQueryParameter("port")) { "منفذ الجهاز غير موجود في رمز QR." }
            .toIntOrNull() ?: throw IllegalArgumentException("منفذ الاقتران غير صالح.")
        val secret = requireNotNull(uri.getQueryParameter("secret")) { "رمز الاقتران غير موجود في رمز QR." }
        require(UltraShareTransport.isValidPairingSecret(secret)) { "رمز الاقتران في QR غير صالح." }
        require(port == UltraShareTransport.DEFAULT_PORT) { "منفذ MalekPlay Ultra Share غير متوافق." }
        return PairingDetails(host, port, secret)
    }

    fun createBitmap(payload: String, dimension: Int = 640): Bitmap {
        require(dimension in 256..1024) { "أبعاد رمز QR خارج النطاق المسموح." }
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q)
            put(EncodeHintType.MARGIN, 4)
        }
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            dimension,
            dimension,
            hints
        )
        val pixels = IntArray(dimension * dimension)
        for (y in 0 until dimension) {
            for (x in 0 until dimension) {
                pixels[y * dimension + x] =
                    if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
    }

    fun decodeImage(contentResolver: ContentResolver, imageUri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = contentResolver.openInputStream(imageUri)
            ?: error("تعذر فتح صورة رمز QR.")
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "الصورة المحددة غير صالحة." }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("تعذر قراءة صورة رمز QR.")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            }
            return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } finally {
            bitmap.recycle()
        }
    }
}
