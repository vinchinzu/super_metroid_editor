package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Renders Super Metroid room maps from decompressed level data.
 * 
 * Each 2-byte block entry (LE):
 *   Bits 0-9:   Block/metatile index
 *   Bit  10:    Horizontal flip
 *   Bit  11:    Vertical flip
 *   Bits 12-15: Block type (collision)
 * 
 * Block types: 0=Air, 1=Slope, 8=Solid, 9=Door, A=Spike, 
 *              B=Crumble, C=Shot, E=Grapple, F=Bomb
 */
class MapRenderer(private val romParser: RomParser) {
    
    companion object {
        const val BLOCK_SIZE = 16
        const val BLOCKS_PER_SCREEN = 16
    }
    
    /**
     * Classify block types into visual categories for rendering.
     */
    private fun isSolid(blockType: Int): Boolean = when (blockType) {
        0x1, 0x3, 0x8, 0xB, 0xC, 0xE, 0xF -> true  // Slope, treadmill, solid, crumble, shot, grapple, bomb
        else -> false
    }
    
    private fun isDoor(blockType: Int): Boolean = blockType == 0x9
    private fun isSpike(blockType: Int): Boolean = blockType == 0xA
    private fun isSlope(blockType: Int): Boolean = blockType == 0x1
    
    // Area-based terrain colors
    private val areaTerrainColors = mapOf(
        0 to intArrayOf(0xFF566878.toInt(), 0xFF4A5A6A.toInt()), // Crateria — cool gray-blue
        1 to intArrayOf(0xFF4A6848.toInt(), 0xFF3E5A3E.toInt()), // Brinstar — green
        2 to intArrayOf(0xFF785040.toInt(), 0xFF6A4438.toInt()), // Norfair — warm red-brown
        3 to intArrayOf(0xFF505868.toInt(), 0xFF444E5C.toInt()), // Wrecked Ship — dark blue-gray
        4 to intArrayOf(0xFF406888.toInt(), 0xFF385A78.toInt()), // Maridia — ocean blue
        5 to intArrayOf(0xFF706048.toInt(), 0xFF625440.toInt()), // Tourian — brown-gold
        6 to intArrayOf(0xFF686868.toInt(), 0xFF5A5A5A.toInt()), // Ceres — neutral gray
    )
    
    private val bgColor = 0xFF0C0C18.toInt()       // Deep dark background
    private val doorColor = 0xFF4090F0.toInt()      // Bright blue doors
    private val spikeColor = 0xFFD04040.toInt()     // Red spikes
    private val edgeColor = 0xFF1A1A28.toInt()      // Dark edge highlight
    private val gridColor = 0x30FFFFFF              // Subtle screen grid
    
