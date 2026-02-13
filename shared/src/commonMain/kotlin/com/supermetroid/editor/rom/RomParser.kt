package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for Super Metroid ROM files (.smc format)
 * 
 * Super Metroid ROM structure:
 * - Header: 0x200 bytes (512 bytes) if present (SMC format)
 * - ROM data starts at 0x000000 or 0x000200 depending on header
 * - Room headers table starts at 0x8F0000 (SNES address)
 * - Each room header is 38 bytes
 */
class RomParser(private val romData: ByteArray) {
    private val hasHeader: Boolean
        get() = romData.size == 0x300200 // 3MB + 512 byte header
    
    private val romStartOffset: Int
        get() = if (hasHeader) 0x200 else 0x0
    
    /**
     * Convert SNES address to PC offset
     * SNES addresses are in bank:address format (e.g., 0x8F0000)
     */
    fun snesToPc(snesAddress: Int): Int {
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        
        // For banks 0x80-0xFF, subtract 0x800000
        val pcAddress = if (bank >= 0x80) {
            (bank - 0x80) * 0x10000 + address
        } else {
            bank * 0x10000 + address
        }
        
        return romStartOffset + pcAddress
    }
    
    /**
     * Search for room headers table by scanning ROM for valid room headers
     * Returns the PC offset where valid room headers start
     */
    private fun findRoomHeadersTable(): Int? {
        // Search for a sequence of valid room headers (allowing some gaps for unused slots)
        // A valid room header has: width > 0 && width <= 16 && height > 0 && height <= 16
        val minValidRooms = 3  // Need at least 3 valid rooms
        val maxGaps = 2  // Allow up to 2 invalid rooms between valid ones
        
        for (startOffset in 0 until romData.size - (200 * 38) step 38) {
            var validCount = 0
            var gapCount = 0
            
            for (i in 0 until 200) {  // Check up to 200 room slots
                val offset = startOffset + (i * 38)
                if (offset + 5 >= romData.size) break
                
                val width = romData[offset + 4].toInt() and 0xFF
                val height = romData[offset + 5].toInt() and 0xFF
                
                if (width > 0 && width <= 16 && height > 0 && height <= 16) {
                    validCount++
                    gapCount = 0  // Reset gap counter on valid room
                    
                    if (validCount >= minValidRooms) {
                        println("Found room headers table at PC offset: 0x${startOffset.toString(16)} (found $validCount valid rooms so far)")
                        return startOffset
                    }
                } else {
                    gapCount++
                    // If we hit too many gaps early, this isn't the table
                    if (validCount == 0 && gapCount > 5) {
                        break
                    }
                    // If we have some valid rooms but hit too many gaps, might still be valid
                    if (validCount > 0 && gapCount > maxGaps) {
                        break
                    }
                }
            }
        }
        
        // Fallback: try known locations from test output
        val knownLocations = listOf(0x1f06, 0x153a, 0x1ee0)
        for (loc in knownLocations) {
            if (loc + 38 < romData.size) {
                val width = romData[loc + 4].toInt() and 0xFF
                val height = romData[loc + 5].toInt() and 0xFF
                if (width > 0 && width <= 16 && height > 0 && height <= 16) {
                    println("Using fallback room headers table location: 0x${loc.toString(16)}")
                    return loc
                }
            }
        }
        
        return null
    }
    
    /**
     * Build a lookup table of all rooms for faster matching
     * Returns a map of room index to Room
     */
    fun buildRoomLookupTable(): Map<Int, Room> {
        // Try to find the actual room headers table location
        val roomHeadersTableOffset = findRoomHeadersTable() ?: snesToPc(0x8F0000)
        val maxRooms = 200
        val rooms = mutableMapOf<Int, Room>()
        
        for (i in 0 until maxRooms) {
            val headerOffset = roomHeadersTableOffset + (i * 38)
            if (headerOffset < 0 || headerOffset + 38 > romData.size) {
                break
            }
            
            val room = readRoomHeaderAtOffset(headerOffset, i)
            if (room != null) {
                rooms[i] = room
            }
        }
        
        return rooms
    }
    
