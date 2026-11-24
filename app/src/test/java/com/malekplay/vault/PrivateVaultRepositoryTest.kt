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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
            assertEquals(source.size.toLong(), entry.sizeBytes)
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
    @Test
    fun interruptedImportLeavesNoVisibleEntryOrPartialPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "interrupted-import-password".toCharArray()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val brokenInput = object : java.io.InputStream() {
                private var delivered = 0
                override fun read(): Int {
                    if (delivered >= 8192) throw java.io.IOException("simulated source interruption")
                    delivered++
                    return 0x5A
                }
            }

            assertThrows(Exception::class.java) {
                repository.importStream("interrupted.bin", "application/octet-stream", brokenInput, password)
            }
            assertTrue(repository.list(password).isEmpty())
            assertTrue(vaultDirectory.listFiles().orEmpty().none {
                it.name.endsWith(".tmp") || it.name.endsWith(".pending") || it.name.endsWith(".mkv")
            })
        } finally {
            password.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }

    @Test
    fun recoveryRestoresInterruptedDeleteAndRemovesUncommittedImportArtifacts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "transaction-recovery-password".toCharArray()
        val orphanId = UUID.randomUUID().toString()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            val entry = repository.importStream(
                "keep.bin", "application/octet-stream",
                ByteArrayInputStream(byteArrayOf(1, 3, 3, 7)), password
            )
            val committedPayload = File(vaultDirectory, "${entry.id}.mkv")
            assertTrue(committedPayload.renameTo(File(vaultDirectory, "${entry.id}.delete")))

            File(vaultDirectory, "${orphanId}.pending").writeBytes(byteArrayOf(1))
            File(vaultDirectory, "${orphanId}.tmp").writeBytes(byteArrayOf(2))
            File(vaultDirectory, "${orphanId}.mkv").writeBytes(byteArrayOf(3))

            val recovered = PrivateVaultRepository(context)
            assertEquals(entry.id, recovered.list(password).single().id)
            assertTrue(committedPayload.isFile)
            assertTrue(!File(vaultDirectory, "${entry.id}.delete").exists())
            assertTrue(!File(vaultDirectory, "${orphanId}.pending").exists())
            assertTrue(!File(vaultDirectory, "${orphanId}.tmp").exists())
            assertTrue(!File(vaultDirectory, "${orphanId}.mkv").exists())
        } finally {
            password.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }

    @Test
    fun damagedLegacyPayloadDoesNotBlockOpeningOtherVaultEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "damaged-legacy-password".toCharArray()
        val id = UUID.randomUUID().toString()

        try {
            val repository = PrivateVaultRepository(context)
            repository.create(password)
            File(vaultDirectory, "$id.mkv").writeBytes(byteArrayOf(9, 8, 7, 6))
            val legacyIndex = ByteArrayOutputStream()
            DataOutputStream(legacyIndex).use { output ->
                output.writeInt(0x4D4B5649)
                output.writeInt(1)
                output.writeUTF(id)
                output.writeUTF("damaged.bin")
                output.writeUTF("application/octet-stream")
                output.writeLong(4L)
            }
            FileOutputStream(File(vaultDirectory, "vault.index.enc")).use { output ->
                PrivateVaultCrypto.encrypt(ByteArrayInputStream(legacyIndex.toByteArray()), output, password)
            }

            val entries = PrivateVaultRepository(context).list(password)
            assertEquals(1, entries.size)
            assertEquals(-1L, entries.single().sizeBytes)
        } finally {
            password.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacyCiphertextSizeToOriginalPlaintextSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDirectory = File(context.filesDir, "private_vault")
        vaultDirectory.deleteRecursively()
        val password = "legacy-migration-password".toCharArray()
        val original = ByteArray(91_337) { (it * 13).toByte() }
        val id = UUID.randomUUID().toString()

        try {
            val repository = PrivateVaultRepository(context)
            val payload = File(vaultDirectory, "$id.mkv")
            FileOutputStream(payload).use { encrypted ->
                PrivateVaultCrypto.encrypt(ByteArrayInputStream(original), encrypted, password)
            }

            val legacyIndex = ByteArrayOutputStream()
            DataOutputStream(legacyIndex).use { output ->
                output.writeInt(0x4D4B5649) // Previous index format
                output.writeInt(1)
                output.writeUTF(id)
                output.writeUTF("legacy.bin")
                output.writeUTF("application/octet-stream")
                output.writeLong(payload.length()) // Previous builds stored ciphertext length
            }
            val encryptedIndex = File(vaultDirectory, "vault.index.enc")
            FileOutputStream(encryptedIndex).use { output ->
                PrivateVaultCrypto.encrypt(ByteArrayInputStream(legacyIndex.toByteArray()), output, password)
            }

            val migrated = repository.list(password).single()
            assertEquals(original.size.toLong(), migrated.sizeBytes)

            val reopened = PrivateVaultRepository(context).list(password).single()
            assertEquals(original.size.toLong(), reopened.sizeBytes)
        } finally {
            password.fill('\u0000')
            vaultDirectory.deleteRecursively()
        }
    }

}
