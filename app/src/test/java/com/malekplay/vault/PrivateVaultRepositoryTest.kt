package com.malekplay.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PrivateVaultRepositoryTest {

    @Test
    fun importListExportAndDeleteRoundTripPreservesOriginalBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "vault-password-2026".toCharArray()
        val source = ByteArray(256 * 1024 + 37) { ((it * 17) xor (it ushr 5)).toByte() }
        val restored = File(context.cacheDir, "vault-test-${System.nanoTime()}.bin")

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val entry = repository.importStream(
                "sample.bin",
                "application/octet-stream",
                ByteArrayInputStream(source),
                password
            )

            assertEquals(entry.id, repository.list(password).single().id)
            repository.exportToFile(entry.id, restored, password)
            assertArrayEquals(source, restored.readBytes())

            repository.delete(entry.id, password)
            assertTrue(repository.list(password).isEmpty())
            assertTrue(!File(vaultDirectory, "${entry.id}.mkv").exists())
        } finally {
            password.fill('\u0000')
            restored.delete()
            vaultDirectory.deleteRecursively()
        }
    }

    @Test
    fun wrongPasswordCannotListOrExportVaultContents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "correct-password".toCharArray()
        val wrongPassword = "incorrect-password".toCharArray()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val entry = repository.importStream(
                "secret.txt",
                "text/plain",
                ByteArrayInputStream("private contents".toByteArray()),
                password
            )

            assertThrows(Exception::class.java) { repository.list(wrongPassword) }
            assertThrows(Exception::class.java) {
                repository.exportToFile(entry.id, File(context.cacheDir, "wrong-password.txt"), wrongPassword)
            }
        } finally {
            password.fill('\u0000')
            wrongPassword.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }

    @Test
    fun missingCurrentIndexRestoresLastCommittedBackupOnRepositoryStartup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "backup-recovery-password".toCharArray()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val entry = repository.importStream(
                "recovery.dat",
                "application/octet-stream",
                ByteArrayInputStream(byteArrayOf(4, 8, 15, 16, 23, 42)),
                password
            )
            val index = File(vaultDirectory, "vault.index.enc")
            val backup = File(vaultDirectory, "vault.index.bak")
            assertTrue(index.renameTo(backup))

            val recoveredRepository = PrivateVaultRepository(context)
            assertEquals(entry.id, recoveredRepository.list(password).single().id)
        } finally {
            password.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }
}
