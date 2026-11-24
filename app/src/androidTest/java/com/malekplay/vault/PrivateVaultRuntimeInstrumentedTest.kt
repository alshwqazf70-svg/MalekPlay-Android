package com.malekplay.vault

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PrivateVaultRuntimeInstrumentedTest {

    @Test
    fun encryptedImageImportPreviewAndRestoreRoundTripOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "private_vault")
        directory.deleteRecursively()
        val password = "device-vault-runtime-test".toCharArray()
        val restored = File(context.cacheDir, "vault-runtime-restored.png")
        var preview: File? = null

        try {
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val encoded = java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()

            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val entry = repository.importStream("sample.png", "image/png", ByteArrayInputStream(encoded), password)
            assertEquals(encoded.size.toLong(), entry.sizeBytes)

            preview = repository.createImagePreviewFile(entry, password)
            assertNotNull("Image preview should decrypt from the vault", preview)
            assertArrayEquals(encoded, requireNotNull(preview).readBytes())

            repository.exportToFile(entry.id, restored, password)
            assertArrayEquals(encoded, restored.readBytes())
            assertEquals(entry.id, repository.list(password).single().id)
        } finally {
            preview?.delete()
            restored.delete()
            password.fill('\\u0000')
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedImportAndInterruptedDeleteAreRecoveredWithoutLosingCommittedPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "private_vault")
        directory.deleteRecursively()
        val password = "device-vault-recovery-test".toCharArray()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val interrupted = object : java.io.InputStream() {
                private var count = 0
                override fun read(): Int {
                    if (count >= 4096) throw IOException("simulated device-side interruption")
                    count++
                    return 0x33
                }
            }
            try {
                repository.importStream("interrupted.bin", "application/octet-stream", interrupted, password)
                throw AssertionError("Interrupted import unexpectedly succeeded")
            } catch (_: IOException) {
                // Expected: no index entry or partial payload may be committed.
            }
            assertTrue(repository.list(password).isEmpty())

            val entry = repository.importStream(
                "committed.bin", "application/octet-stream",
                ByteArrayInputStream(byteArrayOf(5, 4, 3, 2, 1)), password
            )
            val payload = File(directory, "${entry.id}.mkv")
            assertTrue(payload.renameTo(File(directory, "${entry.id}.delete")))

            val recovered = PrivateVaultRepository(context)
            assertEquals(entry.id, recovered.list(password).single().id)
            assertTrue(payload.isFile)
        } finally {
            password.fill('\\u0000')
            directory.deleteRecursively()
        }
    }
}
