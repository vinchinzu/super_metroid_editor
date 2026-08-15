package com.supermetroid.editor.rom

import com.supermetroid.editor.data.NewRoomAllocation
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.StateDataChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRoomExporterTest {
    @Test
    fun `expanded scroll data aborts when bank 8F has no free space`() {
        val rom = TestRomHelper.loadRomBytes()?.copyOf() ?: return
        val parser = RomParser(rom)
        val roomId = RoomRepository().getAllRooms()
            .map { it.getRoomIdAsInt() }
            .first { id ->
                val room = parser.readRoomHeader(id)
                room != null && room.roomScrollsPtr > 1 && room.width > 0 && room.height > 0
            }
        val room = parser.readRoomHeader(roomId)!!

        fillTrailingBank8FFreeSpace(rom, parser)

        val project = SmEditProject(romPath = "base.smc").also {
            it.getOrCreateRoom(roomId).roomHeaderChange = RoomHeaderChange(
                width = room.width + 1,
                height = room.height,
            )
        }

        val failure = assertFailsWith<ProjectRoomExportException> {
            ProjectRoomExporter(
                project = project,
                romParser = parser,
                romData = rom,
            ).exportRooms()
        }

        assertTrue(failure.message.orEmpty().contains("avoid corrupting adjacent data"))
    }

    @Test
    fun `new room creation allocates all required structures`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        // Allocate a new room
        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        assertNotNull(result, "Allocation should succeed")

        // Create RoomEdits with the allocation
        val roomEdits = creator.createInitialRoomEdits(
            roomId = result.roomId,
            allocation = result.allocation,
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        val project = SmEditProject(romPath = "base.smc")
        project.rooms[result.roomId.toString(16).uppercase()] = roomEdits

        // Export should write the room
        val exporter = ProjectRoomExporter(
            project = project,
            romParser = RomParser(rom),
            romData = rom,
        )

        exporter.exportRooms()
        
        // Verify room is readable
        val allocatedRoom = RomParser(rom).readRoomHeader(result.roomId)
        assertNotNull(allocatedRoom, "New room should be readable after export")
        assertEquals(1, allocatedRoom.width, "Width should match")
        assertEquals(1, allocatedRoom.height, "Height should match")
        assertEquals(0, allocatedRoom.area, "Area should match")
    }

    @Test
    fun `new room export fails closed when free space is exhausted`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())
        
        // Take snapshot before filling
        val romSnapshot = rom.copyOf()

        // Fill $A1 bank to cause allocation failure
        val bankA1Start = parser.snesToPc(0xA18000)
        val bankA1End = parser.snesToPc(0xA1FFFF)
        for (offset in bankA1Start..bankA1End) {
            rom[offset] = 0x00.toByte()
        }

        // Try to allocate - should fail
        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        assertNull(result, "Allocation should fail when free space is exhausted")
        
        // ROM should be unchanged except for the $A1 bank we filled
        // Check that $8F and other banks are unchanged
        val bank8FStart = parser.snesToPc(0x8F8000)
        val bank8FEnd = parser.snesToPc(0x8FFFFF)
        for (offset in bank8FStart..bank8FEnd) {
            assertEquals(
                romSnapshot[offset],
                rom[offset],
                "Bank \$8F should be unchanged at offset $offset"
            )
        }
    }

    @Test
    fun `new room does not corrupt adjacent data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        // Place a sentinel value in bank $8F
        val sentinelAddress = parser.snesToPc(0x8F8000) + 0x100
        val sentinelValue = 0x42.toByte()
        rom[sentinelAddress] = sentinelValue

        // Allocate and write a new room
        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        assertNotNull(result, "Allocation should succeed")

        creator.writeAllocatedRoom(
            roomId = result.roomId,
            allocation = result.allocation,
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        // Verify sentinel was not overwritten
        assertEquals(
            sentinelValue,
            rom[sentinelAddress],
            "Sentinel value should not be overwritten"
        )
    }

    @Test
    fun `export applies later tile edits to new room`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser, emptyList())

        // Allocate a new room
        val result = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        assertNotNull(result, "Allocation should succeed")

        // Create RoomEdits with the allocation
        val roomEdits = creator.createInitialRoomEdits(
            roomId = result.roomId,
            allocation = result.allocation,
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        // Add a tile edit (change tile at block position 0,0 to a non-air tile)
        roomEdits.operations.add(
            com.supermetroid.editor.data.EditOperation(
                description = "Test tile edit",
                edits = listOf(
                    com.supermetroid.editor.data.TileEdit(
                        blockX = 0,
                        blockY = 0,
                        oldBlockWord = RomConstants.AIR_TILE_WORD,
                        newBlockWord = 0x1234, // Some non-air tile
                        layer = com.supermetroid.editor.data.TILE_EDIT_LAYER_1,
                    )
                )
            )
        )

        val project = SmEditProject(romPath = "base.smc")
        project.rooms[result.roomId.toString(16).uppercase()] = roomEdits

        // Export should write the room AND apply the tile edit
        val exporter = ProjectRoomExporter(
            project = project,
            romParser = RomParser(rom),
            romData = rom,
        )
        exporter.exportRooms()

        // Read back the level data and verify the tile edit was applied
        val writtenRoom = parser.readRoomHeader(result.roomId)
        assertNotNull(writtenRoom, "Room should be written to ROM")
        
        val decompressed = parser.decompressLZ2(writtenRoom.levelDataPtr)
        val layer1Size = (decompressed[0].toInt() and 0xFF) or ((decompressed[1].toInt() and 0xFF) shl 8)
        
        // First tile should be the edited value
        val firstTile = (decompressed[2].toInt() and 0xFF) or ((decompressed[3].toInt() and 0xFF) shl 8)
        assertEquals(0x1234, firstTile, "First tile should be the edited value, not air")
    }

    private fun createFreshTestRom(): ByteArray {
        val rom = ByteArray(RomConstants.ROM_SIZE) { 0xFF.toByte() }
        return rom
    }

    private fun fillTrailingBank8FFreeSpace(rom: ByteArray, parser: RomParser) {
        val bankStart = parser.snesToPc(0x8F8000)
        val bankEndExclusive = parser.snesToPc(0x8FFFFF) + 1
        var firstTrailingFree = bankEndExclusive
        while (firstTrailingFree > bankStart && (rom[firstTrailingFree - 1].toInt() and 0xFF) == 0xFF) {
            firstTrailingFree--
        }
        for (offset in firstTrailingFree until bankEndExclusive) {
            rom[offset] = 0
        }
    }
}
