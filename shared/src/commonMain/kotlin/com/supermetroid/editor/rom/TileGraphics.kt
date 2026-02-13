package com.supermetroid.editor.rom

/**
 * Loads and decodes Super Metroid tile graphics from ROM.
 * 
 * Pipeline (from SMILE source and Metroid Construction wiki):
 *   1. Tileset pointer table at $8F:E6A2 — 29 entries × 9 bytes each
 *      [tile_table_ptr(3)] [gfx_ptr(3)] [palette_ptr(3)]
 *   2. Decompress tile table → 1024 metatile entries × 8 bytes
 *      Each metatile = 4 SNES BG tilemap words (TL, TR, BL, BR)
 *   3. Decompress 8x8 tile graphics (4bpp planar, 32 bytes/tile)
 *      Variable tiles: indices 0-639, CRE tiles: indices 640-1023
 *   4. Load palette: 8 sub-palettes × 16 colors (BGR555)
 *   5. Render: level data tile index → metatile → 4 sub-tiles → pixels
 *
 * CRE (Common Room Elements) are always loaded from fixed addresses:
 *   GFX:        $B9:8000 (PC: $1C8000)
 *   Tile table: $B9:A09D (PC: $1CA09D)
 *
 * Sources:
 *   - SMILE legacy: DecompressTiles.vb, DecompressTtable.vb, DrawTiles
 *   - aremath/sm_rando: leveldata_utils.py
 *   - SNESLab wiki: https://sneslab.net/wiki/LZ5
 *   - Metroid Construction: room_data_format
 */
class TileGraphics(private val romParser: RomParser) {
    
    companion object {
        // Tileset pointer table: SNES $8F:E6A2, 29 entries × 9 bytes
        const val TILESET_TABLE_SNES = 0x8FE6A2
        const val NUM_TILESETS = 29
        
        // CRE (Common Room Elements) fixed addresses
        const val CRE_GFX_SNES = 0xB98000
        const val CRE_TILE_TABLE_SNES = 0xB9A09D
        
        // Tile counts
        const val VARIABLE_TILE_COUNT = 640   // Tiles 0-639
        const val CRE_TILE_START = 640        // CRE tiles start at index 640
        const val TOTAL_TILES = 1024          // 0-1023
        const val METATILE_COUNT = 1024       // Number of metatile definitions
        
        // SNES 4bpp tile: 32 bytes per 8x8 tile
        const val BYTES_PER_TILE = 32
    }
    
    // Cached data per tileset
    private var cachedTilesetId: Int = -1
    private var tilePixels: Array<IntArray>? = null   // tile[tileIdx] = 64 ARGB pixels (8x8)
    private var metatiles: Array<IntArray>? = null     // metatile[idx] = 4 sub-tile words
    
    /**
     * Load a complete tileset (graphics + tile table + palette).
     * Returns true if successful.
     */
    fun loadTileset(tilesetId: Int): Boolean {
        if (tilesetId == cachedTilesetId && tilePixels != null) return true
        
        val romData = romParser.getRomData()
        
        // Read tileset pointer table entry
        val tablePC = romParser.snesToPc(TILESET_TABLE_SNES)
        val entryOffset = tablePC + tilesetId * 9
        
        if (entryOffset + 9 > romData.size) return false
        
        val tileTablePtr = readUInt24(romData, entryOffset)
        val gfxPtr = readUInt24(romData, entryOffset + 3)
        val palettePtr = readUInt24(romData, entryOffset + 6)
        
        println("Tileset $tilesetId: tileTable=0x${tileTablePtr.toString(16)}, gfx=0x${gfxPtr.toString(16)}, palette=0x${palettePtr.toString(16)}")
        
        // Decompress variable tile table and CRE tile table
        val varTileTable = romParser.decompressLZ2(tileTablePtr)
        val creTileTable = romParser.decompressLZ2(CRE_TILE_TABLE_SNES)
        
        // Decompress variable tile graphics and CRE graphics
        val varGfx = romParser.decompressLZ2(gfxPtr)
        val creGfx = romParser.decompressLZ2(CRE_GFX_SNES)
        
        println("  varTileTable: ${varTileTable.size} bytes, creTileTable: ${creTileTable.size} bytes")
        println("  varGfx: ${varGfx.size} bytes, creGfx: ${creGfx.size} bytes")
        
        // Load palette
        val palettePC = romParser.snesToPc(palettePtr)
        val palette = loadPalette(romData, palettePC)
        
        // Combine tile tables: variable first, then CRE
        // Each metatile entry = 8 bytes (4 × 16-bit SNES tilemap words)
        metatiles = parseTileTable(varTileTable, creTileTable)
        
        // Combine tile graphics: variable (0-639) then CRE (640-1023)
        // Decode 4bpp tiles to ARGB pixels using palette
        tilePixels = decodeTileGraphics(varGfx, creGfx, palette)
        
        cachedTilesetId = tilesetId
        return true
    }
    
