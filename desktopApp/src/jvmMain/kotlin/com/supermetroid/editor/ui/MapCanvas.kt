package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import com.supermetroid.editor.ui.LocalSwingWindow

/**
 * Shot block (type 0xC) BTS classification.
 * BTS is an index into the PLM table at $94:9EA6. The PLM determines break behavior.
 * Grouped by PLM setup routine (the actual discriminator):
 *   Setup $CE6B (BTS 0x00-0x03): Beam/missile/bomb breakable (visible, various respawn modes)
 *   Setup $B3C1 (BTS 0x04-0x07): Hidden shot blocks (look solid, beam-breakable when revealed)
 *   Setup $CF2E (BTS 0x08-0x09): Power bomb breakable
 *   Setup $CF67 (BTS 0x0A-0x0B): Super missile required
 *   BTS 0x40-0x4F: Door cap / gate mechanisms (not real shot blocks)
 */
private fun shotBlockCategory(bts: Int): ShotCategory = when (bts) {
    0x00, 0x01, 0x02, 0x03 -> ShotCategory.BEAM    // beam/missile/bomb (setup $CE6B)
    0x04, 0x05, 0x06, 0x07 -> ShotCategory.HIDDEN   // hidden (setup $B3C1)
    0x08, 0x09 -> ShotCategory.PB                    // power bomb (setup $CF2E)
    0x0A, 0x0B -> ShotCategory.SUPER                 // super missile (setup $CF67)
    in 0x40..0x4F -> ShotCategory.DOOR               // door cap / gate mechanism
    else -> ShotCategory.BEAM                         // default
}

private enum class ShotCategory { BEAM, SUPER, PB, HIDDEN, DOOR }

/** Named BTS options for block types that have well-known sub-types. */
private fun btsOptionsForBlockType(blockType: Int): List<Pair<Int, String>> = when (blockType) {
    0xC -> listOf(
        0x00 to "Beam/Bomb (respawn)",
        0x01 to "Beam/Bomb (no respawn)",
        0x02 to "Beam/Bomb (respawn, speed)",
        0x03 to "Beam/Bomb (no respawn, speed)",
        0x04 to "Hidden (respawn)",
        0x05 to "Hidden (no respawn)",
        0x06 to "Hidden (respawn, alt)",
        0x07 to "Hidden (no respawn, alt)",
        0x08 to "Power Bomb (respawn)",
        0x09 to "Power Bomb (no respawn)",
        0x0A to "Super Missile (respawn)",
        0x0B to "Super Missile (no respawn)",
    )
    0xF -> listOf(0x00 to "Normal")
    0xB -> listOf(0x00 to "Normal")
    else -> emptyList()
}

enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    // Block types (from level data bits 12-15)
    SOLID("Solid", "S", 0xCC4488FF),       // blue
    SLOPE("Slope", "/", 0xCCEE7700),       // orange
    DOOR("Door", "D", 0xCC6080B0),         // gray-blue (casing color varies by PLM in render)
    SPIKE("Spike", "!", 0xCCFF4444),       // red
    BOMB("Bomb", "B", 0xCCAA44DD),         // purple
    CRUMBLE("Crumble", "C", 0xCCBB5522),   // brown/rust
    GRAPPLE("Grapple", "G", 0xCC00AA88),   // teal
    SPEED("Speed", "~", 0xCC66AAFF),       // light blue
    // Shot blocks by break method (block type 0xC + BTS)
    SHOT_BEAM("Shot (Beam)", "Xb", 0xCCFFDD00),    // yellow: beam/missile/bomb
    SHOT_SUPER("Shot (Super)", "Xs", 0xCC00CC44),   // green: super missile required
    SHOT_PB("Shot (PB)", "Xp", 0xCCCC44AA),         // magenta: power bomb
    // Items/powerups (from PLM data; drawn when we have item positions)
    ITEMS("Items", "I", 0xCCFFCC00),       // gold/yellow
}

