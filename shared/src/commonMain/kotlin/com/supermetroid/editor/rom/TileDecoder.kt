package com.supermetroid.editor.rom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes SNES tile graphics (4bpp format)
 * Super Metroid uses 8x8 tiles with 4 bits per pixel (16 colors)
 */
class TileDecoder {
    /**
     * Decode a single 8x8 tile from 4bpp format
     * Each tile is 32 bytes (8x8 pixels * 4 bits per pixel / 8 bits per byte)
     */
    fun decodeTile(tileData: ByteArray, offset: Int = 0): Array<IntArray> {
        val tile = Array(8) { IntArray(8) }
        val buffer = ByteBuffer.wrap(tileData, offset, 32)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // SNES 4bpp format: 4 bitplanes interleaved
        // Each row is 2 bytes per bitplane = 8 bytes total per row
        for (y in 0 until 8) {
            val rowOffset = y * 4 // 4 bytes per row (2 bytes per bitplane)
            
            // Read bitplanes
            val bp0Low = buffer.get(rowOffset).toInt() and 0xFF
            val bp0High = buffer.get(rowOffset + 1).toInt() and 0xFF
            val bp1Low = buffer.get(rowOffset + 16).toInt() and 0xFF
            val bp1High = buffer.get(rowOffset + 17).toInt() and 0xFF
            
            // Decode pixels (combine bitplanes)
            for (x in 0 until 8) {
                val bit = 7 - x
                val pixel = ((bp0High shr bit) and 0x01) shl 3 or
                           ((bp0Low shr bit) and 0x01) shl 2 or
                           ((bp1High shr bit) and 0x01) shl 1 or
                           ((bp1Low shr bit) and 0x01)
                tile[y][x] = pixel
            }
        }
        
        return tile
    }
    
    /**
     * Decode multiple tiles from a tileset
     */
    fun decodeTileset(tilesetData: ByteArray, numTiles: Int): List<Array<IntArray>> {
        val tiles = mutableListOf<Array<IntArray>>()
        for (i in 0 until numTiles) {
            val offset = i * 32
            if (offset + 32 <= tilesetData.size) {
                tiles.add(decodeTile(tilesetData, offset))
            }
        }
        return tiles
    }
}
