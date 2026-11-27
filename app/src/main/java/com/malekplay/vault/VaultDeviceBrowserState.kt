package com.malekplay.vault

import android.net.Uri
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaFolder
import java.util.Collections
import java.util.Locale

enum class VaultDeviceSourceKind {
    MEDIA_STORE,
    SAF
}

data class VaultDeviceFolder(
    val path: String,
    val name: String,
    val itemCount: Int,
    val subfolderCount: Int = 0,
    val totalBytes: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val sampleUri: Uri? = null,
    val sourceKind: VaultDeviceSourceKind = VaultDeviceSourceKind.MEDIA_STORE,
    val safTreeUri: Uri? = null,
    val safDocumentId: String? = null
)

data class VaultDeviceItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val folderPath: String,
    val folderDisplayPath: String,
    val folderDisplayName: String = "",
    val isDirectory: Boolean = false,
    val sourceKind: VaultDeviceSourceKind = VaultDeviceSourceKind.MEDIA_STORE,
    val mediaStoreCollection: String? = null,
    val safTreeUri: Uri? = null,
    val safParentDocumentId: String? = null,
    val safDocumentId: String? = null,
    val subfolderCount: Int? = null
) {
    val selectionKey: String get() = uri.toString()

    fun toSelectedBrowserItem(): SelectedVaultBrowserItem = SelectedVaultBrowserItem(
        documentId = safDocumentId ?: id,
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModified = lastModifiedMs,
        isDirectory = isDirectory,
        treeUri = safTreeUri ?: Uri.parse(PrivateVaultTreeSupport.MEDIASTORE_ORIGIN_TREE_URI),
        parentDocumentId = safParentDocumentId ?: folderPath,
        displayFolderPath = folderDisplayPath
    )
}

sealed interface VaultBrowserStep {
    data object Closed : VaultBrowserStep
    data object FolderList : VaultBrowserStep
    data class FolderContents(
        val folderPath: String,
        val folderName: String,
        val stack: List<Pair<String, String>> = listOf(folderPath to folderName),
        val sourceKind: VaultDeviceSourceKind = VaultDeviceSourceKind.MEDIA_STORE,
        val safTreeUri: Uri? = null,
        val safDocumentId: String? = null
    ) : VaultBrowserStep {
        val currentPath: String get() = stack.lastOrNull()?.first ?: folderPath
        val currentName: String get() = stack.lastOrNull()?.second ?: folderName
        val currentSafDocId: String?
            get() = if (sourceKind == VaultDeviceSourceKind.SAF) stack.lastOrNull()?.first ?: safDocumentId else null
    }
}

data class VaultBrowserSessionSnapshot(
    val step: VaultBrowserStep = VaultBrowserStep.Closed,
    val pendingSelectedByKey: Map<String, VaultDeviceItem> = emptyMap(),
    val savedKeys: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val lastSaveResultMessage: String? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val loadedPageCount: Int = 1
) {
    val isOpen: Boolean get() = step != VaultBrowserStep.Closed
    val isInsideFolder: Boolean get() = step is VaultBrowserStep.FolderContents
    val pendingCount: Int get() = pendingSelectedByKey.size
    val pendingTotalBytes: Long
        get() = pendingSelectedByKey.values.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val pendingItems: List<VaultDeviceItem>
        get() = pendingSelectedByKey.values.toList()
    val visibleItemLimit: Int
        get() = (pageSize * loadedPageCount).coerceAtLeast(pageSize)

    companion object {
        const val DEFAULT_PAGE_SIZE = 120
    }
}

/**
 * Explicit state holder for the MX Player–style Private Vault internal browser.
 *
 * Keeps navigation, cross-folder pending selection, already-saved item separation,
 * re-entrant save protection, and the exact X / Android Back semantics decoupled from Compose.
 */
