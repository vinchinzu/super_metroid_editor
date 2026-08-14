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
 * - Door tracking bits: stored in PLM param with high byte 0x80-0x9F
 *   (0x80XX through 0x9FXX where XX is the door bit index)
 */
object PlmIdAssigner {

    /**
     * PLM entry with room context for scanning.
     */
    data class PlmLocation(
        val roomId: Int,
        val roomName: String,
        val plmIndex: Int,
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
     */
    data class Assignment(
        val roomId: Int,
        val plmIndex: Int,
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
            
            plms.forEachIndexed { index, plm ->
                when {
                    RomParser.isItemPlm(plm.id) -> {
                        itemPlms.add(PlmLocation(
                            roomId = roomId,
                            roomName = room.name,
                            plmIndex = index,
                            plm = plm,
                            kind = PlmKind.ITEM,
                        ))
                    }
                    isDoorCapWithBitTracking(plm) -> {
                        doorPlms.add(PlmLocation(
                            roomId = roomId,
                            roomName = room.name,
                            plmIndex = index,
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
     * Grey/yellow/green/red doors with high byte 0x80-0x9F track open state.
     * Blue doors (beam) don't track state, so param is just door index.
     */
    private fun isDoorCapWithBitTracking(plm: RomParser.PlmEntry): Boolean {
        val color = RomParser.doorCapColor(plm.id) ?: return false
        if (color == RomParser.DOOR_CAP_BLUE) return false
        val highByte = (plm.param shr 8) and 0xFF
        return highByte in 0x80..0x9F
    }

    /**
     * Extract the door bit index from a door PLM param.
     * Format: 0xHHLL where HH is 0x80-0x9F and LL is the bit index.
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
     * Policy: preserve already-unique bits, assign sequential IDs to duplicates
     * starting from the lowest unused bit.
     * 
     * @param itemPlms All item PLMs from scan
     * @param collisions Detected item bit collisions
     * @param startBit Starting bit for sequential assignment (default 0x0000)
     * @return List of assignments for PLMs that need new params
     */
    fun assignItemIds(
        itemPlms: List<PlmLocation>,
        collisions: List<BitCollision>,
        startBit: Int = 0x0000,
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        val usedBits = itemPlms.map { it.plm.param }.toMutableSet()
        val needsAssignment = mutableSetOf<PlmLocation>()

        for (collision in collisions.filter { it.kind == PlmKind.ITEM }) {
            needsAssignment.addAll(collision.locations)
        }

        if (needsAssignment.isEmpty()) {
            return emptyList()
        }

        var nextBit = startBit
        
        val sortedForAssignment = needsAssignment.sortedWith(
            compareBy({ it.roomId }, { it.plmIndex })
        )

        for (plmLoc in sortedForAssignment) {
            while (nextBit in usedBits) {
                nextBit++
                if (nextBit > 0xFFFF) {
                    throw IllegalStateException("Item bit space exhausted (exceeded 0xFFFF)")
                }
            }

            assignments.add(Assignment(
                roomId = plmLoc.roomId,
                plmIndex = plmLoc.plmIndex,
                oldParam = plmLoc.plm.param,
                newParam = nextBit,
            ))
            
            usedBits.add(nextBit)
            nextBit++
        }

        return assignments.sortedWith(compareBy({ it.roomId }, { it.plmIndex }))
    }

    /**
     * Assign sequential unique IDs to door PLMs.
     * 
     * Policy: preserve already-unique bits, assign sequential IDs to duplicates.
     * Door params have format 0xHHLL where HH is 0x80-0x9F and LL is the bit index.
     * 
     * @param doorPlms All door PLMs from scan
     * @param collisions Detected door bit collisions
     * @param startBit Starting bit for sequential assignment (default 0x00)
     * @return List of assignments for PLMs that need new params
     */
    fun assignDoorIds(
        doorPlms: List<PlmLocation>,
        collisions: List<BitCollision>,
        startBit: Int = 0x00,
    ): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        val usedDoorBits = doorPlms.map { extractDoorBit(it.plm.param) }.toMutableSet()
        val needsAssignment = mutableSetOf<PlmLocation>()

        for (collision in collisions.filter { it.kind == PlmKind.DOOR }) {
            needsAssignment.addAll(collision.locations)
        }

        if (needsAssignment.isEmpty()) {
            return emptyList()
        }

        var nextBit = startBit
        
        val sortedForAssignment = needsAssignment.sortedWith(
            compareBy({ it.roomId }, { it.plmIndex })
        )

        for (plmLoc in sortedForAssignment) {
            while (nextBit in usedDoorBits) {
                nextBit++
                if (nextBit > 0xFF) {
                    throw IllegalStateException("Door bit space exhausted (exceeded 0xFF)")
                }
            }

            val highByte = (plmLoc.plm.param shr 8) and 0xFF
            val newParam = (highByte shl 8) or (nextBit and 0xFF)

            assignments.add(Assignment(
                roomId = plmLoc.roomId,
                plmIndex = plmLoc.plmIndex,
                oldParam = plmLoc.plm.param,
                newParam = newParam,
            ))
            
            usedDoorBits.add(nextBit)
            nextBit++
        }

        return assignments.sortedWith(compareBy({ it.roomId }, { it.plmIndex }))
    }
}
