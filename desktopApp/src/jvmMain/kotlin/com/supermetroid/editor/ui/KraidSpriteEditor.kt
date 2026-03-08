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
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage

private enum class KraidTab { COMPONENTS, TILE_SHEET }

private sealed class KraidComponent(val displayName: String) {
    object FullBody : KraidComponent("Full Body (nametable)")
    data class Body(val def: KraidSpritemap.BodyTilemapDef) : KraidComponent(def.name)
    data class BigSprmap(val def: KraidSpritemap.ComponentDef) : KraidComponent(def.name)
}

private val ALL_COMPONENTS: List<KraidComponent> = buildList {
    add(KraidComponent.FullBody)
    KraidSpritemap.BODY_TILEMAPS.forEach { add(KraidComponent.Body(it)) }
    KraidSpritemap.BIGSPRMAP_COMPONENTS.forEach { add(KraidComponent.BigSprmap(it)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KraidSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(KraidTab.COMPONENTS) }
    var selectedComponent by remember { mutableStateOf<KraidComponent>(
        KraidComponent.Body(KraidSpritemap.BODY_TILEMAPS[0])
    ) }

    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingIsTileSheet by remember { mutableStateOf(false) }
    var editingAssembledSprite by remember { mutableStateOf<KraidSpritemap.AssembledSprite?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val ep = editingPixels
    if (ep != null) {
        SpritePixelEditor(
            label = editingLabel,
            initialPixels = ep,
            imageWidth = editingWidth,
            imageHeight = editingHeight,
            fixedPalette = editingPalette,
            referenceImage = null,
            onApply = { pixels ->
                if (editingIsTileSheet) {
                    editorState.applyKraidTileSheetEdits(pixels, editingWidth, editingHeight)
                } else {
                    val sprite = editingAssembledSprite
                    if (sprite != null) {
                        editorState.applyKraidComponentEdits(sprite, pixels)
                    }
                }
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingIsTileSheet = false
                editingAssembledSprite = null
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
                Text("Kraid", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = activeTab == KraidTab.COMPONENTS,
                    onClick = { activeTab = KraidTab.COMPONENTS },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
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
                    selected = activeTab == KraidTab.TILE_SHEET,
                    onClick = { activeTab = KraidTab.TILE_SHEET },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile Sheet", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (editorState.hasCustomKraidTileSheet()) {
                                Text("\u25CF", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
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
            KraidTab.COMPONENTS -> KraidComponentsTab(
                editorState = editorState,
                romParser = romParser,
                selectedComponent = selectedComponent,
                onSelectComponent = { selectedComponent = it },
                refreshKey = refreshKey,
                onEditPixels = { comp ->
                    val rp = romParser ?: return@KraidComponentsTab
                    val sprite = renderKraidComponent(editorState, rp, comp) ?: return@KraidComponentsTab
                    editingPixels = sprite.pixels.copyOf()
                    editingWidth = sprite.width
                    editingHeight = sprite.height
                    editingPalette = editorState.getKraidPalette(rp)
                    editingLabel = "Kraid ${comp.displayName}"
                    editingIsTileSheet = false
                    editingAssembledSprite = sprite
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )

            KraidTab.TILE_SHEET -> KraidTileSheetTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEditTiles = {
                    val rp = romParser ?: return@KraidTileSheetTab
                    val result = editorState.loadKraidTileSheet(rp) ?: return@KraidTileSheetTab
                    val (pixels, w, h) = result
                    editingPixels = pixels
                    editingWidth = w
                    editingHeight = h
                    editingPalette = editorState.getKraidSheetPalette()
                    editingLabel = "Kraid Tile Sheet"
                    editingIsTileSheet = true
                    editingAssembledSprite = null
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun renderKraidComponent(
    editorState: EditorState,
    romParser: RomParser,
    comp: KraidComponent
): KraidSpritemap.AssembledSprite? = when (comp) {
    is KraidComponent.FullBody -> editorState.renderKraidFullBody(romParser)
    is KraidComponent.Body -> editorState.renderKraidBodyTilemap(romParser, comp.def)
    is KraidComponent.BigSprmap -> editorState.renderKraidBigSprmap(romParser, comp.def)
}

@Composable
private fun KraidComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    selectedComponent: KraidComponent,
    onSelectComponent: (KraidComponent) -> Unit,
    refreshKey: Int,
    onEditPixels: (KraidComponent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Room: \$A59F \u00b7 Tileset: 27 \u00b7 AI: \$A7", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            Text("128 tiles from \$B9:FA38. Body rendered via BG2 " +
                "nametable + body tilemaps for rising states.",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

            Text("Body Views", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))

            KraidComponentItem(KraidComponent.FullBody, selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            KraidSpritemap.BODY_TILEMAPS.forEach { def ->
                KraidComponentItem(KraidComponent.Body(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(4.dp))
            Text("Detail Components", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))

            KraidSpritemap.BIGSPRMAP_COMPONENTS.forEach { def ->
                KraidComponentItem(KraidComponent.BigSprmap(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        KraidComponentDetail(
            comp = selectedComponent,
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
private fun KraidComponentItem(
    comp: KraidComponent,
    selected: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onSelect: (KraidComponent) -> Unit
) {
    val isSelected = comp.displayName == selected.displayName
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(comp) },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KraidThumb(comp, editorState, romParser, refreshKey, size = 28)
            Column(modifier = Modifier.weight(1f)) {
                Text(comp.displayName, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface)
                val addrText = when (comp) {
                    is KraidComponent.FullBody -> "\$B9:FE3E"
                    is KraidComponent.Body -> "\$${comp.def.snesAddr.toString(16).uppercase()}"
                    is KraidComponent.BigSprmap -> "\$${comp.def.tilemapSnes.toString(16).uppercase()}"
                }
                Text(addrText, fontSize = 9.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KraidThumb(
    comp: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    size: Int
) {
    val bitmap by produceState<ImageBitmap?>(null, comp.displayName, refreshKey) {
        value = try {
            val rp = romParser ?: return@produceState
            val sprite = renderKraidComponent(editorState, rp, comp) ?: return@produceState
            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            img.toComposeImageBitmap()
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = comp.displayName,
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
private fun KraidComponentDetail(
    comp: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditPixels: (KraidComponent) -> Unit,
    @Suppress("UNUSED_PARAMETER") onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KraidThumb(comp, editorState, romParser, refreshKey, size = 56)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(comp.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                val addrText = when (comp) {
                    is KraidComponent.FullBody -> "BG2 Nametable: \$B9:FE3E (32\u00d764)"
                    is KraidComponent.Body -> "Tilemap: \$${comp.def.snesAddr.toString(16).uppercase()} (${comp.def.cols}\u00d7${comp.def.rows})"
                    is KraidComponent.BigSprmap -> "Tilemap: \$${comp.def.tilemapSnes.toString(16).uppercase()}"
                }
                Text(addrText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Species: \$E2BF \u00b7 8 sub-entities", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Divider()
        Text("Actions", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onEditPixels(comp) },
                enabled = romParser != null
            ) { Text("Edit Pixels", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    val rp = romParser ?: return@OutlinedButton
                    val sprite = renderKraidComponent(editorState, rp, comp) ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, "Export ${comp.displayName} PNG", FileDialog.SAVE)
                    dialog.file = "kraid_${comp.displayName.lowercase().replace(" ", "_").replace("(", "").replace(")", "")}.png"
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

        if (comp is KraidComponent.FullBody) {
            Surface(
                color = Color(0xFF2A1A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Raw initial BG2 state. In-game, the engine clears this and " +
                    "rebuilds from body tilemaps. Use Body views for accurate renders.",
                    fontSize = 9.sp, color = Color(0xFFCC8888),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (comp is KraidComponent.Body && comp.def.name == "Body (full height)") {
            Surface(
                color = Color(0xFF2A2A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Some tiles in this view are loaded dynamically during the " +
                    "fight as Kraid rises. Tiles not present in the static tileset " +
                    "may render incorrectly. Other body views are fully accurate.",
                    fontSize = 9.sp, color = Color(0xFFCCCC88),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        Surface(
            color = Color(0xFF1A2A1A),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kraid body tiles from \$B9:FA38 (128 tiles, tile index base 0x100). " +
                "Pixel edits write to the 4bpp tile data and affect all views.",
                fontSize = 9.sp, color = Color(0xFF88CC88),
                lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        Divider()

        val rp = romParser
        if (rp != null) {
            var spriteBitmap by remember(comp.displayName, refreshKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(comp.displayName, refreshKey) {
                val sprite = renderKraidComponent(editorState, rp, comp)
                spriteBitmap = sprite?.let {
                    val img = BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, it.width, it.height, it.pixels, 0, it.width)
                    img.toComposeImageBitmap()
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF111122), RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopStart
            ) {
                val bm = spriteBitmap
                if (bm != null) {
                    val scale = if (comp is KraidComponent.BigSprmap) 6 else 2
                    Image(
                        bitmap = bm,
                        contentDescription = "Kraid ${comp.displayName}",
                        modifier = Modifier
                            .padding(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .size((bm.width * scale).dp, (bm.height * scale).dp)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().defaultMinSize(minHeight = 100.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
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
private fun KraidTileSheetTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditTiles: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCustom = editorState.hasCustomKraidTileSheet()

    var sheetBitmap by remember(refreshKey) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(refreshKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, romParser) {
        val rp = romParser
        if (rp == null) {
            loadError = "Load a ROM to view tile sheet"
            return@LaunchedEffect
        }
        sheetBitmap = try {
            val result = editorState.loadKraidTileSheet(rp)
            if (result != null) {
                val (pixels, w, h) = result
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                img.toComposeImageBitmap()
            } else null
        } catch (e: Exception) { loadError = e.message; null }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Kraid Sprite Tile Sheet", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("128 tiles \u00b7 Bank \$B9: FA38 \u00b7 VRAM base 0x0100",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("These tiles form Kraid's body on BG2. Edits are written to the ROM on export.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasCustom) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)) {
                        Text("Custom tile data active \u2014 will patch ROM on export",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onEditTiles,
                    enabled = romParser != null
                ) { Text("Edit Tiles", fontSize = 11.sp) }

                if (hasCustom) {
                    OutlinedButton(
                        onClick = { editorState.resetKraidTileSheet(); onRefresh() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)
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
                        contentDescription = "Kraid tile sheet",
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

        val palette = editorState.getKraidSheetPalette()
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
