package com.supermetroid.editor.rom

import com.supermetroid.editor.data.NewRoomAllocation
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.RoomRepository

/**
 * Creates new blank rooms with two-phase allocation.
 *
 * CRITICAL: In Super Metroid, the room ID IS the SNES address of the room header.
 * We allocate header+state (39 bytes) in bank $8F first via RomFreeSpaceAllocator,
 * and the resulting SNES address IS the room ID.
 *
 * Two-phase allocation:
 * 1. Reserve ALL structures (header, door table, level data, PLM set, enemy pop, enemy GFX, scroll)
 * 2. If ANY reserve fails, return null without mutating ROM
 * 3. Store allocation + compressed level data on RoomEdits.newRoomAllocation
 * 4. Write to ROM only at export time
 *
 * Allocates:
 * - Room header + state select (39 bytes) → room ID = headerAllocation.snesAddress & 0xFFFF
 * - Door table (2 bytes minimum for empty table)
 * - Level data space (reserved, not written until export)
 * - PLM set (2 bytes: terminator 0x0000)
 * - Enemy population (3 bytes: terminator 0xFFFF + kill count byte)
 * - Enemy GFX set (2 bytes: terminator 0xFFFF)
 * - Scroll data (width × height bytes: 0x01 = blue/explorable)
 *
 * All allocations use the existing RomFreeSpaceAllocator fail-closed approach.
 */
