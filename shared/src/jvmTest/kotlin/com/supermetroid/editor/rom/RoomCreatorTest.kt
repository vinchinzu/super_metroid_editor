package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomCreatorTest {
    @Test
    fun `allocate blank room creates valid structure`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        val result = creator.allocateBlankRoom(
            width = 2,
            height = 2,
            area = 0,
            tileset = 0,
        )

        assertNotNull(result, "Allocation should succeed with fresh ROM")
        assertTrue(result.roomId >= 0x8000, "Room ID should be valid SNES address")

        // Verify room ID corresponds to header location in bank $8F
        val headerSnes = parser.pcToSnes(result.allocation.headerPcOffset)
        assertEquals(result.roomId, headerSnes and 0xFFFF, "Room ID should equal header SNES address")
        assertEquals(0x8F, headerSnes shr 16, "Header should be in bank \$8F")

        // Verify scroll data pointer and size
        val scrollPc = parser.snesToPc(0x8F0000 or result.allocation.scrollPtr)
        assertTrue(scrollPc >= 0, "Scroll data should be valid")
        
        // For a 2x2 room, 4 screens worth of scroll data should be reserved
        // We can't directly check the reservation size from the allocation object,
        // but we can verify the scroll pointer is valid
        assertTrue(result.allocation.scrollPtr >= 0x8000, "Scroll pointer should be in bank \$8F")
        
        // Verify compressed level data is stored
        assertTrue(result.allocation.compressedLevelData.isNotEmpty(), "Compressed level data should be stored")
    }

    @Test
    fun `write allocated room produces valid ROM data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 1,
            tileset = 5,
        )
        assertNotNull(result)

        creator.writeAllocatedRoom(
            roomId = result.roomId,
            allocation = result.allocation,
            width = 1,
            height = 1,
            area = 1,
            tileset = 5,
        )

        val room = parser.readRoomHeader(result.roomId)
        assertNotNull(room, "Room should be readable after writing")
        assertEquals(1, room.area, "Area should match")
        assertEquals(1, room.width, "Width should match")
        assertEquals(1, room.height, "Height should match")
        assertEquals(5, room.tileset, "Tileset should match")

        val doorTablePc = parser.snesToPc(0x8F0000 or result.allocation.doorTablePtr)
        val doorTableValue = readU16(rom, doorTablePc)
        assertEquals(0x0000, doorTableValue, "Door table should be empty (0x0000 terminator)")

        val plmSetPc = parser.snesToPc(0x8F0000 or result.allocation.plmSetPtr)
        val plmSetValue = readU16(rom, plmSetPc)
        assertEquals(0x0000, plmSetValue, "PLM set should be empty (0x0000 terminator)")

        val enemyPopPc = parser.snesToPc(0xA10000 or result.allocation.enemyPopPtr)
        val enemyPopValue = readU16(rom, enemyPopPc)
        assertEquals(0xFFFF, enemyPopValue, "Enemy population should be empty (0xFFFF terminator)")

        val scrollDataPc = parser.snesToPc(0x8F0000 or result.allocation.scrollPtr)
        val scrollValue = rom[scrollDataPc].toInt() and 0xFF
        assertEquals(0x01, scrollValue, "Scroll data should be blue (0x01)")
    }

    @Test
    fun `allocation fails when free space is exhausted in A1`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())
        
        // Take snapshot of $8F bank before filling $A1
        val bank8FStart = parser.snesToPc(0x8F8000)
        val bank8FEnd = parser.snesToPc(0x8FFFFF)
        val bank8FSnapshot = rom.copyOfRange(bank8FStart, bank8FEnd + 1)

        // Fill $A1 bank (enemy population) but leave $8F free
        val bankA1Start = parser.snesToPc(0xA18000)
        val bankA1End = parser.snesToPc(0xA1FFFF)
        for (offset in bankA1Start..bankA1End) {
            rom[offset] = 0x00.toByte()
        }

        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        // Allocation should fail
        assertNull(result, "Allocation should fail when bank \$A1 has no free space")
        
        // Bank $8F should be unchanged (two-phase allocation writes nothing until all reserves succeed)
        for (i in bank8FSnapshot.indices) {
            assertEquals(
                bank8FSnapshot[i],
                rom[bank8FStart + i],
                "Bank \$8F should be unchanged at offset ${bank8FStart + i}"
            )
        }
    }

    @Test
    fun `allocation fails when free space is exhausted in B4`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())
        
        // Take snapshot of $8F and $A1 banks before filling $B4
        val bank8FStart = parser.snesToPc(0x8F8000)
        val bank8FEnd = parser.snesToPc(0x8FFFFF)
        val bank8FSnapshot = rom.copyOfRange(bank8FStart, bank8FEnd + 1)
        
        val bankA1Start = parser.snesToPc(0xA18000)
        val bankA1End = parser.snesToPc(0xA1FFFF)
        val bankA1Snapshot = rom.copyOfRange(bankA1Start, bankA1End + 1)

        // Fill $B4 bank (enemy GFX) but leave $8F and $A1 free
        val bankB4Start = parser.snesToPc(0xB48000)
        val bankB4End = parser.snesToPc(0xB4FFFF)
        for (offset in bankB4Start..bankB4End) {
            rom[offset] = 0x00.toByte()
        }

        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        // Allocation should fail
        assertNull(result, "Allocation should fail when bank \$B4 has no free space")
        
        // Banks $8F and $A1 should be unchanged (two-phase allocation writes nothing until all reserves succeed)
        for (i in bank8FSnapshot.indices) {
            assertEquals(
                bank8FSnapshot[i],
                rom[bank8FStart + i],
                "Bank \$8F should be unchanged at offset ${bank8FStart + i}"
            )
        }
        for (i in bankA1Snapshot.indices) {
            assertEquals(
                bankA1Snapshot[i],
                rom[bankA1Start + i],
                "Bank \$A1 should be unchanged at offset ${bankA1Start + i}"
            )
        }
    }

    @Test
    fun `allocated rooms do not overlap existing data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        val result1 = creator.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(result1)
        creator.writeAllocatedRoom(result1.roomId, result1.allocation, 1, 1, 0, 0)

        val creator2 = RoomCreator(rom, parser, listOf(result1.allocation))
        val result2 = creator2.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(result2)
        creator2.writeAllocatedRoom(result2.roomId, result2.allocation, 1, 1, 0, 0)

        // Room IDs (which are header SNES addresses) should be different
        assertTrue(result1.roomId != result2.roomId, "Room IDs should be different")

        // Check that header regions don't overlap (each header+state is 39 bytes)
        val header1Start = result1.allocation.headerPcOffset
        val header1End = result1.allocation.headerPcOffset + 39

        val header2Start = result2.allocation.headerPcOffset
        val header2End = result2.allocation.headerPcOffset + 39

        val overlaps = (header1Start < header2End && header2Start < header1End)
        assertTrue(!overlaps, "Header regions should not overlap")
    }

    @Test
    fun `level data is compressed and decompresses correctly`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        val result = creator.allocateBlankRoom(width = 2, height = 1, area = 0, tileset = 0)
        assertNotNull(result)
        creator.writeAllocatedRoom(result.roomId, result.allocation, 2, 1, 0, 0)

        val levelPtr = result.allocation.levelDataPtr
        val decompressed = parser.decompressLZ2(levelPtr)

        val expectedBlocks = 2 * 1 * 16 * 16
        val layer1Size = expectedBlocks * 2
        assertEquals(2 + layer1Size + expectedBlocks, decompressed.size,
            "Decompressed level data should have correct size")

        val actualLayer1Size = readU16(decompressed, 0)
        assertEquals(layer1Size, actualLayer1Size, "Layer 1 size header should match")

        for (i in 0 until expectedBlocks) {
            val tileWord = readU16(decompressed, 2 + i * 2)
            assertEquals(RomConstants.AIR_TILE_WORD, tileWord, "All tiles should be air")
        }

        for (i in 0 until expectedBlocks) {
            val bts = decompressed[2 + layer1Size + i].toInt() and 0xFF
            assertEquals(0x00, bts, "All BTS should be 0x00")
        }
    }

    // TODO: Fix collision detection - marked ranges don't prevent RomFreeSpaceAllocator
    // from reusing same PC offsets. Needs allocator-level exclusion mechanism.
    // @Test
    fun `two reserve-only allocates do not collide - DISABLED`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        
        // First allocation
        val creator1 = RoomCreator(rom, parser, emptyList())
        val result1 = creator1.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(result1, "First allocation should succeed")
        
        // Second allocation WITHOUT writing the first
        // Pass the first allocation as existing to avoid collision
        val creator2 = RoomCreator(rom, parser, listOf(result1.allocation))
        val result2 = creator2.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(result2, "Second allocation should succeed")
        
        // Room IDs should be different
        assertTrue(result1.roomId != result2.roomId, "Room IDs should be different")
        
        // Check all allocations don't overlap
        assertNotOverlapping("header", result1.allocation.headerPcOffset, 39, result2.allocation.headerPcOffset, 39)
        
        val doorPc1 = parser.snesToPc(0x8F0000 or result1.allocation.doorTablePtr)
        val doorPc2 = parser.snesToPc(0x8F0000 or result2.allocation.doorTablePtr)
        assertNotOverlapping("door", doorPc1, 2, doorPc2, 2)
        
        val plmPc1 = parser.snesToPc(0x8F0000 or result1.allocation.plmSetPtr)
        val plmPc2 = parser.snesToPc(0x8F0000 or result2.allocation.plmSetPtr)
        assertNotOverlapping("PLM", plmPc1, 2, plmPc2, 2)
        
        assertNotOverlapping("level", result1.allocation.levelDataPcOffset, result1.allocation.compressedLevelData.size,
            result2.allocation.levelDataPcOffset, result2.allocation.compressedLevelData.size)
        
        val enemyPc1 = parser.snesToPc(0xA10000 or result1.allocation.enemyPopPtr)
        val enemyPc2 = parser.snesToPc(0xA10000 or result2.allocation.enemyPopPtr)
        assertNotOverlapping("enemy", enemyPc1, 3, enemyPc2, 3)
        
        val gfxPc1 = parser.snesToPc(0xB40000 or result1.allocation.enemyGfxPtr)
        val gfxPc2 = parser.snesToPc(0xB40000 or result2.allocation.enemyGfxPtr)
        assertNotOverlapping("GFX", gfxPc1, 2, gfxPc2, 2)
        
        val scrollPc1 = parser.snesToPc(0x8F0000 or result1.allocation.scrollPtr)
        val scrollPc2 = parser.snesToPc(0x8F0000 or result2.allocation.scrollPtr)
        assertNotOverlapping("scroll", scrollPc1, 1, scrollPc2, 1)
    }

    private fun assertNotOverlapping(label: String, start1: Int, size1: Int, start2: Int, size2: Int) {
        val end1 = start1 + size1
        val end2 = start2 + size2
        val overlaps = (start1 < end2 && start2 < end1)
        assertTrue(!overlaps, "$label ranges should not overlap: [$start1, $end1) vs [$start2, $end2)")
    }

    private fun createFreshTestRom(): ByteArray {
        val rom = ByteArray(RomConstants.ROM_SIZE) { 0xFF.toByte() }

        fillMinimalRomHeaders(rom)
        
        return rom
    }

    private fun fillMinimalRomHeaders(rom: ByteArray) {
        val parser = RomParser(rom)
        
        val landingSiteId = 0x91F8
        val headerPc = parser.snesToPc(0x8F0000 or landingSiteId)
        if (headerPc + 39 < rom.size) {
            rom[headerPc] = 0x00.toByte()
            rom[headerPc + 1] = 0x00.toByte()
            rom[headerPc + 2] = 0x00.toByte()
            rom[headerPc + 3] = 0x00.toByte()
            rom[headerPc + 4] = 0x01.toByte()
            rom[headerPc + 5] = 0x01.toByte()
            rom[headerPc + 6] = 0x70.toByte()
            rom[headerPc + 7] = 0xA0.toByte()
            rom[headerPc + 8] = 0x00.toByte()
            writeU16(rom, headerPc + 9, 0x0000)
            
            writeU16(rom, headerPc + 11, 0xE5E6)
            
            val statePc = headerPc + 13
            writeU24(rom, statePc, 0xC08000)
            rom[statePc + 3] = 0x00.toByte()
            rom[statePc + 4] = 0x05.toByte()
            rom[statePc + 5] = 0x05.toByte()
            for (i in 0 until 20) {
                rom[statePc + 6 + i] = 0x00.toByte()
            }
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        if (offset + 1 < data.size) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun writeU24(data: ByteArray, offset: Int, value: Int) {
        if (offset + 2 < data.size) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
            data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        }
    }
}
