package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Parser for Super Metroid ROM files (.smc format)
 * 
 * Super Metroid ROM structure:
 * - Optional SMC header: 0x200 bytes (512 bytes) 
 * - ROM data: 3MB (0x300000 bytes)
 * - LoROM mapping (mode byte 0x30 at internal header offset $7FD5)
 * 
 * Room IDs (e.g., 0x91F8 for Landing Site) are 16-bit pointers within
 * SNES bank $8F. Each room header lives at SNES address $8F:<roomId>.
 */
class RomParser(private val romData: ByteArray) {
    private val hasHeader: Boolean
        get() = romData.size == 0x300200
    
    private val romStartOffset: Int
        get() = if (hasHeader) 0x200 else 0x0
    
    /**
     * Convert SNES address to PC offset using LoROM mapping.
     * PC offset = ((bank & 0x7F) * 0x8000) + (address & 0x7FFF)
     */
    fun snesToPc(snesAddress: Int): Int {
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        val pcAddress = ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
        return romStartOffset + pcAddress
    }
    
    /**
     * Convert a 16-bit room ID to PC offset.
     * Room IDs are pointers within SNES bank $8F.
     */
    fun roomIdToPc(roomId: Int): Int {
        return snesToPc(0x8F0000 or (roomId and 0xFFFF))
    }
    
    /**
     * Read a room header directly from ROM using the room ID.
     */
    fun readRoomHeader(roomId: Int): Room? {
        val pcOffset = roomIdToPc(roomId)
        
        if (pcOffset < 0 || pcOffset + 11 > romData.size) {
            return null
        }
        
        return try {
            val index = romData[pcOffset].toInt() and 0xFF
            val area = romData[pcOffset + 1].toInt() and 0xFF
            val mapX = romData[pcOffset + 2].toInt() and 0xFF
            val mapY = romData[pcOffset + 3].toInt() and 0xFF
            val width = romData[pcOffset + 4].toInt() and 0xFF
            val height = romData[pcOffset + 5].toInt() and 0xFF
            val upScroller = romData[pcOffset + 6].toInt() and 0xFF
            val downScroller = romData[pcOffset + 7].toInt() and 0xFF
            val creBitflag = romData[pcOffset + 8].toInt() and 0xFF
            val doorOut = readUInt16At(pcOffset + 9)
            
            if (width == 0 || height == 0 || width > 16 || height > 16) return null
            if (area > 6) return null
            
            // Find default state data
            val stateDataOffset = findDefaultStateData(pcOffset + 11)
            
            var levelDataPtr = 0
            var tileset = 0
            var musicData = 0
            var musicTrack = 0
            var fxPtr = 0
            var enemySetPtr = 0
            var enemyGfxPtr = 0
            var bgScrolling = 0
            var roomScrollsPtr = 0
            var mainAsmPtr = 0
            var plmSetPtr = 0
            var bgDataPtr = 0
            var setupAsmPtr = 0
            
            if (stateDataOffset != null && stateDataOffset + 26 <= romData.size) {
                levelDataPtr = readUInt24At(stateDataOffset)
                tileset = romData[stateDataOffset + 3].toInt() and 0xFF
                musicData = romData[stateDataOffset + 4].toInt() and 0xFF
                musicTrack = romData[stateDataOffset + 5].toInt() and 0xFF
                fxPtr = readUInt16At(stateDataOffset + 6)
                enemySetPtr = readUInt16At(stateDataOffset + 8)
                enemyGfxPtr = readUInt16At(stateDataOffset + 10)
                bgScrolling = readUInt16At(stateDataOffset + 12)
                roomScrollsPtr = readUInt16At(stateDataOffset + 14)
                mainAsmPtr = readUInt16At(stateDataOffset + 18)
                plmSetPtr = readUInt16At(stateDataOffset + 20)
                bgDataPtr = readUInt16At(stateDataOffset + 22)
                setupAsmPtr = readUInt16At(stateDataOffset + 24)
            }
            
            Room(
                roomId = roomId,
                name = "Room 0x${roomId.toString(16)}",
                handle = "room_${roomId.toString(16)}",
                index = index,
                area = area,
                mapX = mapX,
                mapY = mapY,
                width = width,
                height = height,
                upScroller = upScroller,
                downScroller = downScroller,
                creBitflag = creBitflag,
                doorOut = doorOut,
                levelDataPtr = levelDataPtr,
                tileset = tileset,
                musicData = musicData,
                musicTrack = musicTrack,
                fxPtr = fxPtr,
                enemySetPtr = enemySetPtr,
                enemyGfxPtr = enemyGfxPtr,
                bgScrolling = bgScrolling,
                roomScrollsPtr = roomScrollsPtr,
                mainAsmPtr = mainAsmPtr,
                plmSetPtr = plmSetPtr,
                bgDataPtr = bgDataPtr,
                setupAsmPtr = setupAsmPtr
            )
        } catch (e: Exception) {
            println("Room 0x${roomId.toString(16)}: exception ${e.message}")
            null
        }
    }
    
