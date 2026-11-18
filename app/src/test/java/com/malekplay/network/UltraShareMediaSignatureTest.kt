package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

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

    @Test fun rejectsManifestAboveTotalBatchLimit() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))
        val first = UltraShareBatchProtocol.newEntry("a.mp4", "video/mp4",
            11L * 1024 * 1024 * 1024, digest)
        val second = UltraShareBatchProtocol.newEntry("b.mp4", "video/mp4",
            10L * 1024 * 1024 * 1024, digest)
        val manifest = UltraShareBatchProtocol.Manifest(UUID.randomUUID().toString(), listOf(first, second))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            UltraShareBatchProtocol.validate(manifest)
        }
    }
}
