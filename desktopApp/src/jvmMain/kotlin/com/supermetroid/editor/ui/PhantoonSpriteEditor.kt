package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.supermetroid.editor.rom.PhantoonSpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage

private enum class SpriteEditorTab { COMPONENTS, TILE_SHEET }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhantoonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(SpriteEditorTab.COMPONENTS) }
    var selectedDef by remember { mutableStateOf(PhantoonSpritemap.COMPONENT_TILEMAPS.first()) }

    // Pixel editor state
    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingIsTileSheet by remember { mutableStateOf(false) }
    var editingAssembledSprite by remember { mutableStateOf<PhantoonSpritemap.AssembledSprite?>(null) }
    var editingReference by remember { mutableStateOf<ImageBitmap?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val ep = editingPixels
    if (ep != null) {
        SpritePixelEditor(
            label = editingLabel,
            initialPixels = ep,
            imageWidth = editingWidth,
            imageHeight = editingHeight,
            fixedPalette = editingPalette,
            referenceImage = editingReference,
            onApply = { pixels ->
                if (editingIsTileSheet) {
                    editorState.applyPhantoonTileSheetEdits(pixels, editingWidth, editingHeight)
                } else {
                    val rp = romParser
                    val sprite = editingAssembledSprite
                    if (rp != null && sprite != null) {
                        editorState.applyPhantoonComponentEdits(rp, sprite, pixels)
                    }
                }
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingIsTileSheet = false
                editingAssembledSprite = null
                editingReference = null
            },
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Phantoon", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = activeTab == SpriteEditorTab.COMPONENTS,
                    onClick = { activeTab = SpriteEditorTab.COMPONENTS },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Components", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTab == SpriteEditorTab.TILE_SHEET,
                    onClick = { activeTab = SpriteEditorTab.TILE_SHEET },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile Sheet", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (editorState.hasCustomPhantoonTileSheet()) {
                                Text("●", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                if (romParser == null) {
                    Text("(load a ROM to enable editing)",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (activeTab) {
            SpriteEditorTab.COMPONENTS -> ComponentsTab(
                editorState = editorState,
                romParser = romParser,
                selectedDef = selectedDef,
                onSelectDef = { selectedDef = it },
                refreshKey = refreshKey,
                onEditPixels = { def ->
                    val rp = romParser ?: return@ComponentsTab
                    val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@ComponentsTab
                    editingPixels = sprite.pixels.copyOf()
                    editingWidth = sprite.width
                    editingHeight = sprite.height
                    editingPalette = editorState.getPhantoonPalette(rp)
                    editingLabel = "Phantoon ${def.name}"
                    editingIsTileSheet = false
                    editingAssembledSprite = sprite
                    editingReference = null
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )

            SpriteEditorTab.TILE_SHEET -> TileSheetTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEditTiles = {
                    val rp = romParser ?: return@TileSheetTab
                    val result = editorState.loadPhantoonTileSheet(rp)
                    if (result != null) {
                        val (pixels, w, h) = result
                        editingPixels = pixels
                        editingWidth = w
                        editingHeight = h
                        editingPalette = editorState.getSpriteSheetPalette()
                        editingLabel = "Phantoon Tile Sheet"
                        editingIsTileSheet = true
                        editingAssembledSprite = null
                        editingReference = buildReferenceImageBitmap(editorState, romParser)
                    }
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    selectedDef: PhantoonSpritemap.ComponentDef,
    onSelectDef: (PhantoonSpritemap.ComponentDef) -> Unit,
    refreshKey: Int,
    onEditPixels: (PhantoonSpritemap.ComponentDef) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Room: \$CD13 · Tileset: 5 · AI: \$A7", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            Text("BG2 tilemap components rendered from the room tileset. " +
                "Editing a tile updates all instances of that tile.",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

            PhantoonSpritemap.COMPONENT_TILEMAPS.forEach { def ->
                val isSelected = selectedDef.tilemapSnes == def.tilemapSnes
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectDef(def) },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComponentThumb(def, editorState, romParser, refreshKey, size = 32)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(def.name, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                            Text("\$${def.tilemapSnes.toString(16).uppercase()}", fontSize = 9.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        ComponentDetailPanel(
            def = selectedDef,
            editorState = editorState,
            romParser = romParser,
            refreshKey = refreshKey,
            onEditPixels = onEditPixels,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun ComponentDetailPanel(
    def: PhantoonSpritemap.ComponentDef,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditPixels: (PhantoonSpritemap.ComponentDef) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ComponentThumb(def, editorState, romParser, refreshKey, size = 72)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(def.name, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Tilemap: \$${def.tilemapSnes.toString(16).uppercase()}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Species: \$${def.speciesId}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Divider()
        Text("Actions", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onEditPixels(def) },
                enabled = romParser != null
            ) { Text("Edit Pixels", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    val rp = romParser ?: return@OutlinedButton
                    val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, "Export ${def.name} PNG", FileDialog.SAVE)
                    dialog.file = "phantoon_${def.name.lowercase().replace(" ", "_")}.png"
                    dialog.isVisible = true
                    val file = dialog.file
                    if (file != null) {
                        val path = if (file.endsWith(".png", ignoreCase = true)) "${dialog.directory}$file"
                                   else "${dialog.directory}$file.png"
                        val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
                        img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
                        javax.imageio.ImageIO.write(img, "PNG", java.io.File(path))
                    }
                },
                enabled = romParser != null
            ) { Text("Export PNG", fontSize = 11.sp) }
        }

        Surface(
            color = Color(0xFF1A2A1A),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Assembled from ROM tileset tiles via BG2 tilemap. " +
                "Pixel edits write back to the underlying 8x8 tiles \u2014 " +
                "shared tiles update everywhere simultaneously.",
                fontSize = 9.sp, color = Color(0xFF88CC88),
                lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        Divider()

        // Large preview of the assembled sprite
        val rp = romParser
        if (rp != null) {
            var spriteBitmap by remember(def.tilemapSnes, refreshKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(def.tilemapSnes, refreshKey) {
                val sprite = editorState.renderPhantoonComponent(rp, def)
                spriteBitmap = sprite?.let {
                    val img = BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, it.width, it.height, it.pixels, 0, it.width)
                    img.toComposeImageBitmap()
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bm = spriteBitmap
                if (bm != null) {
                    Image(
                        bitmap = bm,
                        contentDescription = "Phantoon ${def.name}",
                        modifier = Modifier
                            .padding(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .size((bm.width * 4).dp, (bm.height * 4).dp)
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("Load a ROM to view sprites", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComponentThumb(
    def: PhantoonSpritemap.ComponentDef,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    size: Int
) {
    val bitmap by produceState<ImageBitmap?>(null, def.tilemapSnes, refreshKey) {
        value = try {
            val rp = romParser ?: return@produceState
            val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@produceState
            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            img.toComposeImageBitmap()
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "Sprite ${def.name}",
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222244))
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222244)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = (size / 3).sp, color = Color(0xFF666688))
        }
    }
}

@Composable
private fun TileSheetTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditTiles: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCustom = editorState.hasCustomPhantoonTileSheet()

    var sheetBitmap by remember(refreshKey) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(refreshKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, romParser) {
        val rp = romParser
        if (rp == null) {
            loadError = "Load a ROM to view tile sheet"
            return@LaunchedEffect
        }
        sheetBitmap = try {
            val result = editorState.loadPhantoonTileSheet(rp)
            if (result != null) {
                val (pixels, w, h) = result
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                img.toComposeImageBitmap()
            } else null
        } catch (e: Exception) { loadError = e.message; null }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Phantoon Sprite Tile Sheet", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("78 tiles total \u00b7 Bank \$B7: 0x170F (37 tiles) + 0x1808 (41 tiles) \u00b7 VRAM 0x0300/0x0380",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("These tiles are shared by all 4 Phantoon species. Edits here are written to the ROM on export.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasCustom) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("Custom tile data active \u2014 will patch ROM on export",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onEditTiles,
                    enabled = romParser != null
                ) { Text("Edit Tiles", fontSize = 11.sp) }

                if (hasCustom) {
                    OutlinedButton(
                        onClick = { editorState.resetPhantoonTileSheet(); onRefresh() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reset to ROM Default", fontSize = 11.sp) }
                }
            }
        }

        Divider()

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.TopStart
        ) {
            when {
                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "Error", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                sheetBitmap == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                else -> {
                    Image(
                        bitmap = sheetBitmap!!,
                        contentDescription = "Phantoon tile sheet",
                        modifier = Modifier
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .let { m ->
                                val bm = sheetBitmap!!
                                m.size((bm.width * 4).dp, (bm.height * 4).dp)
                            }
                    )
                }
            }
        }

        val palette = editorState.getSpriteSheetPalette()
        if (palette != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Palette:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                palette.forEachIndexed { _, argb ->
                    val alpha = (argb ushr 24) and 0xFF
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (alpha == 0) Color(0xFF444444) else Color(argb))
                            .border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

/**
 * Build a reference image (the assembled Phantoon body from ROM) for
 * display alongside the raw tile sheet editor.
 */
private fun buildReferenceImageBitmap(editorState: EditorState, romParser: RomParser?): ImageBitmap? {
    val rp = romParser ?: return null
    val sprite = editorState.renderPhantoonComponent(rp, PhantoonSpritemap.COMPONENT_TILEMAPS[0]) ?: return null
    return try {
        val bi = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
        bi.toComposeImageBitmap()
    } catch (_: Exception) { null }
}
