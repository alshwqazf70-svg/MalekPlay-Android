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
        assertFalse(sanitized.contains("\\\\"))
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('\u0000'))
        assertTrue(sanitized.isNotBlank())
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
