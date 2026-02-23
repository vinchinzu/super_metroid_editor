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
                    // Clip to 15-bit signed then sign-extend (SPC700 behavior)
                    val clipped = (clamped shl 1) shr 1
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
                    val clipped = (clamped shl 1) shr 1
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
     * Find the song set pointer table in the ROM.
     * The music loading routine at $80:8FC7 (approximately) uses a table of
     * 3-byte pointers indexed by (songSet / 3). Each pointer leads to a chain
     * of SPC transfer blocks for that song set.
     *
     * We find the table by scanning bank $CF's transfer block chain to identify
     * additional data sets.
     */
    fun findSongSetTransferData(
        romParser: RomParser,
        songSet: Int
    ): List<TransferBlock> {
        // Song set 0 = the base SPC data at $CF:8000.
        // Other song sets load additional transfer blocks from specific ROM locations.
        // The pointer table is indexed by songSet value.
        //
        // Known table location: $80:8FC9 has a table of 3-byte SNES pointers.
        // For now, scan room states to build the mapping, or use a hardcoded table
        // based on the known SM song set locations.

        val songSetPointers = findSongSetPointerTable(romParser)
        val ptr = songSetPointers[songSet] ?: return emptyList()
        val pc = romParser.snesToPc(ptr)
        return parseTransferBlocks(romParser.romData, pc)
    }

    /**
     * Try to locate the song set -> SPC data pointer table.
     * Returns a map of songSet value -> SNES address of transfer block chain.
     *
     * SM's music loading routine multiplies the song set value by 3 to get a
     * byte offset into a table of 3-byte SNES pointers. Song set values are
     * multiples of 3: 0x03, 0x06, ..., 0x45.
     *
     * Vanilla table is at $80:C4C5; ROM hacks may relocate it.
     */
    fun findSongSetPointerTable(romParser: RomParser): Map<Int, Int> {
        val rom = romParser.romData

        val diagPc = romParser.snesToPc(0x80C4C5)
        if (diagPc in 0 until rom.size - 30) {
            val hex = (0 until 30).joinToString(" ") {
                (rom[diagPc + it].toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            System.err.println("[SPC] Bytes at \$80:C4C5 (PC=0x${diagPc.toString(16)}): $hex")
        }

        for (tableBase in listOf(
            0x80C4C5, 0x80C4C0, 0x80C4D0,
            0x8FE000, 0x8FE010, 0x8FE020,
            0x808FDB, 0x808FC9
        )) {
            val pc = romParser.snesToPc(tableBase)
            if (pc < 0 || pc >= rom.size) continue
            val map = tryParseSongSetTable(rom, pc, romParser)
            if (map.size >= 5) {
                System.err.println("[SPC] Found song set table at \$${tableBase.toString(16).uppercase()} with ${map.size} entries")
                for ((ss, addr) in map.entries.sortedBy { it.key }) {
                    System.err.println("[SPC]   songSet 0x${ss.toString(16).padStart(2, '0')} -> \$${addr.toString(16).uppercase()}")
                }
                return map
            } else {
                System.err.println("[SPC] Table at \$${tableBase.toString(16).uppercase()}: ${map.size} valid entries, skipping")
            }
        }

        System.err.println("[SPC] Brute-force scanning bank \$80 for pointer table...")
        val bankStart = romParser.snesToPc(0x808000)
        val bankEnd = minOf(romParser.snesToPc(0x80FFFF), rom.size - 3)
        var bestMap = emptyMap<Int, Int>()
        var bestScan = -1
        var scan = bankStart
        while (scan < bankEnd) {
            val map = tryParseSongSetTable(rom, scan, romParser)
            if (map.size > bestMap.size) { bestMap = map; bestScan = scan }
            if (map.size >= 8) {
                System.err.println("[SPC] Found song set table by scan at PC=0x${scan.toString(16)} with ${map.size} entries")
                return map
            }
            scan++
        }
        if (bestMap.size >= 3) {
            System.err.println("[SPC] Using best scan result at PC=0x${bestScan.toString(16)} with ${bestMap.size} entries")
            return bestMap
        }
        System.err.println("[SPC] Best scan: PC=0x${bestScan.toString(16)} with ${bestMap.size} entries")
        System.err.println("[SPC] WARNING: could not find song set pointer table")
        return emptyMap()
    }

    /**
     * Validate a 3-byte pointer table by checking that entries actually point
     * to parseable SPC transfer block chains in the ROM. This is bank-agnostic
     * and works regardless of where ROM hacks place their music data.
     */
    private fun tryParseSongSetTable(rom: ByteArray, tablePc: Int, romParser: RomParser): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        var consecutiveInvalid = 0
        for (i in 0 until 70) {
            val off = tablePc + i * 3
            if (off + 2 >= rom.size) break
            val lo = rom[off].toInt() and 0xFF
            val mid = rom[off + 1].toInt() and 0xFF
            val hi = rom[off + 2].toInt() and 0xFF
            val ptr = (hi shl 16) or (mid shl 8) or lo

            if (ptr == 0) {
                consecutiveInvalid++
                if (consecutiveInvalid > 8) break
                continue
            }

            val pc = romParser.snesToPc(ptr)
            if (pc < 0 || pc + 4 >= rom.size) {
                consecutiveInvalid++
                if (consecutiveInvalid > 8) break
                continue
            }

            val blockSize = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
            val blockDest = (rom[pc + 2].toInt() and 0xFF) or ((rom[pc + 3].toInt() and 0xFF) shl 8)

            if (blockSize in 1..0xF000 && blockDest < 0x10000 && pc + 4 + blockSize <= rom.size) {
                consecutiveInvalid = 0
                result[i] = ptr
            } else {
                consecutiveInvalid++
                if (consecutiveInvalid > 8) break
            }
        }
        return result
    }
}
