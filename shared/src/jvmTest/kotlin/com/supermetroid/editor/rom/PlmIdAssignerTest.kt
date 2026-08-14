package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for PlmIdAssigner with synthetic fixtures.
 * No ROMs required, no RomParser subclassing.
 */
class PlmIdAssignerTest {

    // ─── Assignment Tests (no parser needed) ────────────────────────

    @Test
    fun `assigner gives distinct sequential bits to colliding items`() {
        val plms = listOf(
            plmLoc(0x91F8, "Room A", 0, itemPlm(0xEED7, 0x0010)),
            plmLoc(0x91F8, "Room A", 1, itemPlm(0xEED7, 0x0010)), // collision
            plmLoc(0x91F8, "Room A", 2, itemPlm(0xEF83, 0x0010)), // collision
        )
        val collisions = listOf(
            PlmIdAssigner.BitCollision(0x0010, PlmIdAssigner.PlmKind.ITEM, plms)
        )

        val assignments = PlmIdAssigner.assignItemIds(plms, collisions, startBit = 0x0000)

        assertEquals(3, assignments.size, "All 3 items should get new assignments")
        val bits = assignments.map { it.newParam }.toSet()
        assertEquals(3, bits.size, "All assigned bits should be unique")
        assertTrue(0x0000 in bits)
        assertTrue(0x0001 in bits)
        assertTrue(0x0002 in bits)
    }

    @Test
    fun `assigner gives distinct sequential bits to colliding doors`() {
        val plms = listOf(
            plmLoc(0x91F8, "Room A", 0, doorPlm(0xC842, 0x9005)),
            plmLoc(0x91F8, "Room A", 1, doorPlm(0xC848, 0x9005)), // collision
        )
        val collisions = listOf(
            PlmIdAssigner.BitCollision(0x05, PlmIdAssigner.PlmKind.DOOR, plms)
        )

        val assignments = PlmIdAssigner.assignDoorIds(plms, collisions, startBit = 0x00)

        assertEquals(2, assignments.size)
        val lowBytes = assignments.map { it.newParam and 0xFF }
        assertEquals(2, lowBytes.toSet().size, "Door bits should be unique")
        val highBytes = assignments.map { (it.newParam shr 8) and 0xFF }
        assertTrue(highBytes.all { it == 0x90 }, "High byte should be preserved")
    }

    @Test
    fun `already unique bits produce no assignments`() {
        val plms = listOf(
            plmLoc(0x91F8, "Room A", 0, itemPlm(0xEED7, 0x0010)),
            plmLoc(0x91F8, "Room A", 1, itemPlm(0xEED7, 0x0020)),
            plmLoc(0x91F8, "Room A", 2, itemPlm(0xEF83, 0x0030)),
        )
        val collisions = emptyList<PlmIdAssigner.BitCollision>()

        val assignments = PlmIdAssigner.assignItemIds(plms, collisions)

        assertEquals(0, assignments.size, "No assignments when all bits unique")
    }

    @Test
    fun `assignment is deterministic`() {
        val plms = listOf(
            plmLoc(0x91F8, "Room A", 0, itemPlm(0xEED7, 0x0010)),
            plmLoc(0x91F8, "Room A", 1, itemPlm(0xEED7, 0x0010)),
        )
        val collisions = listOf(
            PlmIdAssigner.BitCollision(0x0010, PlmIdAssigner.PlmKind.ITEM, plms)
        )

        val assignments1 = PlmIdAssigner.assignItemIds(plms, collisions, startBit = 0x0000)
        val assignments2 = PlmIdAssigner.assignItemIds(plms, collisions, startBit = 0x0000)

        assertEquals(assignments1, assignments2, "Assignments should be deterministic")
    }