class VaultBrowserStateHolder(
    pageSize: Int = VaultBrowserSessionSnapshot.DEFAULT_PAGE_SIZE
) {
    private val safePageSize = pageSize.coerceAtLeast(20)
    private var _snapshot = VaultBrowserSessionSnapshot(pageSize = safePageSize)
    val snapshot: VaultBrowserSessionSnapshot get() = _snapshot

    fun openFolderList(): VaultBrowserSessionSnapshot {
        _snapshot = VaultBrowserSessionSnapshot(
            step = VaultBrowserStep.FolderList,
            pendingSelectedByKey = emptyMap(),
            savedKeys = emptySet(),
            isSaving = false,
            lastSaveResultMessage = null,
            pageSize = safePageSize,
            loadedPageCount = 1
        )
        return _snapshot
    }

    fun enterFolder(folder: VaultDeviceFolder): VaultBrowserSessionSnapshot {
        if (!_snapshot.isOpen) openFolderList()
        _snapshot = _snapshot.copy(
            step = VaultBrowserStep.FolderContents(
                folderPath = folder.path,
                folderName = folder.name,
                stack = listOf(folder.path to folder.name),
                sourceKind = folder.sourceKind,
                safTreeUri = folder.safTreeUri,
                safDocumentId = folder.safDocumentId
            ),
            loadedPageCount = 1
        )
        return _snapshot
    }

    fun enterSubfolder(path: String, name: String): VaultBrowserSessionSnapshot {
        val current = _snapshot.step as? VaultBrowserStep.FolderContents ?: return _snapshot
        if (current.currentPath == path) return _snapshot
        _snapshot = _snapshot.copy(
            step = current.copy(stack = current.stack + (path to name)),
            loadedPageCount = 1
        )
        return _snapshot
    }

    fun navigateToBreadcrumb(index: Int): VaultBrowserSessionSnapshot {
        val current = _snapshot.step as? VaultBrowserStep.FolderContents ?: return _snapshot
        if (index < 0) {
            _snapshot = _snapshot.copy(step = VaultBrowserStep.FolderList, loadedPageCount = 1)
            return _snapshot
        }
        if (index >= current.stack.lastIndex) return _snapshot
        _snapshot = _snapshot.copy(
            step = current.copy(stack = current.stack.take(index + 1)),
            loadedPageCount = 1
        )
        return _snapshot
    }

    /**
     * Exact MX Player X button contract:
     * - Case A (inside any folder / subfolder): return to FolderList, keeping all pending
     *   selections and active add-session state intact.
     * - Case B (at FolderList): close browser, finish add-session, and return to Vault.
     */
    fun onCloseButtonPressed(): VaultBrowserSessionSnapshot {
        if (_snapshot.isSaving) return _snapshot
        return when (_snapshot.step) {
            is VaultBrowserStep.FolderContents -> {
                _snapshot = _snapshot.copy(
                    step = VaultBrowserStep.FolderList,
                    loadedPageCount = 1
                )
                _snapshot
            }
            VaultBrowserStep.FolderList -> closeBrowser()
            VaultBrowserStep.Closed -> _snapshot
        }
    }

    /**
     * Android system Back navigation:
     * - Subfolder -> parent folder
     * - Root folder contents -> FolderList (preserving pending selections)
     * - FolderList -> close browser and return to Vault
     */
    fun onSystemBackPressed(): VaultBrowserSessionSnapshot {
        if (_snapshot.isSaving) return _snapshot
        return when (val current = _snapshot.step) {
            is VaultBrowserStep.FolderContents -> {
                if (current.stack.size > 1) {
                    _snapshot = _snapshot.copy(
                        step = current.copy(stack = current.stack.dropLast(1)),
                        loadedPageCount = 1
                    )
                } else {
                    _snapshot = _snapshot.copy(
                        step = VaultBrowserStep.FolderList,
                        loadedPageCount = 1
                    )
                }
                _snapshot
            }
            VaultBrowserStep.FolderList -> closeBrowser()
            VaultBrowserStep.Closed -> _snapshot
        }
    }

    fun closeBrowser(): VaultBrowserSessionSnapshot {
        if (_snapshot.isSaving) return _snapshot
        _snapshot = VaultBrowserSessionSnapshot(
            step = VaultBrowserStep.Closed,
            pendingSelectedByKey = emptyMap(),
            savedKeys = emptySet(),
            isSaving = false,
            lastSaveResultMessage = null,
            pageSize = safePageSize,
            loadedPageCount = 1
        )
        return _snapshot
    }

    fun toggleSelection(item: VaultDeviceItem): Boolean {
        if (item.isDirectory || _snapshot.isSaving) return false
        val key = item.selectionKey
        if (key in _snapshot.savedKeys) return false
        val updated = LinkedHashMap(_snapshot.pendingSelectedByKey)
        val added = if (updated.containsKey(key)) {
            updated.remove(key)
            false
        } else {
            updated[key] = item
            true
        }
        _snapshot = _snapshot.copy(pendingSelectedByKey = updated)
        return added
    }

    fun setSelected(item: VaultDeviceItem, selected: Boolean) {
        if (item.isDirectory || _snapshot.isSaving) return
        val key = item.selectionKey
        if (selected && key in _snapshot.savedKeys) return
        val updated = LinkedHashMap(_snapshot.pendingSelectedByKey)
        if (selected) updated[key] = item else updated.remove(key)
        _snapshot = _snapshot.copy(pendingSelectedByKey = updated)
    }

    fun selectAll(items: List<VaultDeviceItem>) {
        if (_snapshot.isSaving) return
        val updated = LinkedHashMap(_snapshot.pendingSelectedByKey)
        items.forEach { item ->
            if (!item.isDirectory && item.selectionKey !in _snapshot.savedKeys) {
                updated[item.selectionKey] = item
            }
        }
        _snapshot = _snapshot.copy(pendingSelectedByKey = updated)
    }

    fun deselectAll(items: List<VaultDeviceItem>) {
        if (_snapshot.isSaving) return
        val updated = LinkedHashMap(_snapshot.pendingSelectedByKey)
        items.forEach { updated.remove(it.selectionKey) }
        _snapshot = _snapshot.copy(pendingSelectedByKey = updated)
    }

    fun clearPendingSelection() {
        if (_snapshot.isSaving) return
        _snapshot = _snapshot.copy(pendingSelectedByKey = emptyMap())
    }

    fun loadMoreItems(): VaultBrowserSessionSnapshot {
        _snapshot = _snapshot.copy(loadedPageCount = _snapshot.loadedPageCount + 1)
        return _snapshot
    }

    /**
     * Guards against double-taps / concurrent save launches.
     * Returns the ordered batch to save if acquired, or null if a save is already running or nothing is pending.
     */
    fun tryBeginSave(): List<VaultDeviceItem>? {
        if (_snapshot.isSaving || _snapshot.pendingSelectedByKey.isEmpty()) return null
        val batch = _snapshot.pendingSelectedByKey.values.toList()
        _snapshot = _snapshot.copy(isSaving = true, lastSaveResultMessage = null)
        return batch
    }

    /**
     * Separates newly protected items from still-pending/failed items:
     * - Successfully saved keys are removed from [pendingSelectedByKey] and recorded in [savedKeys].
     * - Failed items remain selectable/pending (or kept for retry) and are never marked saved.
     * - Keeps the current browser step open so the user can continue browsing other folders!
     */
    fun finishSave(
        succeededKeys: Set<String>,
        resultMessage: String? = null
    ): VaultBrowserSessionSnapshot {
        val nextPending = LinkedHashMap(_snapshot.pendingSelectedByKey)
        succeededKeys.forEach { nextPending.remove(it) }
        val nextSaved = LinkedHashSet(_snapshot.savedKeys).apply { addAll(succeededKeys) }
        _snapshot = _snapshot.copy(
            pendingSelectedByKey = nextPending,
            savedKeys = nextSaved,
            isSaving = false,
            lastSaveResultMessage = resultMessage
        )
        return _snapshot
    }

    fun clearSaveResultMessage() {
        if (_snapshot.lastSaveResultMessage != null) {
            _snapshot = _snapshot.copy(lastSaveResultMessage = null)
        }
    }

    fun areAllSelectableFilesSelected(items: List<VaultDeviceItem>): Boolean {
        val candidates = items.filter { !it.isDirectory && it.selectionKey !in _snapshot.savedKeys }
        return candidates.isNotEmpty() && candidates.all { it.selectionKey in _snapshot.pendingSelectedByKey }
    }
}