/** Shared tile-meta icon: black fill, colored 2px border, centered white letter (matches map). */
@Composable
private fun TileMetaIcon(
    overlay: TileOverlay,
    sizeDp: Dp,
    borderDp: Dp,
    fontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(Color.Black, RectangleShape)
            .border(borderDp, Color(overlay.color.toInt()), RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = overlay.shortLabel,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState? = null,
    rooms: List<RoomInfo> = emptyList(),
    modifier: Modifier = Modifier
) {
    val zoomState = remember { mutableStateOf(1f) }
    val zoomLevel = zoomState.value
    AttachMacPinchZoom(LocalSwingWindow.current, zoomState, minZoom = 0.25f, maxZoom = 4f)
    var showGrid by remember { mutableStateOf(true) }
    var tileMetaExpanded by remember { mutableStateOf(false) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>() }
    val overlayCount = overlayToggles.values.count { it }
    
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (room != null && romParser != null) {
                var isLoading by remember(room.id) { mutableStateOf(true) }
                var errorMessage by remember(room.id) { mutableStateOf<String?>(null) }
                var renderData by remember(room.id) { mutableStateOf<RoomRenderData?>(null) }
                
                LaunchedEffect(room.id) {
                    isLoading = true
                    errorMessage = null
                    renderData = null
                    try {
                        val roomId = room.getRoomIdAsInt()
                        val roomHeader = romParser.readRoomHeader(roomId)
                        if (roomHeader != null) {
                            // Load working level data for editing
                            editorState?.loadRoom(roomId, romParser, roomHeader)
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
                
                val editVersion = editorState?.editVersion ?: 0
                
                // ─── Compact toolbar ─────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Zoom
                    Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp, modifier = Modifier.width(32.dp))
                    Slider(
                        value = zoomLevel,
                        onValueChange = { zoomState.value = it },
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
                    
                    // Tile Meta multi-select dropdown (trigger: icon like map + label, same color as map square)
                    val firstOverlay = overlayToggles.entries.firstOrNull { it.value }?.key
                    val triggerBg = firstOverlay?.let { Color(it.color.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
                    val triggerFg = if (overlayCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    Box {
                        Surface(
                            modifier = Modifier
                                .height(28.dp)
                                .clickable { tileMetaExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = triggerBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (firstOverlay != null) {
                                    TileMetaIcon(overlay = firstOverlay, sizeDp = 16.dp, borderDp = 2.dp, fontSize = 9.sp)
                                }
                                Text(
                                    text = if (overlayCount > 0) "Tile Meta ($overlayCount)" else "Tile Meta",
                                    fontSize = 12.sp,
                                    color = triggerFg
                                )
                                Text(
                                    text = "▼",
                                    fontSize = 8.sp,
                                    color = triggerFg.copy(alpha = 0.8f)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = tileMetaExpanded,
                            onDismissRequest = { tileMetaExpanded = false }
                        ) {
                            TileOverlay.values().forEach { overlay ->
                                val isOn = overlayToggles[overlay] ?: false
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isOn,
                                                onCheckedChange = null
                                            )
                                            TileMetaIcon(overlay = overlay, sizeDp = 14.dp, borderDp = 2.dp, fontSize = 7.sp)
                                            Text(overlay.label, fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        overlayToggles[overlay] = !isOn
                                    }
                                )
                            }
                        }
                    }
                    
                    // ─── Editor tools ─────────────────────────────
                    if (editorState != null) {
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Tool selection
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.PAINT,
                            onClick = { editorState.activeTool = EditorTool.PAINT },
                            label = { Text("Paint", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.FILL,
                            onClick = { editorState.activeTool = EditorTool.FILL },
                            label = { Text("Fill", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SAMPLE,
                            onClick = { editorState.activeTool = EditorTool.SAMPLE },
                            label = { Text("Sample", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp)
                        )
                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Undo / Redo
                        TextButton(
                            onClick = { editorState.undo() },
                            enabled = editorState.undoStack.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) { Text("Undo", fontSize = 9.sp) }
                        TextButton(
                            onClick = { editorState.redo() },
                            enabled = editorState.redoStack.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) { Text("Redo", fontSize = 9.sp) }
                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Brush info + hover tile info
                        val brush = editorState.brush
                        if (brush != null) {
                            Text(
                                "${brush.cols}×${brush.rows}" +
                                    " #${brush.primaryIndex}" +
                                    (if (brush.hFlip) " H" else "") +
                                    (if (brush.vFlip) " V" else ""),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Hover tile info (always visible when hovering)
                        if (editorState.hoverBlockX >= 0) {
                            val hw = editorState.hoverTileWord
                            val hIdx = hw and 0x3FF
                            val hType = (hw shr 12) and 0xF
                            Text(
                                "Tile #$hIdx Type 0x${hType.toString(16)}",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                            
                            val hScrollState = rememberScrollState()
                            val vScrollState = rememberScrollState()
                            val coroutineScope = rememberCoroutineScope()
                            var isDragging by remember { mutableStateOf(false) }
                            var lastDragX by remember { mutableStateOf(0f) }
                            var lastDragY by remember { mutableStateOf(0f) }
                            
                            var isPainting by remember { mutableStateOf(false) }
                            
                            // Right-click properties popup state
                            var propsBlockX by remember { mutableStateOf(-1) }
                            var propsBlockY by remember { mutableStateOf(-1) }
                            var propsExpanded by remember { mutableStateOf(false) }
                            var propsBlockType by remember { mutableStateOf(0) }
                            var propsBts by remember { mutableStateOf(0) }
                            var propsMetatile by remember { mutableStateOf(0) }
                            
                            fun pointerToBlock(posX: Float, posY: Float): Pair<Int, Int> {
                                val imgX = (posX + hScrollState.value) / zoomLevel
                                val imgY = (posY + vScrollState.value) / zoomLevel
                                return Pair((imgX / 16).toInt(), (imgY / 16).toInt())
                            }
                            
                            // Re-render from working data (reacts to editVersion from EditorState)
                            val compositeForEdit = remember(data, editVersion, activeOverlays.toSet(), showGrid) {
                                val es = editorState
                                if (es != null && es.workingLevelData != null) {
                                    val roomHeader = romParser.readRoomHeader(room.getRoomIdAsInt())
                                    if (roomHeader != null) {
                                        val r = MapRenderer(romParser).renderRoomFromLevelData(roomHeader, es.workingLevelData!!, es.workingPlms)
                                        if (r != null) return@remember buildCompositeImage(r, activeOverlays, showGrid)
                                    }
                                }
                                compositeImage
                            }
                            val editBitmap = remember(compositeForEdit) { compositeForEdit.toComposeImageBitmap() }
                            
                            // Keyboard shortcuts
                            val focusReq = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focusReq.requestFocus() }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(focusReq)
                                    .focusable()
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown && editorState != null) {
                                            when (keyEvent.key) {
                                                Key.H -> { editorState.toggleHFlip(); true }
                                                Key.V -> { if (!keyEvent.isCtrlPressed && !keyEvent.isMetaPressed) { editorState.toggleVFlip(); true } else false }
                                                Key.R -> { editorState.rotateClockwise(); true }
                                                Key.Z -> {
                                                    if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                                        if (keyEvent.isShiftPressed) editorState.redo() else editorState.undo()
                                                        true
                                                    } else false
                                                }
                                Key.One -> { editorState.activeTool = EditorTool.PAINT; true }
                                Key.Two -> { editorState.activeTool = EditorTool.FILL; true }
                                Key.Three -> { editorState.activeTool = EditorTool.SAMPLE; true }
                                Key.Escape -> { if (propsExpanded) { propsExpanded = false; true } else false }
                                else -> false
                                            }
                                        } else false
                                    }
                                    .onPointerEvent(PointerEventType.Scroll) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        val zoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                                        val sd = event.changes.first().scrollDelta
                                        if (zoom) zoomState.value = (zoomLevel * if (sd.y < 0) 1.15f else 1f / 1.15f).coerceIn(0.25f, 4f)
                                        else coroutineScope.launch {
                                            hScrollState.scrollTo((hScrollState.value + sd.x).toInt().coerceIn(0, hScrollState.maxValue))
                                            vScrollState.scrollTo((vScrollState.value + sd.y).toInt().coerceIn(0, vScrollState.maxValue))
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        focusReq.requestFocus()
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne != null && ne.button == MouseEvent.BUTTON2) {
                                            isDragging = true; val p = event.changes.first().position; lastDragX = p.x; lastDragY = p.y
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON3 && editorState != null) {
                                            // Right-click: open tile properties
                                            val (bx, by) = pointerToBlock(event.changes.first().position.x, event.changes.first().position.y)
                                            if (bx in 0 until data.blocksWide && by in 0 until data.blocksTall) {
                                                val word = editorState.readBlockWord(bx, by)
                                                propsBlockX = bx; propsBlockY = by
                                                propsMetatile = word and 0x3FF
                                                propsBlockType = (word shr 12) and 0xF
                                                propsBts = editorState.readBts(bx, by)
                                                propsExpanded = true
                                            }
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON1 && editorState != null) {
                                            val (bx, by) = pointerToBlock(event.changes.first().position.x, event.changes.first().position.y)
                                            when (editorState.activeTool) {
                                                EditorTool.PAINT -> if (editorState.brush != null) {
                                                    isPainting = true; editorState.beginStroke(); editorState.paintAt(bx, by)
                                                }
                                                EditorTool.FILL -> if (editorState.brush != null) {
                                                    editorState.beginStroke(); editorState.floodFill(bx, by); editorState.endStroke()
                                                }
                                                EditorTool.SAMPLE -> {
                                                    editorState.sampleTile(bx, by)
                                                }
                                            }
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Release) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne == null || ne.button == MouseEvent.BUTTON2) isDragging = false
                                        if (isPainting) { isPainting = false; editorState?.endStroke() }
                                    }
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        val pos = event.changes.first().position
                                        if (editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            editorState.updateHover(bx, by)
                                        }
                                        if (isDragging) {
                                            val dx = lastDragX - pos.x; val dy = lastDragY - pos.y; lastDragX = pos.x; lastDragY = pos.y
                                            coroutineScope.launch {
                                                hScrollState.scrollTo((hScrollState.value + dx.toInt()).coerceIn(0, hScrollState.maxValue))
                                                vScrollState.scrollTo((vScrollState.value + dy.toInt()).coerceIn(0, vScrollState.maxValue))
                                            }
                                        }
                                        if (isPainting && editorState != null && editorState.activeTool == EditorTool.PAINT) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            editorState.paintAt(bx, by)
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Exit) {
                                        if (editorState != null) { editorState.hoverBlockX = -1; editorState.hoverBlockY = -1 }
                                    }
                                    .horizontalScroll(hScrollState)
                                    .verticalScroll(vScrollState)
                            ) {
                                // Map image + cursor preview overlay
                                Box {
                                    Image(
                                        bitmap = editBitmap,
                                        contentDescription = room.name,
                                        modifier = Modifier
                                            .requiredWidth((data.width * zoomLevel).dp)
                                            .requiredHeight((data.height * zoomLevel).dp),
                                        contentScale = ContentScale.FillBounds
                                    )
                                    // Ghost cursor preview: render actual tile graphics
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.brush != null &&
                                        editorState.activeTool == EditorTool.PAINT) {
                                        val hx = editorState.hoverBlockX
                                        val hy = editorState.hoverBlockY
                                        val b = editorState.brush!!
                                        val tg = editorState.tileGraphics
                                        // Build a preview image of the brush at the hover position
                                        val previewBitmap = remember(b, hx, hy, tg) {
                                            if (tg == null) null
                                            else {
                                                val pw = b.cols * 16; val ph = b.rows * 16
                                                val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
                                                for (r in 0 until b.rows) {
                                                    for (c in 0 until b.cols) {
                                                        val idx = b.tiles.getOrNull(r)?.getOrNull(c) ?: continue
                                                        val pixels = tg.renderMetatile(idx) ?: continue
                                                        val dc = if (b.hFlip) (b.cols - 1 - c) else c
                                                        val dr = if (b.vFlip) (b.rows - 1 - r) else r
                                                        for (ty in 0 until 16) for (tx in 0 until 16) {
                                                            val sx = if (b.hFlip) 15 - tx else tx
                                                            val sy = if (b.vFlip) 15 - ty else ty
                                                            val argb = pixels[sy * 16 + sx]
                                                            if (argb != 0) img.setRGB(dc * 16 + tx, dr * 16 + ty, argb)
                                                        }
                                                    }
                                                }
                                                // Make semi-transparent
                                                for (y in 0 until ph) for (x in 0 until pw) {
                                                    val p = img.getRGB(x, y)
                                                    if (p != 0) img.setRGB(x, y, (p and 0x00FFFFFF) or 0x99000000.toInt())
                                                }
                                                img.toComposeImageBitmap()
                                            }
                                        }
                                        if (previewBitmap != null) {
                                            val tileSize = 16f * zoomLevel
                                            val offX = hx * tileSize
                                            val offY = hy * tileSize
                                            Image(
                                                bitmap = previewBitmap,
                                                contentDescription = "Brush preview",
                                                modifier = Modifier
                                                    .offset(x = (offX).dp, y = (offY).dp)
                                                    .requiredWidth((b.cols * 16 * zoomLevel).dp)
                                                    .requiredHeight((b.rows * 16 * zoomLevel).dp),
                                                contentScale = ContentScale.FillBounds
                                            )
                                        }
                                    }
                                    // Sample cursor: outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SAMPLE) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileSize = 16f * zoomLevel
                                            drawRect(
                                                color = Color.Cyan.copy(alpha = 0.4f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileSize, editorState.hoverBlockY * tileSize),
                                                size = androidx.compose.ui.geometry.Size(tileSize, tileSize),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Right-click selection border
                                    if (propsExpanded && propsBlockX >= 0) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileSize = 16f * zoomLevel
                                            drawRect(
                                                color = Color.Yellow.copy(alpha = 0.7f),
                                                topLeft = androidx.compose.ui.geometry.Offset(propsBlockX * tileSize, propsBlockY * tileSize),
                                                size = androidx.compose.ui.geometry.Size(tileSize, tileSize),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // ─── Right-click tile properties panel (floating, non-modal) ──────
                            if (propsExpanded && editorState != null) {
                                val editableBlockTypes = listOf(
                                    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Speed Booster",
                                    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
                                    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
                                    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
                                )
                                val blockTypeName = editableBlockTypes.firstOrNull { it.first == propsBlockType }?.second
                                    ?: "0x${propsBlockType.toString(16).uppercase()}"
                                val btsOptions = btsOptionsForBlockType(propsBlockType)

                                Card(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .width(260.dp)
                                        .heightIn(max = 600.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        // Header
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Tile ($propsBlockX, $propsBlockY)  #$propsMetatile",
                                                fontSize = 11.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                            Text(
                                                "✕",
                                                modifier = Modifier
                                                    .clickable { propsExpanded = false }
                                                    .padding(4.dp),
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // ── Block Type selector ──
                                        Text("Block Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        var btExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                                    .clickable { btExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        "0x${propsBlockType.toString(16).uppercase()} $blockTypeName",
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text("▾", fontSize = 10.sp)
                                                }
                                            }
                                            DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                                                for ((typeVal, typeName) in editableBlockTypes) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                RadioButton(selected = propsBlockType == typeVal, onClick = null, modifier = Modifier.size(16.dp))
                                                                Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            btExpanded = false
                                                            if (typeVal != propsBlockType) {
                                                                propsBlockType = typeVal
                                                                editorState.setTileProperties(propsBlockX, propsBlockY, typeVal, propsBts)
                                                            }
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // ── Sub Type (BTS) ──
                                        val btsLabel = when (propsBlockType) {
                                            0x9 -> "Door Connection Index"
                                            0x1 -> "Slope Type"
                                            else -> "Sub Type (BTS)"
                                        }
                                        Text(btsLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(2.dp))

                                        if (btsOptions.isNotEmpty()) {
                                            var btsDropExpanded by remember { mutableStateOf(false) }
                                            val btsName = btsOptions.firstOrNull { it.first == propsBts }?.second
                                                ?: "Custom (0x${propsBts.toString(16).uppercase().padStart(2, '0')})"
                                            Box {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(32.dp)
                                                        .clickable { btsDropExpanded = true },
                                                    shape = MaterialTheme.shapes.small,
                                                    color = MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(btsName, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                        Text("▾", fontSize = 10.sp)
                                                    }
                                                }
                                                DropdownMenu(expanded = btsDropExpanded, onDismissRequest = { btsDropExpanded = false }) {
                                                    for ((btsVal, btsOptName) in btsOptions) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                    RadioButton(selected = propsBts == btsVal, onClick = null, modifier = Modifier.size(16.dp))
                                                                    Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsOptName", fontSize = 11.sp)
                                                                }
                                                            },
                                                            onClick = {
                                                                btsDropExpanded = false
                                                                if (btsVal != propsBts) {
                                                                    propsBts = btsVal
                                                                    editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, btsVal)
                                                                }
                                                            },
                                                            modifier = Modifier.height(28.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }

                                        // Raw hex input (always visible)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(if (btsOptions.isNotEmpty()) "Raw:" else "BTS:", fontSize = 10.sp)
                                            var rawText by remember(propsBlockX, propsBlockY, propsBts) {
                                                mutableStateOf(propsBts.toString(16).uppercase().padStart(2, '0'))
                                            }
                                            OutlinedTextField(
                                                value = rawText,
                                                onValueChange = { s ->
                                                    rawText = s
                                                    val v = s.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                                                    if (v != null && v in 0..255 && v != propsBts) {
                                                        propsBts = v
                                                        editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, v)
                                                    }
                                                },
                                                modifier = Modifier.width(80.dp).height(36.dp),
                                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                                singleLine = true
                                            )
                                        }

                                        // ── Door Connection Info (when block type = Door) ──
                                        if (propsBlockType == 0x9) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Divider()
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Door Connection", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.height(2.dp))

                                            val allDoors = editorState.doorEntries
                                            val roomIdToName = remember(rooms) {
                                                rooms.associate {
                                                    it.getRoomIdAsInt() to it.name
                                                }
                                            }

                                            if (allDoors.isEmpty()) {
                                                Text("No door entries found", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } else {
                                                // Door index dropdown (BTS selects which door)
                                                var doorDropExpanded by remember { mutableStateOf(false) }
                                                val currentDoor = allDoors.getOrNull(propsBts)
                                                val doorLabel = if (currentDoor != null) {
                                                    val destName = roomIdToName[currentDoor.destRoomPtr]
                                                        ?: "Room 0x${currentDoor.destRoomPtr.toString(16).uppercase()}"
                                                    "#$propsBts → $destName"
                                                } else {
                                                    "#$propsBts (invalid index)"
                                                }
                                                Box {
                                                    Surface(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(32.dp)
                                                            .clickable { doorDropExpanded = true },
                                                        shape = MaterialTheme.shapes.small,
                                                        color = MaterialTheme.colorScheme.surfaceVariant
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(doorLabel, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                                            Text("▾", fontSize = 10.sp)
                                                        }
                                                    }
                                                    DropdownMenu(
                                                        expanded = doorDropExpanded,
                                                        onDismissRequest = { doorDropExpanded = false }
                                                    ) {
                                                        for ((idx, door) in allDoors.withIndex()) {
                                                            val dName = roomIdToName[door.destRoomPtr]
                                                                ?: "Room 0x${door.destRoomPtr.toString(16).uppercase()}"
                                                            val elevTag = if (door.isElevator) " ⇕" else ""
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                        RadioButton(selected = propsBts == idx, onClick = null, modifier = Modifier.size(16.dp))
                                                                        Text("#$idx → $dName (${door.directionName}$elevTag)", fontSize = 10.sp)
                                                                    }
                                                                },
                                                                onClick = {
                                                                    doorDropExpanded = false
                                                                    if (idx != propsBts) {
                                                                        propsBts = idx
                                                                        editorState.setTileProperties(propsBlockX, propsBlockY, propsBlockType, idx)
                                                                    }
                                                                },
                                                                modifier = Modifier.height(28.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                // Show destination details for current door
                                                if (currentDoor != null) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    val destName = roomIdToName[currentDoor.destRoomPtr]
                                                        ?: "0x${currentDoor.destRoomPtr.toString(16).uppercase()}"
                                                    Column(modifier = Modifier.padding(start = 4.dp)) {
                                                        Text("Dest: $destName", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(
                                                            "Dir: ${currentDoor.directionName}  Screen: (${currentDoor.screenX}, ${currentDoor.screenY})",
                                                            fontSize = 9.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        if (currentDoor.isElevator) {
                                                            Text("Elevator transition", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // ── Items / PLMs at this tile ──
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Divider()
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Items / PLMs", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        val plmsHere = editorState.getPlmsAt(propsBlockX, propsBlockY)
                                        val itemPlms = plmsHere.filter { RomParser.isItemPlm(it.id) }
                                        val otherPlms = plmsHere.filter { !RomParser.isItemPlm(it.id) }

                                        if (itemPlms.isEmpty() && otherPlms.isEmpty()) {
                                            Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        for (plm in itemPlms) {
                                            val iName = RomParser.itemNameForPlm(plm.id) ?: "PLM 0x${plm.id.toString(16)}"
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(iName, fontSize = 10.sp)
                                                    Text(
                                                        "bit: 0x${plm.param.toString(16).uppercase().padStart(2, '0')}",
                                                        fontSize = 8.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    "✕",
                                                    modifier = Modifier
                                                        .clickable { editorState.removePlm(plm.x, plm.y, plm.id) }
                                                        .padding(horizontal = 4.dp),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        for (plm in otherPlms) {
                                            val pName = RomParser.doorCapColor(plm.id)?.let { "Door Cap" }
                                                ?: "PLM 0x${plm.id.toString(16).uppercase()}"
                                            Text(pName, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                        }

                                        // Add Item button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addItemExpanded by remember { mutableStateOf(false) }
                                        var addItemStyle by remember { mutableStateOf(0) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addItemExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Item", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addItemExpanded,
                                                onDismissRequest = { addItemExpanded = false }
                                            ) {
                                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    listOf("Visible" to 0, "Chozo" to 1, "Hidden" to 2).forEach { (label, idx) ->
                                                        FilterChip(
                                                            selected = addItemStyle == idx,
                                                            onClick = { addItemStyle = idx },
                                                            label = { Text(label, fontSize = 9.sp) },
                                                            modifier = Modifier.height(24.dp)
                                                        )
                                                    }
                                                }
                                                Divider()
                                                for (item in RomParser.ITEM_DEFS) {
                                                    val plmId = when (addItemStyle) {
                                                        1 -> item.chozoId
                                                        2 -> item.hiddenId
                                                        else -> item.visibleId
                                                    }
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(item.shortLabel, fontSize = 9.sp,
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                                Text(item.name, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addItemExpanded = false
                                                            editorState.addPlm(plmId, propsBlockX, propsBlockY, 0)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
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

private const val SCREEN_PX = 16 * 16  // 256 — one screen in pixels

private fun buildCompositeImage(
    data: RoomRenderData,
    activeOverlays: Set<TileOverlay>,
    showGrid: Boolean
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    
    // Draw screen grid when toggle is on (one line every 256 px)
    if (showGrid) {
        g.color = java.awt.Color(255, 255, 255, 0x30)
        var x = 0
        while (x <= data.width) {
            g.drawLine(x, 0, x, data.height)
            x += SCREEN_PX
        }
        var y = 0
        while (y <= data.height) {
            g.drawLine(0, y, data.width, y)
            y += SCREEN_PX
        }
    }
    
    if (activeOverlays.isEmpty()) {
        g.dispose()
        return img
    }
    
    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall
    
    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) {
        g.dispose()
        return img
    }
    val btsData = data.btsData
    val itemBlocks = data.itemBlocks
    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue
            
            val blockType = data.blockTypes[idx]
            val bts = if (idx < btsData.size) btsData[idx].toInt() and 0xFF else 0
            val px = bx * 16
            val py = by * 16
            
            val matchingOverlays = mutableListOf<TileOverlay>()
            if (activeOverlays.contains(TileOverlay.SOLID) && blockType == 0x8) matchingOverlays.add(TileOverlay.SOLID)
            if (activeOverlays.contains(TileOverlay.SLOPE) && blockType == 0x1) matchingOverlays.add(TileOverlay.SLOPE)
            if (activeOverlays.contains(TileOverlay.DOOR) && blockType == 0x9) matchingOverlays.add(TileOverlay.DOOR)
            if (activeOverlays.contains(TileOverlay.SPIKE) && blockType == 0xA) matchingOverlays.add(TileOverlay.SPIKE)
            if (activeOverlays.contains(TileOverlay.BOMB) && blockType == 0xF) matchingOverlays.add(TileOverlay.BOMB)
            if (blockType == 0xC) {
                when (shotBlockCategory(bts)) {
                    ShotCategory.BEAM -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.SUPER -> if (activeOverlays.contains(TileOverlay.SHOT_SUPER)) matchingOverlays.add(TileOverlay.SHOT_SUPER)
                    ShotCategory.PB -> if (activeOverlays.contains(TileOverlay.SHOT_PB)) matchingOverlays.add(TileOverlay.SHOT_PB)
                    ShotCategory.HIDDEN -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.DOOR -> {} // Door cap blocks in shot type — skip, shown by Door overlay
                }
            }
            if (activeOverlays.contains(TileOverlay.CRUMBLE) && blockType == 0xB) matchingOverlays.add(TileOverlay.CRUMBLE)
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.SPEED) && blockType == 0x3) matchingOverlays.add(TileOverlay.SPEED)
            if (activeOverlays.contains(TileOverlay.ITEMS) && itemBlocks.contains(idx)) matchingOverlays.add(TileOverlay.ITEMS)
            
            var iconX = px + 16 - 8
            val iconY = py + 16 - 8
            
            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )
                // Black fill, 2px colored border, centered white letter (match dropdown)
                g.color = java.awt.Color.BLACK
                g.fillRect(iconX, iconY, 8, 8)
                g.color = color
                val g2 = g as? java.awt.Graphics2D
                g2?.stroke = java.awt.BasicStroke(2f)
                g.drawRect(iconX + 1, iconY + 1, 5, 5) // inset so 2px stroke fits in 8x8
                g2?.stroke = java.awt.BasicStroke(1f)
                g.color = java.awt.Color.WHITE
                g.font = java.awt.Font("Monospaced", java.awt.Font.BOLD, 7)
                val fm = g.fontMetrics
                val textY = iconY + (8 - fm.height) / 2 + fm.ascent
                g.drawString(overlay.shortLabel, iconX + 1, textY)
                iconX -= 9
            }
        }
    }
    
    g.dispose()
    return img
}
