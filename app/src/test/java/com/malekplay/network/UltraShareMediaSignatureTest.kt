package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UltraShareMediaSignatureTest {
    @Test fun recognizesMp4ContainerAndRejectsRenamedExecutable() {
        val mp4 = File.createTempFile("ultrashare", ".mp4")
        val fake = File.createTempFile("ultrashare", ".mp4")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(8))
            fake.writeBytes("MZ-not-a-video".toByteArray())
            assertTrue(UltraShareMediaSignature.isValid(mp4, "clip.mp4", "video/mp4"))
            assertFalse(UltraShareMediaSignature.isValid(fake, "clip.mp4", "video/mp4"))
        } finally {
            mp4.delete()
            fake.delete()
        }
    }

    @Test fun acceptsNonMediaDocumentsArchivesAndUnknownBinaryByDigestPolicy() {
        val cases = listOf("archive.zip" to "application/zip", "notes.txt" to "text/plain",
            "unknown.bin" to "application/octet-stream",
            "report.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val file = File.createTempFile("ultrashare-general", ".tmp")
        try {
            file.writeBytes("opaque but integrity-checked bytes".toByteArray())
            cases.forEach { (name, mime) -> assertTrue("$name should be accepted",
                UltraShareMediaSignature.isValid(file, name, mime)) }
            assertFalse(UltraShareMediaSignature.isValid(file, "image.svg", "image/svg+xml"))
            assertFalse(UltraShareMediaSignature.isValid(file, "drawing.svg", "image/png"))
            assertFalse(UltraShareMediaSignature.isValid(file, "drawing.png", "image/svg+xml"))
            assertFalse(UltraShareMediaSignature.isValid(file, "blocked.pdf", "application/pdf"))
            assertFalse(UltraShareMediaSignature.isValid(file, "blocked.apk", "application/octet-stream"))
        } finally { file.delete() }
    }



    @Test fun blocksPdfAndAndroidPackageArchivesEvenWhenRenamedAsUnknownBinary() {
        val disguisedPdf = File.createTempFile("ultrashare-disguised", ".bin")
        val disguisedApk = File.createTempFile("ultrashare-disguised", ".bin")
        val ordinaryZip = File.createTempFile("ultrashare-ordinary", ".zip")
        fun writeZip(file: File, vararg entries: String) {
            ZipOutputStream(file.outputStream()).use { output ->
                entries.forEach { name ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(byteArrayOf(1, 2, 3))
                    output.closeEntry()
                }
            }
        }
        try {
            disguisedPdf.writeBytes("%PDF-1.7\\nopaque".toByteArray(Charsets.US_ASCII))
            writeZip(disguisedApk, "AndroidManifest.xml", "classes.dex")
            writeZip(ordinaryZip, "readme.txt", "folder/data.bin")

            assertFalse(UltraShareMediaSignature.isValid(disguisedPdf, "unknown.bin", "application/octet-stream"))
            assertFalse(UltraShareMediaSignature.isValid(disguisedApk, "unknown.bin", "application/octet-stream"))
            assertTrue(UltraShareMediaSignature.isValid(ordinaryZip, "archive.zip", "application/zip"))
        } finally {
            disguisedPdf.delete()
            disguisedApk.delete()
            ordinaryZip.delete()
        }
    }

    @Test fun mediaMimeValidationIsIndependentOfDeviceLocale() {
        val previousLocale = java.util.Locale.getDefault()
        val mp4 = File.createTempFile("ultrashare-locale", ".tmp")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(8))
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertTrue(UltraShareMediaSignature.isValid(mp4, "clip.mp4", "video/mp4"))
            assertTrue(UltraShareMediaSignature.isValid(mp4, "clip.mp4", "application/octet-stream"))
        } finally {
            java.util.Locale.setDefault(previousLocale)
            mp4.delete()
        }
    }

    @Test fun rejectsUnknownAndEmptySignatures() {
        val file = File.createTempFile("ultrashare", ".mp4")
        try {
            file.writeBytes(ByteArray(0))
            assertFalse(UltraShareMediaSignature.isValid(file, "clip.mp4", "video/mp4"))
            assertFalse(UltraShareMediaSignature.isValid(file, "clip.unknown", "video/mp4"))
        } finally {
            file.delete()
        }
    }

    @Test fun infersKnownMediaContainerWhenDocumentProviderOmitsExtension() {
        val mp4 = File.createTempFile("ultrashare-no-extension", ".tmp")
        val mp3 = File.createTempFile("ultrashare-no-extension", ".tmp")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(8))
            mp3.writeBytes("ID3".toByteArray() + ByteArray(16))
            assertTrue(UltraShareMediaSignature.isValid(mp4, "clip", "video/mp4"))
            assertTrue(UltraShareMediaSignature.isValid(mp3, "track", "audio/mpeg"))
        } finally {
            mp4.delete()
            mp3.delete()
        }
    }

    @Test fun rejectsManifestAbove100GiBTotalBatchLimit() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))
        val first = UltraShareBatchProtocol.newEntry("a.mp4", "video/mp4",
            60L * 1024 * 1024 * 1024, digest)
        val second = UltraShareBatchProtocol.newEntry("b.mp4", "video/mp4",
            41L * 1024 * 1024 * 1024, digest)
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(first, second))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchProtocol.validate(manifest)
        }
    }
}
