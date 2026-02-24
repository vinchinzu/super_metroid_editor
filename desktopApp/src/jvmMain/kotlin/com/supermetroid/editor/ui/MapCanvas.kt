package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
import javax.imageio.ImageIO

private object EnemySpriteCache {
    private val cache = mutableMapOf<String, BufferedImage?>()

    fun get(hexId: String): BufferedImage? {
        return cache.getOrPut(hexId) {
            val stream = EnemySpriteCache::class.java.getResourceAsStream("/enemies/$hexId.png")
            stream?.use { ImageIO.read(it) }
        }
    }
}

/**
 * Shot block (type 0xC) BTS classification.
 * BTS is an index into the PLM table at $94:9EA6. The PLM determines break behavior.
 * Grouped by PLM setup routine (the actual discriminator):
 *   Setup $CE6B (BTS 0x00-0x03): Beam/missile/bomb breakable (visible, various respawn modes)
 *   Setup $B3C1 (BTS 0x04-0x07): Hidden shot blocks (look solid, beam-breakable when revealed)
 *   Setup $CF2E (BTS 0x08-0x09): Power bomb breakable
 *   Setup $CF67 (BTS 0x0A-0x0B): Super missile required
 *   BTS 0x40-0x4F: door cap PLMs (managed by door PLMs, not user-editable)
 */
private fun shotBlockCategory(bts: Int): ShotCategory = when (bts) {
    0x00, 0x01, 0x02, 0x03 -> ShotCategory.BEAM
    0x04, 0x05, 0x06, 0x07 -> ShotCategory.HIDDEN
    0x08, 0x09 -> ShotCategory.PB
    0x0A, 0x0B -> ShotCategory.SUPER
    in 0x40..0x4F -> ShotCategory.DOOR
    else -> ShotCategory.BEAM
}

private enum class ShotCategory { BEAM, SUPER, PB, HIDDEN, DOOR }

/** Named BTS options for block types that have well-known sub-types. */
internal fun btsOptionsForBlockType(blockType: Int): List<Pair<Int, String>> = when (blockType) {
    0xC -> listOf(
        0x00 to "Beam/Bomb (reform)",
        0x01 to "Beam/Bomb (no reform)",
        0x02 to "Beam/Bomb (reform, alt)",
        0x03 to "Beam/Bomb (no reform, alt)",
        0x04 to "Hidden (reform)",
        0x05 to "Hidden (no reform)",
        0x06 to "Hidden (reform, alt)",
        0x07 to "Hidden (no reform, alt)",
        0x08 to "Power Bomb (reform)",
        0x09 to "Power Bomb (no reform)",
        0x0A to "Super Missile (reform)",
        0x0B to "Super Missile (no reform)",
    )
    0x3 -> listOf(
        0x08 to "Treadmill (left)",
        0x09 to "Treadmill (right)",
        0x81 to "Treadmill (down, var 1)",
        0x82 to "Treadmill (down, var 2)",
        0x83 to "Treadmill (down, var 3)",
        0x85 to "Treadmill (down, var 5)",
    )
    0xA -> listOf(
        0x00 to "Spike (normal)",
        0x0F to "Grinder",
    )
    0xB -> listOf(
        0x00 to "Crumble (reform)",
        0x04 to "Crumble (permanent)",
        0x0E to "Speed Booster (reform)",
        0x0F to "Speed Booster (permanent)",
        0x0B to "Barrier",
    )
    0xE -> listOf(
        0x00 to "Grapple (normal)",
        0x01 to "Crumble grapple (reform)",
        0x02 to "Crumble grapple (permanent)",
    )
    0xF -> listOf(
        0x00 to "Bomb (reform)",
        0x04 to "Bomb (permanent)",
    )
    else -> emptyList()
}

internal val blockTypeNames = mapOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid", 0x9 to "Door",
    0xA to "Spike", 0xB to "Crumble", 0xC to "Shot Block", 0xD to "V-Extend",
    0xE to "Grapple", 0xF to "Bomb Block"
)
internal fun blockTypeName(type: Int): String = blockTypeNames[type] ?: "0x${type.toString(16).uppercase()}"