    /**
     * Find room state data. Uses the first E629 (morph ball) conditional state
     * if available, otherwise falls back to the default E5E6 state.
     * 
     * E629 represents "normal gameplay" (Wrecked Ship powered on, etc.).
     * We use a byte-scan for E5E6 as the reliable way to find the default,
     * and specifically look for E629 as the first condition.
     * 
     * E629 entry format: condition(2) + arg(1) + statePtr(2) = 5 bytes.
     * The statePtr points to 26 bytes of state data in bank $8F.
     */
    private fun findDefaultStateData(stateListOffset: Int): Int? {
        // Check if first condition is E629 (morph ball check)
        if (stateListOffset + 5 <= romData.size) {
            val firstCondition = readUInt16At(stateListOffset)
            if (firstCondition == 0xE629) {
                // E629: condition(2) + arg(1) + ptr(2) = 5 bytes
                val statePtr = readUInt16At(stateListOffset + 3)
                val statePc = snesToPc(0x8F0000 or statePtr)
                if (statePc + 26 <= romData.size) {
                    return statePc
                }
            }
        }
        
        // Byte-scan for E5E6 default state marker
        val maxScan = 200
        val endOffset = minOf(stateListOffset + maxScan, romData.size - 1)
        for (offset in stateListOffset until endOffset) {
            if (readUInt16At(offset) == 0xE5E6) {
                return offset + 2  // 26-byte state data follows
            }
        }
        
        return null
    }
    
    // ─── LZ5 Decompression ──────────────────────────────────────────────
    //
    // Ported from the verified working Python implementation:
    //   https://github.com/aremath/sm_rando/blob/master/rom_tools/compress/decompress.py
    // Algorithm spec: https://sneslab.net/wiki/LZ5
    //
    // Commands 0-6 are standard, command 7 is extended (2-byte header).
    // 0xFF terminates decompression.
    //
    // CRITICAL differences from our old broken implementation:
    //   1. 0xFF IS the end marker (not a no-op)
    //   2. Command 6 (Negative Repeat) takes 1 byte (relative offset), not 2
    //   3. Dictionary copies wrap around when referencing past current output end
    
    fun decompressLZ2(snesAddress: Int): ByteArray {
        val startPc = snesToPc(snesAddress)
        return decompressLZ5AtPc(startPc)
    }
    
    /**
     * Decompress and return both the data and the number of ROM bytes consumed.
     * Used by the export system to know how much space is available for recompression.
     */
    fun decompressLZ2WithSize(snesAddress: Int): Pair<ByteArray, Int> {
        val startPc = snesToPc(snesAddress)
        return decompressLZ5AtPcWithSize(startPc)
    }
    
