package com.malekplay.vault

import java.io.File

/** File-system recovery policy for vault payload/index/archive transactions. */
internal object PrivateVaultRecovery {
    private val validId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    const val WORK_DIR_NAME = ".work"

    fun recover(
        directory: File,
        committedIds: Set<String>,
        pruneOrphanThumbs: Boolean = false,
        activeTempNames: Set<String> = emptySet()
    ) {
        directory.listFiles()?.forEach { file ->
            if (file.name in activeTempNames) return@forEach
            when {
                file.name == "vault.index.new" -> file.delete()
                file.name == "vault.key.new" || file.name == "vault.key.tmp" -> file.delete()
                // These files contain decrypted index metadata or restored plaintext.
                // A process kill bypasses finally blocks, so remove them on next unlock.
                file.name.startsWith("index-") && file.name.endsWith(".tmp") -> file.delete()
                file.name.startsWith("restore-") && file.name.endsWith(".tmp") -> file.delete()
                file.isDirectory && file.name == WORK_DIR_NAME -> {
                    file.listFiles()?.forEach { workFile ->
                        if (workFile.name !in activeTempNames) {
                            workFile.deleteRecursively()
                        }
                    }
                }
                file.isDirectory && file.name == "thumbs" -> {
                    file.listFiles()?.forEach { thumb ->
                        val id = thumb.name.removeSuffix(".thumb.enc").removeSuffix(".tmp")
                        if (thumb.name.endsWith(".tmp") || (pruneOrphanThumbs && validId.matches(id) && id !in committedIds)) {
                            thumb.delete()
                        }
                    }
                }
                file.name.endsWith(".pending") -> {
                    val id = file.name.removeSuffix(".pending")
                    if (validId.matches(id)) {
                        if (id !in committedIds) File(directory, "$id.mkv").delete()
                        File(directory, "$id.tmp").delete()
                        file.delete()
                    }
                }
                file.name.endsWith(".delete") -> {
                    val id = file.name.removeSuffix(".delete")
                    if (validId.matches(id)) {
                        val target = File(directory, "$id.mkv")
                        if (id in committedIds) {
                            if (!target.exists()) file.renameTo(target) else file.delete()
                        } else {
                            file.delete()
                        }
                    }
                }
                file.name.endsWith(".mkv.bak") -> {
                    val id = file.name.removeSuffix(".mkv.bak")
                    if (validId.matches(id)) {
                        val target = File(directory, "$id.mkv")
                        if (id in committedIds && !target.exists()) {
                            file.renameTo(target)
                        } else {
                            file.delete()
                        }
                    }
                }
                file.name.endsWith(".edit.tmp") -> {
                    file.delete()
                }
                file.name.endsWith(".tmp") -> {
                    val id = file.name.removeSuffix(".tmp")
                    if (validId.matches(id)) file.delete()
                }
            }
        }
    }
}