enum class TileOverlay(val label: String, val shortLabel: String, val color: Long) {
    // Block types (from level data bits 12-15)
    SOLID("Solid", "S", 0xCC4488FF),       // blue
    SLOPE("Slope", "/", 0xCCEE7700),       // orange
    DOOR("Door", "D", 0xCC6080B0),         // gray-blue (casing color varies by PLM in render)
    SPIKE("Spike", "!", 0xCCFF4444),       // red
    BOMB("Bomb", "B", 0xCCAA44DD),         // purple
    CRUMBLE("Crumble", "C", 0xCCBB5522),   // brown/rust
    GRAPPLE("Grapple", "G", 0xCC00AA88),   // teal
    SPEED("Speed Booster", "~", 0xCC66AAFF),   // light blue (type 0xB + BTS 0x0E/0x0F)
    TREADMILL("Treadmill", "T", 0xCC44CCCC),   // cyan (type 0x3)
    // Shot blocks by break method (block type 0xC + BTS)
    SHOT_BEAM("Shot (Beam)", "Xb", 0xCCFFDD00),    // yellow: beam/missile/bomb
    SHOT_SUPER("Shot (Super)", "Xs", 0xCC00CC44),   // green: super missile required
    SHOT_PB("Shot (PB)", "Xp", 0xCCCC44AA),         // magenta: power bomb
    // Items/powerups (from PLM data; drawn when we have item positions)
    ITEMS("Items", "I", 0xCCFFCC00),       // gold/yellow
    // Enemies (from enemy population data in bank $A1)
    ENEMIES("Enemies", "E", 0xCCFF6644),   // orange-red
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
    var showGrid by remember { mutableStateOf(true) }
    var tileMetaExpanded by remember { mutableStateOf(false) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>(
        TileOverlay.ITEMS to true,
        TileOverlay.ENEMIES to true,
    ) }
    val overlayCount = overlayToggles.values.count { it }
    