    /** Decompress LZ5 and return (decompressed data, ROM bytes consumed). */
    fun decompressLZ5AtPcWithSize(startPc: Int): Pair<ByteArray, Int> {
        val result = decompressLZ5AtPc(startPc)
        // Re-scan to find end position (where 0xFF terminator is)
        var pos = startPc
        while (pos < romData.size) {
            val cmd = romData[pos].toInt() and 0xFF
            if (cmd == 0xFF) { pos++; break }
            val topBits = (cmd shr 5) and 7
            val length: Int
            if (topBits == 7) {
                val cmdCode = (cmd shr 2) and 7
                length = ((cmd and 0x03) shl 8 or (romData[pos + 1].toInt() and 0xFF)) + 1
                pos += 2
            } else {
                val cmdCode = topBits
                length = (cmd and 0x1F) + 1
                pos += 1
            }
            val cmdCode = if (topBits == 7) (cmd shr 2) and 7 else topBits
            when (cmdCode) {
                0 -> pos += length       // direct copy: skip length data bytes
                1 -> pos += 1            // byte fill: 1 byte
                2 -> pos += 2            // word fill: 2 bytes
                3 -> pos += 1            // increasing fill: 1 byte
                4, 5 -> pos += 2         // absolute copy: 2-byte address
                6, 7 -> pos += 1         // relative copy: 1 byte offset
            }
        }
        return Pair(result, pos - startPc)
    }
    
