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
    private var rawTileData: ByteArray? = null          // Combined 4bpp tile graphics (var + CRE)
    private var metatiles: Array<IntArray>? = null       // metatile[idx] = 4 sub-tile words
    private var cachedPalette: Array<IntArray>? = null   // 8 palettes × 16 ARGB colors
    
    /**
     * Load a complete tileset (graphics + tile table + palette).
     * Returns true if successful.
     */
    fun loadTileset(tilesetId: Int): Boolean {
        if (tilesetId == cachedTilesetId && rawTileData != null) return true
        
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
        cachedPalette = loadPalette(romData, palettePC)
        
        // Combine tile tables: CRE first, then variable (from SMILE source)
        metatiles = parseTileTable(varTileTable, creTileTable)
        
        // Combine tile graphics: variable at offset 0, CRE at offset 0x5000
        // From SMILE DecompressTiles.vb:
        //   CombineArrays VarTiles, CRETiles, SizeOfVarTiles, SizeOfCRETiles, &H0, &H5000, OutputArray
        val combinedGfxSize = 0x5000 + creGfx.size
        val combinedGfx = ByteArray(maxOf(combinedGfxSize, TOTAL_TILES * BYTES_PER_TILE))
        System.arraycopy(varGfx, 0, combinedGfx, 0, minOf(varGfx.size, 0x5000))
        System.arraycopy(creGfx, 0, combinedGfx, 0x5000, minOf(creGfx.size, combinedGfx.size - 0x5000))
        rawTileData = combinedGfx
        
        cachedTilesetId = tilesetId
        return true
    }
    
    /**
     * Render a 16x16 metatile to an ARGB pixel array.
     * Returns 256 pixels (16x16) or null if index is invalid.
     */
    /**
     * Render a 16x16 metatile to an ARGB pixel array.
     * Each metatile is 4 sub-tiles arranged: TL(0), TR(1), BL(2), BR(3).
     * 
     * SNES BG tilemap word format (per sub-tile):
     *   VH0PPPTTTTTTTTTT
     *   V = vertical flip (bit 15)
     *   H = horizontal flip (bit 14)
     *   0 = priority (bit 13, ignored for our purposes)
     *   PPP = palette row (bits 10-12)
     *   TTTTTTTTTT = tile number (bits 0-9)
     */
    fun renderMetatile(metatileIndex: Int): IntArray? {
        val metas = metatiles ?: return null
        val pal = cachedPalette ?: return null
        if (rawTileData == null) return null
        
        if (metatileIndex < 0 || metatileIndex >= metas.size) return null
        
        val meta = metas[metatileIndex]
        val pixels = IntArray(16 * 16)
        
        // 4 sub-tiles: TL(0), TR(1), BL(2), BR(3)
        for (quadrant in 0..3) {
            val word = meta[quadrant]
            val tileNum = word and 0x03FF
            val paletteIdx = (word shr 10) and 7
            val hFlip = (word shr 14) and 1
            val vFlip = (word shr 15) and 1
            
            if (tileNum >= TOTAL_TILES) continue
            
            val baseX = if (quadrant % 2 == 0) 0 else 8
            val baseY = if (quadrant < 2) 0 else 8
            
            // Decode this 8x8 tile with its specific palette
            val subTilePixels = decode4bppTileWithPalette(tileNum, pal, paletteIdx)
            
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (hFlip != 0) 7 - px else px
                    val sy = if (vFlip != 0) 7 - py else py
                    val srcIdx = sy * 8 + sx
                    val dstIdx = (baseY + py) * 16 + (baseX + px)
                    pixels[dstIdx] = subTilePixels[srcIdx]
                }
            }
        }
        
        return pixels
    }
    
    // ─── Internal helpers ──────────────────────────────────────────────
    
    /**
     * Parse metatile table from decompressed tile table data.
     * 
     * From SMILE DecompressTtable.vb:
     *   CombineArrays CRETtable, VarTtable, SizeOfCRETtable, SizeOfVarTtable, &H0, SizeOfCRETtable, OutputArray
     * 
     * This means: CRE tile table FIRST (at offset 0), then Variable tile table AFTER.
     * Each entry = 8 bytes = 4 × 16-bit LE words (TL, TR, BL, BR).
     */
    private fun parseTileTable(varTable: ByteArray, creTable: ByteArray): Array<IntArray> {
        // Combine: CRE first, then Variable
        val creSize = creTable.size
        val combined = ByteArray(creSize + varTable.size)
        System.arraycopy(creTable, 0, combined, 0, creSize)
        System.arraycopy(varTable, 0, combined, creSize, varTable.size)
        
        val result = Array(METATILE_COUNT) { IntArray(4) }
        val entryCount = minOf(combined.size / 8, METATILE_COUNT)
        
        for (i in 0 until entryCount) {
            val offset = i * 8
            for (q in 0..3) {
                val lo = combined[offset + q * 2].toInt() and 0xFF
                val hi = combined[offset + q * 2 + 1].toInt() and 0xFF
                result[i][q] = (hi shl 8) or lo
            }
        }
        
        return result
    }
    
    /**
     * Decode a single 8x8 SNES 4bpp planar tile with a specific palette.
     * 
     * SNES 4bpp format (32 bytes per tile):
     *   Bytes 0-15: Bitplanes 0 & 1 interleaved (row 0: bytes 0,1; row 1: bytes 2,3; ...)
     *   Bytes 16-31: Bitplanes 2 & 3 interleaved (row 0: bytes 16,17; row 1: bytes 18,19; ...)
     */
    private fun decode4bppTileWithPalette(tileNum: Int, palette: Array<IntArray>, paletteIdx: Int): IntArray {
        val pixels = IntArray(64)
        val data = rawTileData ?: return pixels
        val offset = tileNum * BYTES_PER_TILE
        
        if (offset + 32 > data.size) return pixels
        
        val palIdx = minOf(paletteIdx, palette.size - 1)
        
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
                    pixels[y * 8 + x] = 0x00000000
                } else {
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
