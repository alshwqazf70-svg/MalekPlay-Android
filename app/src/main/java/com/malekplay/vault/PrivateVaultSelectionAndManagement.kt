package com.malekplay.vault

import android.net.Uri
import java.util.Collections
import java.util.Locale

enum class VaultBrowserSort {
    NAME,
    SIZE,
    DATE
}

enum class VaultEntryFilter {
    ALL,
    VIDEO,
    AUDIO,
    IMAGE,
    OTHER
}

enum class VaultEntrySort {
    NEWEST,
    NAME,
    SIZE
}

data class SelectedVaultBrowserItem(
    val documentId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val treeUri: Uri,
    val parentDocumentId: String?,
    val displayFolderPath: String
)

/**
 * Maintains cross-folder selections keyed by URI string so navigating between folders
 * never drops earlier selections and selecting the same document twice never duplicates it.
 */
class VaultBrowserSelectionManager {
    private val selectedByUri = LinkedHashMap<String, SelectedVaultBrowserItem>()

    fun toggle(
        item: VaultBrowserItem,
        treeUri: Uri,
        parentDocumentId: String?,
        displayFolderPath: String
    ): Boolean {
        val key = item.uri.toString()
        return if (selectedByUri.containsKey(key)) {
            selectedByUri.remove(key)
            false
        } else {
            selectedByUri[key] = item.toSelected(treeUri, parentDocumentId, displayFolderPath)
            true
        }
    }

    fun select(
        item: VaultBrowserItem,
        treeUri: Uri,
        parentDocumentId: String?,
        displayFolderPath: String
    ) {
        selectedByUri[item.uri.toString()] = item.toSelected(treeUri, parentDocumentId, displayFolderPath)
    }

    fun selectAll(
        items: List<VaultBrowserItem>,
        treeUri: Uri,
        parentDocumentId: String?,
        displayFolderPath: String,
        includeDirectories: Boolean = false
    ) {
        items.forEach { item ->
            if (includeDirectories || !item.isDirectory) {
                selectedByUri[item.uri.toString()] =
                    item.toSelected(treeUri, parentDocumentId, displayFolderPath)
            }
        }
    }

    fun deselectAll(items: List<VaultBrowserItem>) {
        items.forEach { selectedByUri.remove(it.uri.toString()) }
    }

    fun clear() {
        selectedByUri.clear()
    }

    fun contains(uri: Uri): Boolean = selectedByUri.containsKey(uri.toString())

    fun count(): Int = selectedByUri.size

    fun totalBytes(): Long = selectedByUri.values.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    fun selectedItems(): List<SelectedVaultBrowserItem> = selectedByUri.values.toList()

    fun areAllVisibleFilesSelected(items: List<VaultBrowserItem>): Boolean {
        val files = items.filterNot { it.isDirectory }
        return files.isNotEmpty() && files.all { selectedByUri.containsKey(it.uri.toString()) }
    }

    private fun VaultBrowserItem.toSelected(
        treeUri: Uri,
        parentDocumentId: String?,
        displayFolderPath: String
    ) = SelectedVaultBrowserItem(
        documentId = documentId,
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        isDirectory = isDirectory,
        treeUri = treeUri,
        parentDocumentId = parentDocumentId,
        displayFolderPath = displayFolderPath
    )
}

fun filterAndSortBrowserItems(
    items: List<VaultBrowserItem>,
    query: String,
    sort: VaultBrowserSort,
    ascending: Boolean = true
): List<VaultBrowserItem> {
    val q = query.trim().lowercase(Locale.getDefault())
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter { it.displayName.lowercase(Locale.getDefault()).contains(q) }
    }
    val itemComparator = when (sort) {
        VaultBrowserSort.NAME -> compareBy<VaultBrowserItem> {
            it.displayName.lowercase(Locale.getDefault())
        }
        VaultBrowserSort.SIZE -> compareBy<VaultBrowserItem> {
            it.sizeBytes.coerceAtLeast(0L)
        }.thenBy { it.displayName.lowercase(Locale.getDefault()) }
        VaultBrowserSort.DATE -> compareBy<VaultBrowserItem> {
            it.lastModified
        }.thenBy { it.displayName.lowercase(Locale.getDefault()) }
    }
    val ordered = if (ascending) itemComparator else Collections.reverseOrder(itemComparator)
    // Keep directories grouped before files like MX Player does.
    return filtered.sortedWith(compareBy<VaultBrowserItem> { !it.isDirectory }.then(ordered))
}