class RoomCreator(
    private val romData: ByteArray,
    private val romParser: RomParser,
    /**
     * Existing in-memory allocations from project.rooms values with newRoomAllocation != null.
     * These are treated as already-reserved space to avoid collisions between multiple creates.
     */
    private val existingAllocations: List<com.supermetroid.editor.data.NewRoomAllocation> = emptyList(),
) {
    private val snesToPc = romParser::snesToPc
    private val pcToSnes = romParser::pcToSnes

    data class AllocationResult(
        val roomId: Int,
        val allocation: NewRoomAllocation,
    )

    /**
     * Allocate a new blank room with the specified dimensions.
     * Returns null if there is insufficient free space to allocate all structures.
     *
     * CRITICAL: Header is allocated FIRST in bank $8F, and its SNES address IS the room ID.
     * TWO-PHASE: Reserves all space but writes NOTHING to ROM.
     *
     * @param width Room width in screens (1-15)
     * @param height Room height in screens (1-15)
     * @param area Area index (0-6)
     * @param tileset Tileset index (0-28)
     * @return AllocationResult if successful, null if free space exhausted
     */
    fun allocateBlankRoom(
        width: Int = 1,
        height: Int = 1,
        area: Int = 0,
        tileset: Int = 0,
    ): AllocationResult? {
        require(width in 1..15) { "width must be 1-15" }
        require(height in 1..15) { "height must be 1-15" }
        require(area in 0..6) { "area must be 0-6" }
        require(tileset in 0..28) { "tileset must be 0-28" }

        val usedRoomIds = collectUsedRoomIds()

        // Mark existing in-memory allocations as used by writing sentinel bytes
        // This prevents collisions when creating multiple rooms in the same session
        val markedRanges = mutableListOf<Pair<Int, Int>>()
        for (alloc in existingAllocations) {
            // Mark header+state
            markRangeAsUsed(alloc.headerPcOffset, 39, markedRanges)
            // Mark door table
            val doorPc = snesToPc(0x8F0000 or alloc.doorTablePtr)
            markRangeAsUsed(doorPc, 2, markedRanges)
            // Mark level data
            markRangeAsUsed(alloc.levelDataPcOffset, alloc.compressedLevelData.size, markedRanges)
            // Mark PLM set
            val plmPc = snesToPc(0x8F0000 or alloc.plmSetPtr)
            markRangeAsUsed(plmPc, 2, markedRanges)
            // Mark enemy pop
            val enemyPopPc = snesToPc(0xA10000 or alloc.enemyPopPtr)
            markRangeAsUsed(enemyPopPc, 3, markedRanges)
            // Mark enemy GFX
            val enemyGfxPc = snesToPc(0xB40000 or alloc.enemyGfxPtr)
            markRangeAsUsed(enemyGfxPc, 2, markedRanges)
            // Mark scroll data (size depends on room dimensions, estimate conservatively)
            val scrollPc = snesToPc(0x8F0000 or alloc.scrollPtr)
            markRangeAsUsed(scrollPc, 15 * 15, markedRanges) // Max possible size
        }

        val allocator = RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = snesToPc,
            pcToSnes = pcToSnes,
            guardBytes = 1,
        )

        // CRITICAL: Allocate header+state FIRST. Its SNES address IS the room ID.
        val headerAllocation = allocator.reserve(
            size = 39,
            banks = listOf(0x8F),
            label = "new room header+state",
        )
        
        // Restore marked ranges before returning
        restoreMarkedRanges(markedRanges)
        
        if (headerAllocation == null) return null

        val roomId = headerAllocation.snesAddress and 0xFFFF
        // Fail if this room ID is already in use
        if (roomId in usedRoomIds) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Reserve door table
        val doorTableAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0x8F),
            label = "room 0x${roomId.toString(16)} door table",
        )
        if (doorTableAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Prepare compressed level data but DON'T write it yet
        val compressedLevelData = createMinimalLevelData(width, height)
        
        // Reserve space for level data (DON'T use allocate() which writes!)
        val levelDataAllocation = allocator.reserve(
            size = compressedLevelData.size,
            banks = levelDataRelocationBanks(),
            label = "room 0x${roomId.toString(16)} level data",
        )
        if (levelDataAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Reserve PLM set
        val plmSetAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0x8F),
            label = "room 0x${roomId.toString(16)} PLM set",
        )
        if (plmSetAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Reserve enemy population
        val enemyPopAllocation = allocator.reserve(
            size = 3,
            banks = listOf(0xA1),
            label = "room 0x${roomId.toString(16)} enemy population",
        )
        if (enemyPopAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Reserve enemy GFX set
        val enemyGfxAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0xB4),
            label = "room 0x${roomId.toString(16)} enemy GFX set",
        )
        if (enemyGfxAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // Reserve scroll data
        val scrollDataAllocation = allocator.reserve(
            size = width * height,
            banks = listOf(0x8F),
            label = "room 0x${roomId.toString(16)} scroll data",
        )
        if (scrollDataAllocation == null) {
            restoreMarkedRanges(markedRanges)
            return null
        }

        // SUCCESS - don't restore marked ranges, they represent real allocations now

        // Find a free room index
        val roomIndex = findFreeRoomIndex(area)

        val allocation = NewRoomAllocation(
            headerPcOffset = headerAllocation.pcOffset,
            doorTablePtr = doorTableAllocation.snesAddress and 0xFFFF,
            levelDataPtr = levelDataAllocation.snesAddress,
            levelDataPcOffset = levelDataAllocation.pcOffset,
            compressedLevelData = compressedLevelData,
            plmSetPtr = plmSetAllocation.snesAddress and 0xFFFF,
            enemyPopPtr = enemyPopAllocation.snesAddress and 0xFFFF,
            enemyGfxPtr = enemyGfxAllocation.snesAddress and 0xFFFF,
            scrollPtr = scrollDataAllocation.snesAddress and 0xFFFF,
            roomIndex = roomIndex,
        )

        return AllocationResult(roomId = roomId, allocation = allocation)
    }

    /**
     * Find a free room index for the given area.
     * Room index is byte 0 of the header. Using 0x00 may collide with area map index.
     * Returns the first unused index in range 0-255.
     */
    /**
     * Build a synthetic Room object from a NewRoomAllocation.
     * This allows the editor to load and display a newly created room before it's written to ROM.
     */
    fun buildSyntheticRoom(
        roomId: Int,
        allocation: com.supermetroid.editor.data.NewRoomAllocation,
        width: Int,
        height: Int,
        area: Int,
        tileset: Int,
        mapX: Int,
        mapY: Int,
        musicData: Int = 0x05,
        musicTrack: Int = 0x05,
    ): com.supermetroid.editor.data.Room {
        return com.supermetroid.editor.data.Room(
            roomId = roomId,
            name = "New Room 0x${roomId.toString(16).uppercase()}",
            handle = "new_room_${roomId.toString(16).lowercase()}",
            index = allocation.roomIndex,
            area = area,
            mapX = mapX,
            mapY = mapY,
            width = width,
            height = height,
            upScroller = 0x70,
            downScroller = 0xA0,
            creBitflag = 0xE0,
            doorOut = allocation.doorTablePtr,
            levelDataPtr = allocation.levelDataPtr,
            tileset = tileset,
            musicData = musicData,
            musicTrack = musicTrack,
            fxPtr = 0,
            enemySetPtr = allocation.enemyPopPtr,
            enemyGfxPtr = allocation.enemyGfxPtr,
            bgScrolling = 0,
            roomScrollsPtr = allocation.scrollPtr,
            mainAsmPtr = 0,
            plmSetPtr = allocation.plmSetPtr,
            bgDataPtr = 0,
            setupAsmPtr = 0,
        )
    }

    private fun findFreeRoomIndex(area: Int): Int {
        val usedIndices = mutableSetOf<Int>()
        
        // Collect all used room indices from existing rooms
        for (roomInfo in RoomRepository().getAllRooms()) {
            try {
                val room = romParser.readRoomHeader(roomInfo.getRoomIdAsInt())
                if (room != null && room.area == area) {
                    usedIndices.add(room.index)
                }
            } catch (e: Exception) {
                // Skip rooms that fail to parse
            }
        }
        
        // Find first free index
        for (index in 0..255) {
            if (index !in usedIndices) {
                return index
            }
        }
        
        // Fallback to 0 if all indices are used (unlikely)
        return 0
    }

    /**
     * Create initial RoomEdits for a newly allocated room.
     * Stores allocation details so export can write the room.
     */
    fun createInitialRoomEdits(
        roomId: Int,
        allocation: NewRoomAllocation,
        width: Int,
        height: Int,
        area: Int,
        tileset: Int,
        mapX: Int = 0,
        mapY: Int = 0,
        musicData: Int = 0x05,
        musicTrack: Int = 0x05,
    ): RoomEdits {
        val roomEdits = RoomEdits(roomId = roomId)

        roomEdits.roomHeaderChange = RoomHeaderChange(
            index = allocation.roomIndex,
            area = area,
            mapX = mapX,
            mapY = mapY,
            width = width,
            height = height,
            upScroller = 0x70,
            downScroller = 0xA0,
            creBitflag = 0x00,
            doorOut = allocation.doorTablePtr,
        )

        roomEdits.stateDataChange = com.supermetroid.editor.data.StateDataChange(
            tileset = tileset,
            musicData = musicData,
            musicTrack = musicTrack,
            bgScrolling = 0,
        )

        roomEdits.newRoomAllocation = allocation

        return roomEdits
    }

    /**
     * Write the allocated room data to ROM.
     * Should be called during export after all allocations are confirmed.
     * 
     * CRITICAL: The header is written at the room ID address (already allocated).
     */
    fun writeAllocatedRoom(
        roomId: Int,
        allocation: NewRoomAllocation,
        width: Int,
        height: Int,
        area: Int,
        tileset: Int,
        mapX: Int = 0,
        mapY: Int = 0,
        musicData: Int = 0x05,
        musicTrack: Int = 0x05,
    ) {
        val headerPc = allocation.headerPcOffset

        // Write 11-byte room header
        romData[headerPc] = allocation.roomIndex.toByte()
        romData[headerPc + 1] = area.toByte()
        romData[headerPc + 2] = mapX.toByte()
        romData[headerPc + 3] = mapY.toByte()
        romData[headerPc + 4] = width.toByte()
        romData[headerPc + 5] = height.toByte()
        romData[headerPc + 6] = 0x70.toByte()
        romData[headerPc + 7] = 0xA0.toByte()
        romData[headerPc + 8] = 0x00.toByte()
        writeU16(romData, headerPc + 9, allocation.doorTablePtr)

        // Write 2-byte state select (E5E6 = default)
        val stateSelectPc = headerPc + 11
        writeU16(romData, stateSelectPc, 0xE5E6)

        // Write 26-byte state data
        val statePc = stateSelectPc + 2
        writeU24(romData, statePc, allocation.levelDataPtr)
        romData[statePc + 3] = tileset.toByte()
        romData[statePc + 4] = musicData.toByte()
        romData[statePc + 5] = musicTrack.toByte()
        writeU16(romData, statePc + 6, 0x0000)  // FX pointer
        writeU16(romData, statePc + 8, allocation.enemyPopPtr)
        writeU16(romData, statePc + 10, allocation.enemyGfxPtr)
        writeU16(romData, statePc + 12, 0x0000)  // BG scrolling
        writeU16(romData, statePc + 14, allocation.scrollPtr)
        writeU16(romData, statePc + 16, 0x0000)  // RoomVar
        writeU16(romData, statePc + 18, 0x0000)  // Main ASM
        writeU16(romData, statePc + 20, allocation.plmSetPtr)
        writeU16(romData, statePc + 22, 0x0000)  // BG data
        writeU16(romData, statePc + 24, 0x0000)  // Setup ASM

        // Write compressed level data
        allocation.compressedLevelData.copyInto(romData, allocation.levelDataPcOffset)

        // Write door table (2-byte terminator)
        val doorTablePc = snesToPc(0x8F0000 or allocation.doorTablePtr)
        writeU16(romData, doorTablePc, 0x0000)

        // Write PLM set (2-byte terminator)
        val plmSetPc = snesToPc(0x8F0000 or allocation.plmSetPtr)
        writeU16(romData, plmSetPc, 0x0000)

        // Write enemy population (3 bytes: terminator + kill count)
        val enemyPopPc = snesToPc(0xA10000 or allocation.enemyPopPtr)
        writeU16(romData, enemyPopPc, 0xFFFF)
        romData[enemyPopPc + 2] = 0x00.toByte()

        // Write enemy GFX set (2-byte terminator)
        val enemyGfxPc = snesToPc(0xB40000 or allocation.enemyGfxPtr)
        writeU16(romData, enemyGfxPc, 0xFFFF)

        // Write scroll data (blue/explorable for all screens)
        val scrollDataPc = snesToPc(0x8F0000 or allocation.scrollPtr)
        for (i in 0 until width * height) {
            romData[scrollDataPc + i] = 0x01.toByte()
        }
    }

    /**
     * Collect used room IDs from RoomRepository instead of byte-scanning.
     * This avoids false positives from ROM patterns that look like headers.
     */
    private fun collectUsedRoomIds(): Set<Int> {
        val ids = mutableSetOf<Int>()
        
        for (roomInfo in RoomRepository().getAllRooms()) {
            ids.add(roomInfo.getRoomIdAsInt())
        }
        
        // Add room IDs from existing allocations
        for (alloc in existingAllocations) {
            val headerSnes = pcToSnes(alloc.headerPcOffset)
            ids.add(headerSnes and 0xFFFF)
        }
        
        return ids
    }

    /**
     * Temporarily mark a range as used by writing sentinel bytes.
     * Records original values for later restoration.
     */
    private fun markRangeAsUsed(pcOffset: Int, size: Int, markedRanges: MutableList<Pair<Int, Int>>) {
        if (pcOffset < 0 || pcOffset + size > romData.size) return
        markedRanges.add(Pair(pcOffset, size))
        for (i in 0 until size) {
            romData[pcOffset + i] = 0x00.toByte()
        }
    }

    /**
     * Restore all marked ranges back to 0xFF (free space).
     */
    private fun restoreMarkedRanges(markedRanges: List<Pair<Int, Int>>) {
        for ((pcOffset, size) in markedRanges) {
            for (i in 0 until size) {
                if (pcOffset + i < romData.size) {
                    romData[pcOffset + i] = 0xFF.toByte()
                }
            }
        }
    }

    private fun createMinimalLevelData(width: Int, height: Int): ByteArray {
        val blocksWide = width * 16
        val blocksTall = height * 16
        val totalBlocks = blocksWide * blocksTall

        val layer1Size = totalBlocks * 2
        val decompressed = ByteArray(2 + layer1Size + totalBlocks)

        writeU16(decompressed, 0, layer1Size)

        for (i in 0 until totalBlocks) {
            writeU16(decompressed, 2 + i * 2, RomConstants.AIR_TILE_WORD)
        }

        for (i in 0 until totalBlocks) {
            decompressed[2 + layer1Size + i] = 0x00.toByte()
        }

        return LZ5Compressor.compress(decompressed)
    }

    private fun levelDataRelocationBanks(): List<Int> {
        return (0xCE downTo 0xC0).toList()
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
