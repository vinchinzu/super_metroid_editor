package com.supermetroid.editor.rom

/**
 * Super Metroid SPC700 music data parser.
 *
 * Music in SM is stored as transfer blocks that get DMA'd to the SPC700's 64KB RAM.
 * Each "song set" loads a different instrument/sample set. The "play index" selects
 * the sequence to play within that set.
 *
 * ROM banks $CF (setup) and $D0-$DF contain all music data.
 * Transfer block format: [size:u16] [spcDest:u16] [data:size bytes]...  [0x0000 = end]
 */
object SpcData {

    // ─── Known SM tracks (song set, play index) → name ──────────────

    data class TrackInfo(
        val songSet: Int,
        val playIndex: Int,
        val name: String,
        val area: String = ""
    ) {
        val id get() = (songSet shl 8) or playIndex
    }

    val KNOWN_TRACKS = listOf(
        TrackInfo(0x03, 0x05, "Title Screen", "Menu"),
        TrackInfo(0x03, 0x06, "Title Screen (After Button)", "Menu"),
        TrackInfo(0x36, 0x05, "Intro Story", "Intro"),
        TrackInfo(0x2D, 0x05, "Flying to Ceres / Zebes", "Intro"),
        TrackInfo(0x2D, 0x06, "Ceres Station", "Ceres"),
        TrackInfo(0x06, 0x05, "Empty Crateria (Rain + Thunder)", "Crateria"),
        TrackInfo(0x06, 0x06, "Empty Crateria (Rain Only)", "Crateria"),
        TrackInfo(0x06, 0x07, "Empty Crateria (Silent)", "Crateria"),
        TrackInfo(0x0C, 0x05, "Crateria Surface", "Crateria"),
        TrackInfo(0x09, 0x05, "Space Pirates", "Crateria"),
        TrackInfo(0x09, 0x06, "Golden Statues Room", "Crateria"),
        TrackInfo(0x0F, 0x05, "Green Brinstar", "Brinstar"),
        TrackInfo(0x12, 0x05, "Red Brinstar / Kraid's Lair", "Brinstar"),
        TrackInfo(0x15, 0x05, "Upper Norfair", "Norfair"),
        TrackInfo(0x18, 0x05, "Lower Norfair", "Norfair"),
        TrackInfo(0x30, 0x05, "Wrecked Ship (Boss Alive)", "Wrecked Ship"),
        TrackInfo(0x30, 0x06, "Wrecked Ship (Boss Dead)", "Wrecked Ship"),
        TrackInfo(0x1B, 0x05, "Eastern Maridia", "Maridia"),
        TrackInfo(0x1B, 0x06, "Western Maridia", "Maridia"),
        TrackInfo(0x1E, 0x05, "Tourian", "Tourian"),
        TrackInfo(0x2A, 0x05, "Mini-Boss Fight", "Boss"),
        TrackInfo(0x27, 0x05, "Boss Fight (Roaring)", "Boss"),
        TrackInfo(0x27, 0x06, "Pre-Boss Tension (Roaring)", "Boss"),
        TrackInfo(0x45, 0x05, "Boss Fight (Metroid Sounds)", "Boss"),
        TrackInfo(0x45, 0x06, "Pre-Boss Tension (Metroid)", "Boss"),
        TrackInfo(0x24, 0x05, "Boss Fight (Draygon / Ridley)", "Boss"),
        TrackInfo(0x24, 0x06, "Bomb Torizo Awakening", "Boss"),
        TrackInfo(0x24, 0x07, "Escape Music", "Escape"),
        TrackInfo(0x21, 0x05, "Mother Brain Fight", "Tourian"),
        TrackInfo(0x3C, 0x05, "Credits", "Ending"),
        TrackInfo(0x3F, 0x05, "\"The Last Metroid is in Captivity\"", "Intro"),
        TrackInfo(0x42, 0x05, "\"The Galaxy is at Peace\"", "Intro"),
        TrackInfo(0x33, 0x05, "Zebes Exploding", "Ending"),
        TrackInfo(0x39, 0x05, "Samus Dying", "Game Over"),
    )