    @Test
    fun `assignment fills gaps in used bit ranges`() {
        val plms = listOf(
            plmLoc(0x91F8, "Room A", 0, itemPlm(0xEED7, 0x0000)), // bit 0x0000 (used)
            plmLoc(0x91F8, "Room A", 1, itemPlm(0xEED7, 0x0002)), // bit 0x0002 (gap at 0x0001)
            plmLoc(0x91F8, "Room A", 2, itemPlm(0xEED7, 0x0005)), // collision with next
            plmLoc(0x91F8, "Room A", 3, itemPlm(0xEF83, 0x0005)), // collision
        )
        val collisions = listOf(
            PlmIdAssigner.BitCollision(0x0005, PlmIdAssigner.PlmKind.ITEM, plms.drop(2))
        )

        val assignments = PlmIdAssigner.assignItemIds(plms, collisions, startBit = 0x0000)

        assertEquals(2, assignments.size)
        val newBits = assignments.map { it.newParam }.toSet()
        assertTrue(0x0001 in newBits, "Should fill gap at 0x0001")
        assertTrue(0x0003 in newBits, "Should use next available at 0x0003")
    }

    // ─── Scanner Tests (need real ROM bytes) ────────────────────────

    @Test
    fun `scanner detects item collision from synthetic ROM`() {
        val rom = buildSyntheticRom(mapOf(
            0x91F8 to listOf(
                SyntheticPlm(0xEED7, 5, 10, 0x0010), // Energy Tank
                SyntheticPlm(0xEED7, 8, 12, 0x0010), // Energy Tank (duplicate bit)
            )
        ))
        val parser = RomParser(rom)

        val scan = PlmIdAssigner.scanRooms(parser, listOf(0x91F8))

        assertEquals(2, scan.itemPlms.size, "Should find 2 item PLMs")
        assertEquals(1, scan.itemCollisions.size, "Should detect 1 collision")
        val collision = scan.itemCollisions.first()
        assertEquals(0x0010, collision.bit)
        assertEquals(2, collision.locations.size)
    }

    @Test
    fun `scanner detects door collision from synthetic ROM`() {
        val rom = buildSyntheticRom(mapOf(
            0x91F8 to listOf(
                SyntheticPlm(0xC842, 10, 20, 0x9005), // Grey door
                SyntheticPlm(0xC848, 15, 20, 0x9005), // Grey door (duplicate bit)
            )
        ))
        val parser = RomParser(rom)

        val scan = PlmIdAssigner.scanRooms(parser, listOf(0x91F8))

        assertEquals(2, scan.doorPlms.size, "Should find 2 door PLMs")
        assertEquals(1, scan.doorCollisions.size, "Should detect 1 collision")
        assertEquals(0x05, scan.doorCollisions.first().bit)
    }

    @Test
    fun `scanner excludes blue doors without bit tracking`() {
        val rom = buildSyntheticRom(mapOf(
            0x91F8 to listOf(
                SyntheticPlm(0xC8A2, 10, 20, 0x0000), // Blue door (no tracking)
                SyntheticPlm(0xC842, 15, 20, 0x9005), // Grey door (has tracking)
            )
        ))
        val parser = RomParser(rom)

        val scan = PlmIdAssigner.scanRooms(parser, listOf(0x91F8))

        assertEquals(1, scan.doorPlms.size, "Should only find grey door")
        assertEquals(0xC842, scan.doorPlms.first().plm.id)
    }

    @Test
    fun `scanner handles rooms with no items or doors`() {
        val rom = buildSyntheticRom(mapOf(
            0x91F8 to listOf(
                SyntheticPlm(0xB703, 5, 10, 0x1234), // Scroll PLM
            )
        ))
        val parser = RomParser(rom)

        val scan = PlmIdAssigner.scanRooms(parser, listOf(0x91F8))

        assertEquals(0, scan.itemPlms.size)
        assertEquals(0, scan.doorPlms.size)
        assertEquals(0, scan.itemCollisions.size)
        assertEquals(0, scan.doorCollisions.size)
    }

    @Test
    fun `scanner treats item and door bits as separate namespaces`() {
        val rom = buildSyntheticRom(mapOf(
            0x91F8 to listOf(
                SyntheticPlm(0xEED7, 5, 10, 0x0005), // Item bit 0x0005
                SyntheticPlm(0xC842, 10, 20, 0x9005), // Door bit 0x05
            )
        ))
        val parser = RomParser(rom)

        val scan = PlmIdAssigner.scanRooms(parser, listOf(0x91F8))

        assertEquals(1, scan.itemPlms.size)
        assertEquals(1, scan.doorPlms.size)
        assertEquals(0, scan.itemCollisions.size, "No collision between namespaces")
        assertEquals(0, scan.doorCollisions.size)
    }

