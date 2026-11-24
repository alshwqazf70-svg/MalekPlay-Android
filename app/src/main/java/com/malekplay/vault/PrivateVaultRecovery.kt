package com.malekplay.vault

import java.io.File

/** File-system recovery policy for vault payload/index transactions. */
internal object PrivateVaultRecovery {
    private val validId = Regex("[0-9a-fA-F-]{36}")

    fun recover(directory: File, committedIds: Set<String>) {
        directory.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".pending") -> {
                    val id = file.name.removeSuffix(".pending")
                    if (validId.matches(id)) {
                        if (id !in committedIds) File(directory, "$id.mkv").delete()
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
            }
        }
    }
}
