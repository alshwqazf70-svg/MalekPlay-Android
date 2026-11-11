package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareFolderArchiveTest {

    @Test
    fun safeSegmentRemovesTraversalSeparatorsAndControlCharacters() {
        val sanitized = UltraShareFolderArchive.safeSegment("../photos\\\\2026\u0000:family")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('\u0000'))
        assertTrue(sanitized.isNotBlank())
    }

    @Test
    fun archiveEntryPathValidationRejectsTraversalAndAbsolutePaths() {
        listOf("../escape.txt", "/absolute.txt", "folder/../escape.txt", "folder//empty.txt", "folder\\\\escape.txt").forEach { unsafe ->
            assertTrue(runCatching {
                UltraShareFolderArchive.validatedArchiveSegments(unsafe, false)
            }.isFailure)
        }
    }

    @Test
    fun archiveEntryPathValidationRejectsExcessiveNesting() {
        val deepPath = (1..65).joinToString("/") { "folder" } + "/file.txt"
        assertTrue(runCatching {
            UltraShareFolderArchive.validatedArchiveSegments(deepPath, false)
        }.isFailure)
    }

    @Test
    fun archiveEntryPathValidationPreservesNestedUnicodePaths() {
        assertEquals(
            listOf("مجلد", "صور", "image.jpg"),
            UltraShareFolderArchive.validatedArchiveSegments("مجلد/صور/image.jpg", false)
        )
        assertEquals(
            listOf("مجلد", "صور"),
            UltraShareFolderArchive.validatedArchiveSegments("مجلد/صور/", true)
        )
    }

    @Test
    fun safeSegmentReplacesEmptyAndDotOnlyNames() {
        assertEquals("item", UltraShareFolderArchive.safeSegment(""))
        assertEquals("item", UltraShareFolderArchive.safeSegment("..."))
        assertEquals("item", UltraShareFolderArchive.safeSegment("   "))
    }

    @Test
    fun safeSegmentPreservesOrdinaryUnicodeNames() {
        assertEquals("صور العائلة 2026", UltraShareFolderArchive.safeSegment("صور العائلة 2026"))
    }
}
