package com.malekplay.vault

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
            password.fill('\u0000')
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
            } catch (_: Exception) {
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
            password.fill('\u0000')
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifyIntegrityPendingMoveRollbackAndFailedRestorePreserveDataOnDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "private_vault")
        directory.deleteRecursively()
        val password = "device-vault-move-safety".toCharArray()

        try {
            val repository = PrivateVaultRepository(context)
            val treeSupport = PrivateVaultTreeSupport(context, repository)
            val session = repository.create(password)
            try {
                val videoBytes = ByteArray(32 * 1024) { (it * 17).toByte() }
                val stored = repository.importStream(
                    displayName = "device_clip.mp4",
                    mimeType = "video/mp4",
                    input = ByteArrayInputStream(videoBytes),
                    session = session,
                    sourceOrigin = VaultSourceOrigin(
                        treeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
                        parentDocumentId = "WhatsApp/Media/WhatsApp Video",
                        displayPath = "WhatsApp/Media/WhatsApp Video/device_clip.mp4"
                    )
                )
                assertTrue(repository.verifyEncryptedEntryIntegrity(stored.id, session))

                // Pending move persistence and rollback leave no orphan copy
                val pendingUri = "content://media/external/video/media/999001"
                repository.addPendingMoves(
                    listOf(PendingVaultMove(stored.id, pendingUri, "device_clip.mp4")),
                    session
                )
                assertEquals(1, repository.loadPendingMoves(session).size)

                val rollbackOutcome = treeSupport.rollbackPendingMove(
                    entryId = stored.id,
                    sourceUri = pendingUri,
                    displayName = "device_clip.mp4",
                    status = VaultMoveItemStatus.USER_CANCELLED,
                    session = session
                )
                assertEquals(VaultMoveItemStatus.USER_CANCELLED, rollbackOutcome.status)
                assertTrue(repository.list(session).isEmpty())
                assertTrue(repository.loadPendingMoves(session).isEmpty())

                // Entry without valid origin refuses restore rather than deleting encrypted copy
                val noOrigin = repository.importStream(
                    displayName = "safe_audio.mp3",
                    mimeType = "audio/mpeg",
                    input = ByteArrayInputStream(videoBytes),
                    session = session
                )
                val restoreSummary = treeSupport.restoreEntries(
                    entries = listOf(noOrigin),
                    session = session,
                    destinationTree = null,
                    useOriginalFolder = true,
                    removeFromVaultAfterRestore = true
                )
                assertEquals(0, restoreSummary.succeededCount)
                assertEquals(1, restoreSummary.failedCount)
                assertEquals(1, restoreSummary.keptInVaultCount)
                assertEquals(noOrigin.id, repository.list(session).single().id)

                val verifiedExport = File(context.cacheDir, "verified_export_check.mp3")
                try {
                    repository.exportToFile(noOrigin.id, verifiedExport, session)
                    val digest = MessageDigest.getInstance("SHA-256")
                    assertArrayEquals(digest.digest(videoBytes), digest.digest(verifiedExport.readBytes()))
                } finally {
                    verifiedExport.delete()
                }
            } finally {
                session.close()
            }
        } finally {
            password.fill('\u0000')
            directory.deleteRecursively()
        }
    }

    @Test
    fun deviceSourcePermissionsNeverRequestImagesAndAllowCustomFolders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = PrivateVaultDeviceSource(context)
        val perms = source.permissionsForBrowsePrompt().toSet()
        assertFalse(perms.contains(Manifest.permission.READ_MEDIA_IMAGES))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(perms.contains(Manifest.permission.READ_MEDIA_VIDEO))
            assertTrue(perms.contains(Manifest.permission.READ_MEDIA_AUDIO))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val whatsappEntry = PrivateVaultEntry(
                id = "wa-device-check",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                sizeBytes = 1024L,
                sourceTreeUri = PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI,
                sourceParentDocumentId = "WhatsApp/Media/WhatsApp Video"
            )
            assertTrue(source.canRestoreToMediaStoreOrigin(whatsappEntry))
        }
        assertFalse(isVaultAllowedMediaMime("image/jpeg"))
        assertFalse(isVaultAllowedMediaMime("application/pdf"))
        assertTrue(isVaultAllowedMediaMime("video/mp4"))
        assertTrue(isVaultAllowedMediaMime("audio/mpeg"))
    }
}
