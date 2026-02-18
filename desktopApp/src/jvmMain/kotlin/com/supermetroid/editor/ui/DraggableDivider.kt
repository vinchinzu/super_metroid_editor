package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draggable strip for resizing column width. Grabs on press (no click needed):
 * while pressed, move applies resize; overlay continues once pointer leaves the strip.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DraggableDividerVertical(
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragDelta: (deltaPx: Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 10.dp
) {
    var pressed by remember { mutableStateOf(false) }
    var lastX by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .onPointerEvent(PointerEventType.Press) {
                lastX = it.changes.first().position.x
                pressed = true
                onDragStart()
            }
            .onPointerEvent(PointerEventType.Move) {
                if (!pressed) return@onPointerEvent
                val x = it.changes.first().position.x
                onDragDelta(x - lastX)
                lastX = x
            }
            .onPointerEvent(PointerEventType.Release) {
                pressed = false
                onDragEnd()
            }
    )
}

/**
 * Draggable strip for resizing tileset height. Grabs on press; drag up = taller, down = shorter.
 * Overlay continues once pointer leaves the strip.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DraggableDividerHorizontal(
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragDelta: (deltaPx: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    var pressed by remember { mutableStateOf(false) }
    var lastY by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .onPointerEvent(PointerEventType.Press) {
                lastY = it.changes.first().position.y
                pressed = true
                onDragStart()
            }
            .onPointerEvent(PointerEventType.Move) {
                if (!pressed) return@onPointerEvent
                val y = it.changes.first().position.y
                onDragDelta(y - lastY)
                lastY = y
            }
            .onPointerEvent(PointerEventType.Release) {
                pressed = false
                onDragEnd()
            }
    )
}
