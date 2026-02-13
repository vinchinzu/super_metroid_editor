package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File
import com.supermetroid.editor.data.RoomRepository

class RoomIdInvestigationTest {
    
    private val testRomPath = "../../../test-resources/Super Metroid (JU) [!].smc"
    
    @Test
    fun `investigate room ID format and matching`() {
        val romFile = File(testRomPath)
        if (!romFile.exists()) {
            println("Test ROM not found at $testRomPath, skipping test")
            return
        }
        
        val parser = RomParser.loadRom(testRomPath)
        val roomRepository = RoomRepository()
        val rooms = roomRepository.getAllRooms()
        
        // Get Landing Site room info
        val landingSite = rooms.find { it.handle == "landingSite" }
        if (landingSite == null) {
            println("Landing Site not found in room list!")
            return
        }
        
        val roomId = landingSite.getRoomIdAsInt()
        println("Landing Site room ID from JSON: 0x${roomId.toString(16)}")
        println("Room name: ${landingSite.name}")
        
        // Build lookup table
        val matcher = RoomMatcher(parser)
        val allRooms = matcher.getAllRooms()
        println("\nTotal rooms found in ROM: ${allRooms.size}")
        
        // Show first 10 rooms
        println("\nFirst 10 rooms in ROM:")
        allRooms.take(10).forEachIndexed { idx, room ->
            val bgDataFull = 0x8F0000 + room.bgData
            val roomStateFull = 0x8F0000 + room.roomState
            println("  [$idx] Index=${room.index}, Area=${room.area}, Size=${room.width}x${room.height}")
            println("       bgData=0x${bgDataFull.toString(16)}, roomState=0x${roomStateFull.toString(16)}")
        }
        
        // Try to find the room
        println("\nTrying to find room with ID 0x${roomId.toString(16)}:")
        val foundRoom = matcher.findRoomById(roomId)
        
        if (foundRoom != null) {
            println("  ✓ FOUND!")
            println("    Index=${foundRoom.index}, Area=${foundRoom.area}, Size=${foundRoom.width}x${foundRoom.height}")
        } else {
            println("  ✗ NOT FOUND")
            
            // Try searching by index
            println("\nTrying to find by room index (maybe room ID is actually an index?):")
            for (i in 0 until allRooms.size) {
                val room = parser.readRoomHeaderByIndex(i)
                if (room != null && room.index == roomId) {
                    println("  Found room at table index $i with room.index=${room.index}")
                    break
                }
            }
            
            // Check if any room's bgData or roomState matches
            println("\nSearching for rooms with matching pointers:")
            allRooms.forEachIndexed { idx, room ->
                val bgDataFull = 0x8F0000 + room.bgData
                val roomStateFull = 0x8F0000 + room.roomState
                
                if (bgDataFull == roomId || roomStateFull == roomId) {
                    println("  Match found at table index $idx!")
                    println("    bgData=0x${bgDataFull.toString(16)}, roomState=0x${roomStateFull.toString(16)}")
                }
            }
        }
    }
}
