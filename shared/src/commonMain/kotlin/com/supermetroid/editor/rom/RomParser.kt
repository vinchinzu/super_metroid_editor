package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * 
 * LoROM SNES-to-PC conversion:
 *   PC offset = ((bank & 0x7F) * 0x8000) + (address - 0x8000)
 */
class RomParser(private val romData: ByteArray) {
    private val hasHeader: Boolean
        get() = romData.size == 0x300200 // 3MB + 512 byte header
    
    private val romStartOffset: Int
        get() = if (hasHeader) 0x200 else 0x0
    
    /**
     * Convert SNES address to PC offset using LoROM mapping.
     * 
     * LoROM: banks $00-$7F and $80-$FF, addresses $8000-$FFFF map to ROM.
     * PC offset = ((bank & 0x7F) * 0x8000) + (address & 0x7FFF)
     */
    fun snesToPc(snesAddress: Int): Int {
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        
        // LoROM mapping
        val pcAddress = ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
        
        return romStartOffset + pcAddress
    }
    
    /**
     * Convert a 16-bit room ID to a PC file offset.
     * Room IDs are pointers within SNES bank $8F.
     * Full SNES address = $8F:<roomId>
     */
    fun roomIdToPc(roomId: Int): Int {
        val snesAddress = 0x8F0000 or (roomId and 0xFFFF)
        return snesToPc(snesAddress)
    }
    
    /**
     * Read a room header directly from ROM using the room ID.
     * 
     * Room IDs (like 0x91F8) are offsets within SNES bank $8F.
     * The room header is at SNES address $8F:<roomId>.
     * 
     * Room header format (from Metroid Construction wiki):
     *   Byte 0:    Room index
     *   Byte 1:    Area (0=Crateria, 1=Brinstar, 2=Norfair, 3=Wrecked Ship, 4=Maridia, 5=Tourian, 6=Ceres)
     *   Byte 2:    Map X position
     *   Byte 3:    Map Y position
     *   Byte 4:    Width (in screens)
     *   Byte 5:    Height (in screens)
     *   Byte 6:    Up scroller
     *   Byte 7:    Down scroller
     *   Byte 8:    CRE bitflag / special graphics
     *   Bytes 9-10: Door out pointer (16-bit LE)
     *   Bytes 11+:  Room state entries (variable length)
     */
    fun readRoomHeader(roomId: Int): Room? {
        val pcOffset = roomIdToPc(roomId)
        
        // Bounds check — need at least 11 bytes for the fixed header
        if (pcOffset < 0 || pcOffset + 11 > romData.size) {
            println("Room 0x${roomId.toString(16)}: PC offset 0x${pcOffset.toString(16)} out of bounds")
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
            
            // Validate basic room properties
            if (width == 0 || height == 0 || width > 16 || height > 16) {
                println("Room 0x${roomId.toString(16)}: invalid dimensions ${width}x${height}")
                return null
            }
            if (area > 6) {
                println("Room 0x${roomId.toString(16)}: invalid area $area")
                return null
            }
            
            // Parse the first room state to get level data pointer and other info.
            // Room states start at pcOffset + 11 (after the door out pointer).
            // The state list ends with the default state (condition $E5E6).
            // Each state has: condition (2 bytes) + pointer (2 bytes) + optional args.
            // The default state ($E5E6) is followed directly by the state data (26 bytes).
            val stateDataOffset = findDefaultStateData(pcOffset + 11)
            
            // State data format (26 bytes):
            //   Bytes 0-2:  Level data pointer (3 bytes, 24-bit SNES address)
            //   Byte 3:     Tileset
            //   Byte 4:     Music data
            //   Byte 5:     Music track
            //   Bytes 6-7:  FX pointer
            //   Bytes 8-9:  Enemy set pointer
            //   Bytes 10-11: Enemy GFX pointer
            //   Bytes 12-13: Background scrolling
            //   Bytes 14-15: Room scrolls pointer
            //   Bytes 16-17: X-ray / unused
            //   Bytes 18-19: Main ASM pointer
            //   Bytes 20-21: PLM set pointer
            //   Bytes 22-23: BG data pointer
            //   Bytes 24-25: Setup ASM pointer
            
            var levelDataPtr = 0
            var tileset = 0
            var mainAsm = 0
            var plmSet = 0
            var bgData = 0
            var setupAsm = 0
            
            if (stateDataOffset != null && stateDataOffset + 26 <= romData.size) {
                // 3-byte level data pointer (little-endian)
                levelDataPtr = (romData[stateDataOffset].toInt() and 0xFF) or
                    ((romData[stateDataOffset + 1].toInt() and 0xFF) shl 8) or
                    ((romData[stateDataOffset + 2].toInt() and 0xFF) shl 16)
                tileset = romData[stateDataOffset + 3].toInt() and 0xFF
                mainAsm = readUInt16At(stateDataOffset + 18)
                plmSet = readUInt16At(stateDataOffset + 20)
                bgData = readUInt16At(stateDataOffset + 22)
                setupAsm = readUInt16At(stateDataOffset + 24)
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
                scrollX = upScroller,
                scrollY = downScroller,
                specialGfxBitflag = creBitflag,
                doors = doorOut,
                roomState = 0,      // Not a single field in the real format
                roomDown = 0,
                roomUp = 0,
                roomLeft = 0,
                roomRight = 0,
                roomDownScroll = 0,
                roomUpScroll = 0,
                roomLeftScroll = 0,
                roomRightScroll = 0,
                unused1 = 0,
                mainAsm = mainAsm,
                plmSet = plmSet,
                bgData = bgData,
                roomSetupAsm = setupAsm
            )
        } catch (e: Exception) {
            println("Room 0x${roomId.toString(16)}: exception ${e.message}")
            null
        }
    }
    
