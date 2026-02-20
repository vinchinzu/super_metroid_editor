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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.TilesetGridData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

private val EDITABLE_BLOCK_TYPES = listOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Speed Booster",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TilesetEditor(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    var gridData by remember { mutableStateOf<TilesetGridData?>(null) }
    var palettes by remember { mutableStateOf<Array<IntArray>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var zoomLevel by remember { mutableStateOf(1.8f) }
    var tilesetDropdownExpanded by remember { mutableStateOf(false) }
    var highlightPalette by remember { mutableStateOf(-1) } // -1 = no highlight
    val coroutineScope = rememberCoroutineScope()

    val tilesetId = editorState.editorTilesetId
    val selectedMeta = editorState.editorSelectedMetatile

    fun loadTileset(id: Int) {
        if (romParser == null) return
        isLoading = true; errorMessage = null; gridData = null; palettes = null
        coroutineScope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { editorState.loadEditorTileset(id, romParser) }
                if (!ok) { errorMessage = "Failed to load tileset $id"; return@launch }
                val tg = editorState.editorTileGraphics!!
                gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
                palettes = tg.getPalettes()
            } catch (e: Exception) { errorMessage = e.message ?: "Error" }
            finally { isLoading = false }
        }
    }

    LaunchedEffect(romParser) {
        if (romParser != null && gridData == null) loadTileset(tilesetId)
    }

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            // Header: Tileset selector dropdown
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Tileset Editor", style = MaterialTheme.typography.titleSmall, fontSize = 12.sp)
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    Surface(
                        modifier = Modifier.width(100.dp).height(28.dp)
                            .clickable { tilesetDropdownExpanded = true },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tileset $tilesetId", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("▾", fontSize = 10.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = tilesetDropdownExpanded,
                        onDismissRequest = { tilesetDropdownExpanded = false }
                    ) {
                        for (id in 0 until TileGraphics.NUM_TILESETS) {
                            DropdownMenuItem(
                                text = { Text("Tileset $id", fontSize = 11.sp) },
                                onClick = {
                                    tilesetDropdownExpanded = false
                                    loadTileset(id)
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Palette bar
            if (palettes != null) {
                PaletteBar(
                    palettes = palettes!!,
                    highlightPalette = highlightPalette,
                    onToggle = { idx -> highlightPalette = if (highlightPalette == idx) -1 else idx }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Main content: tileset grid (left) + selected tile details (right)
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Tileset grid
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0C0C18))) {
                    when {
                        isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("Loading…", color = Color.White, fontSize = 12.sp)
                        }
                        errorMessage != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                        romParser == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("Open a ROM first", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        gridData != null -> {
                            val data = gridData!!
                            val tg = editorState.editorTileGraphics
                            val bitmap = remember(data, selectedMeta, highlightPalette) {
                                tilesetEditorGrid(data, selectedMeta, highlightPalette, tg).toComposeImageBitmap()
                            }
                            val hScroll = rememberScrollState()
                            val vScroll = rememberScrollState()

                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .onPointerEvent(PointerEventType.Scroll) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        val sd = event.changes.first().scrollDelta
                                        val zoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                                        if (zoom) {
                                            zoomLevel = (zoomLevel * if (sd.y < 0) 1.15f else 1f / 1.15f).coerceIn(0.5f, 6f)
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        val ne = event.nativeEvent as? MouseEvent ?: return@onPointerEvent
                                        if (ne.button == MouseEvent.BUTTON1) {
                                            val pos = event.changes.first().position
                                            val tx = ((pos.x + hScroll.value) / zoomLevel / 16).toInt()
                                            val ty = ((pos.y + vScroll.value) / zoomLevel / 16).toInt()
                                            val idx = ty * data.gridCols + tx
                                            if (idx in 0 until 1024) editorState.selectEditorMetatile(idx)
                                        }
                                    }
                                    .horizontalScroll(hScroll)
                                    .verticalScroll(vScroll)
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Tileset grid",
                                    modifier = Modifier
                                        .requiredWidth((data.width * zoomLevel).dp)
                                        .requiredHeight((data.height * zoomLevel).dp),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }

                // Selected tile details panel
                if (selectedMeta >= 0 && gridData != null) {
                    TileDetailsPanel(
                        tilesetId = tilesetId,
                        metatileIndex = selectedMeta,
                        editorState = editorState,
                        modifier = Modifier.width(180.dp).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteBar(
    palettes: Array<IntArray>,
    highlightPalette: Int,
    onToggle: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (palIdx in palettes.indices) {
            val isHighlighted = palIdx == highlightPalette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clickable { onToggle(palIdx) }
                    .then(
                        if (isHighlighted) Modifier.border(1.dp, Color.White)
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$palIdx",
                    fontSize = 8.sp,
                    color = if (isHighlighted) Color.White else Color.Gray,
                    modifier = Modifier.width(12.dp)
                )
                for (col in palettes[palIdx]) {
                    val r = (col shr 16) and 0xFF
                    val g = (col shr 8) and 0xFF
                    val b = col and 0xFF
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(r / 255f, g / 255f, b / 255f))
                    )
                }
            }
        }
    }
}

@Composable
private fun TileDetailsPanel(
    tilesetId: Int,
    metatileIndex: Int,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val eff = editorState.getEffectiveTileDefault(tilesetId, metatileIndex)
    var blockType by remember(tilesetId, metatileIndex) { mutableStateOf(eff.blockType) }
    var bts by remember(tilesetId, metatileIndex) { mutableStateOf(eff.bts) }
    val hasOverride = editorState.hasProjectOverride(tilesetId, metatileIndex)
    val hardcoded = TilesetDefaults.get(metatileIndex)

    // Preview image
    val tg = editorState.editorTileGraphics
    val preview = remember(tilesetId, metatileIndex) {
        tg?.renderMetatile(metatileIndex)?.let { pixels ->
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, 16, 16, pixels, 0, 16)
            img.toComposeImageBitmap()
        }
    }

    val palIndices = remember(tilesetId, metatileIndex) {
        tg?.getMetatilePalettes(metatileIndex) ?: emptySet()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Tile preview + info
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "Metatile $metatileIndex",
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.FillBounds
                )
            }
            Column {
                Text("Tile #$metatileIndex", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("0x${metatileIndex.toString(16).uppercase().padStart(3, '0')}", fontSize = 9.sp, color = Color.Gray)
                if (palIndices.isNotEmpty()) {
                    Text("Pal: ${palIndices.sorted().joinToString(",")}", fontSize = 9.sp, color = Color.Gray)
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 2.dp))

        // Source indicator
        val sourceText = when {
            hasOverride -> "Project override"
            hardcoded != null -> "Hardcoded default"
            else -> "No default set"
        }
        val sourceColor = when {
            hasOverride -> Color(0xFF66BB6A)
            hardcoded != null -> Color(0xFF42A5F5)
            else -> Color.Gray
        }
        Text(sourceText, fontSize = 9.sp, color = sourceColor)

        Spacer(modifier = Modifier.height(2.dp))

        // Block Type dropdown
        Text("Block Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var btExpanded by remember { mutableStateOf(false) }
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().height(28.dp).clickable { btExpanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "0x${blockType.toString(16).uppercase()} ${blockTypeName(blockType)}",
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("▾", fontSize = 9.sp)
                }
            }
            DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                for ((typeVal, typeName) in EDITABLE_BLOCK_TYPES) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RadioButton(selected = blockType == typeVal, onClick = null, modifier = Modifier.size(14.dp))
                                Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 10.sp)
                            }
                        },
                        onClick = {
                            btExpanded = false
                            blockType = typeVal
                            editorState.setTileDefault(tilesetId, metatileIndex, typeVal, bts)
                        },
                        modifier = Modifier.height(26.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // BTS dropdown (contextual options based on block type)
        val btsOptions = btsOptionsForBlockType(blockType)
        if (btsOptions.isNotEmpty()) {
            Text("Sub Type (BTS)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var btsExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp).clickable { btsExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val btsName = btsOptions.find { it.first == bts }?.second
                            ?: "0x${bts.toString(16).uppercase().padStart(2, '0')}"
                        Text(btsName, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text("▾", fontSize = 9.sp)
                    }
                }
                DropdownMenu(expanded = btsExpanded, onDismissRequest = { btsExpanded = false }) {
                    for ((btsVal, btsName) in btsOptions) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RadioButton(selected = bts == btsVal, onClick = null, modifier = Modifier.size(14.dp))
                                    Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsName", fontSize = 10.sp)
                                }
                            },
                            onClick = {
                                btsExpanded = false
                                bts = btsVal
                                editorState.setTileDefault(tilesetId, metatileIndex, blockType, btsVal)
                            },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
            }
        } else {
            Text("BTS: 0x${bts.toString(16).uppercase().padStart(2, '0')}", fontSize = 10.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Reset button
        if (hasOverride) {
            OutlinedButton(
                onClick = {
                    editorState.clearTileDefault(tilesetId, metatileIndex)
                    val reverted = editorState.getEffectiveTileDefault(tilesetId, metatileIndex)
                    blockType = reverted.blockType
                    bts = reverted.bts
                },
                modifier = Modifier.fillMaxWidth().height(28.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("Reset to Default", fontSize = 9.sp)
            }
        }
    }
}

/** Render tileset grid with selected tile highlight and optional palette highlighting. */
private fun tilesetEditorGrid(
    data: TilesetGridData,
    selectedMeta: Int,
    highlightPalette: Int,
    tg: TileGraphics?
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    val g = img.createGraphics()

    // Dim tiles that don't use the highlighted palette
    if (highlightPalette >= 0 && tg != null) {
        for (i in 0 until 1024) {
            val pals = tg.getMetatilePalettes(i)
            if (!pals.contains(highlightPalette)) {
                val col = i % data.gridCols
                val row = i / data.gridCols
                val px = col * 16; val py = row * 16
                g.color = java.awt.Color(0, 0, 0, 160)
                g.fillRect(px, py, 16, 16)
            }
        }
    }

    // Selected tile highlight
    if (selectedMeta in 0 until 1024) {
        val col = selectedMeta % data.gridCols
        val row = selectedMeta / data.gridCols
        val px = col * 16; val py = row * 16
        g.color = java.awt.Color(255, 255, 255, 60)
        g.fillRect(px, py, 16, 16)
        g.color = java.awt.Color(255, 200, 0, 220)
        g.stroke = java.awt.BasicStroke(2f)
        g.drawRect(px, py, 15, 15)
    }

    g.dispose()
    return img
}
