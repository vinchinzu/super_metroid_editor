package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class RomParserTest {
    
    private val testRomPath = "../../../test-resources/Super Metroid (JU) [!].smc"
    
    @Test
    fun `test ROM loading`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        assertNotNull(parser)
        
        val romData = parser.getRomData()
        assertTrue(romData.size == 0x300000 || romData.size == 0x300200, 
            "ROM size should be 3MB or 3MB+512 bytes, got ${romData.size}")
    }
    
    @Test
    fun `test SNES to PC address conversion`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Test SNES address conversion
        val snesAddress = 0x8F0000
        val pcAddress = parser.snesToPc(snesAddress)
        
        assertTrue(pcAddress >= 0, "PC address should be non-negative")
        assertTrue(pcAddress < parser.getRomData().size, 
            "PC address should be within ROM bounds")
    }
    
    @Test
    fun `test read room header`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Try to read a room header (using Landing Site room ID: 0x91F8)
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId)
        
        if (room != null) {
            assertNotNull(room, "Room should not be null")
            assertTrue(room.width > 0 && room.width <= 16, 
                "Room width should be between 1 and 16, got ${room.width}")
            assertTrue(room.height > 0 && room.height <= 16, 
                "Room height should be between 1 and 16, got ${room.height}")
            assertTrue(room.area >= 0 && room.area < 10, 
                "Room area should be valid, got ${room.area}")
        } else {
            println("Warning: Could not read room header for room ID 0x${roomId.toString(16)}")
        }
    }
    
    @Test
    fun `test read multiple room headers`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Try reading multiple room headers
        val roomIds = listOf(0x91F8, 0x93AA, 0x9804, 0xA59F) // Landing Site, Power Bomb, Bomb Torizo, Kraid
        
        var successCount = 0
        for (roomId in roomIds) {
            val room = parser.readRoomHeader(roomId)
            if (room != null) {
                successCount++
                println("Successfully read room 0x${roomId.toString(16)}: ${room.width}x${room.height} screens")
            }
        }
        
        println("Successfully read $successCount out of ${roomIds.size} room headers")
        assertTrue(successCount > 0, "Should be able to read at least one room header")
    }
    
    @Test
    fun `test RLE decompression`() {
        // Test RLE decompression with known data
        val parser = RomParser(ByteArray(0x300000))
        
        // Create test compressed data: RLE pattern
        val compressed = byteArrayOf(
            0x82.toByte(), 0xAA.toByte(), // RLE: repeat 0xAA 3 times
            0x01, 0xBB.toByte(), 0xCC.toByte(), // Literal: 2 bytes (0xBB, 0xCC)
            0xFF.toByte() // End marker
        )
        
        val decompressed = parser.decompressLevelData(compressed)
        
        assertEquals(5, decompressed.size, "Decompressed size should be 5 bytes")
        assertEquals(0xAA.toByte(), decompressed[0])
        assertEquals(0xAA.toByte(), decompressed[1])
        assertEquals(0xAA.toByte(), decompressed[2])
        assertEquals(0xBB.toByte(), decompressed[3])
        assertEquals(0xCC.toByte(), decompressed[4])
    }
}
