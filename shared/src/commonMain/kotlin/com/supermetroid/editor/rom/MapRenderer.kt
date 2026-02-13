package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Renders Super Metroid room maps from decompressed level data.
 * 
 * Level data format (after LZ2 decompression):
 *   - Layer 1 tilemap: (width * 16) * (height * 16) entries, 2 bytes each
 *   - BTS (Block Type Special): 1 byte per block
 * 
 * Each 2-byte block entry:
 *   Bits 0-9:   Block/metatile index (which 16x16 tile graphic to use)
 *   Bit  10:    Horizontal flip
 *   Bit  11:    Vertical flip
 *   Bits 12-15: Block type (determines collision behavior)
 * 
 * Block types (upper nibble):
 *   0x0: Air          0x8: Solid
 *   0x1: Slope         0x9: Door
 *   0x2: Air (X-ray)   0xA: Spike
 *   0x3: Treadmill     0xB: Crumble block
 *   0x4: Air (shot)    0xC: Shot block
 *   0x5: H-extend      0xD: V/H extension
 *   0x6: Unused         0xE: Grapple block
 *   0x7: Unused         0xF: Bomb block
 */
class MapRenderer(private val romParser: RomParser) {
    
    companion object {
        const val BLOCK_SIZE = 16      // Each block is 16x16 pixels
        const val BLOCKS_PER_SCREEN = 16 // 16 blocks per screen edge (256px / 16px)
    }
    
    // Block type colors (ARGB)
    // Key insight: many block types that are "special" (bomb, shot, crumble) LOOK like
    // solid terrain in the game. We use terrain-like colors with subtle tints to distinguish them.
    private val blockTypeColors = mapOf(
        0x0 to 0xFF0A0A14.toInt(),   // Air — near black
        0x1 to 0xFF687060.toInt(),   // Slope — muted terrain (slopes look like solid ground)
        0x2 to 0xFF0A0A14.toInt(),   // Air (X-ray) — near black
        0x3 to 0xFF585848.toInt(),   // Treadmill — muted olive
        0x4 to 0xFF0A0A14.toInt(),   // Air (shootable) — near black
        0x5 to 0xFF0A0A14.toInt(),   // H-extend — near black
        0x6 to 0xFF0A0A14.toInt(),   // Unused — near black
        0x7 to 0xFF0A0A14.toInt(),   // Unused — near black
        0x8 to 0xFF707880.toInt(),   // Solid — lighter steel gray
        0x9 to 0xFF4080E0.toInt(),   // Door — bright blue (doors are important!)
        0xA to 0xFFD04040.toInt(),   // Spike — red
        0xB to 0xFF887848.toInt(),   // Crumble — slightly warm terrain
        0xC to 0xFF808068.toInt(),   // Shot block — terrain with slight warm tint
        0xD to 0xFF0A0A14.toInt(),   // Extension — near black
        0xE to 0xFF408858.toInt(),   // Grapple — greenish terrain
        0xF to 0xFF687080.toInt(),   // Bomb block — terrain color (same as solid, slight blue)
    )
    
    // Darker variants for alternating tile pattern (subtle checkerboard within blocks)
    private val blockTypeDarkColors = mapOf(
        0x0 to 0xFF080810.toInt(),
        0x1 to 0xFF586858.toInt(),
        0x2 to 0xFF080810.toInt(),
        0x3 to 0xFF484838.toInt(),
        0x4 to 0xFF080810.toInt(),
        0x5 to 0xFF080810.toInt(),
        0x6 to 0xFF080810.toInt(),
        0x7 to 0xFF080810.toInt(),
        0x8 to 0xFF606870.toInt(),
        0x9 to 0xFF3070D0.toInt(),
        0xA to 0xFFB03030.toInt(),
        0xB to 0xFF786838.toInt(),
        0xC to 0xFF707058.toInt(),
        0xD to 0xFF080810.toInt(),
        0xE to 0xFF307848.toInt(),
        0xF to 0xFF586070.toInt(),
    )
    
