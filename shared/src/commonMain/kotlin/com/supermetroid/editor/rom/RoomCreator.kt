package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomHeaderChange

/**
 * Creates new blank rooms with allocated pointers.
 *
 * Allocates:
 * - Room header in bank $8F (11 bytes)
 * - Door table (2 bytes minimum for empty table)
 * - Level data (compressed, minimal 1x1 screen air tiles)
 * - PLM set (2 bytes: terminator 0x0000)
 * - Enemy population (3 bytes: terminator 0xFFFF + kill count byte)
 * - Enemy GFX set (2 bytes: terminator 0xFFFF)
 * - Scroll data (1 byte: 0x01 = blue/explorable)
 * - State select (28 bytes: E5E6 default condition + 26-byte state data)
 *
 * All allocations use the existing RomFreeSpaceAllocator fail-closed approach.
 */
class RoomCreator(
    private val romData: ByteArray,
    private val romParser: RomParser,
) {
    private val snesToPc = romParser::snesToPc
    private val pcToSnes = romParser::pcToSnes

    data class NewRoomAllocation(
        val roomId: Int,
        val headerAllocation: RomAllocation,
        val stateSelectAllocation: RomAllocation,
        val doorTableAllocation: RomAllocation,
        val levelDataAllocation: RomAllocation,
        val plmSetAllocation: RomAllocation,
        val enemyPopAllocation: RomAllocation,
        val enemyGfxAllocation: RomAllocation,
        val scrollDataAllocation: RomAllocation,
    )

    /**
     * Allocate a new blank room with the specified dimensions.
     * Returns null if there is insufficient free space to allocate all structures.
     *
     * @param width Room width in screens (1-15)
     * @param height Room height in screens (1-15)
     * @param area Area index (0-6)
     * @param tileset Tileset index (0-28)
     * @return NewRoomAllocation if successful, null if free space exhausted
     */
    fun allocateBlankRoom(
        width: Int = 1,
        height: Int = 1,
        area: Int = 0,
        tileset: Int = 0,
    ): NewRoomAllocation? {
        require(width in 1..15) { "width must be 1-15" }
        require(height in 1..15) { "height must be 1-15" }
        require(area in 0..6) { "area must be 0-6" }
        require(tileset in 0..28) { "tileset must be 0-28" }

        val roomId = findUnusedRoomId() ?: return null

        val allocator = RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = snesToPc,
            pcToSnes = pcToSnes,
            guardBytes = 1,
        )

        val headerAllocation = allocator.reserve(
            size = 11 + 28,
            banks = listOf(0x8F),
            label = "new room 0x${roomId.toString(16)} header+stateSelect",
        ) ?: return null

        val stateSelectAllocation = RomAllocation(
            bank = headerAllocation.bank,
            pcOffset = headerAllocation.pcOffset + 11,
            snesAddress = headerAllocation.snesAddress + 11,
            size = 28,
            label = "new room 0x${roomId.toString(16)} stateSelect",
        )

        val doorTableAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0x8F),
            label = "new room 0x${roomId.toString(16)} door table",
        ) ?: return null

        val levelData = createMinimalLevelData(width, height)
        val levelDataAllocation = allocator.allocate(
            bytes = levelData,
            banks = levelDataRelocationBanks(),
            label = "new room 0x${roomId.toString(16)} level data",
        ) ?: return null

        val plmSetAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0x8F),
            label = "new room 0x${roomId.toString(16)} PLM set",
        ) ?: return null

        val enemyPopAllocation = allocator.reserve(
            size = 3,
            banks = listOf(0xA1),
            label = "new room 0x${roomId.toString(16)} enemy population",
        ) ?: return null

        val enemyGfxAllocation = allocator.reserve(
            size = 2,
            banks = listOf(0xB4),
            label = "new room 0x${roomId.toString(16)} enemy GFX set",
        ) ?: return null

        val scrollDataAllocation = allocator.reserve(
            size = width * height,
            banks = listOf(0x8F),
            label = "new room 0x${roomId.toString(16)} scroll data",
        ) ?: return null

        return NewRoomAllocation(
            roomId = roomId,
            headerAllocation = headerAllocation,
            stateSelectAllocation = stateSelectAllocation,
            doorTableAllocation = doorTableAllocation,
            levelDataAllocation = levelDataAllocation,
            plmSetAllocation = plmSetAllocation,
            enemyPopAllocation = enemyPopAllocation,
            enemyGfxAllocation = enemyGfxAllocation,
            scrollDataAllocation = scrollDataAllocation,
        )
    }

    /**
     * Create initial RoomEdits for a newly allocated room.
     * This creates a room header change that sets all the basic room properties.
     */
    fun createInitialRoomEdits(
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
        val roomEdits = RoomEdits(roomId = allocation.roomId)

        val doorOutPtr = allocation.doorTableAllocation.snesAddress and 0xFFFF

        roomEdits.roomHeaderChange = RoomHeaderChange(
            index = 0,
            area = area,
            mapX = mapX,
            mapY = mapY,
            width = width,
            height = height,
            upScroller = 0x70,
            downScroller = 0xA0,
            creBitflag = 0x00,
            doorOut = doorOutPtr,
        )

        roomEdits.stateDataChange = com.supermetroid.editor.data.StateDataChange(
            tileset = tileset,
            musicData = musicData,
            musicTrack = musicTrack,
            bgScrolling = 0,
        )

        return roomEdits
    }

    /**
     * Write the allocated room data to ROM.
     * Should be called during export after all allocations are confirmed.
     */
    fun writeAllocatedRoom(
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
        val headerPc = allocation.headerAllocation.pcOffset
        val doorOutPtr = allocation.doorTableAllocation.snesAddress and 0xFFFF

        romData[headerPc] = 0x00.toByte()
        romData[headerPc + 1] = area.toByte()
        romData[headerPc + 2] = mapX.toByte()
        romData[headerPc + 3] = mapY.toByte()
        romData[headerPc + 4] = width.toByte()
        romData[headerPc + 5] = height.toByte()
        romData[headerPc + 6] = 0x70.toByte()
        romData[headerPc + 7] = 0xA0.toByte()
        romData[headerPc + 8] = 0x00.toByte()
        writeU16(romData, headerPc + 9, doorOutPtr)

        val stateSelectPc = allocation.stateSelectAllocation.pcOffset
        writeU16(romData, stateSelectPc, 0xE5E6)

        val statePc = stateSelectPc + 2
        val levelPtr = allocation.levelDataAllocation.snesAddress
        writeU24(romData, statePc, levelPtr)
        romData[statePc + 3] = tileset.toByte()
        romData[statePc + 4] = musicData.toByte()
        romData[statePc + 5] = musicTrack.toByte()
        writeU16(romData, statePc + 6, 0x0000)
        val enemyPopPtr = allocation.enemyPopAllocation.snesAddress and 0xFFFF
        writeU16(romData, statePc + 8, enemyPopPtr)
        val enemyGfxPtr = allocation.enemyGfxAllocation.snesAddress and 0xFFFF
        writeU16(romData, statePc + 10, enemyGfxPtr)
        writeU16(romData, statePc + 12, 0x0000)
        val scrollPtr = allocation.scrollDataAllocation.snesAddress and 0xFFFF
        writeU16(romData, statePc + 14, scrollPtr)
        writeU16(romData, statePc + 16, 0x0000)
        writeU16(romData, statePc + 18, 0x0000)
        val plmPtr = allocation.plmSetAllocation.snesAddress and 0xFFFF
        writeU16(romData, statePc + 20, plmPtr)
        writeU16(romData, statePc + 22, 0x0000)
        writeU16(romData, statePc + 24, 0x0000)

        val doorTablePc = allocation.doorTableAllocation.pcOffset
        writeU16(romData, doorTablePc, 0x0000)

        val plmSetPc = allocation.plmSetAllocation.pcOffset
        writeU16(romData, plmSetPc, 0x0000)

        val enemyPopPc = allocation.enemyPopAllocation.pcOffset
        writeU16(romData, enemyPopPc, 0xFFFF)
        romData[enemyPopPc + 2] = 0x00.toByte()

        val enemyGfxPc = allocation.enemyGfxAllocation.pcOffset
        writeU16(romData, enemyGfxPc, 0xFFFF)

        val scrollDataPc = allocation.scrollDataAllocation.pcOffset
        for (i in 0 until width * height) {
            romData[scrollDataPc + i] = 0x01.toByte()
        }
    }

    private fun findUnusedRoomId(): Int? {
        val usedIds = collectUsedRoomIds()

        val bankStart = snesToPc(0x8F8000)
        val bankEnd = snesToPc(0x8FFFFF)

        for (offset in bankStart..bankEnd step 16) {
            val snesAddr = pcToSnes(offset)
            val roomId = snesAddr and 0xFFFF

            if (roomId in usedIds) continue
            if (roomId < 0x8000) continue

            if (isLocationFree(offset, 11 + 28)) {
                return roomId
            }
        }

        return null
    }

    private fun collectUsedRoomIds(): Set<Int> {
        val ids = mutableSetOf<Int>()

        val roomsStart = snesToPc(0x8F8000)
        val roomsEnd = snesToPc(0x8FFFFF)

        for (offset in roomsStart..roomsEnd) {
            if (offset + 11 >= romData.size) break

            val snesAddr = pcToSnes(offset)
            val possibleRoomId = snesAddr and 0xFFFF

            if (possibleRoomId < 0x8000) continue

            val width = romData[offset + 4].toInt() and 0xFF
            val height = romData[offset + 5].toInt() and 0xFF
            val area = romData[offset + 1].toInt() and 0xFF

            if (width in 1..15 && height in 1..15 && area in 0..6) {
                val testRoom = romParser.readRoomHeader(possibleRoomId)
                if (testRoom != null) {
                    ids.add(possibleRoomId)
                }
            }
        }

        return ids
    }

    private fun isLocationFree(pcOffset: Int, size: Int): Boolean {
        if (pcOffset < 0 || pcOffset + size > romData.size) return false
        for (i in 0 until size) {
            if ((romData[pcOffset + i].toInt() and 0xFF) != 0xFF) return false
        }
        return true
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
