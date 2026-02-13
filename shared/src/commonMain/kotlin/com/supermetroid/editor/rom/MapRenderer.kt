package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders Super Metroid room maps
 */
class MapRenderer(private val romParser: RomParser) {
    private val tileDecoder = TileDecoder()
    
    /**
     * Get level data for a room
     */
    fun getLevelData(room: Room): ByteArray? {
        // bgData is a pointer to the level data
        // It's stored as a 16-bit value, but we need to convert it to a full address
        // Level data pointers are relative to bank 0x8F
        val levelDataPointer = 0x8F0000 + room.bgData
        val pcOffset = romParser.snesToPc(levelDataPointer)
        
        val romData = romParser.getRomData()
        if (pcOffset < 0 || pcOffset >= romData.size) {
            return null
        }
        
        // Read compressed level data
        // We need to find where it ends (0xFF marker or calculate size)
        val compressedData = mutableListOf<Byte>()
        var offset = pcOffset
        var foundEnd = false
        
        while (offset < romData.size && !foundEnd) {
            val byte = romData[offset]
            compressedData.add(byte)
            
            if (byte.toInt() == 0xFF && compressedData.size > 1) {
                foundEnd = true
            }
            
            // Safety limit
            if (compressedData.size > 10000) break
            offset++
        }
        
        // Decompress
        return romParser.decompressLevelData(compressedData.toByteArray())
    }
    
    /**
     * Parse level data into tile map
     * Level data format: Each screen is 32x32 tiles (256x256 pixels)
     */
    fun parseLevelData(levelData: ByteArray, roomWidth: Int, roomHeight: Int): Array<IntArray> {
        val totalWidth = roomWidth * 32
        val totalHeight = roomHeight * 32
        val tileMap = Array(totalHeight) { IntArray(totalWidth) }
        
        val buffer = ByteBuffer.wrap(levelData)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Level data is stored screen by screen, left to right, top to bottom
        // Each screen is 32x32 tiles = 1024 tiles
        // Each tile index is 2 bytes (little endian)
        var dataIndex = 0
        
        for (screenY in 0 until roomHeight) {
            for (screenX in 0 until roomWidth) {
                // Read 32x32 tiles for this screen
                for (tileY in 0 until 32) {
                    for (tileX in 0 until 32) {
                        if (dataIndex + 1 < levelData.size) {
                            val tileIndex = buffer.getShort(dataIndex).toInt() and 0xFFFF
                            val mapY = screenY * 32 + tileY
                            val mapX = screenX * 32 + tileX
                            
                            if (mapY < totalHeight && mapX < totalWidth) {
                                tileMap[mapY][mapX] = tileIndex
                            }
                            
                            dataIndex += 2
                        } else {
                            return tileMap
                        }
                    }
                }
            }
        }
        
        return tileMap
    }
    
    /**
     * Get tileset graphics from ROM
     * Tilesets are stored at various locations in the ROM
     */
    fun getTilesetGraphics(tilesetId: Int): ByteArray? {
        // Tileset graphics locations (simplified - actual locations vary)
        // For now, we'll use a common tileset location
        // TODO: Implement proper tileset lookup based on tilesetId
        
        // Common tileset graphics are around 0x920000-0x940000
        val tilesetBase = 0x920000
        val romData = romParser.getRomData()
        val pcOffset = romParser.snesToPc(tilesetBase)
        
        if (pcOffset < 0 || pcOffset >= romData.size) {
            return null
        }
        
        // Read a reasonable amount of tileset data (e.g., 512 tiles = 16KB)
        val tilesetSize = 512 * 32 // 512 tiles * 32 bytes per tile
        val endOffset = (pcOffset + tilesetSize).coerceAtMost(romData.size)
        
        return romData.sliceArray(pcOffset until endOffset)
    }
    
    /**
     * Render room to pixel data
     */
    fun renderRoom(room: Room): RoomRenderData? {
        val levelData = getLevelData(room) ?: return null
        val tileMap = parseLevelData(levelData, room.width, room.height)
        val tilesetGraphics = getTilesetGraphics(room.tilesetId) ?: return null
        
        // Decode tileset
        val tiles = tileDecoder.decodeTileset(tilesetGraphics, 512)
        
        // Render to pixel array
        val width = room.width * 256
        val height = room.height * 256
        val pixels = IntArray(width * height)
        
        for (y in tileMap.indices) {
            for (x in tileMap[y].indices) {
                val tileIndex = tileMap[y][x]
                if (tileIndex < tiles.size) {
                    val tile = tiles[tileIndex]
                    
                    // Draw tile at position
                    val screenX = x * 8
                    val screenY = y * 8
                    
                    for (ty in 0 until 8) {
                        for (tx in 0 until 8) {
                            val px = screenX + tx
                            val py = screenY + ty
                            
                            if (px < width && py < height) {
                                val pixelIndex = py * width + px
                                val colorIndex = tile[ty][tx]
                                // Convert color index to ARGB (simplified palette)
                                pixels[pixelIndex] = colorIndexToArgb(colorIndex)
                            }
                        }
                    }
                }
            }
        }
        
        return RoomRenderData(width, height, pixels)
    }
    
    /**
     * Convert color index to ARGB (simplified - uses grayscale for now)
     * TODO: Load actual SNES palette from ROM
     */
    private fun colorIndexToArgb(colorIndex: Int): Int {
        // Simplified: map 0-15 to grayscale
        val gray = (colorIndex * 255 / 15)
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
    
}

/**
 * Render data for a room
 */
data class RoomRenderData(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)
