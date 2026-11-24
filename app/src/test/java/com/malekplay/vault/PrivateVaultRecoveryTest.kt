package com.malekplay.vault

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrivateVaultRecoveryTest {
    private lateinit var directory: File
    private val committed = "12345678-1234-1234-1234-123456789abc"
    private val interrupted = "abcdef12-1234-1234-1234-123456789abc"

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("private-vault-recovery").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun plaintextIndexAndRestoreTemporariesAreRemovedAfterRestart() {
        val indexPlain = File(directory, "index-550e8400-e29b-41d4-a716-446655440000.tmp")
            .apply { writeText("private file names") }
        val restoredPlain = File(directory, "restore-550e8400-e29b-41d4-a716-446655440000.tmp")
            .apply { writeText("private media bytes") }

        PrivateVaultRecovery.recover(directory, emptySet())

        assertFalse(indexPlain.exists())
        assertFalse(restoredPlain.exists())
    }

    @Test
    fun uncommittedImportIsDiscardedAfterRestart() {
        val payload = File(directory, "$interrupted.mkv").apply { writeText("ciphertext") }
        val temporary = File(directory, "$interrupted.tmp").apply { writeText("partial ciphertext") }
        val marker = File(directory, "$interrupted.pending").apply { writeText("pending") }

        PrivateVaultRecovery.recover(directory, emptySet())

        assertFalse(payload.exists())
        assertFalse(temporary.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun committedImportKeepsPayloadAndClearsPendingMarker() {
        val payload = File(directory, "$committed.mkv").apply { writeText("ciphertext") }
        val marker = File(directory, "$committed.pending").apply { writeText("pending") }

        PrivateVaultRecovery.recover(directory, setOf(committed))

        assertTrue(payload.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun interruptedDeleteIsRolledBackWhenIndexStillReferencesItem() {
        val staged = File(directory, "$committed.delete").apply { writeText("ciphertext") }
        val payload = File(directory, "$committed.mkv")

        PrivateVaultRecovery.recover(directory, setOf(committed))

        assertTrue(payload.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun interruptedDeleteIsCompletedWhenIndexNoLongerReferencesItem() {
        val staged = File(directory, "$interrupted.delete").apply { writeText("ciphertext") }
        val payload = File(directory, "$interrupted.mkv")

        PrivateVaultRecovery.recover(directory, emptySet())

        assertFalse(staged.exists())
        assertFalse(payload.exists())
    }
}
