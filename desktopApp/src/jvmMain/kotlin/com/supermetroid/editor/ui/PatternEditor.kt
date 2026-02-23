package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.TilesetGridData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage

private val PATTERN_BLOCK_TYPES = listOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
)

// ─── Left column: pattern list panel ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternListPanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patternVersion
    val patterns = editorState.project.patterns
    val selectedId = editorState.selectedPatternId
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Patterns", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Button(
                onClick = { showCreateDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("+ New", fontSize = 11.sp)
            }
        }

        Divider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (patterns.isEmpty()) {
                Text(
                    "No patterns yet.\nClick + New to create one.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            val crePats = patterns.filter { it.tilesetId == null }
            val urePats = patterns.filter { it.tilesetId != null }

            if (crePats.isNotEmpty()) {
                Text(
                    "CRE (Common)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                )
                for (pat in crePats) {
                    PatternListItem(
                        pattern = pat,
                        isSelected = pat.id == selectedId,
                        onSelect = { editorState.selectPattern(pat.id) },
                        onUse = { editorState.selectAndApplyPattern(pat.id) },
                        onDelete = if (pat.builtIn) null else {{ editorState.removePattern(pat.id) }}
                    )
                }
            }

            if (urePats.isNotEmpty()) {
                val grouped = urePats.groupBy { it.tilesetId!! }
                for ((tsId, pats) in grouped.entries.sortedBy { it.key }) {
                    Text(
                        "Tileset $tsId",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                    )
                    for (pat in pats) {
                        PatternListItem(
                            pattern = pat,
                            isSelected = pat.id == selectedId,
                            onSelect = { editorState.selectPattern(pat.id) },
                            onUse = { editorState.selectAndApplyPattern(pat.id) },
                            onDelete = if (pat.builtIn) null else {{ editorState.removePattern(pat.id) }}
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePatternDialog(
            currentTilesetId = editorState.currentTilesetId,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, cols, rows, tilesetId ->
                val pat = editorState.addPattern(name, cols, rows, tilesetId)
                editorState.selectPattern(pat.id)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PatternListItem(
    pattern: TilePattern,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUse: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
             else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = bg,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pattern.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${pattern.cols}×${pattern.rows}" +
                        if (pattern.builtIn) " (built-in)" else "",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "Use",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onUse() }
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            if (onDelete != null) {
                Text(
                    "✕",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clickable { onDelete() }
                        .padding(4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePatternDialog(
    currentTilesetId: Int,
    onDismiss: () -> Unit,
    onCreate: (name: String, cols: Int, rows: Int, tilesetId: Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var colsText by remember { mutableStateOf("4") }
    var rowsText by remember { mutableStateOf("4") }
    var isCre by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Pattern", fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = colsText,
                        onValueChange = { colsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Width", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Height", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isCre,
                        onCheckedChange = { isCre = it },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isCre) "CRE (shared across all tilesets)"
                        else "Tileset $currentTilesetId only",
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val c = colsText.toIntOrNull()?.coerceIn(1, 16) ?: 4
                    val r = rowsText.toIntOrNull()?.coerceIn(1, 16) ?: 4
                    val n = name.ifBlank { "Pattern ${c}×${r}" }
                    onCreate(n, c, r, if (isCre) null else currentTilesetId)
                }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─── Right canvas: pattern editor view ──────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PatternEditorCanvas(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patternVersion
    val pattern = editorState.activePattern

    if (pattern == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Text(
                "Select a pattern to edit",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        return
    }

    var tileGraphics by remember { mutableStateOf<TileGraphics?>(null) }
    var tilesetGrid by remember { mutableStateOf<TilesetGridData?>(null) }

    LaunchedEffect(editorState.currentTilesetId, romParser) {
        if (romParser == null) return@LaunchedEffect
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(editorState.currentTilesetId)) {
            editorState.applyCustomGfxToTileGraphics(tg, editorState.currentTilesetId)
            tileGraphics = tg
            tilesetGrid = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
        }
    }

    val tg = tileGraphics
    val density = LocalDensity.current.density
    val cellPx = 48 // display size per cell in dp

    var selectedCellR by remember { mutableStateOf(0) }
    var selectedCellC by remember { mutableStateOf(0) }
    var patternHFlip by remember { mutableStateOf(false) }
    var patternVFlip by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // Pattern header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                pattern.name,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${pattern.cols}×${pattern.rows}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pattern.tilesetId != null) {
                Text("TS: ${pattern.tilesetId}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("CRE", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Toolbar: Use, Flip
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val p = editorState.activePattern ?: return@Button
                    editorState.brush = editorState.patternToBrush(p, patternHFlip, patternVFlip)
                    editorState.activeTool = EditorTool.PAINT
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) { Text("Use Pattern", fontSize = 11.sp) }

            OutlinedButton(
                onClick = { patternHFlip = !patternHFlip },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
                colors = if (patternHFlip) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) else ButtonDefaults.outlinedButtonColors()
            ) { Text("H-Flip", fontSize = 11.sp) }

            OutlinedButton(
                onClick = { patternVFlip = !patternVFlip },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
                colors = if (patternVFlip) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) else ButtonDefaults.outlinedButtonColors()
            ) { Text("V-Flip", fontSize = 11.sp) }
        }

        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        // Pattern grid + cell properties in a row
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left: Pattern grid
            Column(modifier = Modifier.weight(1f)) {
                Text("Pattern Grid", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))

                val gridImg = remember(pattern, pv, tg) {
                    renderPatternGrid(pattern, tg, cellPx)
                }

                Box(
                    modifier = Modifier
                        .background(Color(0xFF0C0C18))
                        .border(1.dp, Color(0xFF444444))
                ) {
                    if (gridImg != null) {
                        Image(
                            bitmap = gridImg.toComposeImageBitmap(),
                            contentDescription = "Pattern grid",
                            contentScale = ContentScale.None,
                            modifier = Modifier
                                .size(
                                    (pattern.cols * cellPx).dp,
                                    (pattern.rows * cellPx).dp
                                )
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                    val px = pos.x / density
                                    val py = pos.y / density
                                    val c = (px / cellPx).toInt().coerceIn(0, pattern.cols - 1)
                                    val r = (py / cellPx).toInt().coerceIn(0, pattern.rows - 1)
                                    selectedCellR = r
                                    selectedCellC = c

                                    val brush = editorState.brush
                                    if (brush != null && brush.rows == 1 && brush.cols == 1) {
                                        val mt = brush.tiles[0][0]
                                        val bt = brush.blockTypeAt(0, 0)
                                        val bts = brush.btsAt(0, 0)
                                        val hf = brush.tileHFlip(0, 0)
                                        val vf = brush.tileVFlip(0, 0)
                                        editorState.updatePatternCell(
                                            pattern.id, r, c,
                                            PatternCell(mt, bt, bts, hf, vf)
                                        )
                                    }
                                }
                        )

                        // Selection highlight
                        Box(
                            modifier = Modifier
                                .offset(
                                    (selectedCellC * cellPx).dp,
                                    (selectedCellR * cellPx).dp
                                )
                                .size(cellPx.dp)
                                .border(2.dp, Color(0xFFFFD740))
                        )
                    } else {
                        Box(
                            Modifier.size(
                                (pattern.cols * cellPx).dp,
                                (pattern.rows * cellPx).dp
                            ),
                            Alignment.Center
                        ) {
                            Text("No tileset loaded", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Select a tile from the tileset, then click a cell to place it.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )

                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
                Text("Tileset", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))

                // Mini tileset picker
                val gridData = tilesetGrid
                if (gridData != null) {
                    val tsZoom = 1.5f
                    val gridBitmap = remember(gridData) {
                        BufferedImage(gridData.width, gridData.height, BufferedImage.TYPE_INT_ARGB).also { img ->
                            img.setRGB(0, 0, gridData.width, gridData.height, gridData.pixels, 0, gridData.width)
                        }.toComposeImageBitmap()
                    }
                    val hScroll = rememberScrollState()
                    val vScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF0C0C18))
                            .border(1.dp, Color(0xFF444444))
                    ) {
                        Image(
                            bitmap = gridBitmap,
                            contentDescription = "Tileset picker",
                            contentScale = ContentScale.None,
                            modifier = Modifier
                                .horizontalScroll(hScroll)
                                .verticalScroll(vScroll)
                                .requiredWidth((gridData.width * tsZoom).dp)
                                .requiredHeight((gridData.height * tsZoom).dp)
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                    val tilePxSize = 16f * tsZoom * density
                                    val col = ((pos.x + hScroll.value) / tilePxSize).toInt()
                                        .coerceIn(0, gridData.gridCols - 1)
                                    val row = ((pos.y + vScroll.value) / tilePxSize).toInt()
                                        .coerceIn(0, gridData.gridRows - 1)
                                    editorState.beginTilesetDrag(col, row)
                                    editorState.endTilesetDrag(gridData.gridCols)
                                }
                        )
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(100.dp)
                            .background(Color(0xFF0C0C18)),
                        Alignment.Center
                    ) {
                        Text(
                            "Load a room to see the tileset",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Right: Cell properties
            Column(
                modifier = Modifier.width(180.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Cell (${selectedCellR}, ${selectedCellC})", fontSize = 11.sp, fontWeight = FontWeight.Medium)

                val cell = pattern.getCell(selectedCellR, selectedCellC) ?: PatternCell(0)

                Text("Metatile: 0x${cell.metatile.toString(16).uppercase().padStart(3, '0')}",
                    fontSize = 11.sp)
                Text("Block Type: ${PATTERN_BLOCK_TYPES.find { it.first == cell.blockType }?.second ?: "0x${cell.blockType.toString(16)}"}",
                    fontSize = 11.sp)
                if (cell.bts != 0) {
                    Text("BTS: 0x${cell.bts.toString(16).uppercase().padStart(2, '0')}",
                        fontSize = 11.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = cell.hFlip,
                        onCheckedChange = { h ->
                            editorState.updatePatternCell(
                                pattern.id, selectedCellR, selectedCellC,
                                cell.copy(hFlip = h)
                            )
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("H-Flip", fontSize = 11.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = cell.vFlip,
                        onCheckedChange = { v ->
                            editorState.updatePatternCell(
                                pattern.id, selectedCellR, selectedCellC,
                                cell.copy(vFlip = v)
                            )
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("V-Flip", fontSize = 11.sp)
                }

                Divider()

                // Block type selector
                Text("Block Type:", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    for ((bt, label) in PATTERN_BLOCK_TYPES) {
                        val isSel = bt == cell.blockType
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    editorState.updatePatternCell(
                                        pattern.id, selectedCellR, selectedCellC,
                                        cell.copy(blockType = bt)
                                    )
                                },
                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text(
                                "0x${bt.toString(16).uppercase()} $label",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Render the pattern grid as a BufferedImage for display. */
private fun renderPatternGrid(
    pattern: TilePattern,
    tg: TileGraphics?,
    cellDp: Int
): BufferedImage? {
    if (tg == null) return null
    val w = pattern.cols * cellDp
    val h = pattern.rows * cellDp
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    val bg = java.awt.Color(0x0C, 0x0C, 0x18)
    g.color = bg
    g.fillRect(0, 0, w, h)

    for (r in 0 until pattern.rows) {
        for (c in 0 until pattern.cols) {
            val cell = pattern.getCell(r, c) ?: continue
            val pixels = tg.renderMetatile(cell.metatile) ?: continue

            val dx = c * cellDp
            val dy = r * cellDp
            val scale = cellDp / 16

            for (py in 0 until 16) {
                for (px in 0 until 16) {
                    val srcX = if (cell.hFlip) 15 - px else px
                    val srcY = if (cell.vFlip) 15 - py else py
                    val argb = pixels[srcY * 16 + srcX]
                    if (argb == 0) continue
                    for (sy in 0 until scale) {
                        for (sx in 0 until scale) {
                            val ix = dx + px * scale + sx
                            val iy = dy + py * scale + sy
                            if (ix in 0 until w && iy in 0 until h)
                                img.setRGB(ix, iy, argb)
                        }
                    }
                }
            }
        }
    }

    // Grid lines
    g.color = java.awt.Color(0x44, 0x44, 0x44)
    for (c in 0..pattern.cols) g.drawLine(c * cellDp, 0, c * cellDp, h)
    for (r in 0..pattern.rows) g.drawLine(0, r * cellDp, w, r * cellDp)
    g.dispose()
    return img
}