fun filterAndSortVaultEntries(
    entries: List<PrivateVaultEntry>,
    query: String,
    filter: VaultEntryFilter,
    sort: VaultEntrySort,
    ascending: Boolean = false
): List<PrivateVaultEntry> {
    val q = query.trim().lowercase(Locale.getDefault())
    val base = entries.filter { entry ->
        val matchesQuery = q.isEmpty() || entry.displayName.lowercase(Locale.getDefault()).contains(q)
        val matchesType = when (filter) {
            VaultEntryFilter.ALL -> true
            VaultEntryFilter.VIDEO -> entry.mimeType.startsWith("video/")
            VaultEntryFilter.AUDIO -> entry.mimeType.startsWith("audio/")
            VaultEntryFilter.IMAGE -> entry.mimeType.startsWith("image/")
            VaultEntryFilter.OTHER -> !entry.mimeType.startsWith("video/") &&
                !entry.mimeType.startsWith("audio/") &&
                !entry.mimeType.startsWith("image/")
        }
        matchesQuery && matchesType
    }
    val comparator = when (sort) {
        VaultEntrySort.NEWEST -> compareBy<PrivateVaultEntry> { it.addedAtMs }
            .thenBy { it.displayName.lowercase(Locale.getDefault()) }
        VaultEntrySort.NAME -> compareBy<PrivateVaultEntry> {
            it.displayName.lowercase(Locale.getDefault())
        }
        VaultEntrySort.SIZE -> compareBy<PrivateVaultEntry> { it.sizeBytes.coerceAtLeast(0L) }
            .thenBy { it.displayName.lowercase(Locale.getDefault()) }
    }
    return base.sortedWith(if (ascending) comparator else Collections.reverseOrder(comparator))
}

/**
 * Generates a non-colliding display name (case-insensitive) such as `clip (1).mp4` or
 * `Vacation (1)` so restoring files never silently overwrites existing user files.
 */
fun resolveUniqueChildName(
    desiredName: String,
    existingNames: Set<String>,
    isDirectory: Boolean = false
): String {
    val cleanBaseName = desiredName.trim().ifBlank { if (isDirectory) "Restored folder" else "Restored file" }
    val normalizedExisting = existingNames.map { it.lowercase(Locale.ROOT) }.toHashSet()
    if (cleanBaseName.lowercase(Locale.ROOT) !in normalizedExisting) {
        return cleanBaseName
    }
    val dotIndex = if (!isDirectory) cleanBaseName.lastIndexOf('.') else -1
    val stem = if (dotIndex > 0) cleanBaseName.substring(0, dotIndex) else cleanBaseName
    val extension = if (dotIndex > 0) cleanBaseName.substring(dotIndex) else ""
    var counter = 1
    while (true) {
        val candidate = "$stem ($counter)$extension"
        if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) {
            return candidate
        }
        counter++
    }
}

data class VaultFolderRestoreResult(
    val restoredFiles: Int,
    val failedFiles: Int,
    val rootFolderUri: Uri?,
    val rootFolderName: String,
    val failures: List<String> = emptyList(),
    val createdFileUris: List<Uri> = emptyList()
) {
    val isCompleteSuccess: Boolean get() = failedFiles == 0 && restoredFiles >= 0
}

data class VaultBatchTransferSummary(
    val succeededCount: Int,
    val failedCount: Int,
    val removedFromVaultCount: Int,
    val keptInVaultCount: Int,
    val movedSourceCount: Int = 0,
    val copiedOnlySourceCount: Int = 0,
    val extractedButVaultDeleteFailedCount: Int = 0,
    val failures: List<String> = emptyList(),
    val restoredFilePathsOrUris: List<String> = emptyList()
)