    /** Unique song sets referenced by tracks. */
    val SONG_SETS: List<Int> get() = KNOWN_TRACKS.map { it.songSet }.distinct().sorted()

    // ─── SPC transfer block parsing ─────────────────────────────────

    /**
     * A single SPC transfer block: [size] bytes to be written at [destAddr] in SPC RAM.
     */
    data class TransferBlock(
        val destAddr: Int,
        val data: ByteArray,
    )

    /**
     * Parse a chain of SPC transfer blocks starting at a PC offset in the ROM.
     * Returns the list of blocks and the total ROM bytes consumed.
     */
    fun parseTransferBlocks(romData: ByteArray, startPc: Int): List<TransferBlock> {
        val blocks = mutableListOf<TransferBlock>()
        var pos = startPc
        while (pos + 4 <= romData.size) {
            val size = (romData[pos].toInt() and 0xFF) or
                ((romData[pos + 1].toInt() and 0xFF) shl 8)
            if (size == 0) break
            val dest = (romData[pos + 2].toInt() and 0xFF) or
                ((romData[pos + 3].toInt() and 0xFF) shl 8)
            pos += 4
            if (pos + size > romData.size) break
            val data = romData.copyOfRange(pos, pos + size)
            blocks.add(TransferBlock(dest, data))
            pos += size
        }
        return blocks
    }

    /**
     * Apply transfer blocks to a 64KB SPC RAM image.
     */
    fun applyTransferBlocks(spcRam: ByteArray, blocks: List<TransferBlock>) {
        for (block in blocks) {
            val dest = block.destAddr and 0xFFFF
            val len = minOf(block.data.size, 0x10000 - dest)
            System.arraycopy(block.data, 0, spcRam, dest, len)
        }
    }

    /**
     * Build the initial SPC RAM image by parsing transfer blocks from $CF:8000.
     * This loads the SPC engine code and common sample data.
     */
    fun buildInitialSpcRam(romParser: RomParser): ByteArray {
        val spcRam = ByteArray(0x10000)
        val startPc = romParser.snesToPc(0xCF8000)
        val blocks = parseTransferBlocks(romParser.romData, startPc)
        applyTransferBlocks(spcRam, blocks)
        return spcRam
    }

    // ─── Sample directory parsing ───────────────────────────────────

    /**
     * BRR sample directory entry: start address and loop address in SPC RAM.
     */
    data class SampleDirEntry(
        val index: Int,
        val startAddr: Int,
        val loopAddr: Int,
    )

    /**
     * Find the sample directory in SPC RAM.
     * The DSP DIR register ($5D) sets the page: directory is at page * 0x100.
     * We try common DIR values used by SM's SPC engine.
     */
    fun findSampleDirectory(spcRam: ByteArray): List<SampleDirEntry> {
        // SM typically uses DIR page at $6C (directory at $6C00)
        // Also try other common values
        for (dirPage in listOf(0x6C, 0x6D, 0x04, 0x02, 0x1A)) {
            val dirAddr = dirPage * 0x100
            val entries = parseSampleDirectory(spcRam, dirAddr)
            if (entries.size >= 4) return entries
        }

        // Fallback: scan for a plausible directory
        for (dirPage in 0x01..0xFF) {
            val dirAddr = dirPage * 0x100
            val entries = parseSampleDirectory(spcRam, dirAddr)
            if (entries.size >= 4) return entries
        }
        return emptyList()
    }

