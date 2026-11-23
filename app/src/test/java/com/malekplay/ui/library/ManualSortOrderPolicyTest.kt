package com.malekplay.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ManualSortOrderPolicyTest {
    @Test
    fun movesItemToRequestedFinalIndex() {
        assertEquals(
            listOf("b", "c", "a"),
            ManualSortOrderPolicy.move(
                currentOrder = listOf("a", "b", "c"),
                visibleIds = listOf("a", "b", "c"),
                draggedId = "a",
                targetIndex = 2
            )
        )
    }

    @Test
    fun preservesSlotsForItemsHiddenByCurrentFilter() {
        assertEquals(
            listOf("c", "hidden", "a", "b"),
            ManualSortOrderPolicy.move(
                currentOrder = listOf("a", "hidden", "b", "c"),
                visibleIds = listOf("a", "b", "c"),
                draggedId = "c",
                targetIndex = 0
            )
        )
    }

    @Test
    fun appendsNewVisibleItemsWithoutDroppingExistingOrder() {
        assertEquals(
            listOf("b", "a", "new"),
            ManualSortOrderPolicy.move(
                currentOrder = listOf("a", "b"),
                visibleIds = listOf("a", "b", "new"),
                draggedId = "b",
                targetIndex = 0
            )
        )
    }

    @Test
    fun ignoresUnknownDraggedItemsAndBlankIds() {
        val order = listOf("a", "b")
        assertEquals(order, ManualSortOrderPolicy.move(order, listOf("a", "b"), "missing", 0))
        assertEquals(order, ManualSortOrderPolicy.move(order, listOf("a", "b"), " ", 0))
    }
}
