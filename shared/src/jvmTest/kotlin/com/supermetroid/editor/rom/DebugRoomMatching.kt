package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
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
        
        println("=== Debugging Room ID 0x${roomId.toString(16)} ===")
        println("Room ID bank: 0x${((roomId shr 16) and 0xFF).toString(16)}")
        println("Room ID address: 0x${(roomId and 0xFFFF).toString(16)}")
        println()
        
        // Get all rooms
        val matcher = RoomMatcher(parser)
        val allRooms = matcher.getAllRooms()
        println("Total valid rooms: ${allRooms.size}")
        println()
        
        // Check first 20 rooms
        println("First 20 rooms:")
        allRooms.take(20).forEachIndexed { tableIdx, room ->
            val bgDataFull = 0x8F0000 + room.bgData
            val roomStateFull = 0x8F0000 + room.roomState
            
            println("  Table[$tableIdx] Index=${room.index}, Area=${room.area}, ${room.width}x${room.height}")
            println("    bgData=0x${bgDataFull.toString(16)}, roomState=0x${roomStateFull.toString(16)}")
            
            // Check if room ID matches
            if (bgDataFull == roomId || roomStateFull == roomId) {
                println("    *** MATCH FOUND! ***")
            }
            println()
        }
        
        // Try to find by room index
        println("Checking if room ID is a room index:")
        val roomByIndex = parser.readRoomHeaderByIndex(roomId)
        if (roomByIndex != null) {
            println("  Found room at table index $roomId: Index=${roomByIndex.index}, Area=${roomByIndex.area}")
        } else {
            println("  No room at table index $roomId")
        }
        println()
        
        // Check if room ID matches any room's index field
        println("Checking if room ID matches any room's 'index' field:")
        allRooms.forEachIndexed { tableIdx, room ->
            if (room.index == roomId) {
                println("  Match! Table[$tableIdx] has index=${room.index}")
            }
        }
    }
}
