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
    private val directory = File(context.applicationContext.filesDir, "private_vault").apply {
        check(isDirectory || mkdirs()) { "تعذر إنشاء مساحة الخزنة الخاصة." }
    }
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

    @Synchronized fun importUri(
        uri: Uri,
        password: CharArray,
        displayNameOverride: String? = null,
        mimeTypeOverride: String? = null
    ): PrivateVaultEntry {
        val name = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryName(uri)
        val mime = mimeTypeOverride?.takeIf { it.isNotBlank() } ?: resolver.getType(uri) ?: "application/octet-stream"
        val input = resolver.openInputStream(uri) ?: error("Cannot read selected file.")
        return importStream(name, mime, input, password)
    }

    /** Imports a private temporary stream without exposing it through a public document provider. */
    @Synchronized fun importStream(
        displayName: String,
        mimeType: String,
        input: InputStream,
        password: CharArray
    ): PrivateVaultEntry {
        val name = displayName.takeIf { it.isNotBlank() } ?: "Private file"
        val mime = mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val target = payload(id)
        val temp = File(directory, "$id.tmp")
        val pending = File(directory, "$id.pending")
        var committed = false
        try {
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    PrivateVaultCrypto.encrypt(source, sink, password)
                    sink.fd.sync()
                }
            }
            // Authenticate the completed ciphertext before it can enter the index
            // or before a caller is allowed to delete the original source.
            FileInputStream(temp).use { encrypted ->
                PrivateVaultCrypto.decrypt(encrypted, object : OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                }, password)
            }
            val current = readIndex(password)
            check(current.size < maxEntries) { "Vault item limit reached." }
            FileOutputStream(pending).use { it.fd.sync() }
            check(temp.renameTo(target)) { "Could not finalize encrypted file." }
            val entry = PrivateVaultEntry(id, name, mime, target.length())
            persistIndex(current + entry, password)
            committed = true
            pending.delete()
            return entry
        } catch (failure: Throwable) {
            temp.delete()
            if (!committed) {
                target.delete()
                pending.delete()
            }
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

    /** Decrypts and authenticates a folder archive into app-private storage only. */
    @Synchronized fun exportToFile(id: String, destination: File, password: CharArray) {
        requireValidId(id)
        check(readIndex(password).any { it.id == id }) { "Vault item not found." }
        val encrypted = payload(id)
        check(encrypted.isFile) { "Encrypted file is missing." }
        val verified = File(directory, "restore-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(encrypted).use { source ->
                FileOutputStream(verified).use { sink ->
                    PrivateVaultCrypto.decrypt(source, sink, password)
                    sink.fd.sync()
                }
            }
            FileInputStream(verified).use { source ->
                FileOutputStream(destination).use { sink ->
                    source.copyTo(sink)
                    sink.fd.sync()
                }
            }
        } finally { verified.delete() }
    }

    @Synchronized fun delete(id: String, password: CharArray) {
        requireValidId(id)
        val current = readIndex(password)
        check(current.any { it.id == id }) { "Vault item not found." }
        val source = payload(id)
        val staged = File(directory, "$id.delete")
        check(source.renameTo(staged)) { "Could not stage encrypted item deletion." }
        var indexCommitted = false
        try {
            persistIndex(current.filterNot { it.id == id }, password)
            indexCommitted = true
            // The index is authoritative now. If physical cleanup fails, leave the
            // staged payload for PrivateVaultRecovery instead of resurrecting it.
            staged.delete()
        } catch (failure: Throwable) {
            if (!indexCommitted && staged.exists()) staged.renameTo(source)
            throw failure
        }
    }

    private fun requireValidId(id: String) = require(
        id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    ) { "Invalid vault item." }
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
                val entries = List(count) {
                    val id = input.readUTF()
                    requireValidId(id)
                    val displayName = input.readUTF()
                    val mimeType = input.readUTF()
                    val sizeBytes = input.readLong()
                    require(displayName.isNotBlank() && mimeType.isNotBlank() && sizeBytes >= 0L) {
                        "Invalid vault item metadata."
                    }
                    PrivateVaultEntry(id, displayName, mimeType, sizeBytes)
                }
                require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate vault item identifier." }
                check(input.read() == -1) { "Unexpected vault index data." }
                PrivateVaultRecovery.recover(directory, entries.mapTo(mutableSetOf()) { it.id })
                return entries
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
