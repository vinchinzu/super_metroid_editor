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
    private val blockTypeColors = mapOf(
        0x0 to 0xFF0A0A14.toInt(),   // Air — near black
        0x1 to 0xFF5A4A3A.toInt(),   // Slope — brown
        0x2 to 0xFF0A0A14.toInt(),   // Air (X-ray) — near black
        0x3 to 0xFF4A5A3A.toInt(),   // Treadmill — olive
        0x4 to 0xFF0A0A14.toInt(),   // Air (shootable) — near black
        0x5 to 0xFF0A0A14.toInt(),   // H-extend — near black (air-like)
        0x6 to 0xFF0A0A14.toInt(),   // Unused — near black
        0x7 to 0xFF0A0A14.toInt(),   // Unused — near black
        0x8 to 0xFF606878.toInt(),   // Solid — steel gray
        0x9 to 0xFF3060D0.toInt(),   // Door — bright blue
        0xA to 0xFFC03030.toInt(),   // Spike — red
        0xB to 0xFFC0A030.toInt(),   // Crumble — gold/yellow
        0xC to 0xFFD08030.toInt(),   // Shot block — orange
        0xD to 0xFF0A0A14.toInt(),   // Extension — near black
        0xE to 0xFF30A060.toInt(),   // Grapple — green
        0xF to 0xFF9040C0.toInt(),   // Bomb block — purple
    )
    
    // Darker variants for alternating tile pattern
    private val blockTypeDarkColors = mapOf(
        0x0 to 0xFF080810.toInt(),
        0x1 to 0xFF4A3A2A.toInt(),
        0x2 to 0xFF080810.toInt(),
        0x3 to 0xFF3A4A2A.toInt(),
        0x4 to 0xFF080810.toInt(),
        0x5 to 0xFF080810.toInt(),
        0x6 to 0xFF080810.toInt(),
        0x7 to 0xFF080810.toInt(),
        0x8 to 0xFF505868.toInt(),
        0x9 to 0xFF2050B0.toInt(),
        0xA to 0xFFA02020.toInt(),
        0xB to 0xFFA08020.toInt(),
        0xC to 0xFFB07020.toInt(),
        0xD to 0xFF080810.toInt(),
        0xE to 0xFF208050.toInt(),
        0xF to 0xFF7030A0.toInt(),
    )
    
    /**
     * Render a room's level data to pixel data.
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
        
        val blocksWide = room.width * BLOCKS_PER_SCREEN
        val blocksTall = room.height * BLOCKS_PER_SCREEN
        val expectedSize = blocksWide * blocksTall * 2 // 2 bytes per block for Layer 1
        
        println("Room 0x${room.roomId.toString(16)}: decompressed ${levelData.size} bytes (expected Layer1=${expectedSize}, blocks=${blocksWide}x${blocksTall})")
        
        if (levelData.size < expectedSize) {
            println("  Warning: decompressed data smaller than expected, rendering what we have")
        }
        
        // Parse block types and render
        val pixelWidth = blocksWide * BLOCK_SIZE
        val pixelHeight = blocksTall * BLOCK_SIZE
        val pixels = IntArray(pixelWidth * pixelHeight)
        
        // Fill with background
        pixels.fill(0xFF0A0A14.toInt())
        
        // Parse Layer 1 tilemap and render each block
        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                // Level data is stored screen-by-screen
                // Within each screen, blocks go left-to-right, top-to-bottom
                val screenX = bx / BLOCKS_PER_SCREEN
                val screenY = by / BLOCKS_PER_SCREEN
                val localX = bx % BLOCKS_PER_SCREEN
                val localY = by % BLOCKS_PER_SCREEN
                
                // Screen index (left to right, top to bottom)
                val screenIdx = screenY * room.width + screenX
                // Block index within screen
                val blockIdx = localY * BLOCKS_PER_SCREEN + localX
                // Byte offset in level data
                val dataOffset = (screenIdx * BLOCKS_PER_SCREEN * BLOCKS_PER_SCREEN + blockIdx) * 2
                
                if (dataOffset + 1 >= levelData.size) continue
                
                val lo = levelData[dataOffset].toInt() and 0xFF
                val hi = levelData[dataOffset + 1].toInt() and 0xFF
                val blockWord = (hi shl 8) or lo
                
                val blockType = (blockWord shr 12) and 0x0F
                val tileIndex = blockWord and 0x03FF
                val hFlip = (blockWord shr 10) and 1
                val vFlip = (blockWord shr 11) and 1
                
                // Choose color based on block type
                val isChecker = (bx + by) % 2 == 0
                val color = if (isChecker) {
                    blockTypeColors[blockType] ?: 0xFF0A0A14.toInt()
                } else {
                    blockTypeDarkColors[blockType] ?: 0xFF080810.toInt()
                }
                
                // Draw block
                drawBlock(pixels, pixelWidth, pixelHeight, bx * BLOCK_SIZE, by * BLOCK_SIZE, color)
            }
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
