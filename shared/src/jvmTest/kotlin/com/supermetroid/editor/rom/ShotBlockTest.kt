package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Investigates shot block (type 0xC) BTS values to determine what weapon breaks them.
 * Tests against West Ocean (0x93FE) and other rooms with known shot block types.
 */
class ShotBlockTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    private fun dumpShotBlocks(parser: RomParser, roomId: Int, roomName: String) {
        val room = parser.readRoomHeader(roomId) ?: run {
            println("Could not read room $roomName (0x${roomId.toString(16)})")
            return
        }
        val levelData = parser.decompressLZ2(room.levelDataPtr)
        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val tileDataStart = 2
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val btsDataStart = tileDataStart + layer1Size

        // Also parse PLMs to see if any affect shot blocks
        val plms = parser.parsePlmSet(room.plmSetPtr)

        println("=== $roomName (0x${roomId.toString(16)}) — ${room.width}×${room.height} screens ===")
        println("PLMs: ${plms.size} entries")

        // Count BTS distribution for shot blocks
        val btsCounts = mutableMapOf<Int, Int>()
        val shotBlocks = mutableListOf<Triple<Int, Int, Int>>() // (x, y, bts)

        for (by in 0 until blocksTall) {
            for (bx in 0 until blocksWide) {
                val idx = by * blocksWide + bx
                val offset = tileDataStart + idx * 2
                if (offset + 1 >= levelData.size) continue
                val lo = levelData[offset].toInt() and 0xFF
                val hi = levelData[offset + 1].toInt() and 0xFF
                val blockType = ((hi shl 8) or lo shr 12) and 0x0F
                if (blockType == 0xC) {
                    val btsOffset = btsDataStart + idx
                    val bts = if (btsOffset < levelData.size) levelData[btsOffset].toInt() and 0xFF else 0
                    btsCounts[bts] = (btsCounts[bts] ?: 0) + 1
                    shotBlocks.add(Triple(bx, by, bts))
                }
            }
        }

        println("Shot block BTS distribution:")
        for ((bts, count) in btsCounts.toSortedMap()) {
            println("  BTS 0x${bts.toString(16).padStart(2, '0')}: $count blocks")
        }

        println("Shot block positions (${shotBlocks.size} total):")
        for ((x, y, bts) in shotBlocks) {
            // Check if any PLM is at or near this position
            val nearbyPlm = plms.find { it.x == x && it.y == y }
            val plmTag = if (nearbyPlm != null) " [PLM 0x${nearbyPlm.id.toString(16)}]" else ""
            println("  ($x, $y) BTS=0x${bts.toString(16).padStart(2, '0')}$plmTag")
        }

        // Also dump PLMs that might be shot-block related (not door caps)
        val nonDoorPlms = plms.filter { RomParser.doorCapColor(it.id) == null }
        if (nonDoorPlms.isNotEmpty()) {
            println("Non-door PLMs:")
            for (plm in nonDoorPlms) {
                println("  PLM 0x${plm.id.toString(16).padStart(4, '0')} at (${plm.x}, ${plm.y}) param=0x${plm.param.toString(16)}")
            }
        }
        println()
    }

    @Test
    fun `dump West Ocean shot blocks`() {
        val parser = loadTestRom() ?: return
        dumpShotBlocks(parser, 0x93FE, "West Ocean")
    }

    @Test
    fun `dump Landing Site shot blocks`() {
        val parser = loadTestRom() ?: return
        dumpShotBlocks(parser, 0x91F8, "Landing Site")
    }

    @Test
    fun `dump shot block PLM table from ROM`() {
        val parser = loadTestRom() ?: return
        // Shot block PLM table at SNES $94:9EA6
        // Each entry is a 2-byte PLM ID, indexed by BTS*2
        val tableAddr = parser.snesToPc(0x949EA6)
        val romData = parser.getRomData()
        
        println("=== Shot Block PLM Table ($94:9EA6) ===")
        println("BTS -> PLM ID (what happens when shot/bombed)")
        for (bts in 0 until 0x50) {
            val offset = tableAddr + bts * 2
            if (offset + 1 >= romData.size) break
            val lo = romData[offset].toInt() and 0xFF
            val hi = romData[offset + 1].toInt() and 0xFF
            val plmId = (hi shl 8) or lo
            if (plmId != 0) {
                println("  BTS 0x${bts.toString(16).padStart(2, '0')}: PLM 0x${plmId.toString(16).padStart(4, '0')}")
            }
        }
    }

    @Test
    fun `analyze D0xx shot block PLM headers`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        
        println("=== Shot Block Reaction PLM Headers (D0xx) ===")
        // Each PLM header has 2 pointers: setup routine, first instruction pointer
        // For door-type PLMs there are 3 pointers
        for (bts in 0..0x0B) {
            val tableOffset = parser.snesToPc(0x949EA6) + bts * 2
            val lo = romData[tableOffset].toInt() and 0xFF
            val hi = romData[tableOffset + 1].toInt() and 0xFF
            val plmId = (hi shl 8) or lo
            
            // Read the PLM header at $84:plmId
            val headerAddr = parser.snesToPc(0x840000 or plmId)
            if (headerAddr + 5 < romData.size) {
                val setup = (romData[headerAddr + 1].toInt() and 0xFF shl 8) or (romData[headerAddr].toInt() and 0xFF)
                val instr = (romData[headerAddr + 3].toInt() and 0xFF shl 8) or (romData[headerAddr + 2].toInt() and 0xFF)
                // Read first few bytes of header for analysis
                val headerBytes = (0 until 8).map { romData[headerAddr + it].toInt() and 0xFF }
                    .joinToString(" ") { "%02X".format(it) }
                println("  BTS 0x${bts.toString(16).padStart(2, '0')}: PLM 0x${plmId.toString(16)} -> setup=0x${setup.toString(16)}, instr=0x${instr.toString(16)}, raw=[$headerBytes]")
            }
        }
    }

    @Test
    fun `dump multiple rooms with shot blocks`() {
        val parser = loadTestRom() ?: return
        // Rooms known to have various shot block types
        val rooms = listOf(
            0x93FE to "West Ocean",
            0x91F8 to "Landing Site",
            0x9804 to "Bomb Torizo",
            0x92FD to "Parlor and Alcatraz",
            0xA59F to "Kraid",
        )
        for ((id, name) in rooms) {
            dumpShotBlocks(parser, id, name)
        }
    }
}