    /**
     * Find the default room state data offset.
     * Starting from the first state entry after the door out pointer,
     * scan for the $E5E6 marker which indicates the default state.
     * The state data immediately follows the marker.
     */
    private fun findDefaultStateData(stateListOffset: Int): Int? {
        var offset = stateListOffset
        val maxScan = 100  // Don't scan more than 100 bytes
        
        while (offset + 2 < romData.size && offset < stateListOffset + maxScan) {
            val word = readUInt16At(offset)
            
            if (word == 0xE5E6) {
                // Default state — state data follows immediately
                return offset + 2
            }
            
            // Each non-default state entry:
            //   $E5EB: 2 bytes condition + 2 bytes event param + 2 bytes state ptr = 6 bytes
            //   $E5FF: 2 bytes condition + 2 bytes state ptr = 4 bytes  
            //   $E612: 2 bytes condition + 2 bytes state ptr = 4 bytes
            //   $E629: 2 bytes condition + 2 bytes state ptr = 4 bytes
            //   Others: typically 2 bytes condition + 2 bytes state ptr = 4 bytes
            // Most state entries are 4 bytes (condition + pointer)
            // But some take extra args. We check for known multi-byte conditions.
            
            when (word) {
                0xE5EB -> offset += 6   // Event state: condition(2) + event(2) + ptr(2)
                0xE612 -> offset += 4   // Boss state: condition(2) + ptr(2)
                0xE629 -> offset += 4   // Morph state: condition(2) + ptr(2)
                0xE5FF -> offset += 4   // Powerbomb state: condition(2) + ptr(2)
                else -> offset += 4     // Unknown state type, assume 4 bytes
            }
        }
        
        // Didn't find $E5E6, try treating bytes at stateListOffset as state data directly
        // (some rooms may have the default state first)
        return null
    }
    
    /**
     * Read an unsigned 16-bit little-endian value at the given PC offset.
     */
    private fun readUInt16At(offset: Int): Int {
        val lo = romData[offset].toInt() and 0xFF
        val hi = romData[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
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
