package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareDFileManifestRegistryTest {
    private fun manifest(name: String) = UltraShareDFileManifest.Manifest(
        listOf(
            UltraShareDFileManifest.Entry(
                fileName = name,
                sizeBytes = 0L,
                sha256 = ByteArray(32)
            )
        )
    )

    @Test
    fun acceptsUniquePositiveTransferIdsWithinSessionLimit() {
        val pending = LinkedHashMap<Int, UltraShareDFileManifest.Manifest>()

        assertTrue(UltraShareDFileManifestRegistry.tryAdd(pending, 1, manifest("one.bin")))
        assertTrue(UltraShareDFileManifestRegistry.tryAdd(pending, 2, manifest("two.bin")))
        assertEquals(2, pending.size)
    }

    @Test
    fun rejectsDuplicateTransferIdWithoutReplacingOriginalManifest() {
        val pending = LinkedHashMap<Int, UltraShareDFileManifest.Manifest>()
        val original = manifest("original.bin")

        assertTrue(UltraShareDFileManifestRegistry.tryAdd(pending, 7, original))
        assertFalse(UltraShareDFileManifestRegistry.tryAdd(pending, 7, manifest("replacement.bin")))

        assertEquals("original.bin", pending[7]?.entries?.single()?.fileName)
    }

    @Test
    fun rejectsInvalidIdsAndCapsPendingBatches() {
        val pending = LinkedHashMap<Int, UltraShareDFileManifest.Manifest>()

        assertFalse(UltraShareDFileManifestRegistry.tryAdd(pending, 0, manifest("zero.bin")))
        assertFalse(UltraShareDFileManifestRegistry.tryAdd(pending, -1, manifest("negative.bin")))

        repeat(UltraShareDFileManifestRegistry.MAX_PENDING_MANIFESTS) { index ->
            assertTrue(
                UltraShareDFileManifestRegistry.tryAdd(
                    pending,
                    index + 1,
                    manifest("file-$index.bin")
                )
            )
        }
        assertFalse(
            UltraShareDFileManifestRegistry.tryAdd(
                pending,
                UltraShareDFileManifestRegistry.MAX_PENDING_MANIFESTS + 1,
                manifest("overflow.bin")
            )
        )
        assertEquals(UltraShareDFileManifestRegistry.MAX_PENDING_MANIFESTS, pending.size)
    }
}