    val mapFocusReq = remember { FocusRequester() }
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()
            .focusRequester(mapFocusReq)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && editorState != null) {
                    when (keyEvent.key) {
                        Key.H -> { editorState.flipOrCaptureH(); true }
                        Key.V -> { if (!keyEvent.isCtrlPressed && !keyEvent.isMetaPressed) { editorState.flipOrCaptureV(); true } else false }
                        Key.R -> { editorState.rotateOrCapture(); true }
                        Key.Z -> {
                            if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                if (keyEvent.isShiftPressed) editorState.redo() else editorState.undo()
                                true
                            } else false
                        }
                        Key.S -> {
                            if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                editorState.saveProject(romParser); true
                            } else { editorState.activeTool = EditorTool.SELECT; true }
                        }
                        Key.P -> {
                            if (editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                editorState.captureMapSelection()
                            } else {
                                editorState.activeTool = EditorTool.PAINT
                            }; true
                        }
                        Key.F -> { editorState.activeTool = EditorTool.FILL; true }
                        Key.I -> { editorState.activeTool = EditorTool.SAMPLE; true }
                        Key.Enter -> {
                            if (editorState.activeTool == EditorTool.SELECT && editorState.mapSelStart != null) {
                                editorState.captureMapSelection(); true
                            } else false
                        }
                        Key.Escape -> {
                            if (editorState.activeTool == EditorTool.SELECT && editorState.mapSelStart != null) {
                                editorState.mapSelStart = null; editorState.mapSelEnd = null; true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
        ) {
            if (room != null && romParser != null) {
                var isLoading by remember(room.id, romParser) { mutableStateOf(true) }
                var errorMessage by remember(room.id, romParser) { mutableStateOf<String?>(null) }
                var renderData by remember(room.id, romParser) { mutableStateOf<RoomRenderData?>(null) }
                
                LaunchedEffect(room.id, romParser) {
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
                        
                        // Tool selection with icons
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SELECT,
                            onClick = { editorState.activeTool = EditorTool.SELECT; mapFocusReq.requestFocus() },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                            modifier = Modifier.height(24.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.PAINT,
                            onClick = {
                                if (editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                    editorState.captureMapSelection()
                                } else {
                                    editorState.activeTool = EditorTool.PAINT
                                }
                                mapFocusReq.requestFocus()
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                            modifier = Modifier.height(24.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.FILL,
                            onClick = { editorState.activeTool = EditorTool.FILL; mapFocusReq.requestFocus() },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.FormatColorFill, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                            modifier = Modifier.height(24.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SAMPLE,
                            onClick = { editorState.activeTool = EditorTool.SAMPLE; mapFocusReq.requestFocus() },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Default.Colorize, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            },
                            modifier = Modifier.height(24.dp)
                        )
                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        // Undo / Redo with icons
                        @Suppress("UNUSED_VARIABLE") val uv = editorState.undoVersion
                        IconButton(
                            onClick = { editorState.undo(); mapFocusReq.requestFocus() },
                            enabled = editorState.undoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.redo(); mapFocusReq.requestFocus() },
                            enabled = editorState.redoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Flip / Rotate buttons
                        IconButton(
                            onClick = { editorState.flipOrCaptureH(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "H-Flip (H)",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.brush?.hFlip == true) MaterialTheme.colorScheme.primary
                                       else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.flipOrCaptureV(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "V-Flip (V)",
                                modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 90f),
                                tint = if (editorState.brush?.vFlip == true) MaterialTheme.colorScheme.primary
                                       else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.rotateOrCapture(); mapFocusReq.requestFocus() },
                            enabled = editorState.brush != null,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate (R)",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }

                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Brush info + hover tile info
                        val brush = editorState.brush
                        if (brush != null) {
                            val bt = brush.blockType
                            Text(
                                "${brush.cols}×${brush.rows}" +
                                    " #${brush.primaryIndex}" +
                                    " 0x${bt.toString(16).uppercase()} ${blockTypeName(bt)}" +
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
                                "#$hIdx 0x${hType.toString(16).uppercase()} ${blockTypeName(hType)}",
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

                            // Right-click context menu state
                            var contextMenuExpanded by remember { mutableStateOf(false) }
                            var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
                            var showSavePatternDialog by remember { mutableStateOf(false) }
                            val density = LocalDensity.current.density
                            
                            fun pointerToBlock(posX: Float, posY: Float): Pair<Int, Int> {
                                // Pointer & scroll are in physical pixels; layout uses dp.
                                // Tile display size in pointer units = 16 * zoom * density.
                                val tilePx = 16f * zoomLevel * density
                                return Pair(
                                    ((posX + hScrollState.value) / tilePx).toInt(),
                                    ((posY + vScrollState.value) / tilePx).toInt()
                                )
                            }
                            
                            // Re-render from working data (reacts to editVersion from EditorState)
                            val compositeForEdit = remember(data, editVersion, activeOverlays.toSet(), showGrid) {
                                val es = editorState
                                if (es != null && es.workingLevelData != null) {
                                    val roomHeader = romParser.readRoomHeader(room.getRoomIdAsInt())
                                    if (roomHeader != null) {
                                        val r = MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(roomHeader, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                                        if (r != null) return@remember buildCompositeImage(r, activeOverlays, showGrid)
                                    }
                                }
                                compositeImage
                            }
                            val editBitmap = remember(compositeForEdit) { compositeForEdit.toComposeImageBitmap() }
                            
                            LaunchedEffect(Unit) { mapFocusReq.requestFocus() }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
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
                                        mapFocusReq.requestFocus()
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne != null && ne.button == MouseEvent.BUTTON2) {
                                            isDragging = true; val p = event.changes.first().position; lastDragX = p.x; lastDragY = p.y
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON3 && editorState != null) {
                                            val pos = event.changes.first().position
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            val ss = editorState.mapSelStart
                                            val se = editorState.mapSelEnd
                                            val hasMultiSel = ss != null && se != null &&
                                                (kotlin.math.abs(ss.first - se.first) > 0 || kotlin.math.abs(ss.second - se.second) > 0)

                                            if (hasMultiSel) {
                                                contextMenuOffset = DpOffset((pos.x / density).dp, (pos.y / density).dp)
                                                contextMenuExpanded = true
                                            } else {
                                                if (bx in 0 until data.blocksWide && by in 0 until data.blocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON1 && editorState != null) {
                                            val (bx, by) = pointerToBlock(event.changes.first().position.x, event.changes.first().position.y)
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> {
                                                    editorState.mapSelStart = Pair(bx, by)
                                                    editorState.mapSelEnd = Pair(bx, by)
                                                    isPainting = true
                                                }
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
                                        if (isPainting && editorState?.activeTool == EditorTool.SELECT) {
                                            isPainting = false
                                            val ss = editorState?.mapSelStart
                                            val se = editorState?.mapSelEnd
                                            if (ss != null && se != null && ss == se && editorState != null) {
                                                val bx = ss.first; val by = ss.second
                                                if (bx in 0 until data.blocksWide && by in 0 until data.blocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (isPainting) {
                                            isPainting = false; editorState?.endStroke()
                                        }
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
                                        if (isPainting && editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> editorState.mapSelEnd = Pair(bx, by)
                                                EditorTool.PAINT -> editorState.paintAt(bx, by)
                                                else -> {}
                                            }
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
                                                        val effH = b.tileHFlip(r, c)
                                                        val effV = b.tileVFlip(r, c)
                                                        for (ty in 0 until 16) for (tx in 0 until 16) {
                                                            val sx = if (effH) 15 - tx else tx
                                                            val sy = if (effV) 15 - ty else ty
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
                                    // Select mode cursor: crosshair outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SELECT
                                        && editorState.mapSelStart == null) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.5f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.5f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                                                )
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
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Cyan.copy(alpha = 0.4f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
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
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Yellow.copy(alpha = 0.7f),
                                                topLeft = androidx.compose.ui.geometry.Offset(propsBlockX * tileW, propsBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Map selection rectangle (dotted-style)
                                    if (editorState != null && editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                        val sel0 = editorState.mapSelStart!!
                                        val sel1 = editorState.mapSelEnd!!
                                        val minBx = minOf(sel0.first, sel1.first)
                                        val minBy = minOf(sel0.second, sel1.second)
                                        val maxBx = maxOf(sel0.first, sel1.first)
                                        val maxBy = maxOf(sel0.second, sel1.second)
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            val rx = minBx * tileW
                                            val ry = minBy * tileH
                                            val rw = (maxBx - minBx + 1) * tileW
                                            val rh = (maxBy - minBy + 1) * tileH
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.15f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh)
                                            )
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.9f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 2f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // ─── Right-click context menu (multi-tile selection only) ──────
                            DropdownMenu(
                                expanded = contextMenuExpanded,
                                onDismissRequest = { contextMenuExpanded = false },
                                offset = contextMenuOffset
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Paint", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        editorState?.captureMapSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save Selection as Pattern", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        showSavePatternDialog = true
                                    }
                                )
                            }

                            // Save-as-pattern dialog
                            if (showSavePatternDialog && editorState != null) {
                                var patName by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showSavePatternDialog = false },
                                    title = { Text("Save Selection as Pattern", fontSize = 14.sp) },
                                    text = {
                                        OutlinedTextField(
                                            value = patName,
                                            onValueChange = { patName = it },
                                            label = { Text("Pattern name") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            val n = patName.ifBlank { "Selection" }
                                            val pat = editorState.saveSelectionAsPattern(n)
                                            if (pat != null) editorState.loadPatternForEdit(pat.id)
                                            showSavePatternDialog = false
                                        }) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSavePatternDialog = false }) { Text("Cancel") }
                                    }
                                )
                            }

                            // ─── Right-click tile properties panel (floating, non-modal) ──────
                            if (propsExpanded && editorState != null) {
                                val editableBlockTypes = listOf(
                                    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
                                    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
                                    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
                                    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
                                )
                                val propsTypeName = blockTypeName(propsBlockType)
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
                                                "($propsBlockX, $propsBlockY) #$propsMetatile 0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
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
                                                        "0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
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
                                            val currentDoor = allDoors.getOrNull(propsBts)

                                            if (allDoors.isEmpty()) {
                                                Text("No door entries found", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } else if (currentDoor == null) {
                                                Text("Door index #$propsBts not found (${allDoors.size} doors available)",
                                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                                            } else {
                                                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                val fieldStyle = LocalTextStyle.current.copy(fontSize = 10.sp)

                                                // Helper: 0x00–0xFF dropdown
                                                @Composable
                                                fun ByteDropdown(label: String, value: Int, onValueChange: (Int) -> Unit) {
                                                    var expanded by remember { mutableStateOf(false) }
                                                    val hexStr = "0x${value.toString(16).uppercase().padStart(2, '0')} ($value)"
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                        Text(label, fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                        Box(modifier = Modifier.weight(1f)) {
                                                            Surface(
                                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                    .clickable { expanded = true },
                                                                shape = MaterialTheme.shapes.small,
                                                                color = MaterialTheme.colorScheme.surfaceVariant
                                                            ) {
                                                                Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                    verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(hexStr, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                                                    Text("▾", fontSize = 9.sp)
                                                                }
                                                            }
                                                            DropdownMenu(
                                                                expanded = expanded,
                                                                onDismissRequest = { expanded = false },
                                                                modifier = Modifier.requiredSizeIn(maxHeight = 300.dp)
                                                            ) {
                                                                for (v in 0..0xFF) {
                                                                    DropdownMenuItem(
                                                                        text = {
                                                                            Text(
                                                                                "0x${v.toString(16).uppercase().padStart(2, '0')} ($v)",
                                                                                fontSize = 10.sp,
                                                                                fontWeight = if (v == value) FontWeight.Bold else FontWeight.Normal
                                                                            )
                                                                        },
                                                                        onClick = {
                                                                            expanded = false
                                                                            if (v != value) onValueChange(v)
                                                                        },
                                                                        modifier = Modifier.height(24.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                // Destination room dropdown
                                                var destDropExpanded by remember { mutableStateOf(false) }
                                                val destName = roomIdToName[currentDoor.destRoomPtr]
                                                    ?: "0x${currentDoor.destRoomPtr.toString(16).uppercase()}"
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Destination:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        Surface(
                                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                .clickable { destDropExpanded = true },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(destName, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                                                Text("▾", fontSize = 9.sp)
                                                            }
                                                        }
                                                        DropdownMenu(
                                                            expanded = destDropExpanded,
                                                            onDismissRequest = { destDropExpanded = false },
                                                            modifier = Modifier.requiredSizeIn(maxHeight = 400.dp)
                                                        ) {
                                                            for (r in rooms) {
                                                                val rid = r.getRoomIdAsInt()
                                                                DropdownMenuItem(
                                                                    text = { Text(r.name, fontSize = 10.sp,
                                                                        fontWeight = if (rid == currentDoor.destRoomPtr) FontWeight.Bold else FontWeight.Normal) },
                                                                    onClick = {
                                                                        destDropExpanded = false
                                                                        if (rid != currentDoor.destRoomPtr) {
                                                                            editorState.updateDoor(propsBts, currentDoor.copy(destRoomPtr = rid))
                                                                        }
                                                                    },
                                                                    modifier = Modifier.height(26.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Direction dropdown
                                                var dirDropExpanded by remember { mutableStateOf(false) }
                                                val dirNames = listOf("Right", "Left", "Down", "Up")
                                                val currentDir = currentDoor.direction and 0x03
                                                val isBubble = (currentDoor.direction and 0x04) != 0
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Direction:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        Surface(
                                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                                                .clickable { dirDropExpanded = true },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                val bubbleTag = if (isBubble) " (closing)" else ""
                                                                Text("${dirNames.getOrElse(currentDir) { "?" }}$bubbleTag", fontSize = 9.sp, modifier = Modifier.weight(1f))
                                                                Text("▾", fontSize = 9.sp)
                                                            }
                                                        }
                                                        DropdownMenu(
                                                            expanded = dirDropExpanded,
                                                            onDismissRequest = { dirDropExpanded = false }
                                                        ) {
                                                            for ((di, dn) in dirNames.withIndex()) {
                                                                DropdownMenuItem(
                                                                    text = { Text(dn, fontSize = 10.sp) },
                                                                    onClick = {
                                                                        dirDropExpanded = false
                                                                        val newDir = di + (if (isBubble) 4 else 0)
                                                                        val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                                                        editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newBitflag))
                                                                    },
                                                                    modifier = Modifier.height(26.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Screen X (0x00–0xFF)
                                                ByteDropdown("Screen X:", currentDoor.screenX) { v ->
                                                    editorState.updateDoor(propsBts, currentDoor.copy(screenX = v))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Screen Y (0x00–0xFF)
                                                ByteDropdown("Screen Y:", currentDoor.screenY) { v ->
                                                    editorState.updateDoor(propsBts, currentDoor.copy(screenY = v))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Distance from door (16-bit, keep as text)
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Distance:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var distText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.distFromDoor.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    OutlinedTextField(
                                                        value = distText,
                                                        onValueChange = { v ->
                                                            distText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(distFromDoor = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f).height(36.dp),
                                                        textStyle = fieldStyle, singleLine = true
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Elevator + Closing door toggles
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = currentDoor.isElevator,
                                                        onCheckedChange = { checked ->
                                                            val newFlags = if (checked) currentDoor.bitflag or 0x80 else currentDoor.bitflag and 0x7F
                                                            editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newFlags))
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text("Elevator", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Checkbox(
                                                        checked = isBubble,
                                                        onCheckedChange = { checked ->
                                                            val dir = currentDoor.direction and 0x03
                                                            val newDir = dir + (if (checked) 4 else 0)
                                                            val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                                            editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newBitflag))
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text("Closing door", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))

                                                // Scroll/Entry ASM pointers (advanced)
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Scroll:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var scrollText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.doorCapCode.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    OutlinedTextField(
                                                        value = scrollText,
                                                        onValueChange = { v ->
                                                            scrollText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(doorCapCode = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f).height(36.dp),
                                                        textStyle = fieldStyle, singleLine = true
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    Text("Entry ASM:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                                                    var asmText by remember(currentDoor) {
                                                        mutableStateOf("0x${currentDoor.entryCode.toString(16).uppercase().padStart(4, '0')}")
                                                    }
                                                    OutlinedTextField(
                                                        value = asmText,
                                                        onValueChange = { v ->
                                                            asmText = v
                                                            v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                                                editorState.updateDoor(propsBts, currentDoor.copy(entryCode = it))
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f).height(36.dp),
                                                        textStyle = fieldStyle, singleLine = true
                                                    )
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
                                            val pName = RomParser.plmDisplayName(plm.id, plm.param)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(pName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (RomParser.isStationPlm(plm.id) || RomParser.isGatePlm(plm.id)) {
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

                                        // Add Station button + dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addStationExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addStationExpanded = true },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Station / Gate", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addStationExpanded,
                                                onDismissRequest = { addStationExpanded = false }
                                            ) {
                                                Text("Stations", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                for (station in RomParser.STATION_PLMS) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(station.shortLabel, fontSize = 9.sp,
                                                                    color = MaterialTheme.colorScheme.secondary,
                                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                                Text(station.name, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addStationExpanded = false
                                                            editorState.addPlm(station.plmId, propsBlockX, propsBlockY, station.defaultParam)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                                Divider()
                                                Text("Gates", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                                for (gate in RomParser.GATE_PLMS) {
                                                    DropdownMenuItem(
                                                        text = { Text(gate.name, fontSize = 11.sp) },
                                                        onClick = {
                                                            addStationExpanded = false
                                                            editorState.addPlm(gate.plmId, propsBlockX, propsBlockY, gate.param)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // ─── Enemies at/near this tile ───
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Divider()
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Enemies", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                        val tileCenterX = propsBlockX * 16 + 8
                                        val tileCenterY = propsBlockY * 16 + 8
                                        val enemiesHere = editorState.getEnemiesNear(tileCenterX, tileCenterY, radius = 16)

                                        if (enemiesHere.isEmpty()) {
                                            Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                                        }
                                        for (enemy in enemiesHere) {
                                            val eName = RomParser.enemyName(enemy.id)
                                            var editing by remember { mutableStateOf(false) }
                                            if (!editing) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(eName, fontSize = 10.sp)
                                                        Text(
                                                            "pos: (${enemy.x}, ${enemy.y})  prop: 0x${enemy.properties.toString(16).uppercase().padStart(4, '0')}",
                                                            fontSize = 8.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        "✎",
                                                        modifier = Modifier
                                                            .clickable { editing = true }
                                                            .padding(horizontal = 4.dp),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        "✕",
                                                        modifier = Modifier
                                                            .clickable { editorState.removeEnemy(enemy) }
                                                            .padding(horizontal = 4.dp),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            } else {
                                                // Inline edit form
                                                var editX by remember { mutableStateOf(enemy.x.toString()) }
                                                var editY by remember { mutableStateOf(enemy.y.toString()) }
                                                var editProps by remember { mutableStateOf("0x${enemy.properties.toString(16).uppercase().padStart(4, '0')}") }
                                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                    Text(eName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("X:", fontSize = 9.sp)
                                                        OutlinedTextField(
                                                            value = editX,
                                                            onValueChange = { editX = it },
                                                            modifier = Modifier.width(60.dp).height(32.dp),
                                                            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                                                            singleLine = true
                                                        )
                                                        Text("Y:", fontSize = 9.sp)
                                                        OutlinedTextField(
                                                            value = editY,
                                                            onValueChange = { editY = it },
                                                            modifier = Modifier.width(60.dp).height(32.dp),
                                                            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                                                            singleLine = true
                                                        )
                                                    }
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("Props:", fontSize = 9.sp)
                                                        OutlinedTextField(
                                                            value = editProps,
                                                            onValueChange = { editProps = it },
                                                            modifier = Modifier.width(80.dp).height(32.dp),
                                                            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                                                            singleLine = true
                                                        )
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        modifier = Modifier.padding(top = 2.dp)) {
                                                        Surface(
                                                            modifier = Modifier.height(24.dp).clickable {
                                                                val nx = editX.toIntOrNull() ?: enemy.x
                                                                val ny = editY.toIntOrNull() ?: enemy.y
                                                                val np = editProps.removePrefix("0x").removePrefix("0X")
                                                                    .toIntOrNull(16) ?: enemy.properties
                                                                editorState.updateEnemy(
                                                                    enemy,
                                                                    RomParser.EnemyEntry(enemy.id, nx, ny, enemy.initParam, np,
                                                                        enemy.extra1, enemy.extra2, enemy.extra3)
                                                                )
                                                                editing = false
                                                            },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.primaryContainer
                                                        ) {
                                                            Text("Save", fontSize = 9.sp,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                        }
                                                        Surface(
                                                            modifier = Modifier.height(24.dp).clickable { editing = false },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Text("Cancel", fontSize = 9.sp,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Add Enemy button + searchable dropdown
                                        Spacer(modifier = Modifier.height(4.dp))
                                        var addEnemyExpanded by remember { mutableStateOf(false) }
                                        var enemySearch by remember { mutableStateOf("") }
                                        Box {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                                    .clickable { addEnemyExpanded = true; enemySearch = "" },
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("+ Add Enemy", fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = addEnemyExpanded,
                                                onDismissRequest = { addEnemyExpanded = false },
                                                modifier = Modifier.requiredSizeIn(maxHeight = 400.dp, maxWidth = 250.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = enemySearch,
                                                    onValueChange = { enemySearch = it },
                                                    placeholder = { Text("Search enemies…", fontSize = 10.sp) },
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(36.dp),
                                                    textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                                                    singleLine = true
                                                )
                                                val filtered = remember(enemySearch) {
                                                    val q = enemySearch.trim().lowercase()
                                                    if (q.isEmpty()) RomParser.ENEMY_CATALOG
                                                    else RomParser.ENEMY_CATALOG.filter { (id, name) ->
                                                        name.lowercase().contains(q) ||
                                                            id.toString(16).contains(q, ignoreCase = true)
                                                    }
                                                }
                                                for ((enemyId, enemyName) in filtered) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    enemyId.toString(16).uppercase().padStart(4, '0'),
                                                                    fontSize = 8.sp,
                                                                    color = MaterialTheme.colorScheme.tertiary,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                Text(enemyName, fontSize = 11.sp)
                                                            }
                                                        },
                                                        onClick = {
                                                            addEnemyExpanded = false
                                                            val pixelX = propsBlockX * 16
                                                            val pixelY = propsBlockY * 16
                                                            editorState.addEnemy(enemyId, pixelX, pixelY)
                                                        },
                                                        modifier = Modifier.height(28.dp)
                                                    )
                                                }
                                                if (filtered.isEmpty()) {
                                                    Text("No matches", fontSize = 10.sp,
                                                        modifier = Modifier.padding(8.dp),
                                                        color = MaterialTheme.colorScheme.outline)
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
                    ShotCategory.DOOR -> {}
                }
            }
            if (blockType == 0xB) {
                val isSpeedBts = bts == 0x0E || bts == 0x0F
                if (isSpeedBts && activeOverlays.contains(TileOverlay.SPEED)) matchingOverlays.add(TileOverlay.SPEED)
                else if (!isSpeedBts && activeOverlays.contains(TileOverlay.CRUMBLE)) matchingOverlays.add(TileOverlay.CRUMBLE)
            }
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.TREADMILL) && blockType == 0x3) matchingOverlays.add(TileOverlay.TREADMILL)
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
    
    // Draw item / station / gate labels (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.ITEMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val itemColor = java.awt.Color(0xFF, 0xCC, 0x00)       // gold
        val stationColor = java.awt.Color(0x44, 0xCC, 0xFF)    // cyan
        val gateColor = java.awt.Color(0xCC, 0x66, 0xFF)       // purple
        for (plm in data.plmEntries) {
            val isItem = RomParser.isItemPlm(plm.id)
            val isStation = RomParser.isStationPlm(plm.id)
            val isGate = RomParser.isGatePlm(plm.id)
            if (!isItem && !isStation && !isGate) continue
            val name = when {
                isItem -> RomParser.ITEM_DEFS.find { it.chozoId == plm.id || it.visibleId == plm.id || it.hiddenId == plm.id }?.name ?: continue
                isStation -> RomParser.stationNameForPlm(plm.id) ?: continue
                else -> RomParser.gateNameForPlm(plm.id, plm.param) ?: continue
            }
            val badgeBorder = when {
                isStation -> stationColor
                isGate -> gateColor
                else -> itemColor
            }
            val cx = plm.x * 16 + 8
            val cy = plm.y * 16 + 8
            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = cx - badgeW / 2
            val by = cy - badgeH / 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = badgeBorder
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw enemy sprites / markers (positioned at enemy pixel coordinates)
    if (activeOverlays.contains(TileOverlay.ENEMIES) && data.enemyEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val markerColor = java.awt.Color(0xFF, 0x66, 0x44)
        for (enemy in data.enemyEntries) {
            val name = RomParser.enemyName(enemy.id)
            val ex = enemy.x
            val ey = enemy.y
            if (ex < 0 || ex >= data.width || ey < 0 || ey >= data.height) continue

            val hexId = enemy.id.toString(16).uppercase().padStart(4, '0')
            val sprite = EnemySpriteCache.get(hexId)
            if (sprite != null) {
                val sx = ex - sprite.width / 2
                val sy = ey - sprite.height / 2
                g2.drawImage(sprite, sx, sy, null)
            } else {
                val diamondSize = 6
                val dx = intArrayOf(ex, ex + diamondSize, ex, ex - diamondSize)
                val dy = intArrayOf(ey - diamondSize, ey, ey + diamondSize, ey)
                g2.color = java.awt.Color(0xFF, 0x44, 0x22, 180)
                g2.fillPolygon(dx, dy, 4)
                g2.color = markerColor
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawPolygon(dx, dy, 4)
                g2.stroke = java.awt.BasicStroke(1f)
            }

            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = ex - badgeW / 2
            val spriteH = sprite?.height ?: 12
            val by = ey - spriteH / 2 - badgeH - 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = markerColor
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    g.dispose()
    return img
}
