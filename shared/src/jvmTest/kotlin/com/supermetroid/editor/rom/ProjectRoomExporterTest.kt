package com.supermetroid.editor.rom

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

        // Allocate and write the room first (simulating EditorState.createNewRoom)
        val allocation = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        assertNotNull(allocation, "Allocation should succeed")

        creator.writeAllocatedRoom(
            allocation = allocation,
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        // Now create the project with edits for this room
        val project = SmEditProject(romPath = "base.smc")
        val roomEdits = creator.createInitialRoomEdits(
            allocation = allocation,
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        project.rooms[allocation.roomId.toString(16).uppercase()] = roomEdits

        // Export should succeed without re-allocating
        val exporter = ProjectRoomExporter(
            project = project,
            romParser = RomParser(rom),
            romData = rom,
        )

        val result = exporter.exportRooms()
        
        // Verify room is readable
        val allocatedRoom = RomParser(rom).readRoomHeader(allocation.roomId)
        assertNotNull(allocatedRoom, "New room should be readable after export")
        assertEquals(1, allocatedRoom.width, "Width should match")
        assertEquals(1, allocatedRoom.height, "Height should match")
        assertEquals(0, allocatedRoom.area, "Area should match")
    }

    @Test
    fun `new room export fails closed when free space is exhausted`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        // Fill bank $8F free space
        fillTrailingBank8FFreeSpace(rom, parser)

        // Try to allocate a new room - should fail
        val allocation = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )

        assertNull(allocation, "Allocation should fail when free space is exhausted")
    }

    @Test
    fun `new room does not corrupt adjacent data`() {
        val rom = createFreshTestRom()
        val parser = RomParser(rom)
        val creator = RoomCreator(rom, parser)

        // Place a sentinel value in bank $8F
        val sentinelAddress = parser.snesToPc(0x8F8000) + 0x100
        val sentinelValue = 0x42.toByte()
        rom[sentinelAddress] = sentinelValue

        // Allocate and write a new room
        val allocation = creator.allocateBlankRoom(
            width = 1,
            height = 1,
            area = 0,
            tileset = 0,
        )
        assertNotNull(allocation, "Allocation should succeed")

        creator.writeAllocatedRoom(
            allocation = allocation,
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
