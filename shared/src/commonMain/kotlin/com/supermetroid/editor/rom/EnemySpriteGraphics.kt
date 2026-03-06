package com.supermetroid.editor.rom

/**
 * Handles boss/enemy sprite tile graphics for the sprite editor.
 *
 * Super Metroid enemy sprites are stored as LZ5-compressed 4bpp tile blocks in bank $B7.
 * Each 8x8 tile = 32 bytes (standard SNES 4bpp interleaved format):
 *   - Bytes  0-15: bitplanes 0+1 interleaved (2 bytes per row × 8 rows)
 *   - Bytes 16-31: bitplanes 2+3 interleaved (2 bytes per row × 8 rows)
 *
 * Tile block addresses confirmed via PhantoonSpriteInvestigateTest (room $CD13 enemy GFX set).
 */
class EnemySpriteGraphics(private val romParser: RomParser) {

    companion object {
        const val BYTES_PER_TILE = 32

        /**
         * One LZ5-compressed block of sprite tiles in the ROM.
         * @param pcAddress PC offset of compressed data
         * @param snesAddress SNES address (for decompressLZ2WithSize)
         * @param vramWordAddr SNES VRAM word destination (informational)
         * @param label Human-readable tag
         */
        data class SpriteBlock(
            val pcAddress: Int,
            val snesAddress: Int,
            val vramWordAddr: Int,
            val label: String
        )

        /**
         * Phantoon sprite tile blocks — verified via room $CD13 enemy GFX set analysis.
         * All 4 Phantoon species ($E4BF body, $E4FF/$E53F/$E57F components) share these.
         *
         * The 5-byte GFX entry format parsed from raw $8F:8D1D data:
         *   Entry 0: [00 03] [B7] [0F 17] → VRAM=0x0300, bank=$B7, src=0x170F → $B7:170F
         *   Entry at +30: [80 03] [B7] [08 18] → VRAM=0x0380, bank=$B7, src=0x1808 → $B7:1808
         */
        val PHANTOON_BLOCKS = listOf(
            SpriteBlock(0x1B970F, 0xB7170F, 0x0300, "Phantoon Tiles A"),
            SpriteBlock(0x1B9808, 0xB71808, 0x0380, "Phantoon Tiles B")
        )

        /**
         * Kraid sprite tile block — 128 tiles LZ-compressed in bank $B9.
         * Loaded by Kraid_SetupGfxWithTilePrioClear ($A7:AAC6) to $7E:4000.
         */
        val KRAID_BLOCKS = listOf(
            SpriteBlock(0x1CFA38, 0xB9FA38, 0x0100, "Kraid Tiles")
        )

        /**
         * Complete 16-color SNES palette for Phantoon sprites, derived from
         * all 4 component PNGs (E4BF, E4FF, E53F, E57F). Index 0 = transparent.
         * The index order matches the sorted-brightness order found across all PNGs.
         * This palette is used for tile sheet rendering and ARGB→4bpp conversion.
         */
        val PHANTOON_PALETTE = intArrayOf(
            0x00000000,             // 0: transparent
            0xff181800.toInt(),     // 1: very dark olive
            0xff303000.toInt(),     // 2: dark olive
            0xff404008.toInt(),     // 3: olive
            0xff484810.toInt(),     // 4: olive (from E4FF/E53F/E57F)
            0xff585820.toInt(),     // 5: medium olive
            0xff686830.toInt(),     // 6: olive-green (from E4FF/E53F/E57F)
            0xff808040.toInt(),     // 7: yellow-olive
            0xff909058.toInt(),     // 8: light olive (E4BF only)
            0xffa8a868.toInt(),     // 9: pale olive (from E4FF/E53F/E57F)
            0xffa8a870.toInt(),     // 10: pale olive (similar)
            0xffd8d888.toInt(),     // 11: light yellow
            0xfff8f8f8.toInt(),     // 12: white (from E4FF/E53F/E57F)
            0xff500000.toInt(),     // 13: dark red
            0xffa00030.toInt(),     // 14: red-pink
            0xffe80070.toInt()      // 15: bright pink
        )

        /**
         * Known tile block addresses for supported enemies.
         * Each entry: species ID → list of SpriteBlocks.
         */
        val ENEMY_TILE_BLOCKS = mapOf(
            0xE4BF to PHANTOON_BLOCKS,
            0xE2BF to KRAID_BLOCKS,
        )

        /**
         * Defined enemy entries that appear in the sprite editor.
         * @param speciesId Species pointer in bank $A0
         * @param name Display name
         * @param category "Boss" or "Enemy"
         */
        data class EnemySpriteEntry(
            val speciesId: Int,
            val name: String,
            val category: String = "Enemy"
        )

        val EDITOR_ENEMIES = listOf(
            EnemySpriteEntry(0xE4BF, "Phantoon", "Boss"),
            EnemySpriteEntry(0xE2BF, "Kraid", "Boss"),
            EnemySpriteEntry(0xDCFF, "Zoomer"),
            EnemySpriteEntry(0xDC7F, "Zeela"),
            EnemySpriteEntry(0xD93F, "Sidehopper"),
            EnemySpriteEntry(0xD7FF, "Skree"),
            EnemySpriteEntry(0xCFFF, "Cacatac"),
        )

        /**
         * Read a 16-color ARGB palette from a species header.
         * Palette address = $(aiBank):(palPtr + 0x20), where:
         *   - aiBank is at species header +$0C
         *   - palPtr is at species header +$02
         */
        fun readEnemyPalette(romParser: RomParser, speciesId: Int): IntArray? {
            val rom = romParser.getRomData()
            val headerPc = romParser.snesToPc(0xA00000 or speciesId)
            if (headerPc < 0 || headerPc + 0x0D > rom.size) return null
            val palPtr = (rom[headerPc + 2].toInt() and 0xFF) or
                ((rom[headerPc + 3].toInt() and 0xFF) shl 8)
            val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

            // Enemy palette blocks can have multiple 32-byte rows.
            // Row 1 (+0x20) is the standard sprite palette in many enemies,
            // but some have only 1 row. Detect by checking for valid BGR555:
            // valid BGR555 values have bit 15 clear (max 0x7FFF).
            val row1Snes = (aiBank shl 16) or ((palPtr + 0x20) and 0xFFFF)
            val row1Pc = romParser.snesToPc(row1Snes)
            val row1Valid = row1Pc >= 0 && row1Pc + 32 <= rom.size && run {
                (0 until 16).all { i ->
                    val w = (rom[row1Pc + i * 2].toInt() and 0xFF) or
                        ((rom[row1Pc + i * 2 + 1].toInt() and 0xFF) shl 8)
                    w <= 0x7FFF
                }
            }

            val palPc = if (row1Valid) {
                row1Pc
            } else {
                val row0Snes = (aiBank shl 16) or (palPtr and 0xFFFF)
                romParser.snesToPc(row0Snes)
            }
            if (palPc < 0 || palPc + 32 > rom.size) return null

            val pal = IntArray(16)
            pal[0] = 0x00000000
            for (i in 1 until 16) {
                val bgr = (rom[palPc + i * 2].toInt() and 0xFF) or
                    ((rom[palPc + i * 2 + 1].toInt() and 0xFF) shl 8)
                pal[i] = snesColorToArgb(bgr)
            }
            return pal
        }

        /**
         * Read species header stats.
         * @return Triple(tileDataSize, hp, damage) or null
         */
        fun readSpeciesStats(romParser: RomParser, speciesId: Int): Triple<Int, Int, Int>? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(0xA00000 or speciesId)
            if (pc < 0 || pc + 8 > rom.size) return null
            val tileSize = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
            val hp = (rom[pc + 4].toInt() and 0xFF) or ((rom[pc + 5].toInt() and 0xFF) shl 8)
            val damage = (rom[pc + 6].toInt() and 0xFF) or ((rom[pc + 7].toInt() and 0xFF) shl 8)
            return Triple(tileSize, hp, damage)
        }

