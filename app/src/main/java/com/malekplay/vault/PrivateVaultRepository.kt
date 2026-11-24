package com.malekplay.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.*
import java.util.UUID

data class PrivateVaultEntry(val id: String, val displayName: String, val mimeType: String, val sizeBytes: Long)

/** Encrypted app-private vault. Import copies; it never deletes a source implicitly. */
class PrivateVaultRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "private_vault").apply { mkdirs() }
    private val indexFile = File(directory, "vault.index.enc")
    private val backupIndex = File(directory, "vault.index.bak")
    init { if (!indexFile.exists() && backupIndex.exists()) backupIndex.renameTo(indexFile) }
    private val indexMagic = 0x4D4B5649
    private val maxEntries = 20_000

    @Synchronized fun isConfigured() = indexFile.isFile && indexFile.length() > 0L
    @Synchronized fun create(password: CharArray) {
        check(!isConfigured()) { "Vault is already configured." }
        persistIndex(emptyList(), password)
    }
    @Synchronized fun list(password: CharArray): List<PrivateVaultEntry> = readIndex(password)

    @Synchronized fun importUri(uri: Uri, password: CharArray): PrivateVaultEntry {
        val name = queryName(uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val target = payload(id)
        val temp = File(directory, "$id.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: error("Cannot read selected file.")
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    PrivateVaultCrypto.encrypt(source, sink, password)
                    sink.fd.sync()
                }
            }
            check(temp.renameTo(target)) { "Could not finalize encrypted file." }
            val current = readIndex(password)
            check(current.size < maxEntries) { "Vault item limit reached." }
            val entry = PrivateVaultEntry(id, name, mime, target.length())
            try { persistIndex(current + entry, password) }
            catch (failure: Throwable) { target.delete(); throw failure }
            return entry
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    @Synchronized fun exportTo(id: String, destination: Uri, password: CharArray) {
        requireValidId(id)
        check(readIndex(password).any { it.id == id }) { "Vault item not found." }
        val file = payload(id)
        check(file.isFile) { "Encrypted file is missing." }
        // Verify the entire GCM tag into private temporary storage before exposing plaintext.
        val verified = File(directory, "restore-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(file).use { source ->
                FileOutputStream(verified).use { sink ->
                    PrivateVaultCrypto.decrypt(source, sink, password)
                    sink.fd.sync()
                }
            }
            val output = resolver.openOutputStream(destination, "wt") ?: error("Cannot write destination.")
            output.use { sink -> FileInputStream(verified).use { source -> source.copyTo(sink) } }
        } finally { verified.delete() }
    }

    @Synchronized fun delete(id: String, password: CharArray) {
        requireValidId(id)
        val current = readIndex(password)
        check(current.any { it.id == id }) { "Vault item not found." }
        val source = payload(id)
        val staged = File(directory, "$id.delete")
        check(source.renameTo(staged)) { "Could not stage encrypted item deletion." }
        try {
            persistIndex(current.filterNot { it.id == id }, password)
            check(staged.delete()) { "Index updated but encrypted payload cleanup failed." }
        } catch (failure: Throwable) {
            if (staged.exists()) staged.renameTo(source)
            throw failure
        }
    }

    private fun requireValidId(id: String) = require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid vault item." }
    private fun payload(id: String) = File(directory, "$id.mkv")

    private fun queryName(uri: Uri): String {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Private file"
    }

    private fun readIndex(password: CharArray): List<PrivateVaultEntry> {
        check(indexFile.isFile) { "Vault has not been created." }
        val plain = File(directory, "index-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(indexFile).use { input ->
                FileOutputStream(plain).use { output -> PrivateVaultCrypto.decrypt(input, output, password); output.fd.sync() }
            }
            DataInputStream(FileInputStream(plain)).use { input ->
                check(input.readInt() == indexMagic) { "Invalid vault index." }
                val count = input.readInt()
                require(count in 0..maxEntries) { "Invalid vault index size." }
                return List(count) {
                    val id = input.readUTF()
                    requireValidId(id)
                    PrivateVaultEntry(id, input.readUTF(), input.readUTF(), input.readLong())
                }.also { check(input.read() == -1) { "Unexpected vault index data." } }
            }
        } finally { plain.delete() }
    }

    private fun persistIndex(entries: List<PrivateVaultEntry>, password: CharArray) {
        val plain = File(directory, "index-${UUID.randomUUID()}.tmp")
        val encrypted = File(directory, "vault.index.new")
        try {
            DataOutputStream(FileOutputStream(plain)).use { out ->
                out.writeInt(indexMagic); out.writeInt(entries.size)
                entries.forEach { out.writeUTF(it.id); out.writeUTF(it.displayName.take(4096)); out.writeUTF(it.mimeType.take(512)); out.writeLong(it.sizeBytes) }
                out.flush()
            }
            FileInputStream(plain).use { input ->
                FileOutputStream(encrypted).use { output -> PrivateVaultCrypto.encrypt(input, output, password); output.fd.sync() }
            }
            backupIndex.delete()
            if (indexFile.exists()) check(indexFile.renameTo(backupIndex)) { "Could not stage previous vault index." }
            if (!encrypted.renameTo(indexFile)) {
                if (backupIndex.exists()) backupIndex.renameTo(indexFile)
                error("Could not save vault index.")
            }
            backupIndex.delete()
        } finally { plain.delete(); encrypted.delete() }
    }
}
