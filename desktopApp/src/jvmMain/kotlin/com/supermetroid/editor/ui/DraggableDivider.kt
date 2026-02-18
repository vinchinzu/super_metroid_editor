package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * Visual drag handle for resizing column width.
 * Only detects press/release to start/stop dragging.
 * All movement tracking is handled by a full-area overlay in the parent layout
 * using absolute coordinates for jank-free 1:1 mouse tracking.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DraggableDividerVertical(
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
            .onPointerEvent(PointerEventType.Press) { onDragStart() }
            .onPointerEvent(PointerEventType.Release) { onDragEnd() }
    )
}

/**
 * Visual drag handle for resizing tileset height.
 * Only detects press/release; movement tracked by parent overlay.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DraggableDividerHorizontal(
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .onPointerEvent(PointerEventType.Press) { onDragStart() }
            .onPointerEvent(PointerEventType.Release) { onDragEnd() }
    )
}
