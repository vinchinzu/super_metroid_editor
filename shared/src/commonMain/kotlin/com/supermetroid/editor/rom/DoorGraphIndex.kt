package com.supermetroid.editor.rom

/**
 * Per-ROM door/room graph index that traces door connections from save stations
 * and door lists, and reports orphaned or disconnected rooms.
 *
 * This index is built lazily when first accessed and should be cached per ROM.
 * It is useful for validation, minimap visualization, and randomizer workflows.
 *
 * **Orphaned room**: A room in the room list that is not reachable from any save station.
 * **Disconnected room**: A room with no incoming or outgoing doors.
 */
class DoorGraphIndex private constructor(
    private val parser: RomParser,
    private val allRoomIds: List<Int>
) {
    /**
     * Outgoing doors from a room: room ID → list of (door index, destination room ID).
     */
    val outgoingDoors: Map<Int, List<DoorConnection>>

    /**
     * Incoming doors to a room: room ID → list of (source room ID, door index).
     */
    val incomingDoors: Map<Int, List<DoorConnection>>

    /**
     * Room IDs that contain save station PLMs (entry points for graph traversal).
     */
    val saveStationRooms: Set<Int>

    /**
     * Room IDs reachable from any save station by following door connections.
     */
    val reachableRooms: Set<Int>

    /**
     * Room IDs that exist in the room list but are not reachable from any save station.
     */
    val orphanedRooms: Set<Int>

    /**
     * Room IDs that have no incoming or outgoing doors (completely disconnected).
     */
    val disconnectedRooms: Set<Int>

    /**
     * A door connection from one room to another.
     * @param sourceRoomId The room containing the door
     * @param destRoomId The room the door leads to
     * @param doorIndex The index of the door in the source room's door list
     */
    data class DoorConnection(
        val sourceRoomId: Int,
        val destRoomId: Int,
        val doorIndex: Int
    )

    init {
        val outgoingMap = mutableMapOf<Int, MutableList<DoorConnection>>()
        val incomingMap = mutableMapOf<Int, MutableList<DoorConnection>>()
        val saveStations = mutableSetOf<Int>()

        // Build door graph and find save stations
        for (roomId in allRoomIds) {
            val room = parser.readRoomHeader(roomId) ?: continue

            // Parse doors and build outgoing/incoming edges
            val doors = parser.parseDoorList(room.doorOut)
            for ((doorIdx, door) in doors.withIndex()) {
                val destRoomId = door.destRoomPtr
                outgoingMap.getOrPut(roomId) { mutableListOf() }
                    .add(DoorConnection(roomId, destRoomId, doorIdx))
                incomingMap.getOrPut(destRoomId) { mutableListOf() }
                    .add(DoorConnection(roomId, destRoomId, doorIdx))
            }

            // Check for save station PLMs (ID 0xB76F)
            val plms = parser.getAllPlmEntriesForRoom(roomId)
            if (plms.any { it.id == SAVE_STATION_PLM_ID }) {
                saveStations.add(roomId)
            }
        }

        outgoingDoors = outgoingMap
        incomingDoors = incomingMap
        saveStationRooms = saveStations

        // Find all reachable rooms from save stations via BFS
        reachableRooms = findReachableRooms(saveStations, outgoingMap)

        // Identify orphaned rooms (exist but not reachable)
        orphanedRooms = allRoomIds.filter { it !in reachableRooms }.toSet()

        // Identify disconnected rooms (no doors at all)
        disconnectedRooms = allRoomIds.filter { roomId ->
            outgoingMap[roomId].isNullOrEmpty() && incomingMap[roomId].isNullOrEmpty()
        }.toSet()
    }

    /**
     * Perform BFS from save station rooms to find all reachable rooms.
     */
    private fun findReachableRooms(
        startRooms: Set<Int>,
        outgoing: Map<Int, List<DoorConnection>>
    ): Set<Int> {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque(startRooms)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited) continue
            visited.add(current)

            // Add all rooms this room connects to
            outgoing[current]?.forEach { conn ->
                if (conn.destRoomId !in visited) {
                    queue.add(conn.destRoomId)
                }
            }
        }

        return visited
    }

    /**
     * Get all doors leading to a specific room.
     * This replaces the inefficient `RomParser.findDoorsLeadingTo()` which scans all rooms.
     */
    fun findDoorsLeadingTo(roomId: Int): List<RomParser.DoorEntry> {
        val connections = incomingDoors[roomId] ?: return emptyList()
        return connections.mapNotNull { conn ->
            parser.parseDoorEntry(
                parser.readRoomHeader(conn.sourceRoomId)?.doorOut ?: return@mapNotNull null,
                conn.doorIndex
            )
        }
    }

    /**
     * Get all doors from a specific room.
     */
    fun findDoorsFrom(roomId: Int): List<RomParser.DoorEntry> {
        val connections = outgoingDoors[roomId] ?: return emptyList()
        val room = parser.readRoomHeader(roomId) ?: return emptyList()
        return connections.mapNotNull { conn ->
            parser.parseDoorEntry(room.doorOut, conn.doorIndex)
        }
    }

    /**
     * Check if a room is reachable from any save station.
     */
    fun isReachable(roomId: Int): Boolean = roomId in reachableRooms

    /**
     * Check if a room is orphaned (not reachable from any save station).
     */
    fun isOrphaned(roomId: Int): Boolean = roomId in orphanedRooms

    /**
     * Check if a room is disconnected (no doors at all).
     */
    fun isDisconnected(roomId: Int): Boolean = roomId in disconnectedRooms

    /**
     * Get a summary report of the door graph.
     */
    fun summary(): GraphSummary = GraphSummary(
        totalRooms = allRoomIds.size,
        reachableRooms = reachableRooms.size,
        orphanedRooms = orphanedRooms.size,
        disconnectedRooms = disconnectedRooms.size,
        saveStationCount = saveStationRooms.size,
        totalDoors = outgoingDoors.values.sumOf { it.size }
    )

    data class GraphSummary(
        val totalRooms: Int,
        val reachableRooms: Int,
        val orphanedRooms: Int,
        val disconnectedRooms: Int,
        val saveStationCount: Int,
        val totalDoors: Int
    )

    companion object {
        /**
         * PLM ID for save stations.
         */
        private const val SAVE_STATION_PLM_ID = 0xB76F

        /**
         * Build a door graph index from a ROM parser and room catalog.
         */
        fun build(parser: RomParser, roomIds: List<Int>): DoorGraphIndex {
            return DoorGraphIndex(parser, roomIds)
        }

        /**
         * Build a door graph index from a ROM parser using the ROM's own room catalog.
         */
        fun build(parser: RomParser): DoorGraphIndex {
            val roomIds = parser.roomCatalog.rooms.map { it.getRoomIdAsInt() }
            return build(parser, roomIds)
        }
    }
}
