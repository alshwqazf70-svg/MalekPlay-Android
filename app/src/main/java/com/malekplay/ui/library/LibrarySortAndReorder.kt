package com.malekplay.ui.library

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.domain.model.SortMode
import com.malekplay.domain.model.ViewMode
import com.malekplay.ui.components.EmptyState
import com.malekplay.ui.components.MediaGridCard
import com.malekplay.ui.components.MediaListRow
import kotlinx.coroutines.delay

@Composable
fun sortModeLabel(mode: SortMode): String = when (mode) {
    SortMode.NEWEST -> stringResource(R.string.sort_newest)
    SortMode.OLDEST -> stringResource(R.string.sort_oldest)
    SortMode.NAME -> stringResource(R.string.sort_name)
    SortMode.DURATION -> stringResource(R.string.sort_duration)
    SortMode.SIZE -> stringResource(R.string.sort_size)
    SortMode.TYPE -> stringResource(R.string.sort_type)
    SortMode.LAST_PLAYED -> stringResource(R.string.sort_last_played)
    SortMode.MANUAL -> stringResource(R.string.sort_manual)
}

@Composable
fun LibrarySortBar(
    sortMode: SortMode,
    sortAscending: Boolean,
    foldersFirst: Boolean,
    showFoldersFirstOption: Boolean = true,
    onSelectMode: (SortMode) -> Unit,
    onToggleAscending: () -> Unit,
    onToggleFoldersFirst: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.38f)),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { menuExpanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = sortModeLabel(sortMode),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (showFoldersFirstOption) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (foldersFirst) stringResource(R.string.sort_folders_first)
                                    else stringResource(R.string.sort_files_first)
                                )
                            },
                            trailingIcon = {
                                if (foldersFirst) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = { onToggleFoldersFirst() }
                        )
                    }
                    if (sortMode != SortMode.MANUAL) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sortAscending) stringResource(R.string.sort_ascending)
                                    else stringResource(R.string.sort_descending)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = if (sortAscending) Icons.Filled.ArrowUpward
                                    else Icons.Filled.ArrowDownward,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = { onToggleAscending() }
                        )
                    }
                    SortMode.entries.forEach { mode ->
                        val selected = sortMode == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortModeLabel(mode),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) colors.primary else colors.onSurface
                                )
                            },
                            leadingIcon = {
                                if (mode == SortMode.MANUAL) {
                                    Icon(
                                        Icons.Filled.DragIndicator,
                                        contentDescription = null,
                                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                onSelectMode(mode)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            if (sortMode != SortMode.MANUAL) {
                Surface(
                    shape = CircleShape,
                    color = colors.primaryContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onToggleAscending() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (sortAscending) Icons.Filled.ArrowUpward
                            else Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (sortAscending) stringResource(R.string.sort_ascending)
                            else stringResource(R.string.sort_descending),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }

        val quickModes = listOf(
            SortMode.NEWEST,
            SortMode.NAME,
            SortMode.DURATION,
            SortMode.SIZE,
            SortMode.LAST_PLAYED,
            SortMode.MANUAL
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            quickModes.forEach { mode ->
                val selected = sortMode == mode
                val chipBg = if (selected) colors.primary else colors.surfaceContainerHigh.copy(alpha = 0.78f)
                val chipFg = if (selected) contrastingOnColor(colors.primary) else colors.onSurfaceVariant
                Surface(
                    shape = CircleShape,
                    color = chipBg,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.50f)
                    ),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onSelectMode(mode) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (mode == SortMode.MANUAL) {
                            Icon(
                                imageVector = Icons.Filled.DragIndicator,
                                contentDescription = null,
                                tint = chipFg,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = sortModeLabel(mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = chipFg,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManualSortBanner(
    visible: Boolean,
    hasCustomOrder: Boolean,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = colors.primaryContainer.copy(alpha = 0.50f),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sort_manual_banner_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.sort_manual_banner_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasCustomOrder) {
                    TextButton(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.sort_manual_reset))
                    }
                }
                TextButton(onClick = onDone) {
                    Text(
                        text = stringResource(R.string.sort_manual_done),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun findDropTargetIndex(
    items: List<LibraryItem>,
    bounds: Map<String, Rect>,
    draggedId: String,
    pointerRoot: Offset,
    grabOffset: Offset
): Int {
    val draggedBounds = bounds[draggedId] ?: return -1
    val draggedCenter = Offset(
        pointerRoot.x - grabOffset.x + draggedBounds.width / 2f,
        pointerRoot.y - grabOffset.y + draggedBounds.height / 2f
    )
    return items.indexOfFirst { row ->
        row.media.id != draggedId && bounds[row.media.id]?.contains(draggedCenter) == true
    }
}

@Composable
fun ReorderableMediaCollection(
    items: List<LibraryItem>,
    viewMode: ViewMode,
    emptyTitle: String,
    emptyBody: String,
    showFolder: Boolean = false,
    lastPlayedMediaId: String? = null,
    onPlay: (MediaItem) -> Unit,
    onSend: (MediaItem) -> Unit = {},
    selectionMode: Boolean = false,
    selectedUris: Set<Uri> = emptySet(),
    onToggleSelection: (Uri) -> Unit = {},
    onFavourite: (LibraryItem) -> Unit,
    onAddToPlaylist: (LibraryItem) -> Unit,
    onDeleted: () -> Unit = {},
    manualReorderEnabled: Boolean = false,
    onMoveManualItem: (String, Int, List<LibraryItem>) -> Unit = { _, _, _ -> },
    onStepManualItem: (String, Int, List<LibraryItem>) -> Unit = { _, _, _ -> }
) {
    if (items.isEmpty()) {
        EmptyState(title = emptyTitle, body = emptyBody)
        return
    }

    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var viewportBounds by remember { mutableStateOf<Rect?>(null) }
    var activeDragId by remember { mutableStateOf<String?>(null) }
    var activePointerRoot by remember { mutableStateOf(Offset.Zero) }
    var activeGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var dragHasMoved by remember { mutableStateOf(false) }
    var lastTargetIndex by remember { mutableIntStateOf(-1) }
    val dragEdgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val latestItems by rememberUpdatedState(items)

    LaunchedEffect(viewMode, items.map { it.media.id }) {
        itemBounds.clear()
    }

    LaunchedEffect(activeDragId, viewMode) {
        val draggedId = activeDragId ?: return@LaunchedEffect
        while (activeDragId == draggedId) {
            if (dragHasMoved) {
                val viewport = viewportBounds
                if (viewport != null) {
                    val pointerY = activePointerRoot.y
                    val scrollDelta = when {
                        pointerY < viewport.top + dragEdgePx ->
                            -((viewport.top + dragEdgePx - pointerY) * 0.24f).coerceIn(7f, 30f)
                        pointerY > viewport.bottom - dragEdgePx ->
                            ((pointerY - (viewport.bottom - dragEdgePx)) * 0.24f).coerceIn(7f, 30f)
                        else -> 0f
                    }
                    if (scrollDelta != 0f) {
                        if (viewMode == ViewMode.GRID) {
                            gridState.scrollBy(scrollDelta)
                            val visibleKeys = gridState.layoutInfo.visibleItemsInfo
                                .mapNotNull { it.key as? String }.toSet()
                            itemBounds.keys.retainAll(visibleKeys)
                        } else {
                            listState.scrollBy(scrollDelta)
                            val visibleKeys = listState.layoutInfo.visibleItemsInfo
                                .mapNotNull { it.key as? String }.toSet()
                            itemBounds.keys.retainAll(visibleKeys)
                        }
                        val hit = findDropTargetIndex(
                            latestItems, itemBounds, draggedId, activePointerRoot, activeGrabOffset
                        )
                        if (hit >= 0 && hit != lastTargetIndex) {
                            lastTargetIndex = hit
                            onMoveManualItem(draggedId, hit, latestItems)
                        }
                    }
                }
            }
            delay(16)
        }
    }

    val containerModifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { viewportBounds = it.boundsInRoot() }

    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = containerModifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            gridItemsIndexed(items, key = { _, row -> row.media.id }) { index, item ->
                var dragOffset by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var isDragging by remember(item.media.id) { mutableStateOf(false) }
                var dragPointerRoot by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var grabOffset by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var itemPositionRoot by remember(item.media.id) { mutableStateOf(Offset.Zero) }

                val cardWrapperModifier = Modifier
                    .fillMaxWidth()
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationX = dragOffset.x
                        translationY = dragOffset.y
                        val scale = if (isDragging) 1.045f else 1f
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = if (isDragging) 20.dp.toPx() else 0f
                    }
                    .zIndex(if (isDragging) 12f else 0f)
                    .onGloballyPositioned { coords ->
                        val bounds = coords.boundsInRoot()
                        itemBounds[item.media.id] = bounds
                        itemPositionRoot = bounds.topLeft - dragOffset
                        if (isDragging) {
                            dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                        }
                    }
                    .pointerInput(item.media.id, manualReorderEnabled, selectionMode) {
                        if (manualReorderEnabled && !selectionMode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                longPress.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                isDragging = true
                                grabOffset = longPress.position
                                dragPointerRoot = itemPositionRoot + longPress.position
                                activeDragId = item.media.id
                                activePointerRoot = dragPointerRoot
                                activeGrabOffset = grabOffset
                                dragHasMoved = false
                                lastTargetIndex = latestItems.indexOfFirst { it.media.id == item.media.id }
                                var released = false
                                try {
                                    while (!released) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                                        if (!change.pressed) {
                                            released = true
                                        } else {
                                            val delta = change.positionChange()
                                            if (delta != Offset.Zero) {
                                                dragHasMoved = true
                                                dragPointerRoot += delta
                                                activePointerRoot = dragPointerRoot
                                                dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                                                change.consume()
                                            }
                                            val hit = findDropTargetIndex(
                                                latestItems, itemBounds, item.media.id, dragPointerRoot, grabOffset
                                            )
                                            if (hit >= 0 && hit != lastTargetIndex) {
                                                lastTargetIndex = hit
                                                onMoveManualItem(item.media.id, hit, latestItems)
                                            }
                                        }
                                    }
                                } finally {
                                    activeDragId = null
                                    dragHasMoved = false
                                    dragOffset = Offset.Zero
                                    isDragging = false
                                }
                            }
                        }
                    }

                Column(
                    modifier = cardWrapperModifier.then(
                        if (isDragging) {
                            Modifier.border(2.dp, colors.primary, RoundedCornerShape(20.dp))
                        } else {
                            Modifier
                        }
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MediaGridCard(
                        item = item,
                        isLastPlayed = item.media.id == lastPlayedMediaId,
                        onPlay = { onPlay(item.media) },
                        onSend = { onSend(item.media) },
                        onFavourite = { onFavourite(item) },
                        onAddToPlaylist = { onAddToPlaylist(item) },
                        onDeleted = onDeleted,
                        selectionMode = selectionMode,
                        isSelected = item.media.uri in selectedUris,
                        enableLongPressSelection = !manualReorderEnabled,
                        onSelect = { onToggleSelection(item.media.uri) }
                    )
                    if (manualReorderEnabled && !selectionMode) {
                        ManualOrderControlStrip(
                            position = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onMoveUp = { onStepManualItem(item.media.id, -1, items) },
                            onMoveDown = { onStepManualItem(item.media.id, 1, items) },
                            dragHandleModifier = Modifier.pointerInput(item.media.id, items.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isDragging = true
                                    grabOffset = down.position
                                    dragPointerRoot = itemPositionRoot + down.position
                                    activeDragId = item.media.id
                                    activePointerRoot = dragPointerRoot
                                    activeGrabOffset = grabOffset
                                    dragHasMoved = false
                                    lastTargetIndex = latestItems.indexOfFirst { it.media.id == item.media.id }
                                    var released = false
                                    try {
                                        while (!released) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                released = true
                                            } else {
                                                val delta = change.positionChange()
                                                if (delta != Offset.Zero) {
                                                    dragHasMoved = true
                                                    dragPointerRoot += delta
                                                    activePointerRoot = dragPointerRoot
                                                    dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                                                    change.consume()
                                                }
                                                val hit = findDropTargetIndex(
                                                    latestItems, itemBounds, item.media.id, dragPointerRoot, grabOffset
                                                )
                                                if (hit >= 0 && hit != lastTargetIndex) {
                                                    lastTargetIndex = hit
                                                    onMoveManualItem(item.media.id, hit, latestItems)
                                                }
                                            }
                                        }
                                    } finally {
                                        activeDragId = null
                                        dragHasMoved = false
                                        dragOffset = Offset.Zero
                                        isDragging = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = containerModifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items, key = { _, row -> row.media.id }) { index, item ->
                var dragOffset by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var isDragging by remember(item.media.id) { mutableStateOf(false) }
                var dragPointerRoot by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var grabOffset by remember(item.media.id) { mutableStateOf(Offset.Zero) }
                var itemPositionRoot by remember(item.media.id) { mutableStateOf(Offset.Zero) }

                val rowModifier = Modifier
                    .fillMaxWidth()
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .graphicsLayer {
                        translationX = dragOffset.x
                        translationY = dragOffset.y
                        val scale = if (isDragging) 1.03f else 1f
                        scaleX = scale
                        scaleY = scale
                        shadowElevation = if (isDragging) 18.dp.toPx() else 0f
                    }
                    .zIndex(if (isDragging) 12f else 0f)
                    .onGloballyPositioned { coords ->
                        val bounds = coords.boundsInRoot()
                        itemBounds[item.media.id] = bounds
                        itemPositionRoot = bounds.topLeft - dragOffset
                        if (isDragging) {
                            dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                        }
                    }
                    .pointerInput(item.media.id, manualReorderEnabled, selectionMode) {
                        if (manualReorderEnabled && !selectionMode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                longPress.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                isDragging = true
                                grabOffset = longPress.position
                                dragPointerRoot = itemPositionRoot + longPress.position
                                activeDragId = item.media.id
                                activePointerRoot = dragPointerRoot
                                activeGrabOffset = grabOffset
                                dragHasMoved = false
                                lastTargetIndex = latestItems.indexOfFirst { it.media.id == item.media.id }
                                var released = false
                                try {
                                    while (!released) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                                        if (!change.pressed) {
                                            released = true
                                        } else {
                                            val delta = change.positionChange()
                                            if (delta != Offset.Zero) {
                                                dragHasMoved = true
                                                dragPointerRoot += delta
                                                activePointerRoot = dragPointerRoot
                                                dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                                                change.consume()
                                            }
                                            val hit = findDropTargetIndex(
                                                latestItems, itemBounds, item.media.id, dragPointerRoot, grabOffset
                                            )
                                            if (hit >= 0 && hit != lastTargetIndex) {
                                                lastTargetIndex = hit
                                                onMoveManualItem(item.media.id, hit, latestItems)
                                            }
                                        }
                                    }
                                } finally {
                                    activeDragId = null
                                    dragHasMoved = false
                                    dragOffset = Offset.Zero
                                    isDragging = false
                                }
                            }
                        }
                    }

                Column(
                    modifier = rowModifier.then(
                        if (isDragging) {
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .border(2.dp, colors.primary, RoundedCornerShape(16.dp))
                        } else {
                            Modifier
                        }
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MediaListRow(
                        item = item,
                        isCurrent = item.media.id == lastPlayedMediaId,
                        showFolder = showFolder,
                        onPlay = { onPlay(item.media) },
                        onFavourite = { onFavourite(item) },
                        onAddToPlaylist = { onAddToPlaylist(item) },
                        onDeleted = onDeleted,
                        onSend = { onSend(item.media) },
                        selectionMode = selectionMode,
                        isSelected = item.media.uri in selectedUris,
                        enableLongPressSelection = !manualReorderEnabled,
                        onSelect = { onToggleSelection(item.media.uri) }
                    )
                    if (manualReorderEnabled && !selectionMode) {
                        ManualOrderControlStrip(
                            position = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onMoveUp = { onStepManualItem(item.media.id, -1, items) },
                            onMoveDown = { onStepManualItem(item.media.id, 1, items) },
                            dragHandleModifier = Modifier.pointerInput(item.media.id, items.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isDragging = true
                                    grabOffset = down.position
                                    dragPointerRoot = itemPositionRoot + down.position
                                    activeDragId = item.media.id
                                    activePointerRoot = dragPointerRoot
                                    activeGrabOffset = grabOffset
                                    dragHasMoved = false
                                    lastTargetIndex = latestItems.indexOfFirst { it.media.id == item.media.id }
                                    var released = false
                                    try {
                                        while (!released) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                released = true
                                            } else {
                                                val delta = change.positionChange()
                                                if (delta != Offset.Zero) {
                                                    dragHasMoved = true
                                                    dragPointerRoot += delta
                                                    activePointerRoot = dragPointerRoot
                                                    dragOffset = dragPointerRoot - grabOffset - itemPositionRoot
                                                    change.consume()
                                                }
                                                val hit = findDropTargetIndex(
                                                    latestItems, itemBounds, item.media.id, dragPointerRoot, grabOffset
                                                )
                                                if (hit >= 0 && hit != lastTargetIndex) {
                                                    lastTargetIndex = hit
                                                    onMoveManualItem(item.media.id, hit, latestItems)
                                                }
                                            }
                                        }
                                    } finally {
                                        activeDragId = null
                                        dragHasMoved = false
                                        dragOffset = Offset.Zero
                                        isDragging = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualOrderControlStrip(
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragHandleModifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                modifier = Modifier
            ) {
                Text(
                    text = "#$position",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            StepButton(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.sort_manual_move_up),
                enabled = canMoveUp,
                onClick = onMoveUp
            )
            StepButton(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.sort_manual_move_down),
                enabled = canMoveDown,
                onClick = onMoveDown
            )
            Box(
                modifier = dragHandleModifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .border(1.dp, colors.primary.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (enabled) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}
