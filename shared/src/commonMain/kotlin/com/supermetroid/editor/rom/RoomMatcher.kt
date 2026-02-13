package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Helper class to match room IDs to actual rooms in the ROM
 * Uses multiple strategies since room ID format isn't fully documented
 */
class RoomMatcher(private val romParser: RomParser) {
    
    private var roomLookup: Map<Int, Room>? = null
    
    /**
     * Build lookup table of all rooms indexed by their table index
     */
    private fun buildLookup(): Map<Int, Room> {
        if (roomLookup == null) {
            roomLookup = romParser.buildRoomLookupTable()
        }
        return roomLookup!!
    }
    
    /**
     * Find room by room ID using multiple strategies
     */
    fun findRoomById(roomId: Int): Room? {
        val rooms = buildLookup()
        
        println("[RoomMatcher] Looking for room ID: 0x${roomId.toString(16)}")
        println("[RoomMatcher] Total rooms in lookup: ${rooms.size}")
        
        // Strategy 1: Check if room ID matches room's index field
        // Room IDs might actually be the room.index value
        for ((tableIndex, room) in rooms) {
            if (room.index == roomId) {
                println("[RoomMatcher] Found by room.index match at table[$tableIndex]")
                return room.copy(roomId = roomId)
            }
        }
        
        // Strategy 2: Try direct lookup using room ID as table index
        if (roomId < 200 && roomId >= 0) {
            val room = rooms[roomId]
            if (room != null) {
                println("[RoomMatcher] Found by table index lookup")
                return room.copy(roomId = roomId)
            }
        }
        
        // Strategy 3: Search all rooms for matching pointers
        println("[RoomMatcher] Searching by pointer matches...")
        for ((tableIndex, room) in rooms) {
            val bgDataFull = 0x8F0000 + room.bgData
            val roomStateFull = 0x8F0000 + room.roomState
            val doorsFull = 0x8F0000 + room.doors
            
            // Check direct matches
            if (bgDataFull == roomId || roomStateFull == roomId || doorsFull == roomId) {
                println("[RoomMatcher] Found by pointer match at table[$tableIndex]")
                println("  bgData=0x${bgDataFull.toString(16)}, roomState=0x${roomStateFull.toString(16)}")
                return room.copy(roomId = roomId)
            }
            
            // Check direction pointers
            val dirPointers = listOf(
                0x8F0000 + room.roomDown,
                0x8F0000 + room.roomUp,
                0x8F0000 + room.roomLeft,
                0x8F0000 + room.roomRight
            )
            
            if (dirPointers.contains(roomId)) {
                println("[RoomMatcher] Found by direction pointer match at table[$tableIndex]")
                return room.copy(roomId = roomId)
            }
            
            // Check if roomId is close to bgData (level data address)
            val roomIdBank = (roomId shr 16) and 0xFF
            if (roomIdBank >= 0x91 && roomIdBank <= 0xDF) {
                val roomIdPc = romParser.snesToPc(roomId)
                val bgDataPc = romParser.snesToPc(bgDataFull)
                val diff = kotlin.math.abs(bgDataPc - roomIdPc)
                
                if (diff < 0x5000) { // Within 20KB
                    println("[RoomMatcher] Found by proximity match at table[$tableIndex] (diff=$diff)")
                    return room.copy(roomId = roomId)
                }
            }
        }
        
        // Debug: Show first few rooms
        println("[RoomMatcher] No match found. First 5 rooms:")
        rooms.entries.take(5).forEach { (idx, room) ->
            val bgDataFull = 0x8F0000 + room.bgData
            println("  Table[$idx]: index=${room.index}, bgData=0x${bgDataFull.toString(16)}, area=${room.area}")
        }
        
        // Strategy 4: Return first valid room as fallback (for debugging)
        val fallback = rooms.values.firstOrNull()?.copy(roomId = roomId)
        if (fallback != null) {
            println("[RoomMatcher] Returning fallback room (first valid room)")
        }
        return fallback
    }
    
    /**
     * Get all rooms for debugging
     */
    fun getAllRooms(): List<Room> {
        return buildLookup().values.toList()
    }
}