    private fun parseSampleDirectory(spcRam: ByteArray, dirAddr: Int): List<SampleDirEntry> {
        val entries = mutableListOf<SampleDirEntry>()
        var idx = 0
        while (true) {
            val off = dirAddr + idx * 4
            if (off + 3 >= spcRam.size) break
            val startAddr = (spcRam[off].toInt() and 0xFF) or
                ((spcRam[off + 1].toInt() and 0xFF) shl 8)
            val loopAddr = (spcRam[off + 2].toInt() and 0xFF) or
                ((spcRam[off + 3].toInt() and 0xFF) shl 8)

            if (startAddr == 0 && loopAddr == 0 && idx > 0) break
            if (startAddr >= 0xFFF0) break

            // Validate: start address should point to valid SPC RAM with BRR data
            if (startAddr < 0x0200 || startAddr >= 0xFF00) {
                if (idx > 0) break else { idx++; continue }
            }

            // Check if there's a valid BRR header at the start address
            if (startAddr < spcRam.size) {
                val hdr = spcRam[startAddr].toInt() and 0xFF
                val shift = hdr shr 4
                if (shift > 12 && shift != 13) {
                    if (idx > 0) break else { idx++; continue }
                }
            }

            entries.add(SampleDirEntry(idx, startAddr, loopAddr))
            idx++
            if (idx > 64) break // SM never has more than ~30 samples
        }
        return entries
    }

    // ─── BRR decoding ───────────────────────────────────────────────

    /**
     * Decode a BRR sample from SPC RAM starting at [startAddr].
     * Returns signed 16-bit PCM samples at the SPC's native ~32kHz rate.
     *
     * BRR format: 9-byte blocks (1 header + 8 data bytes = 16 PCM samples/block)
     * Header: SSSSFFLE  (S=shift, F=filter, L=loop, E=end)
     */
    fun decodeBrr(spcRam: ByteArray, startAddr: Int, maxSamples: Int = 0x10000): ShortArray {
        val samples = mutableListOf<Short>()
        var prev1 = 0
        var prev2 = 0
        var addr = startAddr

        while (addr + 8 < spcRam.size && samples.size < maxSamples) {
            val header = spcRam[addr].toInt() and 0xFF
            val shift = header shr 4
            val filter = (header shr 2) and 0x3
            val endFlag = header and 1

            for (byteIdx in 1..8) {
                if (addr + byteIdx >= spcRam.size) break
                val b = spcRam[addr + byteIdx].toInt() and 0xFF
                for (nibbleIdx in 0..1) {
                    var nibble = if (nibbleIdx == 0) (b shr 4) else (b and 0x0F)
                    // Sign-extend nibble from 4 bits
                    if (nibble >= 8) nibble -= 16

                    val s: Int = if (shift <= 12) {
                        (nibble shl shift) shr 1
                    } else {
                        // Shift > 12: weird hardware behavior, clamp to 0 or -2048
                        if (nibble < 0) -2048 else 0
                    }

                    val filtered = when (filter) {
                        0 -> s
                        1 -> s + prev1 + ((-prev1) shr 4)
                        2 -> s + (prev1 shl 1) + ((-prev1 * 3) shr 5) - prev2 + (prev2 shr 4)
                        3 -> s + (prev1 shl 1) + ((-prev1 * 13) shr 6) - prev2 + ((prev2 * 3) shr 4)
                        else -> s
                    }

                    val clamped = filtered.coerceIn(-32768, 32767)
                    val clipped = (clamped shl 1).toShort().toInt() shr 1
                    prev2 = prev1
                    prev1 = clipped
                    samples.add(clipped.toShort())
                }
            }

            addr += 9
            if (endFlag != 0) break
        }

        return samples.toShortArray()
    }

