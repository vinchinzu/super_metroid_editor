package com.supermetroid.editor.data

import kotlinx.serialization.Serializable

@Serializable
data class RoomMapping(
    val rooms: Map<String, RoomInfo>
)

@Serializable
data class RoomInfo(
    val id: String,
    val handle: String,
    val name: String,
    val comment: String? = null
) {
    fun getRoomIdAsInt(): Int {
        // Convert "0X91F8" to 0x91F8
        return id.removePrefix("0X").toInt(16)
    }
    
    companion object {
        fun fromRoomInfo(roomInfo: RoomInfo, roomHeader: com.supermetroid.editor.data.Room): com.supermetroid.editor.data.Room {
            return roomHeader.copy(
                roomId = roomInfo.getRoomIdAsInt(),
                name = roomInfo.name,
                handle = roomInfo.handle
            )
        }
    }
}
