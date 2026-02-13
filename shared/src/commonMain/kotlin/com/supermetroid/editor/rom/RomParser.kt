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
     * Find the default room state data offset.
     * 
     * Room state entries are a list of conditional states followed by
     * the default state marker $E5E6. We must parse entry-by-entry to
     * skip the correct number of bytes for each condition type.
     * 
     * State entry format:
     *   condition_code (2 bytes) — which handler to use
     *   parameters     (varies) — depends on condition type
     *   state_ptr      (2 bytes) — pointer to state data (bank $8F)
     * 
     * Known condition types and their TOTAL entry sizes:
     *   $E5E6: Default state (end marker) — just 2 bytes, state data follows
     *   $E5EB: Event set     — 2 + 1 (event byte) + 2 (ptr) = 5 bytes
     *   $E5FF: Power bombs   — 2 + 2 (ptr) = 4 bytes
     *   $E612: Boss defeated  — 2 + 1 (boss byte) + 2 (ptr) = 5 bytes  
     *   $E629: Morph ball     — 2 + 2 (ptr) = 4 bytes
     *   $E640: Morph+missiles — 2 + 2 (ptr) = 4 bytes
     *   $E652: Power bombs    — 2 + 2 (ptr) = 4 bytes
     *   $E669: Event set      — 2 + 1 (event byte) + 2 (ptr) = 5 bytes
     *   $E678: Boss bits      — 2 + 1 (boss byte) + 2 (ptr) = 5 bytes
     */
    private fun findDefaultStateData(stateListOffset: Int): Int? {
        var offset = stateListOffset
        val maxOffset = stateListOffset + 200
        
        while (offset + 2 <= romData.size && offset < maxOffset) {
            val condition = readUInt16At(offset)
            
            if (condition == 0xE5E6) {
                // Default state — 26 bytes of state data follow after the 2-byte marker
                return offset + 2
            }
            
            // Determine entry size based on condition code
            val entrySize = when (condition) {
                // Sizes verified against SM ROM data:
                0xE5EB -> 5  // Door event: condition(2) + door_byte(1) + ptr(2)
                0xE5FF -> 4  // condition(2) + ptr(2)
                0xE612 -> 5  // Boss: condition(2) + boss_byte(1) + ptr(2)
                0xE629 -> 4  // condition(2) + ptr(2)
                0xE640 -> 4  // condition(2) + ptr(2)
                0xE652 -> 4  // condition(2) + ptr(2)
                0xE669 -> 4  // Event flag: condition(2) + ptr(2) (NO extra param)
                0xE678 -> 4  // Boss bits: condition(2) + ptr(2) (NO extra param)
                else -> {
                    // Unknown condition — can't safely parse further
                    // Fall back: scan remaining bytes for $E5E6 at 2-byte aligned offsets
                    for (scanOffset in (offset + 2) until minOf(maxOffset, romData.size - 1) step 2) {
                        if (readUInt16At(scanOffset) == 0xE5E6) {
                            return scanOffset + 2
                        }
                    }
                    return null
                }
            }
            
            offset += entrySize
        }
        
        return null
    }
    
    // ─── LZ2 Decompression ────────────────────────────────────────────
    
    /**
     * Decompress data at the given SNES address using Super Metroid's
     * LZ2 compression format.
     * 
     * Format:
     *   0xFF = end of data
     *   Bits 7-5 of header byte = command type (0-6)
     *   Bits 4-0 = length - 1  (length 1..32)
     *   
     *   If command type = 7 (extended header):
     *     Byte 1 bits 4-2 = actual command type
     *     ((byte1 & 3) << 8) | byte2 + 1 = length (up to 1024)
     *   
     * Commands:
     *   0: Direct copy (copy next `len` bytes verbatim)
     *   1: Byte fill  (repeat 1 byte `len` times)
     *   2: Word fill  (alternate 2 bytes for `len` bytes)
     *   3: Increment  (byte increments by 1 each time, `len` bytes)
     *   4: Dictionary (copy `len` bytes from earlier output at offset)
     *   5: XOR dict   (copy from earlier output, XOR each byte with 0xFF)
     *   6: Minus dict (copy from earlier output, reading backwards)
     */
    fun decompressLZ2(snesAddress: Int): ByteArray {
        val startPc = snesToPc(snesAddress)
        return decompressLZ2AtPc(startPc)
    }
    
    fun decompressLZ2AtPc(startPc: Int): ByteArray {
        val output = mutableListOf<Byte>()
        var pos = startPc
        
        while (pos < romData.size) {
            val header = romData[pos].toInt() and 0xFF
            pos++
            
            if (header == 0xFF) break  // End of compressed data
            
            var cmdType = (header shr 5) and 0x07
            var length: Int
            
            if (cmdType == 7) {
                // Extended header: 2-byte header
                cmdType = (header shr 2) and 0x07
                if (pos >= romData.size) break
                val byte2 = romData[pos].toInt() and 0xFF
                pos++
                length = ((header and 0x03) shl 8) or byte2
                length += 1
            } else {
                length = (header and 0x1F) + 1
            }
            
            when (cmdType) {
                0 -> {
                    // Direct copy
                    for (i in 0 until length) {
                        if (pos >= romData.size) break
                        output.add(romData[pos])
                        pos++
                    }
                }
                1 -> {
                    // Byte fill
                    if (pos >= romData.size) break
                    val fillByte = romData[pos]
                    pos++
                    repeat(length) { output.add(fillByte) }
                }
                2 -> {
                    // Word fill (alternate 2 bytes)
                    if (pos + 1 >= romData.size) break
                    val b1 = romData[pos]
                    val b2 = romData[pos + 1]
                    pos += 2
                    for (i in 0 until length) {
                        output.add(if (i % 2 == 0) b1 else b2)
                    }
                }
                3 -> {
                    // Increment fill
                    if (pos >= romData.size) break
                    var fillByte = romData[pos].toInt() and 0xFF
                    pos++
                    for (i in 0 until length) {
                        output.add((fillByte and 0xFF).toByte())
                        fillByte++
                    }
                }
                4 -> {
                    // Dictionary copy (from earlier in output)
                    if (pos + 1 >= romData.size) break
                    val offset = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    for (i in 0 until length) {
                        val srcIdx = offset + i
                        if (srcIdx < output.size) {
                            output.add(output[srcIdx])
                        } else {
                            output.add(0)
                        }
                    }
                }
                5 -> {
                    // XOR dictionary copy
                    if (pos + 1 >= romData.size) break
                    val offset = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    for (i in 0 until length) {
                        val srcIdx = offset + i
                        if (srcIdx < output.size) {
                            output.add((output[srcIdx].toInt() xor 0xFF).toByte())
                        } else {
                            output.add(0xFF.toByte())
                        }
                    }
                }
                6 -> {
                    // Backwards dictionary copy
                    if (pos + 1 >= romData.size) break
                    val offset = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    for (i in 0 until length) {
                        val srcIdx = offset - i
                        if (srcIdx >= 0 && srcIdx < output.size) {
                            output.add(output[srcIdx])
                        } else {
                            output.add(0)
                        }
                    }
                }
            }
            
            // Safety limit
            if (output.size > 0x20000) break  // 128KB max
        }
        
        return output.toByteArray()
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
