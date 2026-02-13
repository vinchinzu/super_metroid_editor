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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Property overlay types that can be toggled on/off
 */
enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    SOLID("Solid", "S", 0xCC4488FF),
    SLOPE("Slopes", "/", 0xCCFF8844),
    DOOR("Doors", "D", 0xCC4488FF),
    SPIKE("Spikes", "!", 0xCCFF4444),
    BOMB("Bomb Blocks", "B", 0xCCAA44DD),
    SHOT("Shot Blocks", "X", 0xCCFFAA22),
    CRUMBLE("Crumble", "C", 0xCCDDAA22),
    GRAPPLE("Grapple", "G", 0xCC44CC88),
    SPEED("Speed/Treadmill", "~", 0xCC88CCFF),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    // Toolbar state
    var zoomLevel by remember { mutableStateOf(1f) }
    var showGrid by remember { mutableStateOf(true) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>() }
    
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Room info header
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                Text(
                    text = room?.name ?: "No room selected",
                    style = MaterialTheme.typography.titleMedium
                )
                if (room != null) {
                    Text(
                        text = "Room ID: ${room.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
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
                            roomInfoText = "${roomHeader.areaName}  •  ${roomHeader.width}×${roomHeader.height} screens  •  Map: (${roomHeader.mapX}, ${roomHeader.mapY})"
                            val mapRenderer = MapRenderer(romParser)
                            renderData = mapRenderer.renderRoom(roomHeader)
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
                
                // Room metadata
                if (roomInfoText.isNotEmpty()) {
                    Text(
                        text = roomInfoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                
                // ─── Toolbar ───────────────────────────────────────
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Zoom controls
                    Text("Zoom:", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    Slider(
                        value = zoomLevel,
                        onValueChange = { zoomLevel = it },
                        valueRange = 0.25f..4f,
                        steps = 14,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        "${(zoomLevel * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        modifier = Modifier.width(36.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Grid toggle
                    FilterChip(
                        selected = showGrid,
                        onClick = { showGrid = !showGrid },
                        label = { Text("Grid", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
                
                // Overlay toggles row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Overlays:", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    
                    TileOverlay.values().forEach { overlay ->
                        val isOn = overlayToggles[overlay] ?: false
                        FilterChip(
                            selected = isOn,
                            onClick = { overlayToggles[overlay] = !isOn },
                            label = { Text(overlay.label, fontSize = 9.sp) },
                            modifier = Modifier.height(26.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(overlay.color).copy(alpha = 0.3f)
                            )
                        )
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 2.dp))
                
                // ─── Map Display ───────────────────────────────────
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
                            
                            // Build the composite image with overlays
                            val compositeImage = remember(data, activeOverlays.toSet(), showGrid, zoomLevel) {
                                buildCompositeImage(data, activeOverlays, showGrid)
                            }
                            
                            val bitmap = remember(compositeImage) {
                                compositeImage.toComposeImageBitmap()
                            }
                            
                            // Scroll states for middle-click drag panning
                            val hScrollState = rememberScrollState()
                            val vScrollState = rememberScrollState()
                            val coroutineScope = rememberCoroutineScope()
                            var isDragging by remember { mutableStateOf(false) }
                            var lastDragX by remember { mutableStateOf(0f) }
                            var lastDragY by remember { mutableStateOf(0f) }
                            
                            // Scrollable + zoomable map with middle-click drag
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        // Middle mouse button = button index 2 in AWT
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

/**
 * Build a composite BufferedImage with the rendered tiles + overlays
 */
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
    
    // Draw overlay icons on each block
    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue
            
            val blockType = data.blockTypes[idx]
            val px = bx * 16
            val py = by * 16
            
            // Collect active overlays that match this block type
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
            
            // Draw icons from bottom-right corner, going left
            var iconX = px + 16 - 8  // Start at bottom-right
            val iconY = py + 16 - 8
            
            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )
                
                // Draw 8x8 background square
                g.color = java.awt.Color(0, 0, 0, 160)
                g.fillRect(iconX, iconY, 8, 8)
                
                // Draw colored border
                g.color = color
                g.drawRect(iconX, iconY, 7, 7)
                
                // Draw short label character
                g.font = java.awt.Font("Monospaced", java.awt.Font.BOLD, 7)
                g.color = color
                g.drawString(overlay.shortLabel, iconX + 1, iconY + 7)
                
                iconX -= 9  // Next icon goes to the left
            }
        }
    }
    
    g.dispose()
    return img
}