    fun renderRoom(room: Room): RoomRenderData? {
        if (room.levelDataPtr == 0) return renderGrid(room)
        
        val levelData: ByteArray
        try {
            levelData = romParser.decompressLZ2(room.levelDataPtr)
        } catch (e: Exception) {
            return renderGrid(room)
        }
        
        if (levelData.size < 2) return renderGrid(room)
        
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val tileDataStart = 2
        val blocksWide = room.width * BLOCKS_PER_SCREEN
        val blocksTall = room.height * BLOCKS_PER_SCREEN
        val totalBlocks = blocksWide * blocksTall
        
        // Parse block types into a grid
        val blockTypes = IntArray(totalBlocks)
        for (i in 0 until totalBlocks) {
            val offset = tileDataStart + i * 2
            if (offset + 1 >= levelData.size) break
            val lo = levelData[offset].toInt() and 0xFF
            val hi = levelData[offset + 1].toInt() and 0xFF
            blockTypes[i] = ((hi shl 8) or lo shr 12) and 0x0F
        }
        
        // Get area colors
        val terrainColors = areaTerrainColors[room.area] ?: areaTerrainColors[0]!!
        val terrainColor = terrainColors[0]
        val terrainDark = terrainColors[1]
        
        // Render to pixels
        val pixelWidth = blocksWide * BLOCK_SIZE
        val pixelHeight = blocksTall * BLOCK_SIZE
        val pixels = IntArray(pixelWidth * pixelHeight)
        pixels.fill(bgColor)
        
        // Draw blocks
        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                val idx = by * blocksWide + bx
                val blockType = blockTypes[idx]
                
                val color = when {
                    isDoor(blockType) -> doorColor
                    isSpike(blockType) -> spikeColor
                    isSolid(blockType) -> {
                        // Add subtle depth: darker near edges of terrain regions
                        val hasAirAbove = by == 0 || !isSolid(blockTypes[(by - 1) * blocksWide + bx])
                        val hasAirLeft = bx == 0 || !isSolid(blockTypes[by * blocksWide + (bx - 1)])
                        if (hasAirAbove || hasAirLeft) terrainColor else terrainDark
                    }
                    else -> bgColor  // Air types
                }
                
                if (color != bgColor) {
                    fillBlock(pixels, pixelWidth, pixelHeight, bx * BLOCK_SIZE, by * BLOCK_SIZE, color)
                }
            }
        }
        
        // Draw terrain edges — dark outline where solid meets air
        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                val idx = by * blocksWide + bx
                if (!isSolid(blockTypes[idx]) && !isDoor(blockTypes[idx])) continue
                
                // Check each neighbor — draw edge pixel where terrain borders air
                val px = bx * BLOCK_SIZE
                val py = by * BLOCK_SIZE
                
                // Right edge
                if (bx + 1 < blocksWide && !isSolid(blockTypes[idx + 1]) && !isDoor(blockTypes[idx + 1])) {
                    for (y in 0 until BLOCK_SIZE) {
                        setPixel(pixels, pixelWidth, pixelHeight, px + BLOCK_SIZE - 1, py + y, edgeColor)
                    }
                }
                // Bottom edge
                if (by + 1 < blocksTall && !isSolid(blockTypes[(by + 1) * blocksWide + bx]) && !isDoor(blockTypes[(by + 1) * blocksWide + bx])) {
                    for (x in 0 until BLOCK_SIZE) {
                        setPixel(pixels, pixelWidth, pixelHeight, px + x, py + BLOCK_SIZE - 1, edgeColor)
                    }
                }
                // Left edge  
                if (bx > 0 && !isSolid(blockTypes[idx - 1]) && !isDoor(blockTypes[idx - 1])) {
                    for (y in 0 until BLOCK_SIZE) {
                        setPixel(pixels, pixelWidth, pixelHeight, px, py + y, edgeColor)
                    }
                }
                // Top edge
                if (by > 0 && !isSolid(blockTypes[(by - 1) * blocksWide + bx]) && !isDoor(blockTypes[(by - 1) * blocksWide + bx])) {
                    for (x in 0 until BLOCK_SIZE) {
                        setPixel(pixels, pixelWidth, pixelHeight, px + x, py, edgeColor)
                    }
                }
            }
        }
        
        // Draw screen grid
        drawScreenGrid(pixels, pixelWidth, pixelHeight, room.width, room.height)
        
        return RoomRenderData(pixelWidth, pixelHeight, pixels)
    }
    
    private fun fillBlock(pixels: IntArray, w: Int, h: Int, px: Int, py: Int, color: Int) {
        for (y in 0 until BLOCK_SIZE) {
            for (x in 0 until BLOCK_SIZE) {
                setPixel(pixels, w, h, px + x, py + y, color)
            }
        }
    }
    
    private fun setPixel(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x in 0 until w && y in 0 until h) {
            pixels[y * w + x] = color
        }
    }
    
    private fun drawScreenGrid(pixels: IntArray, w: Int, h: Int, screensW: Int, screensH: Int) {
        for (sy in 0..screensH) {
            val y = sy * BLOCKS_PER_SCREEN * BLOCK_SIZE
            if (y in 0 until h) {
                for (x in 0 until w) pixels[y * w + x] = blendColor(pixels[y * w + x], gridColor)
            }
        }
        for (sx in 0..screensW) {
            val x = sx * BLOCKS_PER_SCREEN * BLOCK_SIZE
            if (x in 0 until w) {
                for (y in 0 until h) pixels[y * w + x] = blendColor(pixels[y * w + x], gridColor)
            }
        }
    }
    
    private fun renderGrid(room: Room): RoomRenderData {
        val pw = room.width * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val ph = room.height * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val pixels = IntArray(pw * ph)
        val tc = areaTerrainColors[room.area]?.get(1) ?: 0xFF2A2A2A.toInt()
        pixels.fill(tc)
        drawScreenGrid(pixels, pw, ph, room.width, room.height)
        return RoomRenderData(pw, ph, pixels)
    }
    
    private fun blendColor(base: Int, overlay: Int): Int {
        val alpha = (overlay ushr 24) and 0xFF
        if (alpha == 0) return base
        val inv = 255 - alpha
        val r = (((overlay shr 16) and 0xFF) * alpha + ((base shr 16) and 0xFF) * inv) / 255
        val g = (((overlay shr 8) and 0xFF) * alpha + ((base shr 8) and 0xFF) * inv) / 255
        val b = ((overlay and 0xFF) * alpha + (base and 0xFF) * inv) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

data class RoomRenderData(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)
