package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for DoorGraphIndex using synthetic ROM fixtures.
 * These tests do not require a real Super Metroid ROM and can run in CI.
 */
class DoorGraphIndexTest {

    /**
     * Build a minimal synthetic ROM with a room list and door connections.
     * This creates a fake ROM with room headers and door data.
     * 
     * Address allocation:
     * - Room headers: 0x79000+ (bank $8F)
     * - Door lists: 0x7A000+ (bank $8F)  
     * - Door entries: 0x18200+ (bank $83)
     * - PLM sets: 0x7B000+ (bank $8F)
     */
    private fun buildSyntheticRom(rooms: List<SyntheticRoom>): RomParser {
        // Synthetic ROM: 512-byte header + 3MB data
        val romSize = 0x300200
        val romData = ByteArray(romSize)

        // Mark as having SMC header (512 bytes)
        val headerSize = 0x200

        // Allocate address spaces to avoid conflicts
        var nextDoorListAddr = 0xA000      // Door list pointers in bank $8F (avoid room IDs)
        var nextDoorEntryAddr = 0x8000     // Door entries in bank $83

        // First pass: allocate addresses for door lists and entries
        val roomDoorListAddrs = mutableMapOf<Int, Int>()
        val doorEntryAddrs = mutableMapOf<Pair<Int, Int>, Int>() // (roomId, doorIndex) -> entryAddr

        for (room in rooms) {
            if (room.doors.isNotEmpty()) {
                roomDoorListAddrs[room.roomId] = nextDoorListAddr
                nextDoorListAddr += room.doors.size * 2 + 2 // 2 bytes per pointer + safety margin
                
                for (doorIdx in room.doors.indices) {
                    doorEntryAddrs[room.roomId to doorIdx] = nextDoorEntryAddr
                    nextDoorEntryAddr += 12 // Each door entry is 12 bytes
                }
            }
        }

        // Write each room header and associated data
        for (room in rooms) {
            val roomPc = headerSize + room.roomPcOffset
            if (roomPc + 11 > romData.size) continue

            // Determine actual door-out pointer (use allocated or original if no doors)
            val actualDoorOutPtr = if (room.doors.isNotEmpty()) {
                roomDoorListAddrs[room.roomId] ?: room.doorOutPtr
            } else {
                room.doorOutPtr
            }

            // Write room header (11 bytes)
            romData[roomPc] = room.index.toByte()
            romData[roomPc + 1] = room.area.toByte()
            romData[roomPc + 2] = room.mapX.toByte()
            romData[roomPc + 3] = room.mapY.toByte()
            romData[roomPc + 4] = room.width.toByte()
            romData[roomPc + 5] = room.height.toByte()
            romData[roomPc + 6] = 0x00 // upScroller
            romData[roomPc + 7] = 0x00 // downScroller
            romData[roomPc + 8] = 0x00 // creBitflag
            writeU16(romData, roomPc + 9, actualDoorOutPtr)

            // Write default state data (E5E6 marker + 26 bytes of state data)
            val stateOffset = roomPc + 11
            if (stateOffset + 28 < romData.size) {
                writeU16(romData, stateOffset, 0xE5E6) // Default state marker
                // Write minimal state data (26 bytes)
                writeU24(romData, stateOffset + 2, 0x8F8000) // levelDataPtr
                romData[stateOffset + 5] = 0x00 // tileset
                romData[stateOffset + 6] = 0x00 // musicData
                romData[stateOffset + 7] = 0x00 // musicTrack
                writeU16(romData, stateOffset + 8, 0x0000) // fxPtr
                writeU16(romData, stateOffset + 10, 0x0000) // enemySetPtr
                writeU16(romData, stateOffset + 12, 0x0000) // enemyGfxPtr
                writeU16(romData, stateOffset + 14, 0x0000) // bgScrolling
                writeU16(romData, stateOffset + 16, 0x0001) // scrollPtr (special: all blue)
                writeU16(romData, stateOffset + 20, 0x0000) // mainAsmPtr
                writeU16(romData, stateOffset + 22, room.plmSetPtr) // plmSetPtr
                writeU16(romData, stateOffset + 24, 0x0000) // bgDataPtr
                writeU16(romData, stateOffset + 26, 0x0000) // setupAsmPtr
            }

            // Write door list if present
            if (room.doors.isNotEmpty() && actualDoorOutPtr != 0) {
                val doorListPc = snesToPcForSynthetic(0x8F0000 or actualDoorOutPtr)
                for ((doorIdx, door) in room.doors.withIndex()) {
                    val doorEntryPtr = doorEntryAddrs[room.roomId to doorIdx] ?: 0x8000
                    val doorPtrPc = doorListPc + doorIdx * 2
                    if (doorPtrPc + 1 < romData.size) {
                        writeU16(romData, doorPtrPc, doorEntryPtr)
                    }

                    // Write door entry (12 bytes) at bank $83
                    val doorEntryPc = snesToPcForSynthetic(0x830000 or doorEntryPtr)
                    if (doorEntryPc + 11 < romData.size) {
                        writeU16(romData, doorEntryPc, door.destRoomPtr)
                        writeU16(romData, doorEntryPc + 2, door.bitflag)
                        writeU16(romData, doorEntryPc + 4, 0x0000) // doorCapCode
                        romData[doorEntryPc + 6] = door.screenX.toByte()
                        romData[doorEntryPc + 7] = door.screenY.toByte()
                        writeU16(romData, doorEntryPc + 8, 0x8000) // distFromDoor
                        writeU16(romData, doorEntryPc + 10, 0x0000) // entryCode
                    }
                }
            }

            // Write PLM set if present
            if (room.plmSetPtr != 0 && room.plms.isNotEmpty()) {
                val plmPc = snesToPcForSynthetic(0x8F0000 or room.plmSetPtr)
                for ((idx, plm) in room.plms.withIndex()) {
                    val plmOffset = plmPc + idx * 6
                    if (plmOffset + 5 < romData.size) {
                        writeU16(romData, plmOffset, plm.id)
                        romData[plmOffset + 2] = plm.x.toByte()
                        romData[plmOffset + 3] = plm.y.toByte()
                        writeU16(romData, plmOffset + 4, plm.param)
                    }
                }
                // Write PLM set terminator
                val terminatorOffset = plmPc + room.plms.size * 6
                if (terminatorOffset + 1 < romData.size) {
                    writeU16(romData, terminatorOffset, 0x0000)
                }
            }
        }

        return RomParser(romData)
    }

