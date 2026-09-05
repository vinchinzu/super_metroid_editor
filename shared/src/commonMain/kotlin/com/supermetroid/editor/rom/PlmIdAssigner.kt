package com.supermetroid.editor.rom

/**
 * Auto item/door ID assignment scanner and assigner.
 * 
 * Scans all rooms for item collection bits and door tracking bits,
 * detects duplicates/collisions, and assigns sequential unique IDs.
 * 
 * This prevents collection-bit collisions when placing items/doors:
 * - Two items sharing a collection bit means collecting one makes both vanish
 * - Two doors sharing a bit means opening one opens both
 * 
 * Bit namespaces:
 * - Item collection bits: stored directly in PLM param (0x0000-0xFFFF)
 * - Door tracking bits: low byte of param for colored doors
 */
object PlmIdAssigner {

    /**
     * PLM entry with room context for scanning.
     */
    data class PlmLocation(
        val roomId: Int,
        val roomName: String,
        val plm: RomParser.PlmEntry,
        val kind: PlmKind,
    )

    enum class PlmKind {
        ITEM,
        DOOR,
    }

    /**
     * Collision report for a specific bit value.
     */
    data class BitCollision(
        val bit: Int,
        val kind: PlmKind,
        val locations: List<PlmLocation>,
    )

    /**
     * Full scan result across all rooms.
     */
    data class ScanResult(
        val itemPlms: List<PlmLocation>,
        val doorPlms: List<PlmLocation>,
        val itemCollisions: List<BitCollision>,
        val doorCollisions: List<BitCollision>,
        val usedItemBits: Set<Int>,
        val usedDoorBits: Set<Int>,
    )

    /**
     * Proposed ID assignment for a PLM.
     * Identity: (roomId, plmId, x, y, oldParam)
     */
    data class Assignment(
        val roomId: Int,
        val plmId: Int,
        val x: Int,
        val y: Int,
        val oldParam: Int,
        val newParam: Int,
    )

    /**
     * Scan all rooms for item and door PLMs.
     * Returns locations grouped by kind and collision report.
     */
    fun scanRooms(parser: RomParser, roomIds: List<Int>): ScanResult {
        val itemPlms = mutableListOf<PlmLocation>()
        val doorPlms = mutableListOf<PlmLocation>()
        
        for (roomId in roomIds) {
            val room = parser.readRoomHeader(roomId) ?: continue
            val plms = parser.getAllPlmEntriesForRoom(roomId)
            
            for (plm in plms) {
                when {
                    RomParser.isItemPlm(plm.id) -> {
                        itemPlms.add(PlmLocation(
                            roomId = roomId,
                            roomName = room.name,
                            plm = plm,
                            kind = PlmKind.ITEM,
                        ))
                    }
                    isDoorCapWithBitTracking(plm) -> {
                        doorPlms.add(PlmLocation(
                            roomId = roomId,
                            roomName = room.name,
                            plm = plm,
                            kind = PlmKind.DOOR,
                        ))
                    }
                }
            }
        }

        val itemCollisions = findCollisions(itemPlms, PlmKind.ITEM) { it.plm.param }
        val doorCollisions = findCollisions(doorPlms, PlmKind.DOOR) { extractDoorBit(it.plm.param) }
        
        val usedItemBits = itemPlms.map { it.plm.param }.toSet()
        val usedDoorBits = doorPlms.map { extractDoorBit(it.plm.param) }.toSet()

        return ScanResult(
            itemPlms = itemPlms,
            doorPlms = doorPlms,
            itemCollisions = itemCollisions,
            doorCollisions = doorCollisions,
            usedItemBits = usedItemBits,
            usedDoorBits = usedDoorBits,
        )
    }

    /**
     * Detect if a door PLM uses bit tracking.
     * Grey/yellow/green/red doors track open state using the low byte as the door bit.
     * Blue doors (beam) don't track state.
     */
    private fun isDoorCapWithBitTracking(plm: RomParser.PlmEntry): Boolean {
        val color = RomParser.doorCapColor(plm.id) ?: return false
        return color != RomParser.DOOR_CAP_BLUE
    }