    /**
     * Render a room's level data to pixel data.
     * 
     * Decompressed level data format (from SMILE source):
     *   Bytes 0-1:  Layer 1 size in bytes (16-bit LE) 
     *   Bytes 2+:   Layer 1 tile data (2 bytes per 16x16 block)
     *   Then:       BTS data (1 byte per block)
     *   Then:       Layer 2 tile data (optional)
     * 
     * Each tile entry (2 bytes LE):
     *   Bits 0-9:   Block/metatile index
     *   Bit 10:     H-flip
     *   Bit 11:     V-flip
     *   Bits 12-15: Block type (collision)
     */
    fun renderRoom(room: Room): RoomRenderData? {
        if (room.levelDataPtr == 0) {
            println("Room 0x${room.roomId.toString(16)}: no level data pointer, falling back to grid")
            return renderGrid(room)
        }
        
        // Decompress level data
        val levelData: ByteArray
        try {
            levelData = romParser.decompressLZ2(room.levelDataPtr)
        } catch (e: Exception) {
            println("Room 0x${room.roomId.toString(16)}: LZ2 decompression failed: ${e.message}")
            return renderGrid(room)
        }
        
        if (levelData.size < 2) {
            println("Room 0x${room.roomId.toString(16)}: decompressed data too small (${levelData.size} bytes)")
            return renderGrid(room)
        }
        
        // First 2 bytes = Layer 1 size header
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val tileDataStart = 2 // Tile data starts after 2-byte header
        
        val blocksWide = room.width * BLOCKS_PER_SCREEN
        val blocksTall = room.height * BLOCKS_PER_SCREEN
        val totalBlocks = blocksWide * blocksTall
        val expectedLayer1Size = totalBlocks * 2
        
        println("Room 0x${room.roomId.toString(16)}: decompressed ${levelData.size} bytes, layer1Size=$layer1Size (expected=$expectedLayer1Size), blocks=${blocksWide}x${blocksTall}")
        
        // BTS data starts after Layer 1 tile data
        val btsDataStart = tileDataStart + layer1Size
        
        // Parse block types and render
        val pixelWidth = blocksWide * BLOCK_SIZE
        val pixelHeight = blocksTall * BLOCK_SIZE
        val pixels = IntArray(pixelWidth * pixelHeight)
        pixels.fill(0xFF0A0A14.toInt())
        
        // Parse Layer 1 tilemap.
        // After decompression, tiles are stored as a flat row-major array:
        //   tile[0] = block (0,0), tile[1] = block (1,0), ...
        //   tile[blocksWide] = block (0,1), etc.
        // This goes across the FULL room width (all screens), NOT screen-by-screen.
        // (Verified against SMILE source: it reads tiles linearly then maps using
        //  pixelX = (index * 16) % (width * 256), pixelY = (index * 16) / (width * 256) * 16)
        for (tileIdx in 0 until totalBlocks) {
            val dataOffset = tileDataStart + tileIdx * 2
            if (dataOffset + 1 >= levelData.size) break
            
            val lo = levelData[dataOffset].toInt() and 0xFF
            val hi = levelData[dataOffset + 1].toInt() and 0xFF
            val blockWord = (hi shl 8) or lo
            
            val blockType = (blockWord shr 12) and 0x0F
            
            // Row-major layout: x = index % width, y = index / width
            val bx = tileIdx % blocksWide
            val by = tileIdx / blocksWide
            
            val isChecker = (bx + by) % 2 == 0
            val color = if (isChecker) {
                blockTypeColors[blockType] ?: 0xFF0A0A14.toInt()
            } else {
                blockTypeDarkColors[blockType] ?: 0xFF080810.toInt()
            }
            
            drawBlock(pixels, pixelWidth, pixelHeight, bx * BLOCK_SIZE, by * BLOCK_SIZE, color)
        }
        
        // Draw screen boundary grid lines
        drawScreenGrid(pixels, pixelWidth, pixelHeight, room.width, room.height)
        
        return RoomRenderData(pixelWidth, pixelHeight, pixels)
    }
    