    /**
     * Decode a BRR sample with loop support. Returns (pcmSamples, loopStartSample).
     * If the sample loops, unfolds one iteration for preview purposes.
     */
    fun decodeBrrWithLoop(
        spcRam: ByteArray,
        entry: SampleDirEntry,
        maxBlocks: Int = 2048
    ): Pair<ShortArray, Int> {
        val samples = mutableListOf<Short>()
        var prev1 = 0
        var prev2 = 0
        var addr = entry.startAddr
        var loopSample = -1
        var blockCount = 0

        while (addr + 8 < spcRam.size && blockCount < maxBlocks) {
            if (addr == entry.loopAddr && loopSample < 0) {
                loopSample = samples.size
            }

            val header = spcRam[addr].toInt() and 0xFF
            val shift = header shr 4
            val filter = (header shr 2) and 0x3
            @Suppress("UNUSED_VARIABLE") val loopFlag = (header shr 1) and 1
            val endFlag = header and 1

            for (byteIdx in 1..8) {
                if (addr + byteIdx >= spcRam.size) break
                val b = spcRam[addr + byteIdx].toInt() and 0xFF
                for (nibbleIdx in 0..1) {
                    var nibble = if (nibbleIdx == 0) (b shr 4) else (b and 0x0F)
                    if (nibble >= 8) nibble -= 16

                    val s: Int = if (shift <= 12) {
                        (nibble shl shift) shr 1
                    } else {
                        if (nibble < 0) -2048 else 0
                    }

                    val filtered = when (filter) {
                        0 -> s
                        1 -> s + prev1 + ((-prev1) shr 4)
                        2 -> s + (prev1 shl 1) + ((-prev1 * 3) shr 5) - prev2 + (prev2 shr 4)
                        3 -> s + (prev1 shl 1) + ((-prev1 * 13) shr 6) - prev2 + ((prev2 * 3) shr 4)
                        else -> s
                    }

                    val clamped = filtered.coerceIn(-32768, 32767)
                    val clipped = (clamped shl 1).toShort().toInt() shr 1
                    prev2 = prev1
                    prev1 = clipped
                    samples.add(clipped.toShort())
                }
            }

            addr += 9
            blockCount++
            if (endFlag != 0) break
        }

        return Pair(samples.toShortArray(), if (loopSample >= 0) loopSample else -1)
    }

    // ─── Song set pointer table ─────────────────────────────────────

    /**
     * Vanilla table address: $8F:E7E1.
     *
     * The music loading routine at $80:8F62 does:
     *   LDA $8FE7E1,X  ; X = songSet value (0x00, 0x03, 0x06, ...)
     *   STA $00
     *   LDA $8FE7E2,X
     *   STA $01         ; 3-byte pointer now in $00-$02
     *   JSL $80:8024    ; call SPC upload routine
     *
     * The table is a packed array of 3-byte SNES pointers. Each song set
     * value (a multiple of 3) is used DIRECTLY as a byte offset into the
     * table, so songSet 0x03 reads bytes at table+3..table+5, etc.
     * Song set 0x00 points to the base SPC data at $CF:8000.
     */
    private const val VANILLA_TABLE_SNES = 0x8FE7E1

    fun findSongSetTransferData(
        romParser: RomParser,
        songSet: Int
    ): List<TransferBlock> {
        val ptr = readSongSetPointer(romParser, songSet)
        if (ptr <= 0) return emptyList()
        val pc = romParser.snesToPc(ptr)
        if (pc < 0 || pc + 4 >= romParser.romData.size) return emptyList()
        return parseTransferBlocks(romParser.romData, pc)
    }

    /**
     * Read the 3-byte pointer for a given song set from the pointer table.
     * Tries the vanilla table at $8F:E7E1 first, then scans for a relocated table.
     */
    fun readSongSetPointer(romParser: RomParser, songSet: Int): Int {
        val rom = romParser.romData

        // Try vanilla table location first
        val vanillaPc = romParser.snesToPc(VANILLA_TABLE_SNES)
        val ptr = readPointerAt(rom, vanillaPc + songSet)
        if (ptr > 0 && isValidTransferBlockPointer(rom, romParser, ptr)) {
            return ptr
        }

        // Table may have been relocated by a ROM hack.
        // The loader code at $80:8F72 uses LDA $XXXXXX,X (opcode BF).
        // Scan for the pattern: BF xx xx 8F 85 00 BF yy yy 8F 85 01
        // where yy = xx+1 (the overlapping 3-byte read pattern).
        val tableAddr = findRelocatedTable(rom, romParser)
        if (tableAddr >= 0) {
            val relocPtr = readPointerAt(rom, tableAddr + songSet)
            if (relocPtr > 0 && isValidTransferBlockPointer(rom, romParser, relocPtr)) {
                return relocPtr
            }
        }

        System.err.println("[SPC] WARNING: no valid pointer for songSet 0x${songSet.toString(16).padStart(2, '0')}")
        return 0
    }