    /**
     * Extract the door bit index from a door PLM param.
     * For colored doors (grey/yellow/green/red), the low byte is the bit index.
     */
    private fun extractDoorBit(param: Int): Int {
        return param and 0xFF
    }

    /**
     * Find collisions where multiple PLMs share the same bit.
     */
    private fun findCollisions(
        plms: List<PlmLocation>,
        kind: PlmKind,
        bitExtractor: (PlmLocation) -> Int,
    ): List<BitCollision> {
        val grouped = plms.groupBy(bitExtractor)
        return grouped
            .filter { it.value.size > 1 }
            .map { (bit, locations) ->
                BitCollision(bit, kind, locations)
            }
            .sortedBy { it.bit }
    }

    /**
     * Assign sequential unique IDs to item PLMs.
     * 
     * Policy: Keep one occupant of a colliding bit (first by roomId, x, y),
     * only reassign extras. Start at 0x51 by default (vanilla uses 0x00-0x50).
     * 
     * @param itemPlms All item PLMs from scan
     * @param collisions Detected item bit collisions
     * @param startBit Starting bit for sequential assignment (default 0x51)
     * @return List of assignments for PLMs that need new params
     */
    fun assignItemIds(
        itemPlms: List<PlmLocation>,
        collisions: List<BitCollision>,
        startBit: Int = 0x51,
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        val usedBits = itemPlms.map { it.plm.param }.toMutableSet()

        for (collision in collisions.filter { it.kind == PlmKind.ITEM }) {
            // Sort by (roomId, x, y), keep first, reassign rest
            val sorted = collision.locations.sortedWith(
                compareBy({ it.roomId }, { it.plm.x }, { it.plm.y })
            )
            val extras = sorted.drop(1)

            for (plmLoc in extras) {
                var nextBit = startBit
                while (nextBit in usedBits) {
                    nextBit++
                    if (nextBit > 0xFFFF) {
                        throw IllegalStateException("Item bit space exhausted")
                    }
                }

                assignments.add(Assignment(
                    roomId = plmLoc.roomId,
                    plmId = plmLoc.plm.id,
                    x = plmLoc.plm.x,
                    y = plmLoc.plm.y,
                    oldParam = plmLoc.plm.param,
                    newParam = nextBit,
                ))
                
                usedBits.add(nextBit)
            }
        }

        return assignments.sortedWith(compareBy({ it.roomId }, { it.x }, { it.y }))
    }

    /**
     * Assign sequential unique IDs to door PLMs.
     * 
     * Policy: Keep one occupant of a colliding bit (first by roomId, x, y),
     * only reassign extras. Preserve the high byte, assign unused low bytes.
     * 
     * @param doorPlms All door PLMs from scan
     * @param collisions Detected door bit collisions
     * @return List of assignments for PLMs that need new params
     */
    fun assignDoorIds(
        doorPlms: List<PlmLocation>,
        collisions: List<BitCollision>,
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        val usedDoorBits = doorPlms.map { extractDoorBit(it.plm.param) }.toMutableSet()

        for (collision in collisions.filter { it.kind == PlmKind.DOOR }) {
            // Sort by (roomId, x, y), keep first, reassign rest
            val sorted = collision.locations.sortedWith(
                compareBy({ it.roomId }, { it.plm.x }, { it.plm.y })
            )
            val extras = sorted.drop(1)

            for (plmLoc in extras) {
                // Find next unused door bit (skip used bits, don't hardcode 0x00 start)
                var nextBit = 0
                while (nextBit in usedDoorBits) {
                    nextBit++
                    if (nextBit > 0xFF) {
                        throw IllegalStateException("Door bit space exhausted")
                    }
                }

                val highByte = (plmLoc.plm.param shr 8) and 0xFF
                val newParam = (highByte shl 8) or (nextBit and 0xFF)

                assignments.add(Assignment(
                    roomId = plmLoc.roomId,
                    plmId = plmLoc.plm.id,
                    x = plmLoc.plm.x,
                    y = plmLoc.plm.y,
                    oldParam = plmLoc.plm.param,
                    newParam = newParam,
                ))
                
                usedDoorBits.add(nextBit)
            }
        }

        return assignments.sortedWith(compareBy({ it.roomId }, { it.x }, { it.y }))
    }
}
