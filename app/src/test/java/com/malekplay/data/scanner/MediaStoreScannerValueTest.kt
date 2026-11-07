package com.malekplay.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreScannerValueTest {

    @Test
    fun missingColumnProducesNull() {
        assertEquals(null, nullableMediaStoreLong(columnPresent = false, columnIsNull = false, value = 42L))
    }

    @Test
    fun sqlNullProducesNull() {
        assertEquals(null, nullableMediaStoreLong(columnPresent = true, columnIsNull = true, value = 0L))
    }

    @Test
    fun numericZeroRemainsAValidIdentifier() {
        assertEquals(0L, nullableMediaStoreLong(columnPresent = true, columnIsNull = false, value = 0L))
    }

    @Test
    fun nonZeroIdentifierIsPreserved() {
        assertEquals(987654321L, nullableMediaStoreLong(columnPresent = true, columnIsNull = false, value = 987654321L))
    }
}
