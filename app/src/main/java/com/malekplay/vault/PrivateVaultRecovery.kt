package com.malekplay.vault

import java.io.File

/** File-system recovery policy for vault payload/index transactions. */
internal object PrivateVaultRecovery {
    private val validId = Regex("[0-9a-fA-F-]{36}")

    fun recover(directory: File, committedIds: Set<String>) {
        directory.listFiles()?.forEach { file ->
            when {
                file.name == "vault.index.new" -> file.delete()
                // These files contain decrypted index metadata or restored plaintext.
                // A process kill bypasses finally blocks, so remove them on next unlock.
                file.name.startsWith("index-") && file.name.endsWith(".tmp") -> file.delete()
                file.name.startsWith("restore-") && file.name.endsWith(".tmp") -> file.delete()
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
                file.name.endsWith(".tmp") -> {
                    val id = file.name.removeSuffix(".tmp")
                    if (validId.matches(id)) file.delete()
                }
            }
        }
    }
}
