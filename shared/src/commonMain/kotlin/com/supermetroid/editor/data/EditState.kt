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
 * A PLM (Post Load Modification) change: add or remove a PLM entry.
 * Used for item placement and other PLM edits.
 */
@Serializable
data class PlmChange(
    val action: String,      // "add" or "remove"
    val plmId: Int,
    val x: Int,
    val y: Int,
    val param: Int = 0
)

/**
 * Per-room edit state: all operations applied to a specific room.
 */
@Serializable
data class RoomEdits(
    val roomId: Int,             // e.g. 0x91F8
    val operations: MutableList<EditOperation> = mutableListOf(),
    val plmChanges: MutableList<PlmChange> = mutableListOf()
)

/**
 * Per-metatile default override: block type + BTS for a specific tileset.
 * Key format in the project map: "tilesetId:metatileIndex" (e.g., "12:82").
 */
@Serializable
data class TileDefaultOverride(
    val blockType: Int,
    val bts: Int = 0
)

/**
 * The .smedit project file. JSON-serializable.
 * Keys are hex room IDs (as strings), values are the list of edit operations.
 */
@Serializable
data class SmEditProject(
    val romPath: String,
    val rooms: MutableMap<String, RoomEdits> = mutableMapOf(),  // key = "91F8"
    val tileDefaults: MutableMap<String, TileDefaultOverride> = mutableMapOf() // key = "tilesetId:metatileIndex"
) {
    fun roomKey(roomId: Int): String = roomId.toString(16).uppercase().padStart(4, '0')

    fun getOrCreateRoom(roomId: Int): RoomEdits {
        val key = roomKey(roomId)
        return rooms.getOrPut(key) { RoomEdits(roomId) }
    }

    fun tileDefaultKey(tilesetId: Int, metatileIndex: Int): String = "$tilesetId:$metatileIndex"

    fun getTileDefault(tilesetId: Int, metatileIndex: Int): TileDefaultOverride? =
        tileDefaults[tileDefaultKey(tilesetId, metatileIndex)]

    fun setTileDefault(tilesetId: Int, metatileIndex: Int, blockType: Int, bts: Int) {
        tileDefaults[tileDefaultKey(tilesetId, metatileIndex)] = TileDefaultOverride(blockType, bts)
    }

    fun removeTileDefault(tilesetId: Int, metatileIndex: Int) {
        tileDefaults.remove(tileDefaultKey(tilesetId, metatileIndex))
    }
}
