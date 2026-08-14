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

        assertTrue(allocation.headerAllocation.bank == 0x8F, "Header should be in bank \$8F")
        assertTrue(allocation.doorTableAllocation.bank == 0x8F, "Door table should be in bank \$8F")
        assertTrue(allocation.plmSetAllocation.bank == 0x8F, "PLM set should be in bank \$8F")
        assertTrue(allocation.enemyPopAllocation.bank == 0xA1, "Enemy pop should be in bank \$A1")
        assertTrue(allocation.enemyGfxAllocation.bank == 0xB4, "Enemy GFX should be in bank \$B4")
        assertTrue(allocation.scrollDataAllocation.bank == 0x8F, "Scroll data should be in bank \$8F")

        assertEquals(2, allocation.scrollDataAllocation.size, "Scroll data size should match room screens (2x2=4)")

        assertEquals(11, allocation.headerAllocation.size, "Header should be 11 bytes")
        assertEquals(28, allocation.stateSelectAllocation.size, "State select should be 28 bytes (E5E6 + 26-byte state)")
        assertEquals(2, allocation.doorTableAllocation.size, "Door table should be 2 bytes minimum")
        assertEquals(2, allocation.plmSetAllocation.size, "PLM set should be 2 bytes (terminator)")
        assertEquals(3, allocation.enemyPopAllocation.size, "Enemy pop should be 3 bytes (terminator + kill count)")
        assertEquals(2, allocation.enemyGfxAllocation.size, "Enemy GFX should be 2 bytes (terminator)")
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

        val doorTablePc = parser.snesToPc(0x8F0000 or allocation.doorTableAllocation.snesAddress)
        val doorTableValue = readU16(rom, doorTablePc)
        assertEquals(0x0000, doorTableValue, "Door table should be empty (0x0000 terminator)")

        val plmSetPc = allocation.plmSetAllocation.pcOffset
        val plmSetValue = readU16(rom, plmSetPc)
        assertEquals(0x0000, plmSetValue, "PLM set should be empty (0x0000 terminator)")

        val enemyPopPc = allocation.enemyPopAllocation.pcOffset
        val enemyPopValue = readU16(rom, enemyPopPc)
        assertEquals(0xFFFF, enemyPopValue, "Enemy population should be empty (0xFFFF terminator)")

        val scrollDataPc = allocation.scrollDataAllocation.pcOffset
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

        val range1Start = allocation1.headerAllocation.pcOffset
        val range1End = allocation1.scrollDataAllocation.pcOffset + allocation1.scrollDataAllocation.size

        val range2Start = allocation2.headerAllocation.pcOffset
        val range2End = allocation2.scrollDataAllocation.pcOffset + allocation2.scrollDataAllocation.size

        val overlaps = (range1Start < range2End && range2Start < range1End)
        assertTrue(!overlaps, "Allocated rooms should not overlap")
    }

    @Test
    fun `level data is compressed and decompresses correctly`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        val allocation = creator.allocateBlankRoom(width = 2, height = 1, area = 0, tileset = 0)
        assertNotNull(allocation)
        creator.writeAllocatedRoom(allocation, 2, 1, 0, 0)

        val levelPtr = allocation.levelDataAllocation.snesAddress
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
