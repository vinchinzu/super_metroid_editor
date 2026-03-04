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
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * One Phantoon body-component entry.
 * All 4 species ($E4BF, $E4FF, $E53F, $E57F) are overlaid body/component sprites
 * rendered simultaneously by the SNES PPU. They are NOT separate projectiles.
 * The "flame" projectiles Phantoon fires are dynamically spawned with different species IDs.
 */
data class PhantoonSpriteComponent(
    val speciesIdHex: String,
    val label: String,
    val description: String
)

val PHANTOON_SPRITE_COMPONENTS = listOf(
    PhantoonSpriteComponent("E4BF", "Body",       "Main ghost body. 2500 HP, 40 contact dmg. Eye opens during figure-8."),
    PhantoonSpriteComponent("E4FF", "Component 1","Body component overlay. Shares tile data with main body."),
    PhantoonSpriteComponent("E53F", "Component 2","Body component overlay. Shares tile data with main body."),
    PhantoonSpriteComponent("E57F", "Component 3","Body component overlay. Shares tile data with main body.")
)

private enum class SpriteEditorTab { COMPONENTS, TILE_SHEET }

/**
 * Sprite editor for Phantoon.
 *
 * COMPONENTS tab: pre-rendered PNG per species (visual reference + import/export).
 * TILE SHEET tab: decoded ROM tile graphics from $B7:170F + $B7:1808 (actual edit surface).
 *
 * ROM export applies any tile-sheet edits to both blocks in bank $B7 via LZ5 re-compression.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhantoonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(SpriteEditorTab.COMPONENTS) }
    var selectedComponent by remember { mutableStateOf<PhantoonSpriteComponent?>(PHANTOON_SPRITE_COMPONENTS.first()) }
    var editingComponent by remember { mutableStateOf<PhantoonSpriteComponent?>(null) }
    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingIsTileSheet by remember { mutableStateOf(false) }
    // Reference image: assembled sprite shown alongside the raw tile editor
    var editingReference by remember { mutableStateOf<ImageBitmap?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // If the pixel editor is open, show it full-screen
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
                    val ec = editingComponent
                    if (ec != null) {
                        editorState.saveEnemySpritePixels(ec.speciesIdHex, pixels, editingWidth, editingHeight)
                    }
                }
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingComponent = null
                editingPalette = null
                editingIsTileSheet = false
            },
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Top tab selector ──
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
                    label = { Text("Components", fontSize = 10.sp) },
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
                selectedComponent = selectedComponent,
                onSelectComponent = { selectedComponent = it },
                refreshKey = refreshKey,
                onEditPixels = { comp ->
                    val result = editorState.getEnemySpritePixels(comp.speciesIdHex)
                    if (result != null) {
                        editingPixels = result.first
                        editingWidth = result.second.first
                        editingHeight = result.second.second
                        editingPalette = null
                        editingLabel = comp.label
                        editingIsTileSheet = false
                        editingComponent = comp
                        editingReference = null
                    }
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
                        editingComponent = null
                        editingReference = buildReferenceImageBitmap(editorState)
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
    selectedComponent: PhantoonSpriteComponent?,
    onSelectComponent: (PhantoonSpriteComponent) -> Unit,
    refreshKey: Int,
    onEditPixels: (PhantoonSpriteComponent) -> Unit,
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
            Text("Room: \$CD13 · AI: \$A7", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            Text("All 4 components share tile data in bank \$B7. " +
                "Use the Tile Sheet tab to edit the actual ROM tiles.",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

            PHANTOON_SPRITE_COMPONENTS.forEach { comp ->
                val isSelected = selectedComponent?.speciesIdHex == comp.speciesIdHex
                val hasCustom = editorState.hasCustomEnemySprite(comp.speciesIdHex)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectComponent(comp) },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpriteThumb(comp.speciesIdHex, editorState, refreshKey, size = 32)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(comp.label, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                            Text("\$${comp.speciesIdHex}", fontSize = 9.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hasCustom) Text("●", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        val sel = selectedComponent
        if (sel == null) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Select a sprite component", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ComponentDetailPanel(
                component = sel,
                editorState = editorState,
                refreshKey = refreshKey,
                onEditPixels = onEditPixels,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ComponentDetailPanel(
    component: PhantoonSpriteComponent,
    editorState: EditorState,
    refreshKey: Int,
    onEditPixels: (PhantoonSpriteComponent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCustom = editorState.hasCustomEnemySprite(component.speciesIdHex)

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpriteThumb(component.speciesIdHex, editorState, refreshKey, size = 72)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(component.label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Species ID: \$${component.speciesIdHex}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(component.description, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasCustom) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("Custom PNG active", fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }

        Divider()
        Text("Actions", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEditPixels(component) }) { Text("Edit Preview", fontSize = 11.sp) }

            OutlinedButton(onClick = {
                val dialog = FileDialog(null as Frame?, "Export Sprite PNG", FileDialog.SAVE)
                dialog.file = "${component.speciesIdHex}.png"
                dialog.isVisible = true
                val file = dialog.file
                if (file != null) {
                    val path = if (file.endsWith(".png", ignoreCase = true)) "${dialog.directory}$file"
                               else "${dialog.directory}$file.png"
                    editorState.exportEnemySprite(component.speciesIdHex, path)
                }
            }) { Text("Export PNG", fontSize = 11.sp) }

            OutlinedButton(onClick = {
                val dialog = FileDialog(null as Frame?, "Import Sprite PNG", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.endsWith(".png", ignoreCase = true) }
                dialog.isVisible = true
                val file = dialog.file
                if (file != null) {
                    editorState.importEnemySprite(component.speciesIdHex, "${dialog.directory}$file")
                    onRefresh()
                }
            }) { Text("Import PNG", fontSize = 11.sp) }

            if (hasCustom) {
                OutlinedButton(
                    onClick = { editorState.resetEnemySprite(component.speciesIdHex); onRefresh() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset", fontSize = 11.sp) }
            }
        }

        Surface(
            color = Color(0xFF2A2A1A),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Preview only \u2014 edits here update the display image but do NOT change the ROM. " +
                "To modify in-game sprites, use the Tile Sheet tab.",
                fontSize = 9.sp, color = Color(0xFFCCBB66),
                lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        Divider()

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ROM Info", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Species header: \$A0:${component.speciesIdHex}  ·  AI bank: \$A7",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sprite tiles shared: \$B7:170F (37 tiles) + \$B7:1808 (41 tiles)",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("All 4 Phantoon components overlay these same VRAM tiles.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
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

    // Load and render the tile sheet in background
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
                Text("78 tiles total · Bank \$B7: 0x170F (37 tiles) + 0x1808 (41 tiles) · VRAM 0x0300/0x0380",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("These tiles are shared by all 4 Phantoon species. Edits here are written to the ROM on export.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasCustom) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("Custom tile data active — will patch ROM on export",
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

        // Tile sheet preview
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
                    androidx.compose.foundation.Image(
                        bitmap = sheetBitmap!!,
                        contentDescription = "Phantoon tile sheet",
                        modifier = Modifier
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            // Scale up 4× for visibility (each 8px tile → 32px)
                            .let { m ->
                                val bm = sheetBitmap!!
                                m.size((bm.width * 4).dp, (bm.height * 4).dp)
                            }
                    )
                }
            }
        }

        // Palette swatches (loaded after tile sheet load)
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

@Composable
private fun SpriteThumb(
    speciesIdHex: String,
    editorState: EditorState,
    refreshKey: Int,
    size: Int
) {
    val bitmap by produceState<ImageBitmap?>(null, speciesIdHex, refreshKey) {
        value = try {
            val result = editorState.getEnemySpritePixels(speciesIdHex)
            if (result != null) {
                val (pixels, dims) = result
                val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, dims.first, dims.second, pixels, 0, dims.first)
                img.toComposeImageBitmap()
            } else null
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!,
            contentDescription = "Sprite $speciesIdHex",
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

/**
 * Loads the assembled Phantoon body image (E4BF.png) and converts it to an ImageBitmap
 * for use as a reference panel in the tile sheet editor.
 */
private fun buildReferenceImageBitmap(editorState: EditorState): ImageBitmap? {
    val result = editorState.getEnemySpritePixels("E4BF") ?: return null
    val (pixels, dims) = result
    val (w, h) = dims
    return try {
        val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, w, h, pixels, 0, w)
        bi.toComposeImageBitmap()
    } catch (_: Exception) { null }
}
