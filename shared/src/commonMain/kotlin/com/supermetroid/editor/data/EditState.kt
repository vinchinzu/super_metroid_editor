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
 * A door property change: modify one field of a door entry.
 */
@Serializable
data class DoorChange(
    val doorIndex: Int,
    val destRoomPtr: Int,
    val bitflag: Int,
    val doorCapCode: Int,
    val screenX: Int,
    val screenY: Int,
    val distFromDoor: Int,
    val entryCode: Int
)

/**
 * An enemy population change: add, remove, or update an enemy entry.
 * Coordinates are in pixels (same units as the ROM's enemy population data).
 */
@Serializable
data class EnemyChange(
    val action: String,       // "add", "remove", or "update"
    val enemyId: Int,
    val x: Int,
    val y: Int,
    val initParam: Int = 0,
    val properties: Int = 0,
    val origX: Int = 0,       // for "remove"/"update": match the original position
    val origY: Int = 0
)

/**
 * Per-room edit state: all operations applied to a specific room.
 */
@Serializable
data class RoomEdits(
    val roomId: Int,             // e.g. 0x91F8
    val operations: MutableList<EditOperation> = mutableListOf(),
    val plmChanges: MutableList<PlmChange> = mutableListOf(),
    val doorChanges: MutableList<DoorChange> = mutableListOf(),
    val enemyChanges: MutableList<EnemyChange> = mutableListOf()
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
 * A single patch write operation: write bytes at a PC offset.
 * Equivalent to one IPS record.
 */
@Serializable
data class PatchWrite(
    val offset: Long,         // PC file offset (not SNES address)
    val bytes: List<Int>      // byte values 0x00-0xFF
)

/**
 * A named, toggleable patch: a collection of write operations.
 */
@Serializable
data class SmPatch(
    val id: String,
    var name: String,
    var description: String = "",
    var enabled: Boolean = true,
    val writes: MutableList<PatchWrite> = mutableListOf()
)

/**
 * Custom tileset graphics data (base64-encoded raw 4bpp bytes).
 * URE (area-specific) keyed by tileset ID; CRE (common) is shared.
 */
@Serializable
data class TilesetGfxData(
    val varGfx: MutableMap<String, String> = mutableMapOf(),  // key = tilesetId, value = base64
    var creGfx: String? = null                                 // base64, shared across all tilesets
)

/**
 * The .smedit project file. JSON-serializable.
 * Keys are hex room IDs (as strings), values are the list of edit operations.
 */
@Serializable
data class SmEditProject(
    val romPath: String,
    val rooms: MutableMap<String, RoomEdits> = mutableMapOf(),  // key = "91F8"
    val tileDefaults: MutableMap<String, TileDefaultOverride> = mutableMapOf(), // key = "tilesetId:metatileIndex"
    val patches: MutableList<SmPatch> = mutableListOf(),
    val customGfx: TilesetGfxData = TilesetGfxData()
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