    /**
     * Draw a single 16x16 block at the given pixel position.
     */
    private fun drawBlock(pixels: IntArray, totalW: Int, totalH: Int, px: Int, py: Int, color: Int) {
        for (y in 0 until BLOCK_SIZE) {
            for (x in 0 until BLOCK_SIZE) {
                val fx = px + x
                val fy = py + y
                if (fx in 0 until totalW && fy in 0 until totalH) {
                    pixels[fy * totalW + fx] = color
                }
            }
        }
        
        // Draw a subtle 1px border on top and left edges for tile definition
        val borderColor = brighten(color, 0.15f)
        for (x in 0 until BLOCK_SIZE) {
            val fx = px + x
            if (fx in 0 until totalW && py in 0 until totalH) {
                pixels[py * totalW + fx] = borderColor
            }
        }
        for (y in 0 until BLOCK_SIZE) {
            val fy = py + y
            if (px in 0 until totalW && fy in 0 until totalH) {
                pixels[fy * totalW + px] = borderColor
            }
        }
    }
    
    /**
     * Draw white screen boundary grid lines.
     */
    private fun drawScreenGrid(pixels: IntArray, w: Int, h: Int, screensW: Int, screensH: Int) {
        val gridColor = 0x60FFFFFF  // Semi-transparent white
        
        for (sy in 0..screensH) {
            val y = sy * BLOCKS_PER_SCREEN * BLOCK_SIZE
            for (thickness in -1..0) {
                val gy = y + thickness
                if (gy in 0 until h) {
                    for (x in 0 until w) {
                        val idx = gy * w + x
                        pixels[idx] = blendColor(pixels[idx], gridColor)
                    }
                }
            }
        }
        for (sx in 0..screensW) {
            val x = sx * BLOCKS_PER_SCREEN * BLOCK_SIZE
            for (thickness in -1..0) {
                val gx = x + thickness
                if (gx in 0 until w) {
                    for (y in 0 until h) {
                        val idx = y * w + gx
                        pixels[idx] = blendColor(pixels[idx], gridColor)
                    }
                }
            }
        }
    }
    
    /**
     * Fallback: render a simple grid when level data isn't available.
     */
    private fun renderGrid(room: Room): RoomRenderData {
        val pixelWidth = room.width * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val pixelHeight = room.height * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val pixels = IntArray(pixelWidth * pixelHeight)
        
        val areaColors = mapOf(
            0 to 0xFF2A4858.toInt(), 1 to 0xFF2A5828.toInt(),
            2 to 0xFF6A2828.toInt(), 3 to 0xFF3A3A5A.toInt(),
            4 to 0xFF28486A.toInt(), 5 to 0xFF5A4828.toInt(),
            6 to 0xFF5A5A5A.toInt()
        )
        val bgColor = areaColors[room.area] ?: 0xFF2A2A2A.toInt()
        pixels.fill(bgColor)
        drawScreenGrid(pixels, pixelWidth, pixelHeight, room.width, room.height)
        
        return RoomRenderData(pixelWidth, pixelHeight, pixels)
    }
    
    private fun brighten(color: Int, amount: Float): Int {
        val r = minOf(255, ((color shr 16 and 0xFF) + (255 * amount)).toInt())
        val g = minOf(255, ((color shr 8 and 0xFF) + (255 * amount)).toInt())
        val b = minOf(255, ((color and 0xFF) + (255 * amount)).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    
    private fun blendColor(base: Int, overlay: Int): Int {
        val alpha = (overlay ushr 24) and 0xFF
        if (alpha == 0) return base
        val invAlpha = 255 - alpha
        val r = (((overlay shr 16) and 0xFF) * alpha + ((base shr 16) and 0xFF) * invAlpha) / 255
        val g = (((overlay shr 8) and 0xFF) * alpha + ((base shr 8) and 0xFF) * invAlpha) / 255
        val b = ((overlay and 0xFF) * alpha + (base and 0xFF) * invAlpha) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

data class RoomRenderData(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)