/**
 * Builds an organized folder list combining [LibraryViewModel] folders with any additional
 * MediaStore folders (e.g. images or accessible files) without duplicating paths.
 */
fun combineLibraryAndExtraDeviceFolders(
    libraryFolders: List<MediaFolder>,
    extraItems: List<VaultDeviceItem>,
    safRootFolder: VaultDeviceFolder? = null
): List<VaultDeviceFolder> {
    val byPath = LinkedHashMap<String, VaultDeviceFolder>()
    libraryFolders.forEach { folder ->
        val normalized = normalizeRelativeFolderPath(folder.path)
        byPath[normalized] = VaultDeviceFolder(
            path = normalized,
            name = folder.name.ifBlank { normalized.substringAfterLast('/').ifBlank { "/" } },
            itemCount = folder.itemCount,
            totalBytes = folder.totalBytes,
            lastModifiedMs = folder.lastAddedMs,
            sampleUri = folder.sampleUri,
            sourceKind = VaultDeviceSourceKind.MEDIA_STORE
        )
    }

    val seenExtraUris = HashSet<String>()
    extraItems.forEach { item ->
        if (!seenExtraUris.add(item.selectionKey)) return@forEach
        val normalized = normalizeRelativeFolderPath(item.folderPath)
        val existing = byPath[normalized]
        if (existing == null) {
            val friendlyName = item.folderDisplayName.trim().ifBlank {
                normalized.substringAfterLast('/').ifBlank { normalized }
            }
            byPath[normalized] = VaultDeviceFolder(
                path = normalized,
                name = friendlyName,
                itemCount = 1,
                totalBytes = item.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = item.lastModifiedMs,
                sampleUri = if (item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")) item.uri else null,
                sourceKind = VaultDeviceSourceKind.MEDIA_STORE
            )
        } else {
            byPath[normalized] = existing.copy(
                itemCount = existing.itemCount + 1,
                totalBytes = existing.totalBytes + item.sizeBytes.coerceAtLeast(0L),
                lastModifiedMs = maxOf(existing.lastModifiedMs, item.lastModifiedMs),
                sampleUri = existing.sampleUri
                    ?: item.uri.takeIf { item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/") }
            )
        }
    }

    // Compute direct subfolder counts for nested relative paths like Movies -> Movies/Action
    val allPaths = byPath.keys.toList()
    val enriched = allPaths.map { path ->
        val folder = byPath.getValue(path)
        val prefix = if (path == "/" || path.startsWith("bucket:")) null else "$path/"
        val subCount = if (prefix == null) 0 else {
            allPaths.count { candidate ->
                candidate.startsWith(prefix) && !candidate.removePrefix(prefix).contains('/')
            }
        }
        folder.copy(subfolderCount = subCount)
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    return if (safRootFolder != null) listOf(safRootFolder) + enriched else enriched
}

/**
 * Builds the immediate contents (direct subfolders + deduplicated files) for a folder path
 * using [LibraryViewModel] folder items plus any extra MediaStore items in that folder.
 */
fun buildFolderContentsForPath(
    folderPath: String,
    libraryFolderItems: List<LibraryItem>,
    extraFolderItems: List<VaultDeviceItem>,
    allFolders: List<VaultDeviceFolder>
): List<VaultDeviceItem> {
    val normalizedTarget = normalizeRelativeFolderPath(folderPath)
    val resultByKey = LinkedHashMap<String, VaultDeviceItem>()

    // 1. Direct subfolders whose parent matches normalizedTarget
    if (normalizedTarget != "/" && !normalizedTarget.startsWith("bucket:")) {
        val prefix = "$normalizedTarget/"
        allFolders.forEach { candidate ->
            if (candidate.sourceKind == VaultDeviceSourceKind.MEDIA_STORE &&
                candidate.path.startsWith(prefix)
            ) {
                val remainder = candidate.path.removePrefix(prefix)
                if (remainder.isNotEmpty() && !remainder.contains('/')) {
                    val key = "dir:${candidate.path}"
                    resultByKey[key] = VaultDeviceItem(
                        id = key,
                        uri = Uri.parse("malekfolder://${Uri.encode(candidate.path)}"),
                        displayName = candidate.name,
                        extension = "",
                        mimeType = "inode/directory",
                        sizeBytes = -1L,
                        lastModifiedMs = candidate.lastModifiedMs,
                        folderPath = candidate.path,
                        folderDisplayPath = candidate.path,
                        isDirectory = true,
                        sourceKind = VaultDeviceSourceKind.MEDIA_STORE,
                        subfolderCount = candidate.itemCount
                    )
                }
            }
        }
    }

    // 2. Files from LibraryViewModel (video & audio)
    libraryFolderItems.forEach { libItem ->
        val media = libItem.media
        val rawName = media.title.trim().ifBlank { media.displayTitle }
        val ext = extractExtension(rawName, media.mimeType)
        val displayWithExt = ensureDisplayNameHasExtension(rawName, ext)
        val collectionName = when {
            media.isVideo -> "video"
            media.isAudio -> "audio"
            else -> "files"
        }
        val item = VaultDeviceItem(
            id = media.id,
            uri = media.uri,
            displayName = displayWithExt,
            extension = ext,
            mimeType = media.mimeType,
            sizeBytes = media.sizeBytes,
            lastModifiedMs = media.addedAtMs,
            folderPath = normalizedTarget,
            folderDisplayPath = normalizedTarget,
            isDirectory = false,
            sourceKind = VaultDeviceSourceKind.MEDIA_STORE,
            mediaStoreCollection = collectionName
        )
        resultByKey[item.selectionKey] = item
    }

    // 3. Extra MediaStore items (images, documents, other files) deduplicated against Library items
    extraFolderItems.forEach { extra ->
        if (!resultByKey.containsKey(extra.selectionKey)) {
            resultByKey[extra.selectionKey] = extra
        }
    }

    return resultByKey.values.toList()
}

fun filterAndSortDeviceFolders(
    folders: List<VaultDeviceFolder>,
    query: String
): List<VaultDeviceFolder> {
    val q = query.trim().lowercase(Locale.getDefault())
    if (q.isEmpty()) return folders
    return folders.filter {
        it.name.lowercase(Locale.getDefault()).contains(q) ||
            it.path.lowercase(Locale.getDefault()).contains(q)
    }
}

fun filterAndSortDeviceItems(
    items: List<VaultDeviceItem>,
    query: String,
    sort: VaultBrowserSort,
    ascending: Boolean = true
): List<VaultDeviceItem> {
    val q = query.trim().lowercase(Locale.getDefault())
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter {
            it.displayName.lowercase(Locale.getDefault()).contains(q) ||
                it.extension.lowercase(Locale.getDefault()).contains(q)
        }
    }
    val itemComparator = when (sort) {
        VaultBrowserSort.NAME -> compareBy<VaultDeviceItem> {
            it.displayName.lowercase(Locale.getDefault())
        }
        VaultBrowserSort.SIZE -> compareBy<VaultDeviceItem> {
            it.sizeBytes.coerceAtLeast(0L)
        }.thenBy { it.displayName.lowercase(Locale.getDefault()) }
        VaultBrowserSort.DATE -> compareBy<VaultDeviceItem> {
            it.lastModifiedMs
        }.thenBy { it.displayName.lowercase(Locale.getDefault()) }
    }
    val ordered = if (ascending) itemComparator else Collections.reverseOrder(itemComparator)
    return filtered.sortedWith(compareBy<VaultDeviceItem> { !it.isDirectory }.then(ordered))
}

internal fun normalizeRelativeFolderPath(rawPath: String): String {
    val trimmed = rawPath.trim().replace('\\', '/').trimEnd('/')
    return if (trimmed.isEmpty()) "/" else trimmed
}

internal fun extractExtension(name: String, mimeType: String): String {
    val fromName = name.substringAfterLast('.', "").trim()
    if (fromName.isNotEmpty() && fromName != name && fromName.length <= 10 && fromName.all { it.isLetterOrDigit() }) {
        return fromName.uppercase(Locale.ROOT)
    }
    return when (mimeType.lowercase(Locale.ROOT)) {
        "video/mp4" -> "MP4"
        "video/x-matroska" -> "MKV"
        "video/webm" -> "WEBM"
        "video/x-msvideo" -> "AVI"
        "video/quicktime" -> "MOV"
        "audio/mpeg" -> "MP3"
        "audio/mp4" -> "M4A"
        "audio/aac" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/ogg" -> "OGG"
        "audio/wav", "audio/x-wav" -> "WAV"
        "image/jpeg" -> "JPG"
        "image/png" -> "PNG"
        "image/webp" -> "WEBP"
        "image/gif" -> "GIF"
        "application/pdf" -> "PDF"
        "text/plain" -> "TXT"
        else -> ""
    }
}

private fun ensureDisplayNameHasExtension(name: String, extension: String): String {
    if (extension.isBlank()) return name
    val currentExt = name.substringAfterLast('.', "")
    if (currentExt.isNotEmpty() && currentExt != name && currentExt.equals(extension, ignoreCase = true)) {
        return name
    }
    if (currentExt.isNotEmpty() && currentExt != name && currentExt.length <= 6 && currentExt.all { it.isLetterOrDigit() }) {
        return name
    }
    return "$name.${extension.lowercase(Locale.ROOT)}"
}
