package com.supermetroid.editor.data

import kotlinx.serialization.Serializable

/**
 * A single tile edit: records what one block changed from/to.
 * The block word is the full 16-bit value (metatile index + flip + block type).
 */
@Serializable
data class TileEdit(
    val blockX: Int,
    val blockY: Int,
    val oldBlockWord: Int,   // previous 16-bit block word
    val newBlockWord: Int,   // new 16-bit block word
    val oldBts: Int = 0,     // previous BTS byte
    val newBts: Int = 0      // new BTS byte
)

/**
 * A single undoable operation: one or more tile edits applied together.
 * E.g. a paint stroke or a fill is one operation with many tile edits.
 */
@Serializable
data class EditOperation(
    val description: String,
    val edits: List<TileEdit>
)

/**
 * Per-room edit state: all operations applied to a specific room.
 */
@Serializable
data class RoomEdits(
    val roomId: Int,             // e.g. 0x91F8
    val operations: MutableList<EditOperation> = mutableListOf()
)

/**
 * The .smedit project file. JSON-serializable.
 * Keys are hex room IDs (as strings), values are the list of edit operations.
 */
@Serializable
data class SmEditProject(
    val romPath: String,
    val rooms: MutableMap<String, RoomEdits> = mutableMapOf()  // key = "91F8"
) {
    fun roomKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    fun getOrCreateRoom(roomId: Int): RoomEdits {
        val key = roomKey(roomId)
        return rooms.getOrPut(key) { RoomEdits(roomId) }
    }
}