    /**
     * Build the full map of songSet -> SNES pointer for all known song sets.
     * Used for diagnostics and testing.
     */
    fun findSongSetPointerTable(romParser: RomParser): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val knownSets = listOf(0x00, 0x03, 0x06, 0x09, 0x0C, 0x0F, 0x12, 0x15,
            0x18, 0x1B, 0x1E, 0x21, 0x24, 0x27, 0x2A, 0x2D,
            0x30, 0x33, 0x36, 0x39, 0x3C, 0x3F, 0x42, 0x45)
        for (ss in knownSets) {
            val ptr = readSongSetPointer(romParser, ss)
            if (ptr > 0) result[ss] = ptr
        }
        if (result.isNotEmpty()) {
            System.err.println("[SPC] Found ${result.size} song set pointers")
            for ((ss, addr) in result.entries.sortedBy { it.key }) {
                System.err.println("[SPC]   songSet 0x${ss.toString(16).padStart(2, '0')} -> \$${addr.toString(16).uppercase().padStart(6, '0')}")
            }
        }
        return result
    }

    private fun readPointerAt(rom: ByteArray, pc: Int): Int {
        if (pc < 0 || pc + 2 >= rom.size) return 0
        val lo = rom[pc].toInt() and 0xFF
        val mid = rom[pc + 1].toInt() and 0xFF
        val hi = rom[pc + 2].toInt() and 0xFF
        return (hi shl 16) or (mid shl 8) or lo
    }

    private fun isValidTransferBlockPointer(rom: ByteArray, romParser: RomParser, snesPtr: Int): Boolean {
        val pc = romParser.snesToPc(snesPtr)
        if (pc < 0 || pc + 4 >= rom.size) return false
        val blkSize = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        val blkDest = (rom[pc + 2].toInt() and 0xFF) or ((rom[pc + 3].toInt() and 0xFF) shl 8)
        return blkSize in 1..0xF000 && blkDest < 0x10000 && pc + 4 + blkSize <= rom.size
    }

    /**
     * Scan $80:8F60-$80:8F90 for the BF opcode pattern that reads the table,
     * in case a ROM hack relocated the table address.
     * Pattern: BF [lo] [mid] [hi] 85 00 BF [lo+1] [mid'] [hi'] 85 01
     */
    private fun findRelocatedTable(rom: ByteArray, romParser: RomParser): Int {
        val searchStart = romParser.snesToPc(0x808F50)
        val searchEnd = minOf(romParser.snesToPc(0x808FA0), rom.size - 12)
        if (searchStart < 0) return -1
        for (i in searchStart until searchEnd) {
            if (rom[i].toInt() and 0xFF != 0xBF) continue
            if (i + 11 >= rom.size) break
            if ((rom[i + 4].toInt() and 0xFF) != 0x85) continue
            if ((rom[i + 5].toInt() and 0xFF) != 0x00) continue
            if ((rom[i + 6].toInt() and 0xFF) != 0xBF) continue
            if ((rom[i + 10].toInt() and 0xFF) != 0x85) continue
            if ((rom[i + 11].toInt() and 0xFF) != 0x01) continue

            val lo1 = rom[i + 1].toInt() and 0xFF
            val mid1 = rom[i + 2].toInt() and 0xFF
            val hi1 = rom[i + 3].toInt() and 0xFF
            val lo2 = rom[i + 7].toInt() and 0xFF
            val mid2 = rom[i + 8].toInt() and 0xFF
            val hi2 = rom[i + 9].toInt() and 0xFF

            val addr1 = (hi1 shl 16) or (mid1 shl 8) or lo1
            val addr2 = (hi2 shl 16) or (mid2 shl 8) or lo2
            if (addr2 == addr1 + 1) {
                val tablePc = romParser.snesToPc(addr1)
                System.err.println("[SPC] Found relocated table at \$${addr1.toString(16).uppercase()} (PC 0x${tablePc.toString(16)})")
                return tablePc
            }
        }
        return -1
    }
}
