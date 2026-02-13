package com.supermetroid.editor.data

import kotlinx.serialization.json.Json

/**
 * Repository for loading and managing room data
 */
class RoomRepository {
    private var roomMapping: RoomMapping? = null
    
    fun loadRoomMapping(): RoomMapping {
        if (roomMapping == null) {
            // Try multiple ways to load the resource
            val inputStream = this::class.java.classLoader
                .getResourceAsStream("room_mapping_complete.json")
                ?: Thread.currentThread().contextClassLoader
                    .getResourceAsStream("room_mapping_complete.json")
                ?: throw IllegalStateException("Could not load room_mapping_complete.json")
            
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            roomMapping = Json { ignoreUnknownKeys = true }.decodeFromString<RoomMapping>(jsonString)
        }
        
        return roomMapping!!
    }
    
    fun getAllRooms(): List<RoomInfo> {
        return loadRoomMapping().rooms.values.toList()
    }
    
    fun getRoomById(roomId: Int): RoomInfo? {
        val hexId = "0X${roomId.toString(16).uppercase().padStart(4, '0')}"
        return loadRoomMapping().rooms.values.firstOrNull { it.id == hexId }
    }
    
    fun getRoomByHandle(handle: String): RoomInfo? {
        return loadRoomMapping().rooms[handle]
    }
}