    /**
     * Decompress LZ5 data starting at the given PC offset.
     * Ported directly from aremath/sm_rando decompress.py
     */
    fun decompressLZ5AtPc(startPc: Int): ByteArray {
        val dst = ByteArray(0x20000) // 128KB max output (some rooms are very large)
        var dstPos = 0
        var pos = startPc
        
        while (pos < romData.size) {
            val nextCmd = romData[pos].toInt() and 0xFF
            
            // 0xFF = end of compressed data
            if (nextCmd == 0xFF) {
                pos++
                break
            }
            
            val cmdCode: Int
            val length: Int
            
            val topBits = (nextCmd shr 5) and 7
            if (topBits == 7) {
                // Extended command: 2-byte header
                // Bits 5-3 of first byte = actual command
                // Last 2 bits of first byte + all 8 bits of second byte = 10-bit length
                cmdCode = (nextCmd shr 2) and 7
                val highBits = nextCmd and 0x03
                val lowBits = romData[pos + 1].toInt() and 0xFF
                length = ((highBits shl 8) or lowBits) + 1
                pos += 2
            } else {
                // Standard command: 1-byte header
                cmdCode = topBits
                length = (nextCmd and 0x1F) + 1
                pos += 1
            }
            
            when (cmdCode) {
                0 -> {
                    // Direct copy: copy next `length` bytes from source
                    for (i in 0 until length) {
                        if (pos >= romData.size) break
                        dst[dstPos++] = romData[pos++]
                    }
                }
                1 -> {
                    // Byte fill: repeat one byte `length` times
                    val fillByte = romData[pos++]
                    for (i in 0 until length) {
                        dst[dstPos++] = fillByte
                    }
                }
                2 -> {
                    // Word fill: alternate two bytes for `length` bytes
                    val b1 = romData[pos++]
                    val b2 = romData[pos++]
                    for (i in 0 until length) {
                        dst[dstPos++] = if (i % 2 == 0) b1 else b2
                    }
                }
                3 -> {
                    // Increasing fill: write byte, increment by 1, `length` times
                    var b = romData[pos++].toInt() and 0xFF
                    for (i in 0 until length) {
                        dst[dstPos++] = (b and 0xFF).toByte()
                        b++
                    }
                }
                4 -> {
                    // Repeat (absolute address copy): copy `length` bytes from
                    // absolute position in output buffer. Wraps if past current end.
                    val addr = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    copyFromOutput(dst, dstPos, addr, length) { it }
                    dstPos += length
                }
                5 -> {
                    // XOR Repeat: same as cmd 4 but XOR each byte with 0xFF
                    val addr = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    copyFromOutput(dst, dstPos, addr, length) { (it.toInt() xor 0xFF).toByte() }
                    dstPos += length
                }
                6 -> {
                    // Negative Repeat (relative address copy): copy `length` bytes
                    // from (current_position - offset) in output buffer.
                    // Takes only 1 byte for the relative offset!
                    val relOffset = romData[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    copyFromOutput(dst, dstPos, srcAddr, length) { it }
                    dstPos += length
                }
                7 -> {
                    // Extended cmd 7 = Negative XOR Repeat (relative + XOR 0xFF)
                    val relOffset = romData[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    copyFromOutput(dst, dstPos, srcAddr, length) { (it.toInt() xor 0xFF).toByte() }
                    dstPos += length
                }
            }
            
            if (dstPos >= dst.size) break // Safety
        }
        
        return dst.copyOf(dstPos)
    }
    
    /**
     * Copy bytes from output buffer with wrap-around support.
     * When the copy range extends past what has been written, bytes wrap
     * (repeat from the start of the copied portion).
     * Ported from aremath/sm_rando get_copy_bytes().
     */
    private fun copyFromOutput(
        dst: ByteArray, dstPos: Int, srcAddr: Int, length: Int, 
        transform: (Byte) -> Byte
    ) {
        // First pass: copy bytes that already exist in the output
        var srcIdx = srcAddr
        var written = 0
        while (written < length && srcIdx < dstPos) {
            if (srcIdx >= 0) {
                dst[dstPos + written] = transform(dst[srcIdx])
            } else {
                dst[dstPos + written] = transform(0)
            }
            written++
            srcIdx++
        }
        // Second pass: wrap-around — copy from what we just wrote
        var wrapIdx = 0
        while (written < length) {
            dst[dstPos + written] = transform(dst[dstPos + wrapIdx])
            written++
            wrapIdx++
        }
    }
    
    // ─── Utility ──────────────────────────────────────────────────────
    
    private fun readUInt16At(offset: Int): Int {
        val lo = romData[offset].toInt() and 0xFF
        val hi = romData[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }
    
    private fun readUInt24At(offset: Int): Int {
        val lo = romData[offset].toInt() and 0xFF
        val mid = romData[offset + 1].toInt() and 0xFF
        val hi = romData[offset + 2].toInt() and 0xFF
        return (hi shl 16) or (mid shl 8) or lo
    }
    
    fun getRomData(): ByteArray = romData

    /** Convert PC offset back to SNES address (LoROM). */
    fun pcToSnes(pcOffset: Int): Int {
        val adjusted = pcOffset - romStartOffset
        val bank = (adjusted / 0x8000) or 0x80
        val offset = (adjusted % 0x8000) + 0x8000
        return (bank shl 16) or offset
    }

    /** Get the PC offset of the default state data block for a room.
     *  The PLM set pointer is at stateDataPcOffset + 20. */
    fun getStateDataPcOffset(roomId: Int): Int? {
        val pcOffset = roomIdToPc(roomId)
        if (pcOffset < 0 || pcOffset + 11 > romData.size) return null
        return findDefaultStateData(pcOffset + 11)
    }

    /**
     * Find ALL state data PC offsets for a room by parsing the state condition list.
     * Each room can have multiple state conditions (E629 boss check, E612 event, etc.)
     * plus the E5E6 default. Returns PC offsets of every 26-byte state data block.
     *
     * State condition entry sizes (from SM disassembly):
     *   E5E6: default (terminates list), 26-byte state data follows inline
     *   E5EB: code(2)+doorEvent(2)+statePtr(2) = 6 bytes
     *   E5FF: code(2)+event(2)+statePtr(2) = 6 bytes
     *   E612: code(2)+bossFlag(1)+statePtr(2) = 5 bytes
     *   E629: code(2)+bossFlag(1)+statePtr(2) = 5 bytes
     *   E640/E652/E669/E678: code(2)+statePtr(2) = 4 bytes
     */
    fun findAllStateDataOffsets(roomId: Int): List<Int> {
        val pcOffset = roomIdToPc(roomId)
        if (pcOffset < 0 || pcOffset + 11 > romData.size) return emptyList()

        val stateListOffset = pcOffset + 11
        val results = mutableListOf<Int>()
        var pos = stateListOffset
        val maxPos = minOf(stateListOffset + 200, romData.size - 1)

        while (pos + 1 < maxPos) {
            val code = readUInt16At(pos)
            when (code) {
                0xE5E6 -> {
                    val statePc = pos + 2
                    if (statePc + 26 <= romData.size) results.add(statePc)
                    return results
                }
                0xE5EB, 0xE5FF -> {
                    // 2-byte event arg + 2-byte state pointer = 6 bytes total
                    if (pos + 5 < romData.size) {
                        val statePtr = readUInt16At(pos + 4)
                        val statePc = snesToPc(0x8F0000 or statePtr)
                        if (statePc + 26 <= romData.size) results.add(statePc)
                    }
                    pos += 6
                }
                0xE612, 0xE629 -> {
                    // 1-byte boss/event flag + 2-byte state pointer = 5 bytes total
                    if (pos + 4 < romData.size) {
                        val statePtr = readUInt16At(pos + 3)
                        val statePc = snesToPc(0x8F0000 or statePtr)
                        if (statePc + 26 <= romData.size) results.add(statePc)
                    }
                    pos += 5
                }
                0xE640, 0xE652, 0xE669, 0xE678 -> {
                    // 2-byte state pointer only = 4 bytes total
                    if (pos + 3 < romData.size) {
                        val statePtr = readUInt16At(pos + 2)
                        val statePc = snesToPc(0x8F0000 or statePtr)
                        if (statePc + 26 <= romData.size) results.add(statePc)
                    }
                    pos += 4
                }
                else -> return results
            }
        }
        return results
    }
    
    // ─── PLM (Post Load Modification) parsing ───────────────────────
    
    data class PlmEntry(val id: Int, val x: Int, val y: Int, val param: Int)
    data class RoomItemInfo(val roomId: Int, val area: Int, val plm: PlmEntry)

    /**
     * Scan all rooms for item PLMs and return a list with room/area metadata.
     * Useful for finding used collection bit parameters per area.
     */
    fun scanAllItemPlms(roomIds: List<Int>): List<RoomItemInfo> {
        val result = mutableListOf<RoomItemInfo>()
        for (rid in roomIds) {
            val room = readRoomHeader(rid) ?: continue
            if (room.plmSetPtr == 0 || room.plmSetPtr == 0xFFFF) continue
            val plms = parsePlmSet(room.plmSetPtr)
            for (plm in plms) {
                if (isItemPlm(plm.id)) {
                    result.add(RoomItemInfo(rid, room.area, plm))
                }
            }
        }
        return result
    }
    
    /**
     * Parse the PLM set for a room. plmSetPtr is a 16-bit pointer in bank $8F.
     * Each PLM entry is 6 bytes: 2-byte ID, 1-byte X, 1-byte Y, 2-byte param.
     * Terminated by ID == 0x0000.
     */
    fun parsePlmSet(plmSetPtr: Int): List<PlmEntry> {
        if (plmSetPtr == 0 || plmSetPtr == 0xFFFF) return emptyList()
        val snesAddr = 0x8F0000 or plmSetPtr
        var pc = snesToPc(snesAddr)
        val entries = mutableListOf<PlmEntry>()
        var safety = 0
        while (pc + 5 < romData.size && safety < 256) {
            val id = readUInt16At(pc)
            if (id == 0) break
            val x = romData[pc + 2].toInt() and 0xFF
            val y = romData[pc + 3].toInt() and 0xFF
            val param = readUInt16At(pc + 4)
            entries.add(PlmEntry(id, x, y, param))
            pc += 6
            safety++
        }
        return entries
    }
    
    // ─── Door entry parsing ──────────────────────────────────────────

    /**
     * 12-byte door entry in bank $83. Format (LE):
     *   +0  destRoomPtr  (2) destination room ID within bank $8F
     *   +2  bitflag      (2) direction (high byte) + flags (low byte: bit7=elevator)
     *   +4  doorCapCode  (2) ASM pointer ($8F) for scroll changes on entry
     *   +6  screenX      (1) spawn screen X
     *   +7  screenY      (1) spawn screen Y
     *   +8  distFromDoor (2) Samus distance from door edge (0x8000 = default)
     *   +10 entryCode    (2) ASM pointer ($8F) to run on arrival
     */
    data class DoorEntry(
        val destRoomPtr: Int,
        val bitflag: Int,
        val doorCapCode: Int,
        val screenX: Int,
        val screenY: Int,
        val distFromDoor: Int,
        val entryCode: Int
    ) {
        val direction: Int get() = (bitflag shr 8) and 0xFF
        val directionName: String get() = when (direction and 0x03) {
            0 -> "Right"
            1 -> "Left"
            2 -> "Down"
            3 -> "Up"
            else -> "?"
        }
        val isElevator: Boolean get() = (bitflag and 0x80) != 0
    }

    /**
     * Read a single door entry by index from the room's door-out list.
     * doorOutPtr is within bank $8F; each list slot is a 2-byte pointer into bank $83.
     */
    fun parseDoorEntry(doorOutPtr: Int, doorIndex: Int): DoorEntry? {
        if (doorOutPtr == 0 || doorOutPtr == 0xFFFF) return null
        val listPc = snesToPc(0x8F0000 or doorOutPtr)
        val ptrOff = listPc + doorIndex * 2
        if (ptrOff + 1 >= romData.size) return null
        val entryPtr = readUInt16At(ptrOff)
        if (entryPtr < 0x8000) return null
        val entryPc = snesToPc(0x830000 or entryPtr)
        if (entryPc + 11 >= romData.size) return null
        val destRoom = readUInt16At(entryPc)
        if (destRoom < 0x8000 || destRoom == 0xFFFF) return null
        return DoorEntry(
            destRoomPtr = destRoom,
            bitflag = readUInt16At(entryPc + 2),
            doorCapCode = readUInt16At(entryPc + 4),
            screenX = romData[entryPc + 6].toInt() and 0xFF,
            screenY = romData[entryPc + 7].toInt() and 0xFF,
            distFromDoor = readUInt16At(entryPc + 8),
            entryCode = readUInt16At(entryPc + 10)
        )
    }

    /**
     * Parse all door entries for a room. Reads the door-out list until an
     * invalid pointer is encountered, up to [maxDoors].
     */
    fun parseDoorList(doorOutPtr: Int, maxDoors: Int = 16): List<DoorEntry> {
        val entries = mutableListOf<DoorEntry>()
        for (i in 0 until maxDoors) {
            entries.add(parseDoorEntry(doorOutPtr, i) ?: break)
        }
        return entries
    }

    /**
     * Door cap colors from PLM type IDs.
     * Door caps in SM are PLMs placed at door positions. The PLM ID determines color:
     *   $C842/$C848 = Blue (beam)
     *   $C85A/$C860 = Red/Pink (5 missiles)
     *   $C866/$C86C = Green (super missile)
     *   $C872/$C878 = Yellow (power bomb)
     * Returns ARGB color or null if not a door cap PLM.
     */
    companion object {
        // ─── Item PLM catalog ──────────────────────────────────────
        data class ItemDef(val name: String, val shortLabel: String, val chozoId: Int, val visibleId: Int, val hiddenId: Int)

        val ITEM_DEFS = listOf(
            //                               chozoId  visibleId hiddenId
            ItemDef("Energy Tank",   "ET", 0xEF2B, 0xEED7, 0xEF7F),
            ItemDef("Missile",       "Mi", 0xEF2F, 0xEEDB, 0xEF83),
            ItemDef("Super Missile", "Su", 0xEF33, 0xEEDF, 0xEF87),
            ItemDef("Power Bomb",    "PB", 0xEF37, 0xEEE3, 0xEF8B),
            ItemDef("Bomb",          "Bo", 0xEF3B, 0xEEE7, 0xEF8F),
            ItemDef("Charge Beam",   "Ch", 0xEF3F, 0xEEEB, 0xEF93),
            ItemDef("Ice Beam",      "Ic", 0xEF43, 0xEEEF, 0xEF97),
            ItemDef("Hi-Jump Boots", "HJ", 0xEF47, 0xEEF3, 0xEF9B),
            ItemDef("Speed Booster", "Sp", 0xEF4B, 0xEEF7, 0xEF9F),
            ItemDef("Wave Beam",     "Wa", 0xEF4F, 0xEEFB, 0xEFA3),
            ItemDef("Spazer",        "Sz", 0xEF53, 0xEEFF, 0xEFA7),
            ItemDef("Spring Ball",   "SB", 0xEF57, 0xEF03, 0xEFAB),
            ItemDef("Varia Suit",    "Va", 0xEF5B, 0xEF07, 0xEFAF),
            ItemDef("Gravity Suit",  "Gr", 0xEF5F, 0xEF0B, 0xEFB3),
            ItemDef("X-Ray Scope",   "XR", 0xEF63, 0xEF0F, 0xEFB7),
            ItemDef("Plasma Beam",   "Pl", 0xEF67, 0xEF13, 0xEFBB),
            ItemDef("Grapple Beam",  "Gp", 0xEF6B, 0xEF17, 0xEFBF),
            ItemDef("Space Jump",    "SJ", 0xEF6F, 0xEF1B, 0xEFC3),
            ItemDef("Screw Attack",  "SA", 0xEF73, 0xEF1F, 0xEFC7),
            ItemDef("Morph Ball",    "MB", 0xEF77, 0xEF23, 0xEFCB),
            ItemDef("Reserve Tank",  "RT", 0xEF7B, 0xEF27, 0xEFCF),
        )

        private val plmToItemName: Map<Int, String> = buildMap {
            for (item in ITEM_DEFS) {
                put(item.chozoId,  "${item.name} (Chozo)")
                put(item.visibleId, "${item.name} (Visible)")
                put(item.hiddenId, "${item.name} (Hidden)")
            }
        }

        fun itemNameForPlm(plmId: Int): String? = plmToItemName[plmId]
        fun isItemPlm(plmId: Int): Boolean = plmId in plmToItemName

        // Door cap colors matching the in-game door shield appearance
        val DOOR_CAP_BLUE   = 0xFF3880D0.toInt()   // Blue: opens with any weapon
        val DOOR_CAP_RED    = 0xFFD05050.toInt()    // Red/Pink: 5 missiles or 1 super
        val DOOR_CAP_GREEN  = 0xFF40C048.toInt()    // Green: super missile
        val DOOR_CAP_YELLOW = 0xFFD8C830.toInt()    // Yellow/Orange: power bomb
        val DOOR_CAP_GREY   = 0xFF808088.toInt()    // Grey: boss/event dependent
        
        /**
         * Returns ARGB door cap color for a PLM type ID, or null if not a door cap.
         * Based on Kejardon's PLM documentation:
         *   $C842-$C855 = Grey (boss/event)   facing L/R/U/D
         *   $C85A-$C86D = Orange/Yellow (PB)   facing L/R/U/D
         *   $C872-$C885 = Green (super)        facing L/R/U/D
         *   $C88A-$C89D = Red (missile)        facing L/R/U/D
         *   $C8A2-$C8B5 = Blue (beam) opening  facing L/R/U/D
         *   $C8BA-$C8CD = Blue (beam) closing   facing L/R/U/D
         */
        fun doorCapColor(plmId: Int): Int? = when (plmId) {
            in 0xC842..0xC855 -> DOOR_CAP_GREY      // Grey door caps (boss/event)
            in 0xC85A..0xC86D -> DOOR_CAP_YELLOW     // Orange/Yellow (power bomb)
            in 0xC872..0xC885 -> DOOR_CAP_GREEN      // Green (super missile)
            in 0xC88A..0xC89D -> DOOR_CAP_RED        // Red (5 missiles)
            in 0xC8A2..0xC8CD -> DOOR_CAP_BLUE       // Blue (beam/anything)
            else -> null
        }
        
        fun loadRom(filePath: String): RomParser {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("ROM file not found: $filePath")
            }
            val romData = file.readBytes()
            if (romData.size != 0x300000 && romData.size != 0x300200) {
                throw IllegalArgumentException("Invalid ROM size: ${romData.size} bytes")
            }
            return RomParser(romData)
        }
    }
}
