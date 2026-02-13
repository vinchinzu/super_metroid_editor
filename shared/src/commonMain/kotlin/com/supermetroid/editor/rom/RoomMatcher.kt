package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Helper class to match room IDs to actual rooms in the ROM.
 * 
 * Room IDs are 16-bit pointers within SNES bank $8F.
 * Each room header lives at SNES address $8F:<roomId>.
 * We simply read the header directly at that address.
 */
class RoomMatcher(private val romParser: RomParser) {
    
    /**
     * Find room by room ID — reads the header directly from ROM.
     */
    fun findRoomById(roomId: Int): Room? {
        return romParser.readRoomHeader(roomId)
    }
}