    // ─── Test Helpers ────────────────────────────────────────────────

    private fun plmLoc(
        roomId: Int,
        roomName: String,
        plmIndex: Int,
        plm: RomParser.PlmEntry,
    ) = PlmIdAssigner.PlmLocation(
        roomId = roomId,
        roomName = roomName,
        plmIndex = plmIndex,
        plm = plm,
        kind = if (RomParser.isItemPlm(plm.id)) PlmIdAssigner.PlmKind.ITEM
               else PlmIdAssigner.PlmKind.DOOR
    )

    private fun itemPlm(id: Int, param: Int) = RomParser.PlmEntry(id, 0, 0, param)
    private fun doorPlm(id: Int, param: Int) = RomParser.PlmEntry(id, 0, 0, param)

    private data class SyntheticPlm(
        val id: Int,
        val x: Int,
        val y: Int,
        val param: Int,
    )

    /**
     * Build a minimal synthetic ROM with room headers and PLM sets.
     * Follows Super Metroid LoROM format just enough for RomParser to read.
     */
    private fun buildSyntheticRom(rooms: Map<Int, List<SyntheticPlm>>): ByteArray {
        val rom = ByteArray(0x100000) // 1MB
        
        // Allocate PLM sets with proper spacing to avoid overlaps
        val plmSetBasePtr = 0x9A00 // Safe area in bank $8F
        val roomList = rooms.toList().sortedBy { it.first } // Sort for deterministic order
        var plmSetOffset = 0
        
        for ((roomId, plms) in roomList) {
            val roomHeaderPc = snesToPc(0x8F0000 or roomId)
            if (roomHeaderPc < 0 || roomHeaderPc + 100 > rom.size) continue
            
            // Room header (11 bytes)
            rom[roomHeaderPc + 0] = 0.toByte() // index
            rom[roomHeaderPc + 1] = 0.toByte() // area
            rom[roomHeaderPc + 2] = 0.toByte() // mapX
            rom[roomHeaderPc + 3] = 0.toByte() // mapY
            rom[roomHeaderPc + 4] = 1.toByte() // width (1 screen minimum)
            rom[roomHeaderPc + 5] = 1.toByte() // height
            rom[roomHeaderPc + 6] = 0.toByte() // upScroller
            rom[roomHeaderPc + 7] = 0.toByte() // downScroller
            rom[roomHeaderPc + 8] = 0.toByte() // creBitflag
            writeU16(rom, roomHeaderPc + 9, 0x0000) // doorOut
            
            // State select: E5E6 (default)
            rom[roomHeaderPc + 11] = 0xE6.toByte()
            rom[roomHeaderPc + 12] = 0xE5.toByte()
            
            // State data (26 bytes at +13)
            val statePc = roomHeaderPc + 13
            writeU24(rom, statePc + 0, 0x000000) // levelDataPtr (dummy)
            // Skip bytes 3-19...
            // PLM set pointer at +20
            val plmSetPtr = plmSetBasePtr + plmSetOffset
            writeU16(rom, statePc + 20, plmSetPtr)
            
            // PLM set
            val plmSetPc = snesToPc(0x8F0000 or plmSetPtr)
            if (plmSetPc < 0 || plmSetPc + (plms.size * 6) + 2 > rom.size) continue
            
            var cursor = plmSetPc
            for (plm in plms) {
                writeU16(rom, cursor, plm.id)
                rom[cursor + 2] = plm.x.toByte()
                rom[cursor + 3] = plm.y.toByte()
                writeU16(rom, cursor + 4, plm.param)
                cursor += 6
            }
            writeU16(rom, cursor, 0x0000) // terminator
            
            // Advance offset for next room (6 bytes per PLM + 2 for terminator)
            plmSetOffset += (plms.size * 6) + 2
        }
        
        return rom
    }

    private fun snesToPc(snes: Int): Int {
        val bank = (snes shr 16) and 0x7F
        val offset = snes and 0xFFFF
        return bank * 0x8000 + (offset - 0x8000)
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
}