    private fun snesToPcForSynthetic(snesAddress: Int): Int {
        val headerSize = 0x200
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        val pcAddress = ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
        return headerSize + pcAddress
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    data class SyntheticRoom(
        val roomId: Int,
        val roomPcOffset: Int,
        val index: Int,
        val area: Int,
        val mapX: Int,
        val mapY: Int,
        val width: Int,
        val height: Int,
        val doorOutPtr: Int,
        val plmSetPtr: Int,
        val doors: List<SyntheticDoor>,
        val plms: List<SyntheticPlm>
    )

    data class SyntheticDoor(
        val destRoomPtr: Int,
        val bitflag: Int,
        val screenX: Int,
        val screenY: Int
    )

    data class SyntheticPlm(
        val id: Int,
        val x: Int,
        val y: Int,
        val param: Int
    )

    @Test
    fun `test synthetic ROM room header parsing`() {
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 2, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val room = parser.readRoomHeader(0x9000)
        
        assertNotNull(room, "Room header should be readable")
        assertEquals(2, room!!.width)
        assertEquals(1, room.height)
        assertEquals(0, room.area)
    }

    @Test
    fun `test synthetic ROM save station PLM parsing`() {
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9200,
                doors = emptyList(),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000)) // Save station
            )
        )

        val parser = buildSyntheticRom(rooms)
        val plms = parser.getAllPlmEntriesForRoom(0x9000)
        
        assertEquals(1, plms.size, "Should find 1 PLM")
        assertEquals(0xB76F, plms[0].id, "Should be save station PLM")
    }

    @Test
    fun `test save station detection`() {
        // Two rooms: one with save station, one without
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9200,
                doors = emptyList(),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000)) // Save station
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(2, summary.totalRooms)
        assertEquals(1, summary.saveStationCount)
        assertEquals(setOf(0x9000), index.saveStationRooms)
        
        // Room with save station is reachable (even with no doors)
        assertTrue(index.isReachable(0x9000))
        // Room without save station and no doors is not reachable
        assertFalse(index.isReachable(0x9100))
    }

    @Test
    fun `test disconnected rooms`() {
        // All rooms have no doors
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(2, summary.totalRooms)
        assertEquals(2, summary.disconnectedRooms)
        
        assertTrue(index.isDisconnected(0x9000))
        assertTrue(index.isDisconnected(0x9100))
    }

    @Test
    fun `test with real ROM - Landing Site connections`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        
        val index = parser.doorGraphIndex
        val summary = index.summary()
        
        // Verify index was built
        assertTrue(summary.totalRooms > 0, "Should have rooms in ROM")
        assertTrue(summary.saveStationCount > 0, "Should find save stations")
        assertTrue(summary.reachableRooms > 0, "Should have reachable rooms")
        
        // Landing Site (0x91F8) should be reachable
        assertTrue(index.isReachable(0x91F8), "Landing Site should be reachable")
        assertFalse(index.isOrphaned(0x91F8), "Landing Site should not be orphaned")
        
        // Landing Site has doors, so not disconnected
        assertFalse(index.isDisconnected(0x91F8), "Landing Site should not be disconnected")
        
        // Doors leading to Landing Site should be found efficiently
        val doorsToLandingSite = index.findDoorsLeadingTo(0x91F8)
        assertTrue(doorsToLandingSite.isNotEmpty(), "Should find doors leading to Landing Site")
    }

    @Test
    fun `test connected graph - all rooms reachable from save station`() {
        // Room layout:
        //   Room1 (save station) -> Room2 -> Room3
        //   All rooms should be reachable
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000, // Maps to PC 0x79000
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // Will be assigned by allocator
                plmSetPtr = 0x9800,
                doors = listOf(SyntheticDoor(0x9100, 0x0000, 0, 0)), // To room 0x9100
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000)) // Save station
            ),
            SyntheticRoom(
                roomId = 0x9100, // Maps to PC 0x79100
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // Will be assigned by allocator
                plmSetPtr = 0x0000,
                doors = listOf(
                    SyntheticDoor(0x9000, 0x0100, 0, 0), // Back to room1
                    SyntheticDoor(0x9200, 0x0000, 0, 0)  // To room3
                ),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9200, // Maps to PC 0x79200
                roomPcOffset = 0x79200,
                index = 2, area = 0, mapX = 2, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // Will be assigned by allocator
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9100, 0x0100, 0, 0)),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(3, summary.totalRooms, "Should have 3 total rooms")
        assertEquals(3, summary.reachableRooms, "All 3 rooms should be reachable")
        assertEquals(0, summary.orphanedRooms)
        assertEquals(0, summary.disconnectedRooms)
        assertEquals(1, summary.saveStationCount)
        assertEquals(4, summary.totalDoors, "Should have 4 total doors")

        assertTrue(index.isReachable(0x9000))
        assertTrue(index.isReachable(0x9100))
        assertTrue(index.isReachable(0x9200))
        assertFalse(index.isOrphaned(0x9000))
        assertFalse(index.isOrphaned(0x9100))
        assertFalse(index.isOrphaned(0x9200))
    }

    @Test
    fun `test orphaned room - room not reachable from save station`() {
        // Room layout:
        //   Room1 (save station) -> Room2
        //   Room3 (orphaned, no path from room1)
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9800,
                doors = listOf(SyntheticDoor(0x9100, 0x0000, 0, 0)),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9000, 0x0100, 0, 0)),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9200,
                roomPcOffset = 0x79200,
                index = 2, area = 0, mapX = 2, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9300, 0x0000, 0, 0)), // Points to non-existent room
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(3, summary.totalRooms)
        assertEquals(2, summary.reachableRooms)
        assertEquals(1, summary.orphanedRooms)
        assertEquals(0, summary.disconnectedRooms)

        assertTrue(index.isReachable(0x9000))
        assertTrue(index.isReachable(0x9100))
        assertFalse(index.isReachable(0x9200))
        assertTrue(index.isOrphaned(0x9200))
    }

    @Test
    fun `test disconnected room - room with no doors`() {
        // Room layout:
        //   Room1 (save station) - no doors
        //   Room2 - no doors (disconnected)
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // No doors
                plmSetPtr = 0x9200,
                doors = emptyList(),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // No doors
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(2, summary.totalRooms)
        assertEquals(1, summary.reachableRooms)  // Only room1 (has save station)
        assertEquals(1, summary.orphanedRooms)   // Room2 is orphaned
        assertEquals(2, summary.disconnectedRooms) // Both rooms have no doors

        assertTrue(index.isDisconnected(0x9000))
        assertTrue(index.isDisconnected(0x9100))
        assertTrue(index.isReachable(0x9000)) // Reachable because it has save station
        assertFalse(index.isReachable(0x9100))
    }

    @Test
    fun `test bidirectional vs one-way doors`() {
        // Room layout:
        //   Room1 (save station) <-> Room2 (bidirectional)
        //   Room2 -> Room3 (one-way, no return door)
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9800,
                doors = listOf(SyntheticDoor(0x9100, 0x0000, 0, 0)),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(
                    SyntheticDoor(0x9000, 0x0100, 0, 0),  // Back to room1 (bidirectional)
                    SyntheticDoor(0x9200, 0x0000, 0, 0)   // To room3 (one-way)
                ),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9200,
                roomPcOffset = 0x79200,
                index = 2, area = 0, mapX = 2, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000, // No doors out (one-way entrance)
                plmSetPtr = 0x0000,
                doors = emptyList(),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        // All rooms should be reachable (BFS follows outgoing doors)
        assertTrue(index.isReachable(0x9000))
        assertTrue(index.isReachable(0x9100))
        assertTrue(index.isReachable(0x9200))

        // Room3 has incoming but no outgoing doors
        assertEquals(1, index.incomingDoors[0x9200]?.size)
        assertEquals(0, index.outgoingDoors[0x9200]?.size ?: 0)

        // Room1 and Room2 have bidirectional connection
        assertEquals(1, index.outgoingDoors[0x9000]?.size)
        assertEquals(1, index.incomingDoors[0x9000]?.size)
        assertEquals(2, index.outgoingDoors[0x9100]?.size)
        assertEquals(1, index.incomingDoors[0x9100]?.size)
    }

    @Test
    fun `test multiple save stations`() {
        // Room layout:
        //   Room1 (save station) -> Room2
        //   Room3 (save station) -> Room4
        //   Both clusters should be reachable
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9800,
                doors = listOf(SyntheticDoor(0x9100, 0x0000, 0, 0)),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9000, 0x0100, 0, 0)),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9200,
                roomPcOffset = 0x79200,
                index = 2, area = 0, mapX = 2, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9810,
                doors = listOf(SyntheticDoor(0x9300, 0x0000, 0, 0)),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9300,
                roomPcOffset = 0x79300,
                index = 3, area = 0, mapX = 3, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9200, 0x0100, 0, 0)),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        val summary = index.summary()
        assertEquals(4, summary.totalRooms)
        assertEquals(4, summary.reachableRooms)
        assertEquals(0, summary.orphanedRooms)
        assertEquals(2, summary.saveStationCount)

        assertEquals(setOf(0x9000, 0x9200), index.saveStationRooms)
    }

    @Test
    fun `test findDoorsLeadingTo - efficient door lookup`() {
        val rooms = listOf(
            SyntheticRoom(
                roomId = 0x9000,
                roomPcOffset = 0x79000,
                index = 0, area = 0, mapX = 0, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x9800,
                doors = listOf(SyntheticDoor(0x9100, 0x0000, 0, 0)),
                plms = listOf(SyntheticPlm(0xB76F, 8, 8, 0x8000))
            ),
            SyntheticRoom(
                roomId = 0x9100,
                roomPcOffset = 0x79100,
                index = 1, area = 0, mapX = 1, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(
                    SyntheticDoor(0x9100, 0x0100, 0, 0), // Self-loop
                    SyntheticDoor(0x9200, 0x0000, 0, 0)
                ),
                plms = emptyList()
            ),
            SyntheticRoom(
                roomId = 0x9200,
                roomPcOffset = 0x79200,
                index = 2, area = 0, mapX = 2, mapY = 0, width = 1, height = 1,
                doorOutPtr = 0x0000,
                plmSetPtr = 0x0000,
                doors = listOf(SyntheticDoor(0x9100, 0x0100, 0, 0)),
                plms = emptyList()
            )
        )

        val parser = buildSyntheticRom(rooms)
        val roomIds = rooms.map { it.roomId }
        val index = DoorGraphIndex.build(parser, roomIds)

        // Find doors leading to room2 (0x9100)
        val doorsToRoom1 = index.findDoorsLeadingTo(0x9100)
        assertEquals(3, doorsToRoom1.size) // Room1->Room2, Room2->Room2 (self), Room3->Room2

        // Find doors leading to room3 (0x9200)
        val doorsToRoom2 = index.findDoorsLeadingTo(0x9200)
        assertEquals(1, doorsToRoom2.size)
        assertEquals(0x9200, doorsToRoom2[0].destRoomPtr)
    }
}
