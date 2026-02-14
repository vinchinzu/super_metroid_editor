package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.image.BufferedImage
import java.awt.RenderingHints

enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    SOLID("Solid", "S", 0xCC4488FF),
    SLOPE("Slope", "/", 0xCCFF8844),
    DOOR("Door", "D", 0xCC4488FF),
    SPIKE("Spike", "!", 0xCCFF4444),
    BOMB("Bomb", "B", 0xCCAA44DD),
    SHOT("Shot", "X", 0xCCFFAA22),
    CRUMBLE("Crumble", "C", 0xCCDDAA22),
    GRAPPLE("Grapple", "G", 0xCC44CC88),
    SPEED("Speed", "~", 0xCC88CCFF),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableStateOf(1f) }
    var showGrid by remember { mutableStateOf(true) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>() }
    
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (room != null && romParser != null) {
                var isLoading by remember(room.id) { mutableStateOf(true) }
                var errorMessage by remember(room.id) { mutableStateOf<String?>(null) }
                var renderData by remember(room.id) { mutableStateOf<RoomRenderData?>(null) }
                var roomInfoText by remember(room.id) { mutableStateOf("") }
                
                LaunchedEffect(room.id) {
                    isLoading = true
                    errorMessage = null
                    renderData = null
                    try {
                        val roomId = room.getRoomIdAsInt()
                        val roomHeader = romParser.readRoomHeader(roomId)
                        if (roomHeader != null) {
                            roomInfoText = "${roomHeader.areaName} • ${roomHeader.width}×${roomHeader.height}"
                            renderData = MapRenderer(romParser).renderRoom(roomHeader)
                            if (renderData == null) errorMessage = "Failed to render"
                        } else {
                            errorMessage = "Room header not found"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Error: ${e.message}"
                        e.printStackTrace()
                    } finally {
                        isLoading = false
                    }
                }
                
                // ─── Single compact toolbar row ─────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Room name + info
                    Text(room.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
                    Text(room.id, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (roomInfoText.isNotEmpty()) {
                        Text(roomInfoText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    // Divider
                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                    
                    // Zoom
                    Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp, modifier = Modifier.width(32.dp))
                    Slider(
                        value = zoomLevel,
                        onValueChange = { zoomLevel = it },
                        valueRange = 0.25f..4f,
                        steps = 14,
                        modifier = Modifier.width(80.dp)
                    )
                    
                    // Grid toggle
                    FilterChip(
                        selected = showGrid,
                        onClick = { showGrid = !showGrid },
                        label = { Text("Grid", fontSize = 9.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                    
                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                    
                    // Overlay chips
                    TileOverlay.values().forEach { overlay ->
                        val isOn = overlayToggles[overlay] ?: false
                        FilterChip(
                            selected = isOn,
                            onClick = { overlayToggles[overlay] = !isOn },
                            label = { Text(overlay.label, fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(overlay.color).copy(alpha = 0.3f)
                            )
                        )
                    }
                }
                
                // ─── Map Display ─────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0C0C18)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Loading...", color = Color.White)
                            }
                        }
                        errorMessage != null -> {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                        renderData != null -> {
                            val data = renderData!!
                            val activeOverlays = overlayToggles.filter { it.value }.keys
                            
                            val compositeImage = remember(data, activeOverlays.toSet(), showGrid) {
                                buildCompositeImage(data, activeOverlays, showGrid)
                            }
                            val bitmap = remember(compositeImage) {
                                compositeImage.toComposeImageBitmap()
                            }
                            
                            val hScrollState = rememberScrollState()
                            val vScrollState = rememberScrollState()
                            val coroutineScope = rememberCoroutineScope()
                            var isDragging by remember { mutableStateOf(false) }
                            var lastDragX by remember { mutableStateOf(0f) }
                            var lastDragY by remember { mutableStateOf(0f) }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Mouse wheel = zoom in/out
                                    .onPointerEvent(PointerEventType.Scroll) { event ->
                                        val scrollDelta = event.changes.first().scrollDelta.y
                                        val zoomFactor = if (scrollDelta < 0) 1.15f else 1f / 1.15f
                                        zoomLevel = (zoomLevel * zoomFactor).coerceIn(0.25f, 4f)
                                    }
                                    // Middle click = start drag
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        val nativeEvent = event.nativeEvent as? MouseEvent
                                        if (nativeEvent != null && nativeEvent.button == MouseEvent.BUTTON2) {
                                            isDragging = true
                                            val pos = event.changes.first().position
                                            lastDragX = pos.x
                                            lastDragY = pos.y
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Release) { event ->
                                        val nativeEvent = event.nativeEvent as? MouseEvent
                                        if (nativeEvent == null || nativeEvent.button == MouseEvent.BUTTON2) {
                                            isDragging = false
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        if (isDragging) {
                                            val pos = event.changes.first().position
                                            val dx = lastDragX - pos.x
                                            val dy = lastDragY - pos.y
                                            lastDragX = pos.x
                                            lastDragY = pos.y
                                            coroutineScope.launch {
                                                hScrollState.scrollTo((hScrollState.value + dx.toInt()).coerceIn(0, hScrollState.maxValue))
                                                vScrollState.scrollTo((vScrollState.value + dy.toInt()).coerceIn(0, vScrollState.maxValue))
                                            }
                                        }
                                    }
                                    .horizontalScroll(hScrollState)
                                    .verticalScroll(vScrollState)
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = room.name,
                                    modifier = Modifier
                                        .requiredWidth((data.width * zoomLevel).dp)
                                        .requiredHeight((data.height * zoomLevel).dp),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }
            } else if (room == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a room from the list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Load a ROM file first", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun buildCompositeImage(
    data: RoomRenderData,
    activeOverlays: Set<TileOverlay>,
    showGrid: Boolean
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    
    if (activeOverlays.isEmpty()) return img
    
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    
    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall
    
    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) {
        g.dispose()
        return img
    }
    
    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue
            
            val blockType = data.blockTypes[idx]
            val px = bx * 16
            val py = by * 16
            
            val matchingOverlays = mutableListOf<TileOverlay>()
            if (activeOverlays.contains(TileOverlay.SOLID) && blockType == 0x8) matchingOverlays.add(TileOverlay.SOLID)
            if (activeOverlays.contains(TileOverlay.SLOPE) && blockType == 0x1) matchingOverlays.add(TileOverlay.SLOPE)
            if (activeOverlays.contains(TileOverlay.DOOR) && blockType == 0x9) matchingOverlays.add(TileOverlay.DOOR)
            if (activeOverlays.contains(TileOverlay.SPIKE) && blockType == 0xA) matchingOverlays.add(TileOverlay.SPIKE)
            if (activeOverlays.contains(TileOverlay.BOMB) && blockType == 0xF) matchingOverlays.add(TileOverlay.BOMB)
            if (activeOverlays.contains(TileOverlay.SHOT) && blockType == 0xC) matchingOverlays.add(TileOverlay.SHOT)
            if (activeOverlays.contains(TileOverlay.CRUMBLE) && blockType == 0xB) matchingOverlays.add(TileOverlay.CRUMBLE)
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.SPEED) && blockType == 0x3) matchingOverlays.add(TileOverlay.SPEED)
            
            var iconX = px + 16 - 8
            val iconY = py + 16 - 8
            
            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )
                g.color = java.awt.Color(0, 0, 0, 160)
                g.fillRect(iconX, iconY, 8, 8)
                g.color = color
                g.drawRect(iconX, iconY, 7, 7)
                g.font = java.awt.Font("Monospaced", java.awt.Font.BOLD, 7)
                g.drawString(overlay.shortLabel, iconX + 1, iconY + 7)
                iconX -= 9
            }
        }
    }
    
    g.dispose()
    return img
}
