package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class RomParserRoomMatchingTest {
    
    private val testRomPath = "../../../test-resources/Super Metroid (JU) [!].smc"
    
    @Test
    fun `test find Landing Site room by ID`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Landing Site room ID: 0x91F8
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId)
        
        assertNotNull(room, "Landing Site room (0x91F8) should be found")
        
        if (room != null) {
            println("Found room: ID=${room.roomId}, Index=${room.index}, Area=${room.area}, Size=${room.width}x${room.height}")
            println("  bgData=0x${room.bgData.toString(16)}, roomState=0x${room.roomState.toString(16)}")
            println("  doors=0x${room.doors.toString(16)}")
        }
    }
    
    @Test
    fun `test find multiple known rooms`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Test several known room IDs
        val testRooms = mapOf(
            0x91F8 to "Landing Site",
            0x93AA to "Crateria Power Bomb Room",
            0x9804 to "Bomb Torizo Room",
            0xA59F to "Kraid's Room"
        )
        
        var foundCount = 0
        for ((roomId, name) in testRooms) {
            val room = parser.readRoomHeader(roomId)
            if (room != null) {
                foundCount++
                println("✓ Found $name (0x${roomId.toString(16)}): ${room.width}x${room.height} screens")
            } else {
                println("✗ NOT FOUND: $name (0x${roomId.toString(16)})")
            }
        }
        
        println("\nFound $foundCount out of ${testRooms.size} rooms")
        assertTrue(foundCount > 0, "Should find at least one room")
    }
    
    @Test
    fun `test list all valid rooms in ROM`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        val roomHeadersTableOffset = parser.snesToPc(0x8F0000)
        val maxRooms = 200
        val validRooms = mutableListOf<Pair<Int, String>>()
        
        for (i in 0 until maxRooms) {
            val headerOffset = roomHeadersTableOffset + (i * 38)
            val romData = parser.getRomData()
            
            if (headerOffset < 0 || headerOffset + 38 > romData.size) {
                break
            }
            
            try {
                val room = parser.readRoomHeaderByIndex(i)
                if (room != null && room.width > 0 && room.height > 0) {
                    val bgDataFull = 0x8F0000 + room.bgData
                    val info = "Index $i: ${room.width}x${room.height}, Area ${room.area}, bgData=0x${bgDataFull.toString(16)}, roomState=0x${(0x8F0000 + room.roomState).toString(16)}"
                    validRooms.add(i to info)
                }
            } catch (e: Exception) {
                // Skip invalid rooms
            }
        }
        
        println("Found ${validRooms.size} valid rooms in ROM:")
        validRooms.take(20).forEach { (index, info) ->
            println("  $info")
        }
        if (validRooms.size > 20) {
            println("  ... and ${validRooms.size - 20} more")
        }
        
        assertTrue(validRooms.size > 0, "Should find at least some valid rooms")
    }
    
    @Test
    fun `test room ID to address mapping`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        
        // Test if room IDs are actually SNES addresses or something else
        val roomId = 0x91F8
        println("Testing room ID: 0x${roomId.toString(16)}")
        println("  Bank: 0x${((roomId shr 16) and 0xFF).toString(16)}")
        println("  Address: 0x${(roomId and 0xFFFF).toString(16)}")
        
        // Try to find room by checking all rooms
        val roomHeadersTableOffset = parser.snesToPc(0x8F0000)
        val maxRooms = 200
        
        for (i in 0 until maxRooms) {
            val headerOffset = roomHeadersTableOffset + (i * 38)
            val romData = parser.getRomData()
            
            if (headerOffset < 0 || headerOffset + 38 > romData.size) {
                break
            }
            
            try {
                val room = parser.readRoomHeaderByIndex(i)
                if (room != null) {
                    val bgDataFull = 0x8F0000 + room.bgData
                    val roomStateFull = 0x8F0000 + room.roomState
                    
                    // Check if any pointer matches
                    if (bgDataFull == roomId || roomStateFull == roomId) {
                        println("  Found match at index $i!")
                        println("    bgData=0x${bgDataFull.toString(16)}")
                        println("    roomState=0x${roomStateFull.toString(16)}")
                        return
                    }
                }
            } catch (e: Exception) {
                // Skip
            }
        }
        
        println("  No direct match found")
    }
}
