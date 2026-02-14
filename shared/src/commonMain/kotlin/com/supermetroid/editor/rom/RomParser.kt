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
    
    companion object {
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
