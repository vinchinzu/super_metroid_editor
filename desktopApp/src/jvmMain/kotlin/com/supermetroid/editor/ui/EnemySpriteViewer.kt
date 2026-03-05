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
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.image.BufferedImage

@Composable
@Suppress("UNUSED_PARAMETER")
fun EnemySpriteViewer(
    entry: EnemySpriteGraphics.Companion.EnemySpriteEntry,
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val rp = romParser
    if (rp == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Load a ROM to view sprites", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val stats = remember(entry.speciesId) {
        EnemySpriteGraphics.readSpeciesStats(rp, entry.speciesId)
    }
    val palette = remember(entry.speciesId) {
        EnemySpriteGraphics.readEnemyPalette(rp, entry.speciesId)
    }
    val gfxBlock = remember(entry.speciesId) {
        EnemySpriteGraphics.readGraphicsBlock(rp, entry.speciesId)
    }

    val tileData = remember(entry.speciesId) {
        EnemySpriteGraphics.loadEnemyTileData(rp, entry.speciesId)
    }

    val assembledSprite = remember(entry.speciesId) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        val smap = EnemySpritemap(rp)
        val defaultSmap = smap.findDefaultSpritemap(entry.speciesId) ?: return@remember null
        smap.renderSpritemap(defaultSmap, td, pal)
    }

    val tileSheet = remember(entry.speciesId) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        val gfx = EnemySpriteGraphics(rp)
        gfx.loadFromRaw(listOf(td))
        gfx.renderSheet(pal)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            entry.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Species info
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Species Info", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Divider(modifier = Modifier.padding(vertical = 2.dp))

                val hexId = entry.speciesId.toString(16).uppercase().padStart(4, '0')
                InfoRow("Species ID", "\$A0:$hexId")

                if (stats != null) {
                    val (tileSize, hp, damage) = stats
                    InfoRow("HP", "$hp")
                    InfoRow("Damage", "$damage")
                    InfoRow("Tile Data Size", "$tileSize bytes (${tileSize / 32} tiles)")
                }

                if (gfxBlock != null) {
                    val snesHex = "\$${(gfxBlock.snesAddress shr 16).toString(16).uppercase()}:" +
                        "${(gfxBlock.snesAddress and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"
                    InfoRow("GFX Address", snesHex)
                }
            }
        }

        // Palette display
        if (palette != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Palette [ROM]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0 until 16) {
                            val argb = palette[i]
                            val r = (argb shr 16) and 0xFF
                            val g = (argb shr 8) and 0xFF
                            val b = argb and 0xFF
                            val a = (argb ushr 24) and 0xFF
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (a < 128) MaterialTheme.colorScheme.surface
                                        else Color(r / 255f, g / 255f, b / 255f)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Assembled sprite preview (OAM spritemap assembly)
        if (assembledSprite != null) {
            val scale = maxOf(4, minOf(8, 128 / maxOf(assembledSprite.width, assembledSprite.height)))
            val dispW = assembledSprite.width * scale
            val dispH = assembledSprite.height * scale
            val bitmap = remember(assembledSprite) {
                val img = BufferedImage(assembledSprite.width, assembledSprite.height, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, assembledSprite.width, assembledSprite.height, assembledSprite.pixels, 0, assembledSprite.width)
                img.toComposeImageBitmap()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sprite [Assembled]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    val checkerSize = 4
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(dispW.dp, dispH.dp)) {
                            for (cy in 0 until dispH step checkerSize) {
                                for (cx in 0 until dispW step checkerSize) {
                                    val isLight = ((cx / checkerSize) + (cy / checkerSize)) % 2 == 0
                                    drawRect(
                                        color = if (isLight) Color(0xFF3A3A4A) else Color(0xFF2A2A3A),
                                        topLeft = androidx.compose.ui.geometry.Offset(cx.toFloat(), cy.toFloat()),
                                        size = androidx.compose.ui.geometry.Size(checkerSize.toFloat(), checkerSize.toFloat())
                                    )
                                }
                            }
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${entry.name} Assembled Sprite",
                            modifier = Modifier.size(dispW.dp, dispH.dp),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                        )
                    }
                    Text("${assembledSprite.width}×${assembledSprite.height}px • ${assembledSprite.spritemap.entries.size} OAM entries",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Tile sheet preview
        if (tileSheet != null) {
            val (pixels, w, h) = tileSheet
            val bitmap = remember(pixels, w, h) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                img.toComposeImageBitmap()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tile Sheet [ROM]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    val checkerSize = 4
                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))) {
                        Canvas(modifier = Modifier.size((w * 4).dp, (h * 4).dp)) {
                            for (cy in 0 until (h * 4) step checkerSize) {
                                for (cx in 0 until (w * 4) step checkerSize) {
                                    val isLight = ((cx / checkerSize) + (cy / checkerSize)) % 2 == 0
                                    drawRect(
                                        color = if (isLight) Color(0xFF3A3A4A) else Color(0xFF2A2A3A),
                                        topLeft = androidx.compose.ui.geometry.Offset(cx.toFloat(), cy.toFloat()),
                                        size = androidx.compose.ui.geometry.Size(checkerSize.toFloat(), checkerSize.toFloat())
                                    )
                                }
                            }
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${entry.name} Tile Sheet",
                            modifier = Modifier.size((w * 4).dp, (h * 4).dp),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                        )
                    }
                    Text("${w}×${h}px • ${(w / 8) * (h / 8)} tiles",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Could not load tile data",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("The GRAPHADR field in this enemy's species header may be invalid,\n" +
                        "or the compressed tile data could not be decompressed.",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp))
        Text(value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}
