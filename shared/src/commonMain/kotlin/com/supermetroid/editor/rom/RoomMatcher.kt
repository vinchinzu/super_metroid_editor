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
        
        // Strategy 1: Try direct lookup using room ID as index
        if (roomId < rooms.size) {
            val room = rooms[roomId]
            if (room != null) {
                return room
            }
        }
        
        // Strategy 2: Search all rooms for matching pointers
        for ((index, room) in rooms) {
            val bgDataFull = 0x8F0000 + room.bgData
            val roomStateFull = 0x8F0000 + room.roomState
            val doorsFull = 0x8F0000 + room.doors
            
            // Check direct matches
            if (bgDataFull == roomId || roomStateFull == roomId || doorsFull == roomId) {
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
                return room.copy(roomId = roomId)
            }
            
            // Check if roomId is close to bgData (level data address)
            val roomIdBank = (roomId shr 16) and 0xFF
            if (roomIdBank >= 0x91 && roomIdBank <= 0xDF) {
                val roomIdPc = romParser.snesToPc(roomId)
                val bgDataPc = romParser.snesToPc(bgDataFull)
                val diff = kotlin.math.abs(bgDataPc - roomIdPc)
                
                if (diff < 0x5000) { // Within 20KB
                    return room.copy(roomId = roomId)
                }
            }
        }
        
        // Strategy 3: Return first valid room as fallback (for debugging)
        return rooms.values.firstOrNull()?.copy(roomId = roomId)
    }
    
    /**
     * Get all rooms for debugging
     */
    fun getAllRooms(): List<Room> {
        return buildLookup().values.toList()
    }
}