    /**
     * Render a 16x16 metatile to an ARGB pixel array.
     * Returns 256 pixels (16x16) or null if index is invalid.
     */
    fun renderMetatile(metatileIndex: Int): IntArray? {
        val metas = metatiles ?: return null
        val tiles = tilePixels ?: return null
        
        if (metatileIndex < 0 || metatileIndex >= metas.size) return null
        
        val meta = metas[metatileIndex]
        val pixels = IntArray(16 * 16)
        
        // 4 sub-tiles: TL, TR, BL, BR
        for (quadrant in 0..3) {
            val word = meta[quadrant]
            val tileNum = word and 0x03FF
            val paletteIdx = (word shr 10) and 7
            val hFlip = (word shr 14) and 1
            val vFlip = (word shr 15) and 1
            
            if (tileNum >= tiles.size) continue
            val srcPixels = tiles[tileNum]
            
            val baseX = if (quadrant % 2 == 0) 0 else 8
            val baseY = if (quadrant < 2) 0 else 8
            
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (hFlip != 0) 7 - px else px
                    val sy = if (vFlip != 0) 7 - py else py
                    val srcIdx = sy * 8 + sx
                    val dstIdx = (baseY + py) * 16 + (baseX + px)
                    pixels[dstIdx] = srcPixels[srcIdx]
                }
            }
        }
        
        return pixels
    }
    
    // ─── Internal helpers ──────────────────────────────────────────────
    
    /**
     * Parse metatile table from decompressed tile table data.
     * Variable tile table entries come first, CRE entries fill the rest.
     * Each entry = 8 bytes = 4 × 16-bit LE words (TL, TR, BL, BR).
     */
    private fun parseTileTable(varTable: ByteArray, creTable: ByteArray): Array<IntArray> {
        val result = Array(METATILE_COUNT) { IntArray(4) }
        
        // Variable metatiles from decompressed var tile table
        val varCount = minOf(varTable.size / 8, METATILE_COUNT)
        for (i in 0 until varCount) {
            val offset = i * 8
            for (q in 0..3) {
                val lo = varTable[offset + q * 2].toInt() and 0xFF
                val hi = varTable[offset + q * 2 + 1].toInt() and 0xFF
                result[i][q] = (hi shl 8) or lo
            }
        }
        
        // CRE metatiles fill from the CRE table (combined after variable)
        // In SMILE: CRE tile table is placed after variable tile table
        val creMetaCount = minOf(creTable.size / 8, METATILE_COUNT)
        val creStartIdx = varCount
        for (i in 0 until creMetaCount) {
            val destIdx = creStartIdx + i
            if (destIdx >= METATILE_COUNT) break
            val offset = i * 8
            for (q in 0..3) {
                val lo = creTable[offset + q * 2].toInt() and 0xFF
                val hi = creTable[offset + q * 2 + 1].toInt() and 0xFF
                result[destIdx][q] = (hi shl 8) or lo
            }
        }
        
        return result
    }
    
    /**
     * Decode 4bpp SNES tile graphics to ARGB pixel arrays.
     * Variable graphics fill tiles 0-639, CRE fills 640-1023.
     */
    private fun decodeTileGraphics(
        varGfx: ByteArray, creGfx: ByteArray, palette: Array<IntArray>
    ): Array<IntArray> {
        val result = Array(TOTAL_TILES) { IntArray(64) } // 64 pixels per 8x8 tile
        
        // Decode variable tiles (0-639)
        val varTileCount = minOf(varGfx.size / BYTES_PER_TILE, VARIABLE_TILE_COUNT)
        for (i in 0 until varTileCount) {
            result[i] = decode4bppTile(varGfx, i * BYTES_PER_TILE, palette, 0)
        }
        
        // Decode CRE tiles (640-1023)
        val creTileCount = minOf(creGfx.size / BYTES_PER_TILE, TOTAL_TILES - CRE_TILE_START)
        for (i in 0 until creTileCount) {
            result[CRE_TILE_START + i] = decode4bppTile(creGfx, i * BYTES_PER_TILE, palette, 0)
        }
        
        return result
    }
    
    /**
     * Decode a single 8x8 SNES 4bpp planar tile to 64 ARGB pixels.
     * 
     * SNES 4bpp format (32 bytes per tile):
     *   Bytes 0-15: Bitplanes 0 & 1 interleaved (row 0: bytes 0,1; row 1: bytes 2,3; ...)
     *   Bytes 16-31: Bitplanes 2 & 3 interleaved (row 0: bytes 16,17; row 1: bytes 18,19; ...)
     * 
     * For pixel at (x, y): color index bits come from:
     *   bit 0: byte[y*2]     bit (7-x)
     *   bit 1: byte[y*2+1]   bit (7-x)
     *   bit 2: byte[y*2+16]  bit (7-x)
     *   bit 3: byte[y*2+17]  bit (7-x)
     */
    private fun decode4bppTile(
        data: ByteArray, offset: Int, palette: Array<IntArray>, paletteIdx: Int
    ): IntArray {
        val pixels = IntArray(64)
        
        if (offset + 32 > data.size) return pixels
        
        for (y in 0 until 8) {
            val bp0 = data[offset + y * 2].toInt() and 0xFF
            val bp1 = data[offset + y * 2 + 1].toInt() and 0xFF
            val bp2 = data[offset + y * 2 + 16].toInt() and 0xFF
            val bp3 = data[offset + y * 2 + 17].toInt() and 0xFF
            
            for (x in 0 until 8) {
                val bit = 7 - x
                val colorIdx = ((bp0 shr bit) and 1) or
                    (((bp1 shr bit) and 1) shl 1) or
                    (((bp2 shr bit) and 1) shl 2) or
                    (((bp3 shr bit) and 1) shl 3)
                
                // Color index 0 = transparent
                if (colorIdx == 0) {
                    pixels[y * 8 + x] = 0x00000000 // Transparent
                } else {
                    val palIdx = minOf(paletteIdx, palette.size - 1)
                    pixels[y * 8 + x] = palette[palIdx][colorIdx]
                }
            }
        }
        
        return pixels
    }
    
    /**
     * Load 8 sub-palettes × 16 colors from ROM.
     * Each color is BGR555: 0BBBBBGGGGGRRRRR (16-bit LE).
     * Returns array of 8 palettes, each with 16 ARGB colors.
     */
    private fun loadPalette(romData: ByteArray, palettePC: Int): Array<IntArray> {
        val result = Array(8) { IntArray(16) }
        
        for (pal in 0 until 8) {
            for (col in 0 until 16) {
                val offset = palettePC + (pal * 16 + col) * 2
                if (offset + 1 < romData.size) {
                    val lo = romData[offset].toInt() and 0xFF
                    val hi = romData[offset + 1].toInt() and 0xFF
                    val bgr555 = (hi shl 8) or lo
                    result[pal][col] = bgr555ToArgb(bgr555)
                }
            }
        }
        
        return result
    }
    
    /**
     * Convert BGR555 to ARGB8888.
     * BGR555: 0BBBBBGG GGGRRRRR
     */
    private fun bgr555ToArgb(bgr555: Int): Int {
        val r5 = bgr555 and 0x1F
        val g5 = (bgr555 shr 5) and 0x1F
        val b5 = (bgr555 shr 10) and 0x1F
        // Scale 5-bit to 8-bit: (v << 3) | (v >> 2)
        val r = (r5 shl 3) or (r5 shr 2)
        val g = (g5 shl 3) or (g5 shr 2)
        val b = (b5 shl 3) or (b5 shr 2)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    
    private fun readUInt24(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    }
}
