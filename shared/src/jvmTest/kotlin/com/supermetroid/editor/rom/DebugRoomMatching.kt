package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Debug script to understand room ID matching
 */
class DebugRoomMatching {
    
    @Test
    fun `debug room matching for 0x91F8`() {
        val testRomPath = "../../../test-resources/Super Metroid (JU) [!].smc"
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("ROM not found, skipping")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        val roomId = 0x91F8
        
        System.out.println("=== Debugging Room ID 0x${roomId.toString(16)} ===")
        System.out.println("Room ID bank: 0x${((roomId shr 16) and 0xFF).toString(16)}")
        System.out.println("Room ID address: 0x${(roomId and 0xFFFF).toString(16)}")
        System.out.println()
        
        // Get all rooms
        val matcher = RoomMatcher(parser)
        val allRooms = matcher.getAllRooms()
        System.out.println("Total valid rooms: ${allRooms.size}")
        System.out.println()
        
        // Check first 20 rooms
        System.out.println("First 20 rooms:")
        allRooms.take(20).forEachIndexed { tableIdx, room ->
            val bgDataFull = 0x8F0000 + room.bgData
            val roomStateFull = 0x8F0000 + room.roomState
            
            System.out.println("  Table[$tableIdx] Index=${room.index}, Area=${room.area}, ${room.width}x${room.height}")
            System.out.println("    bgData=0x${bgDataFull.toString(16)}, roomState=0x${roomStateFull.toString(16)}")
            
            // Check if room ID matches
            if (bgDataFull == roomId || roomStateFull == roomId) {
                System.out.println("    *** MATCH FOUND! ***")
            }
            System.out.println()
        }
        
        // Try to find by room index
        System.out.println("Checking if room ID is a room index:")
        val roomByIndex = parser.readRoomHeaderByIndex(roomId)
        if (roomByIndex != null) {
            System.out.println("  Found room at table index $roomId: Index=${roomByIndex.index}, Area=${roomByIndex.area}")
        } else {
            System.out.println("  No room at table index $roomId")
        }
        System.out.println()
        
        // Check if room ID matches any room's index field
        System.out.println("Checking if room ID matches any room's 'index' field:")
        var foundByIndex = false
        allRooms.forEachIndexed { tableIdx, room ->
            if (room.index == roomId) {
                System.out.println("  Match! Table[$tableIdx] has index=${room.index}")
                foundByIndex = true
            }
        }
        if (!foundByIndex) {
            System.out.println("  No match found by index field")
        }
        
        // Try the actual matcher
        System.out.println("\n=== Using RoomMatcher.findRoomById ===")
        val matchedRoom = matcher.findRoomById(roomId)
        if (matchedRoom != null) {
            System.out.println("  ✓ FOUND by matcher!")
            System.out.println("    Index=${matchedRoom.index}, Area=${matchedRoom.area}, Size=${matchedRoom.width}x${matchedRoom.height}")
        } else {
            System.out.println("  ✗ NOT FOUND by matcher")
        }
        
        // Assertions for test framework - include data in assertion message
        val roomInfo = if (allRooms.isNotEmpty()) {
            val firstRoom = allRooms.first()
            "Found ${allRooms.size} rooms. First room: Index=${firstRoom.index}, Area=${firstRoom.area}, bgData=0x${(0x8F0000 + firstRoom.bgData).toString(16)}"
        } else {
            "No rooms found!"
        }
        
        assertTrue(allRooms.isNotEmpty(), roomInfo)
        
        // Also assert about the specific room we're looking for
        val foundRoom = matchedRoom
        val matchInfo = if (foundRoom != null) {
            "Room 0x${roomId.toString(16)} FOUND: Index=${foundRoom.index}, Area=${foundRoom.area}"
        } else {
            "Room 0x${roomId.toString(16)} NOT FOUND. First 5 rooms: ${allRooms.take(5).joinToString { "Table[${it.index}]=0x${(0x8F0000 + it.bgData).toString(16)}" }}"
        }
        
        // Don't fail the test, but print the info
        System.out.println("\n=== Test completed ===")
        System.out.println(roomInfo)
        System.out.println(matchInfo)
        
        // For now, just verify we can read rooms (don't fail on matching)
        assertTrue(allRooms.isNotEmpty(), "Should be able to read rooms from ROM")
        
        // Force output by failing with diagnostic info if room not found
        if (matchedRoom == null) {
            val diagnostic = """
                Room 0x${roomId.toString(16)} NOT FOUND.
                Total rooms: ${allRooms.size}
                First 10 rooms:
                ${allRooms.take(10).joinToString("\n") { 
                    "  Table[${it.index}]: index=${it.index}, area=${it.area}, bgData=0x${(0x8F0000 + it.bgData).toString(16)}, roomState=0x${(0x8F0000 + it.roomState).toString(16)}"
                }}
            """.trimIndent()
            // Don't fail, just print
            System.err.println(diagnostic)
        }
    }
}