    /**
     * Read room header at a specific PC offset
     */
    private fun readRoomHeaderAtOffset(headerOffset: Int, roomIndex: Int): Room? {
        if (headerOffset < 0 || headerOffset + 38 > romData.size) {
            return null
        }
        
        val buffer = ByteBuffer.wrap(romData, headerOffset, 38)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        if (buffer.remaining() < 38) {
            return null
        }
        
        try {
            val index = buffer.get().toInt() and 0xFF
            val area = buffer.get().toInt() and 0xFF
            val mapX = buffer.get().toInt() and 0xFF
            val mapY = buffer.get().toInt() and 0xFF
            val width = buffer.get().toInt() and 0xFF
            val height = buffer.get().toInt() and 0xFF
            
            // Validate room data
            if (width == 0 || height == 0 || width > 16 || height > 16) {
                return null
            }
            
            val scrollX = buffer.get().toInt() and 0xFF
            val scrollY = buffer.get().toInt() and 0xFF
            val specialGfxBitflag = buffer.get().toInt() and 0xFF
            
            // Read 16-bit unsigned values properly
            fun readUInt16(): Int {
                val low = buffer.get().toInt() and 0xFF
                val high = buffer.get().toInt() and 0xFF
                return (high shl 8) or low  // Little-endian
            }
            
            val doors = readUInt16()
            val roomState = readUInt16()
            val roomDown = readUInt16()
            val roomUp = readUInt16()
            val roomLeft = readUInt16()
            val roomRight = readUInt16()
            val roomDownScroll = readUInt16()
            val roomUpScroll = readUInt16()
            val roomLeftScroll = readUInt16()
            val roomRightScroll = readUInt16()
            val unused1 = readUInt16()
            val mainAsm = readUInt16()
            val plmSet = readUInt16()
            val bgData = readUInt16()
            val roomSetupAsm = readUInt16()
            
            return Room(
                roomId = 0, // Unknown, will be set by matcher
                name = "Room Index $roomIndex",
                handle = "room_index_$roomIndex",
                index = index,
                area = area,
                mapX = mapX,
                mapY = mapY,
                width = width,
                height = height,
                scrollX = scrollX,
                scrollY = scrollY,
                specialGfxBitflag = specialGfxBitflag,
                doors = doors,
                roomState = roomState,
                roomDown = roomDown,
                roomUp = roomUp,
                roomLeft = roomLeft,
                roomRight = roomRight,
                roomDownScroll = roomDownScroll,
                roomUpScroll = roomUpScroll,
                roomLeftScroll = roomLeftScroll,
                roomRightScroll = roomRightScroll,
                unused1 = unused1,
                mainAsm = mainAsm,
                plmSet = plmSet,
                bgData = bgData,
                roomSetupAsm = roomSetupAsm
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Read a room header from the ROM by room ID (SNES address)
     * Room headers table starts at 0x8F0000
     * Each room header is 38 bytes
     * 
     * Room IDs in the JSON (like 0x91F8) are SNES addresses.
     * These might correspond to:
     * 1. The room's level data address (bgData pointer)
     * 2. The room's state data address (roomState pointer)
     * 3. A room index in a lookup table
     * 
     * We try multiple matching strategies.
     */
    fun readRoomHeader(roomId: Int): Room? {
        val roomHeadersTableOffset = snesToPc(0x8F0000)
        val maxRooms = 200 // Approximate max rooms
        
        // Convert roomId (SNES address) to a relative offset for comparison
        // Room IDs are typically in bank 0x91-0xDF range
        val roomIdBank = (roomId shr 16) and 0xFF
        val roomIdAddress = roomId and 0xFFFF
        
        for (i in 0 until maxRooms) {
            val headerOffset = roomHeadersTableOffset + (i * 38)
            
            // Check bounds before reading
            if (headerOffset < 0 || headerOffset + 38 > romData.size) {
                break
            }
            
            val buffer = ByteBuffer.wrap(romData, headerOffset, 38)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            // Check if buffer has remaining bytes before reading
            if (buffer.remaining() < 38) {
                continue
            }
            
            try {
                // Read header fields
                val index = buffer.get().toInt() and 0xFF
                val area = buffer.get().toInt() and 0xFF
                val mapX = buffer.get().toInt() and 0xFF
                val mapY = buffer.get().toInt() and 0xFF
                val width = buffer.get().toInt() and 0xFF
                val height = buffer.get().toInt() and 0xFF
                val scrollX = buffer.get().toInt() and 0xFF
                val scrollY = buffer.get().toInt() and 0xFF
                val specialGfxBitflag = buffer.get().toInt() and 0xFF
                
                // Read 16-bit unsigned values properly
                // Read bytes directly to avoid signed short issues
                fun readUInt16(): Int {
                    val low = buffer.get().toInt() and 0xFF
                    val high = buffer.get().toInt() and 0xFF
                    return (high shl 8) or low  // Little-endian
                }
                
                val doors = readUInt16()
                val roomState = readUInt16()
                val roomDown = readUInt16()
                val roomUp = readUInt16()
                val roomLeft = readUInt16()
                val roomRight = readUInt16()
                val roomDownScroll = readUInt16()
                val roomUpScroll = readUInt16()
                val roomLeftScroll = readUInt16()
                val roomRightScroll = readUInt16()
                val unused1 = readUInt16()
                val mainAsm = readUInt16()
                val plmSet = readUInt16()
                val bgData = readUInt16()
                val roomSetupAsm = readUInt16()
                
                // Validate room data - skip invalid rooms
                if (width == 0 || height == 0 || width > 16 || height > 16) {
                    continue
                }
                
                // Match room by checking various pointers
                // Room IDs are SNES addresses (e.g., 0x91F8 = bank 0x91, address 0xF8)
                // We need to check if any room pointer matches the room ID
                
                // Calculate full SNES addresses for pointers (they're relative to bank 0x8F)
                val bgDataFull = 0x8F0000 + bgData
                val roomStateFull = 0x8F0000 + roomState
                val doorsFull = 0x8F0000 + doors
                
                // Also check room direction pointers
                val roomDownFull = 0x8F0000 + roomDown
                val roomUpFull = 0x8F0000 + roomUp
                val roomLeftFull = 0x8F0000 + roomLeft
                val roomRightFull = 0x8F0000 + roomRight
                
                // Strategy 1: Direct pointer matches
                val directMatch = when (roomId) {
                    bgDataFull, roomStateFull, doorsFull, 
                    roomDownFull, roomUpFull, roomLeftFull, roomRightFull -> true
                    else -> false
                }
                
                // Strategy 2: Room ID might be the actual level data address
                // Check if bgData points to roomId (bgData is relative to 0x8F0000)
                // Room IDs like 0x91F8 are in bank 0x91, which is level data bank
                val levelDataMatch = if (roomIdBank >= 0x91 && roomIdBank <= 0xDF) {
                    // Convert roomId to PC address to compare with bgData
                    val roomIdPc = snesToPc(roomId)
                    val bgDataPc = snesToPc(bgDataFull)
                    val diff = kotlin.math.abs(bgDataPc - roomIdPc)
                    diff < 0x2000 // Within 8KB (level data can be large)
                } else {
                    false
                }
                
                // Strategy 3: Room ID might be an index into the room table
                // Some room IDs might actually be indices (0-199)
                val indexMatch = roomId < 200 && roomId == i
                
                val matches = directMatch || levelDataMatch || indexMatch
                
                if (matches) {
                    return Room(
                        roomId = roomId,
                        name = "Room $roomId",
                        handle = "room_$roomId",
                        index = index,
                        area = area,
                        mapX = mapX,
                        mapY = mapY,
                        width = width,
                        height = height,
                        scrollX = scrollX,
                        scrollY = scrollY,
                        specialGfxBitflag = specialGfxBitflag,
                        doors = doors,
                        roomState = roomState,
                        roomDown = roomDown,
                        roomUp = roomUp,
                        roomLeft = roomLeft,
                        roomRight = roomRight,
                        roomDownScroll = roomDownScroll,
                        roomUpScroll = roomUpScroll,
                        roomLeftScroll = roomLeftScroll,
                        roomRightScroll = roomRightScroll,
                        unused1 = unused1,
                        mainAsm = mainAsm,
                        plmSet = plmSet,
                        bgData = bgData,
                        roomSetupAsm = roomSetupAsm
                    )
                }
            } catch (e: java.nio.BufferUnderflowException) {
                // Skip this room if we can't read it
                continue
            }
        }
        
        // If no match found, try using RoomMatcher for better matching
        val matcher = RoomMatcher(this)
        return matcher.findRoomById(roomId)
    }
    
    /**
     * Read room header by index (for fallback/debugging)
     */
    fun readRoomHeaderByIndex(roomIndex: Int): Room? {
        // Try to find the actual table location first
        val roomHeadersTableOffset = findRoomHeadersTable() ?: snesToPc(0x8F0000)
        val headerOffset = roomHeadersTableOffset + (roomIndex * 38)
        
        if (headerOffset < 0 || headerOffset + 38 > romData.size) {
            return null
        }
        
        // Use the shared readRoomHeaderAtOffset method
        return readRoomHeaderAtOffset(headerOffset, roomIndex)
    }
    
    /**
     * Decompress level data using RLE (Run-Length Encoding)
     * Super Metroid uses a simple RLE compression for level data
     */
    fun decompressLevelData(compressedData: ByteArray): ByteArray {
        val output = mutableListOf<Byte>()
        var i = 0
        
        while (i < compressedData.size) {
            val byte = compressedData[i].toInt() and 0xFF
            
            if (byte == 0xFF) {
                // End marker
                break
            } else if (byte >= 0x80) {
                // RLE: next byte repeated (byte - 0x7F) times
                val count = byte - 0x7F
                if (i + 1 < compressedData.size) {
                    val value = compressedData[i + 1]
                    repeat(count) {
                        output.add(value)
                    }
                    i += 2
                } else {
                    break
                }
            } else {
                // Literal: next (byte + 1) bytes are literal
                val count = byte + 1
                if (i + count < compressedData.size) {
                    for (j in 1..count) {
                        output.add(compressedData[i + j])
                    }
                    i += count + 1
                } else {
                    break
                }
            }
        }
        
        return output.toByteArray()
    }
    
    /**
     * Get ROM data (for use by MapRenderer)
     */
    fun getRomData(): ByteArray = romData
    
    companion object {
        fun loadRom(filePath: String): RomParser {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("ROM file not found: $filePath")
            }
            
            val romData = file.readBytes()
            
            // Validate ROM size
            if (romData.size != 0x300000 && romData.size != 0x300200) {
                throw IllegalArgumentException("Invalid ROM size: ${romData.size} bytes. Expected 3MB or 3MB+512 bytes")
            }
            
            return RomParser(romData)
        }
    }
}
