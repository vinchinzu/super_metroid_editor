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
     * Read a room header from the ROM
     * Room headers table starts at 0x8F0000
     * Each room header is 38 bytes
     */
    fun readRoomHeader(roomId: Int): Room? {
        // Room headers are stored in a table
        // Each room has an index, and we need to find the room by ID
        // For now, we'll search through the room headers table
        
        val roomHeadersTableOffset = snesToPc(0x8F0000)
        val maxRooms = 200 // Approximate max rooms
        
        for (i in 0 until maxRooms) {
            val headerOffset = roomHeadersTableOffset + (i * 38)
            
            // Check bounds before reading
            if (headerOffset < 0 || headerOffset + 38 > romData.size) {
                break
            }
            
            // Ensure we have enough data
            if (romData.size < headerOffset + 38) {
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
                
                val doors = buffer.short.toInt() and 0xFFFF
                val roomState = buffer.short.toInt() and 0xFFFF
                val roomDown = buffer.short.toInt() and 0xFFFF
                val roomUp = buffer.short.toInt() and 0xFFFF
                val roomLeft = buffer.short.toInt() and 0xFFFF
                val roomRight = buffer.short.toInt() and 0xFFFF
                val roomDownScroll = buffer.short.toInt() and 0xFFFF
                val roomUpScroll = buffer.short.toInt() and 0xFFFF
                val roomLeftScroll = buffer.short.toInt() and 0xFFFF
                val roomRightScroll = buffer.short.toInt() and 0xFFFF
                val unused1 = buffer.short.toInt() and 0xFFFF
                val mainAsm = buffer.short.toInt() and 0xFFFF
                val plmSet = buffer.short.toInt() and 0xFFFF
                val bgData = buffer.short.toInt() and 0xFFFF
                val roomSetupAsm = buffer.short.toInt() and 0xFFFF
                
                // Validate room data - skip invalid rooms (all zeros or invalid values)
                if (width == 0 || height == 0 || width > 16 || height > 16) {
                    continue
                }
                
                // For v1, return the first valid room we find
                // TODO: Implement proper room ID matching using room pointers
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
            } catch (e: java.nio.BufferUnderflowException) {
                // Skip this room if we can't read it
                continue
            }
        }
        
        return null
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