enum class VaultArchiveSort {
    NAME,
    SIZE,
    TYPE,
    DATE
}

data class VaultArchiveListingItem(
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String,
    val childCount: Int = 0
)

fun filterAndSortArchiveItems(
    items: List<VaultArchiveListingItem>,
    query: String,
    sort: VaultArchiveSort,
    ascending: Boolean = true
): List<VaultArchiveListingItem> {
    val q = query.trim().lowercase(Locale.getDefault())
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter {
            it.name.lowercase(Locale.getDefault()).contains(q) ||
                it.path.lowercase(Locale.getDefault()).contains(q)
        }
    }
    val comparator = when (sort) {
        VaultArchiveSort.NAME -> compareBy<VaultArchiveListingItem> {
            it.name.lowercase(Locale.getDefault())
        }
        VaultArchiveSort.SIZE -> compareBy<VaultArchiveListingItem> {
            it.sizeBytes.coerceAtLeast(0L)
        }.thenBy { it.name.lowercase(Locale.getDefault()) }
        VaultArchiveSort.TYPE -> compareBy<VaultArchiveListingItem> {
            if (it.isDirectory) "" else it.name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        }.thenBy { it.mimeType.lowercase(Locale.getDefault()) }
            .thenBy { it.name.lowercase(Locale.getDefault()) }
        VaultArchiveSort.DATE -> compareBy<VaultArchiveListingItem> {
            it.lastModifiedMs
        }.thenBy { it.name.lowercase(Locale.getDefault()) }
    }
    val ordered = if (ascending) comparator else Collections.reverseOrder(comparator)
    return filtered.sortedWith(compareBy<VaultArchiveListingItem> { !it.isDirectory }.then(ordered))
}

/**
 * Cross-subfolder selection manager for browsing inside an encrypted folder archive.
 * Keyed by normalized archive path so selecting across multiple subfolders preserves everything.
 */
class VaultArchiveSelectionManager {
    private val selectedByPath = LinkedHashMap<String, VaultArchiveListingItem>()

    fun toggle(item: VaultArchiveListingItem): Boolean {
        return if (selectedByPath.containsKey(item.path)) {
            selectedByPath.remove(item.path)
            false
        } else {
            selectedByPath[item.path] = item
            true
        }
    }

    fun setSelected(item: VaultArchiveListingItem, selected: Boolean) {
        if (selected) selectedByPath[item.path] = item else selectedByPath.remove(item.path)
    }

    fun selectAll(items: List<VaultArchiveListingItem>) {
        items.forEach { selectedByPath[it.path] = it }
    }

    fun deselectAll(items: List<VaultArchiveListingItem>) {
        items.forEach { selectedByPath.remove(it.path) }
    }

    fun retainPaths(validPaths: Set<String>) {
        val iter = selectedByPath.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next() !in validPaths) iter.remove()
        }
    }

    fun clear() = selectedByPath.clear()
    fun contains(path: String): Boolean = selectedByPath.containsKey(path)
    fun count(): Int = selectedByPath.size
    fun totalBytes(): Long = selectedByPath.values.sumOf { if (it.isDirectory) 0L else it.sizeBytes.coerceAtLeast(0L) }
    fun selectedItems(): List<VaultArchiveListingItem> = selectedByPath.values.toList()
    fun areAllSelected(items: List<VaultArchiveListingItem>): Boolean =
        items.isNotEmpty() && items.all { selectedByPath.containsKey(it.path) }
}

data class VaultImportBatchResult(
    val importedCount: Int,
    val failedCount: Int,
    val movedCount: Int,
    val copiedOnlyCount: Int,
    val deletedSourceMediaIds: List<String>,
    val failures: List<String> = emptyList(),
    val succeededKeys: Set<String> = emptySet(),
    val pendingSystemDeleteUris: List<String> = emptyList()
)