        /**
         * Read the GRAPHADR (graphics address) from a species header.
         * Located at species header +$36 (16-bit LE offset) and +$38 (bank byte).
         * This points to LZ5-compressed tile data. The game decompresses the full
         * block but only loads the first `tileDataSize` bytes for this species.
         *
         * @return SpriteBlock with pcAddress, snesAddress, and tileDataSize, or null
         */
        fun readGraphicsBlock(romParser: RomParser, speciesId: Int): SpriteBlock? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(0xA00000 or speciesId)
            if (pc < 0 || pc + 0x39 > rom.size) return null
            val gfxOffset = (rom[pc + 0x36].toInt() and 0xFF) or
                ((rom[pc + 0x37].toInt() and 0xFF) shl 8)
            val gfxBank = rom[pc + 0x38].toInt() and 0xFF
            if (gfxBank == 0 && gfxOffset == 0) return null
            val snesAddr = (gfxBank shl 16) or gfxOffset
            val pcAddr = romParser.snesToPc(snesAddr)
            if (pcAddr < 0 || pcAddr >= rom.size) return null
            return SpriteBlock(pcAddr, snesAddr, 0, "Tiles")
        }

        /**
         * Load and render enemy tile data directly from ROM using GRAPHADR.
         * Decompresses the full block, then truncates to tileDataSize.
         * @return raw 4bpp tile bytes (tileDataSize bytes) or null
         */
        fun loadEnemyTileData(romParser: RomParser, speciesId: Int): ByteArray? {
            val stats = readSpeciesStats(romParser, speciesId) ?: return null
            val tileDataSize = stats.first
            if (tileDataSize <= 0) return null
            val block = readGraphicsBlock(romParser, speciesId) ?: return null
            return try {
                val fullData = romParser.decompressLZ5AtPc(block.pcAddress)
                if (fullData.size >= tileDataSize) {
                    fullData.copyOf(tileDataSize)
                } else {
                    fullData
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Extract a ≤16-color palette from an ARGB pixel array (index 0 = transparent). */
        fun extractPaletteFromArgb(pixels: IntArray): IntArray {
            val palette = IntArray(16)
            palette[0] = 0x00000000
            var idx = 1
            for (argb in pixels) {
                if ((argb ushr 24) and 0xFF < 128) continue
                val opaque = argb or (0xFF shl 24)
                if (opaque !in palette && idx < 16) {
                    palette[idx++] = opaque
                }
                if (idx >= 16) break
            }
            return palette
        }

        /** SNES BGR555 → ARGB */
        fun snesColorToArgb(bgr555: Int): Int {
            val r = ((bgr555 and 0x001F) * 255 + 15) / 31
            val g = (((bgr555 shr 5) and 0x001F) * 255 + 15) / 31
            val b = (((bgr555 shr 10) and 0x001F) * 255 + 15) / 31
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** ARGB → SNES BGR555 */
        fun argbToSnesColor(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return ((r * 31 + 127) / 255) or
                (((g * 31 + 127) / 255) shl 5) or
                (((b * 31 + 127) / 255) shl 10)
        }
    }

    /** Mutable working tile data per block. null = not loaded. */
    private var rawBlocks: MutableList<ByteArray>? = null

    /** Load and LZ5-decompress tile blocks from the ROM. Returns false on failure. */
    fun load(blocks: List<SpriteBlock>): Boolean {
        return try {
            rawBlocks = blocks.map { block ->
                romParser.decompressLZ5AtPc(block.pcAddress)
            }.toMutableList()
            true
        } catch (e: Exception) {
            rawBlocks = null
            false
        }
    }

    /**
     * Load from already-decompressed raw 4bpp byte arrays (e.g. stored in project).
     * Each element of rawBlocks corresponds to one SpriteBlock.
     */
    fun loadFromRaw(blocks: List<ByteArray>) {
        rawBlocks = blocks.map { it.copyOf() }.toMutableList()
    }

    /**
     * Load blocks from ROM, then override individual blocks with custom bytes
     * where they exist in the provided custom map (key = block index).
     */
    fun loadWithOverrides(romBlocks: List<SpriteBlock>, customRaw: Map<Int, ByteArray>): Boolean {
        if (!load(romBlocks)) return false
        val blocks = rawBlocks ?: return false
        for ((i, raw) in customRaw) {
            if (i < blocks.size) blocks[i] = raw.copyOf()
        }
        return true
    }

    /** Total number of 8x8 tiles across all loaded blocks. */
    fun getTileCount(): Int =
        rawBlocks?.sumOf { it.size / BYTES_PER_TILE } ?: 0

    /** Number of tiles in block [blockIndex]. */
    fun getTileCountInBlock(blockIndex: Int): Int =
        rawBlocks?.getOrNull(blockIndex)?.let { it.size / BYTES_PER_TILE } ?: 0

    private fun resolveGlobalTile(globalTile: Int): Triple<Int, ByteArray, Int>? {
        val blocks = rawBlocks ?: return null
        var remaining = globalTile
        for ((bi, block) in blocks.withIndex()) {
            val count = block.size / BYTES_PER_TILE
            if (remaining < count) return Triple(bi, block, remaining)
            remaining -= count
        }
        return null
    }

    /** Read pixel palette index (0–15) from a global tile number. */
    fun readPixelIndex(globalTile: Int, px: Int, py: Int): Int {
        val (_, block, localTile) = resolveGlobalTile(globalTile) ?: return 0
        val offset = localTile * BYTES_PER_TILE
        if (offset + BYTES_PER_TILE > block.size) return 0
        val bit = 7 - px
        val bp0 = (block[offset + py * 2].toInt() shr bit) and 1
        val bp1 = (block[offset + py * 2 + 1].toInt() shr bit) and 1
        val bp2 = (block[offset + py * 2 + 16].toInt() shr bit) and 1
        val bp3 = (block[offset + py * 2 + 17].toInt() shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    /** Write palette index (0–15) into the working tile data. */
    fun writePixelIndex(globalTile: Int, px: Int, py: Int, colorIdx: Int) {
        val (_, block, localTile) = resolveGlobalTile(globalTile) ?: return
        val offset = localTile * BYTES_PER_TILE
        if (offset + BYTES_PER_TILE > block.size) return
        val bit = 7 - px
        fun setBit(byteOffset: Int, v: Int) {
            val cur = block[offset + byteOffset].toInt() and 0xFF
            block[offset + byteOffset] = if (v != 0) (cur or (1 shl bit)).toByte()
                                         else (cur and (1 shl bit).inv()).toByte()
        }
        setBit(py * 2,       colorIdx and 1)
        setBit(py * 2 + 1,  (colorIdx shr 1) and 1)
        setBit(py * 2 + 16, (colorIdx shr 2) and 1)
        setBit(py * 2 + 17, (colorIdx shr 3) and 1)
    }

    /**
     * Render all loaded tiles as an ARGB pixel grid arranged in [cols] columns.
     * Palette index 0 = transparent. Returns (pixels, width, height) or null if not loaded.
     */
    fun renderSheet(palette: IntArray, cols: Int = 8): Triple<IntArray, Int, Int>? {
        val blocks = rawBlocks ?: return null
        val total = getTileCount()
        if (total == 0) return null
        val rows = (total + cols - 1) / cols
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)

        var globalTile = 0
        for (block in blocks) {
            val blockTileCount = block.size / BYTES_PER_TILE
            for (t in 0 until blockTileCount) {
                val tileOffset = t * BYTES_PER_TILE
                val col = globalTile % cols
                val row = globalTile / cols
                val baseX = col * 8
                val baseY = row * 8
                for (py in 0 until 8) {
                    val bp0 = block[tileOffset + py * 2].toInt() and 0xFF
                    val bp1 = block[tileOffset + py * 2 + 1].toInt() and 0xFF
                    val bp2 = block[tileOffset + py * 2 + 16].toInt() and 0xFF
                    val bp3 = block[tileOffset + py * 2 + 17].toInt() and 0xFF
                    for (px in 0 until 8) {
                        val bit = 7 - px
                        val ci = ((bp0 shr bit) and 1) or
                            (((bp1 shr bit) and 1) shl 1) or
                            (((bp2 shr bit) and 1) shl 2) or
                            (((bp3 shr bit) and 1) shl 3)
                        pixels[(baseY + py) * w + (baseX + px)] =
                            if (ci == 0) 0x00000000 else (palette[ci.coerceIn(0, palette.size - 1)] or (0xFF shl 24))
                    }
                }
                globalTile++
            }
        }
        return Triple(pixels, w, h)
    }

    /** Return a copy of each raw block's 4bpp byte data. */
    fun getRawBlocks(): List<ByteArray>? = rawBlocks?.map { it.copyOf() }

    /**
     * Re-encode an ARGB pixel grid back into the loaded tile blocks using nearest-color
     * palette matching. Call [load] or [loadFromRaw] before this.
     */
    fun importFromArgb(pixels: IntArray, w: Int, h: Int, palette: IntArray, cols: Int = 8) {
        val blocks = rawBlocks ?: return
        var globalTile = 0
        for (block in blocks) {
            val blockTileCount = block.size / BYTES_PER_TILE
            for (t in 0 until blockTileCount) {
                val col = globalTile % cols
                val row = globalTile / cols
                val baseX = col * 8
                val baseY = row * 8
                if (baseX + 8 > w || baseY + 8 > h) { globalTile++; continue }
                val tileOffset = t * BYTES_PER_TILE
                for (py in 0 until 8) {
                    var bp0 = 0; var bp1 = 0; var bp2 = 0; var bp3 = 0
                    for (px in 0 until 8) {
                        val argb = pixels[(baseY + py) * w + (baseX + px)]
                        val alpha = (argb ushr 24) and 0xFF
                        val ci = if (alpha < 128) 0 else findNearestPaletteIndex(argb, palette)
                        val bit = 7 - px
                        if (ci and 1 != 0) bp0 = bp0 or (1 shl bit)
                        if (ci and 2 != 0) bp1 = bp1 or (1 shl bit)
                        if (ci and 4 != 0) bp2 = bp2 or (1 shl bit)
                        if (ci and 8 != 0) bp3 = bp3 or (1 shl bit)
                    }
                    block[tileOffset + py * 2] = bp0.toByte()
                    block[tileOffset + py * 2 + 1] = bp1.toByte()
                    block[tileOffset + py * 2 + 16] = bp2.toByte()
                    block[tileOffset + py * 2 + 17] = bp3.toByte()
                }
                globalTile++
            }
        }
    }

    private fun findNearestPaletteIndex(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) { bestDist = dist; best = i }
            if (dist == 0) break
        }
        return best
    }
}
