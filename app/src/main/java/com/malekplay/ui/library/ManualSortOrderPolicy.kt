package com.malekplay.ui.library

/**
 * Reorders only the currently visible items while preserving the saved slots
 * occupied by filtered-out or otherwise hidden media IDs.
 */
internal object ManualSortOrderPolicy {
    fun move(
        currentOrder: List<String>,
        visibleIds: List<String>,
        draggedId: String,
        targetIndex: Int
    ): List<String> {
        if (draggedId.isBlank()) return currentOrder
        val uniqueVisibleIds = visibleIds.filter { it.isNotBlank() }.distinct()
        if (uniqueVisibleIds.isEmpty()) return currentOrder

        val visibleSet = uniqueVisibleIds.toSet()
        val allOrder = (currentOrder + uniqueVisibleIds).distinct().toMutableList()
        val visibleSlots = allOrder.indices.filter { allOrder[it] in visibleSet }
        val visibleOrder = visibleSlots.map { allOrder[it] }.toMutableList()
        val sourceIndex = visibleOrder.indexOf(draggedId)
        if (sourceIndex < 0) return currentOrder

        val destinationIndex = targetIndex.coerceIn(0, visibleOrder.lastIndex)
        val movedId = visibleOrder.removeAt(sourceIndex)
        visibleOrder.add(destinationIndex.coerceAtMost(visibleOrder.size), movedId)
        visibleSlots.forEachIndexed { index, slot -> allOrder[slot] = visibleOrder[index] }
        return allOrder
    }
}
