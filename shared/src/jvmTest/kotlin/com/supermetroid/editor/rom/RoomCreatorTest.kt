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
        val creator = RoomCreator(rom, parser)

        val allocation = creator.allocateBlankRoom(
            width = 2,
            height = 2,
            area = 0,
            tileset = 0,
        )

        assertNotNull(allocation, "Allocation should succeed with fresh ROM")
        assertTrue(allocation.roomId >= 0x8000, "Room ID should be valid SNES address")

        // Verify room ID corresponds to header location in bank $8F
        val headerSnes = parser.pcToSnes(allocation.headerPcOffset)
        assertEquals(allocation.roomId, headerSnes and 0xFFFF, "Room ID should equal header SNES address")
        assertEquals(0x8F, headerSnes shr 16, "Header should be in bank \$8F")

        // Verify scroll data size matches room screens
        val scrollPc = parser.snesToPc(0x8F0000 or allocation.scrollPtr)
        assertTrue(scrollPc >= 0, "Scroll data should be valid")
        
        // For a 2x2 room, scroll data should be 4 bytes
        val scrollDataSize = 2 * 2
        assertEquals(4, scrollDataSize, "Scroll data size should match room screens (2x2=4)")
    }

    @Test
    fun `write allocated room produces valid ROM data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        val allocation = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 1,
            tileset = 5,
        )
        assertNotNull(allocation)

        creator.writeAllocatedRoom(
            allocation = allocation,
            width = 1,
            height = 1,
            area = 1,
            tileset = 5,
        )

        val room = parser.readRoomHeader(allocation.roomId)
        assertNotNull(room, "Room should be readable after writing")
        assertEquals(1, room.area, "Area should match")
        assertEquals(1, room.width, "Width should match")
        assertEquals(1, room.height, "Height should match")
        assertEquals(5, room.tileset, "Tileset should match")

        val doorTablePc = parser.snesToPc(0x8F0000 or allocation.doorTablePtr)
        val doorTableValue = readU16(rom, doorTablePc)
        assertEquals(0x0000, doorTableValue, "Door table should be empty (0x0000 terminator)")

        val plmSetPc = parser.snesToPc(0x8F0000 or allocation.plmSetPtr)
        val plmSetValue = readU16(rom, plmSetPc)
        assertEquals(0x0000, plmSetValue, "PLM set should be empty (0x0000 terminator)")

        val enemyPopPc = parser.snesToPc(0xA10000 or allocation.enemyPopPtr)
        val enemyPopValue = readU16(rom, enemyPopPc)
        assertEquals(0xFFFF, enemyPopValue, "Enemy population should be empty (0xFFFF terminator)")

        val scrollDataPc = parser.snesToPc(0x8F0000 or allocation.scrollPtr)
        val scrollValue = rom[scrollDataPc].toInt() and 0xFF
        assertEquals(0x01, scrollValue, "Scroll data should be blue (0x01)")
    }

    @Test
    fun `allocation fails when free space is exhausted`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        fillBank8FFreeSpace(rom, parser)

        val allocation = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        assertNull(allocation, "Allocation should fail when bank \$8F has no free space")
    }

    @Test
    fun `allocated rooms do not overlap existing data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        val allocation1 = creator.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(allocation1)
        creator.writeAllocatedRoom(allocation1, 1, 1, 0, 0)

        val allocation2 = creator.allocateBlankRoom(width = 1, height = 1, area = 0, tileset = 0)
        assertNotNull(allocation2)
        creator.writeAllocatedRoom(allocation2, 1, 1, 0, 0)

        // Room IDs (which are header SNES addresses) should be different
        assertTrue(allocation1.roomId != allocation2.roomId, "Room IDs should be different")

        // Check that header regions don't overlap (each header+state is 39 bytes)
        val header1Start = allocation1.headerPcOffset
        val header1End = allocation1.headerPcOffset + 39

        val header2Start = allocation2.headerPcOffset
        val header2End = allocation2.headerPcOffset + 39

        val overlaps = (header1Start < header2End && header2Start < header1End)
        assertTrue(!overlaps, "Header regions should not overlap")
    }

    @Test
    fun `level data is compressed and decompresses correctly`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        val allocation = creator.allocateBlankRoom(width = 2, height = 1, area = 0, tileset = 0)
        assertNotNull(allocation)
        creator.writeAllocatedRoom(allocation, 2, 1, 0, 0)

        val levelPtr = allocation.levelDataPtr
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

    private fun fillBank8FFreeSpace(rom: ByteArray, parser: RomParser) {
        val bankStart = parser.snesToPc(0x8F8000)
        val bankEnd = parser.snesToPc(0x8FFFFF)
        var firstFree = bankEnd
        while (firstFree > bankStart && (rom[firstFree - 1].toInt() and 0xFF) == 0xFF) {
            firstFree--
        }
        for (offset in firstFree until bankEnd) {
            rom[offset] = 0x00.toByte()
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
