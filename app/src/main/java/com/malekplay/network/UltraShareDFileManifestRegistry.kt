package com.malekplay.network

/**
 * Bounds authenticated-but-not-yet-completed DFile receive batches per session.
 * A duplicate transfer ID must never replace the manifest that was first bound
 * to that transfer.
 */
internal object UltraShareDFileManifestRegistry {
    const val MAX_PENDING_MANIFESTS = 8

    fun tryAdd(
        pending: MutableMap<Int, UltraShareDFileManifest.Manifest>,
        transferId: Int,
        manifest: UltraShareDFileManifest.Manifest
    ): Boolean = synchronized(pending) {
        transferId > 0 &&
            pending.size < MAX_PENDING_MANIFESTS &&
            !pending.containsKey(transferId) &&
            runCatching {
                pending[transferId] = manifest
                true
            }.getOrDefault(false)
    }
}
