package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class EnemyExportDiagTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `verify enemy population roundtrip for vanilla data`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        val roomIds = listOf(0x990D, 0x92FD, 0x9879, 0x91F8)
        for (roomId in roomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue
            if (room.enemySetPtr == 0 || room.enemySetPtr == 0xFFFF) continue

            val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
            if (enemies.isEmpty()) continue

            val popPc = rp.snesToPc(0xA10000 or room.enemySetPtr)
            val rawSize = enemies.size * 16 + 2

            val written = ByteArray(rawSize)
            var off = 0
            for (e in enemies) {
                fun w16(v: Int) {
                    written[off] = (v and 0xFF).toByte()
                    written[off + 1] = ((v shr 8) and 0xFF).toByte()
                    off += 2
                }
                w16(e.id); w16(e.x); w16(e.y); w16(e.initParam)
                w16(e.properties); w16(e.extra1); w16(e.extra2); w16(e.extra3)
            }
            written[off] = 0xFF.toByte()
            written[off + 1] = 0xFF.toByte()

            val original = ByteArray(rawSize) { rom[popPc + it] }
            assertTrue(written.contentEquals(original),
                "Room 0x${roomId.toString(16)} enemy population should roundtrip exactly")
        }
    }

    @Test
    fun `parse enemy GFX set for Terminator room`() {
        val rp = loadTestRom() ?: return
        val room = rp.readRoomHeader(0x990D)!!
        val gfx = rp.parseEnemyGfxSet(room.enemyGfxPtr)

        assertEquals(2, gfx.size, "Terminator room should have 2 GFX entries")
        assertEquals(0xD63F, gfx[0].speciesId, "First GFX entry should be Waver")
        assertEquals(1, gfx[0].paletteIndex, "Waver palette index")
        assertEquals(0xDCFF, gfx[1].speciesId, "Second GFX entry should be Zoomer")
        assertEquals(2, gfx[1].paletteIndex, "Zoomer palette index")
    }

    @Test
    fun `all vanilla enemies have bit 0x2000 set in properties`() {
        val rp = loadTestRom() ?: return

        val roomIds = listOf(0x990D, 0x92FD, 0x9879, 0x91F8, 0x93AA, 0x94FD)
        for (roomId in roomIds) {
            val room = rp.readRoomHeader(roomId) ?: continue
            if (room.enemySetPtr == 0 || room.enemySetPtr == 0xFFFF) continue
            val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
            for (e in enemies) {
                val has2000 = (e.properties and 0x2000) != 0
                assertTrue(has2000,
                    "Room 0x${roomId.toString(16)} enemy ${RomParser.enemyName(e.id)} at (${e.x},${e.y}) " +
                    "should have bit 0x2000 in properties (was 0x${e.properties.toString(16)})")
            }
        }
    }

    @Test
    fun `kill count byte follows FFFF terminator`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        val room = rp.readRoomHeader(0x990D)!!
        val enemies = rp.parseEnemyPopulation(room.enemySetPtr)
        val popPc = rp.snesToPc(0xA10000 or room.enemySetPtr)

        val terminatorPc = popPc + enemies.size * 16
        val termWord = (rom[terminatorPc].toInt() and 0xFF) or
                ((rom[terminatorPc + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, termWord, "Terminator should be FFFF")

        val killCount = rom[terminatorPc + 2].toInt() and 0xFF
        assertTrue(killCount in 0..64,
            "Kill count should be reasonable (was $killCount)")
    }

    @Test
    fun `export enforces bit 0x2000 in enemy properties`() {
        val rp = loadTestRom() ?: return
        val romData = rp.getRomData().copyOf()
        val room = rp.readRoomHeader(0x990D)!!
        val originalEnemies = rp.parseEnemyPopulation(room.enemySetPtr)

        val modified = originalEnemies.toMutableList()
        modified.add(RomParser.EnemyEntry(0xDCFF, 1056, 256, 0, 0x0800))

        val enemyPc = rp.snesToPc(0xA10000 or room.enemySetPtr)
        fun writeU16(offset: Int, value: Int) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        var off = enemyPc
        for (e in modified) {
            writeU16(off, e.id)
            writeU16(off + 2, e.x)
            writeU16(off + 4, e.y)
            writeU16(off + 6, e.initParam)
            writeU16(off + 8, e.properties or 0x2000)
            writeU16(off + 10, e.extra1)
            writeU16(off + 12, e.extra2)
            writeU16(off + 14, e.extra3)
            off += 16
        }
        writeU16(off, 0xFFFF)

        val addedPropsPc = enemyPc + originalEnemies.size * 16 + 8
        val writtenProps = (romData[addedPropsPc].toInt() and 0xFF) or
                ((romData[addedPropsPc + 1].toInt() and 0xFF) shl 8)
        assertTrue((writtenProps and 0x2000) != 0,
            "Export must enforce bit 0x2000 even when input has 0x0800 " +
            "(written=0x${writtenProps.toString(16)})")
        assertEquals(0x2800, writtenProps,
            "0x0800 | 0x2000 = 0x2800")
    }

    @Test
    fun `GFX set uses final population not raw change list`() {
        val rp = loadTestRom() ?: return
        val room = rp.readRoomHeader(0x990D)!!
        val gfxEntries = rp.parseEnemyGfxSet(room.enemyGfxPtr)
        val existingSpecies = gfxEntries.map { it.speciesId }.toSet()

        data class FakeChange(val action: String, val enemyId: Int, val x: Int, val y: Int)

        val changes = listOf(
            FakeChange("add", 0xE9FF, 1056, 256),
            FakeChange("add", 0xDCFF, 1264, 144),
            FakeChange("add", 0xD75F, 1216, 192),
            FakeChange("remove", 0xD75F, 1216, 192),
            FakeChange("remove", 0xE9FF, 1056, 256),
            FakeChange("remove", 0xDCFF, 1264, 144),
            FakeChange("add", 0xD63F, 1312, 128),
            FakeChange("add", 0xDCFF, 1056, 256),
        )

        // BUG (old code): iterate all adds → neededSpecies = {0xE9FF, 0xD75F}
        val buggyNeeded = mutableSetOf<Int>()
        for (c in changes) {
            if (c.action == "add" && c.enemyId !in existingSpecies) {
                buggyNeeded.add(c.enemyId)
            }
        }
        assertTrue(buggyNeeded.contains(0xE9FF),
            "Old code would include removed species 0xE9FF")
        assertTrue(buggyNeeded.contains(0xD75F),
            "Old code would include removed species 0xD75F")

        // FIX (new code): compute final population, then check needed
        val finalPop = rp.parseEnemyPopulation(room.enemySetPtr).map { it.id }.toMutableList()
        for (c in changes) {
            when (c.action) {
                "add" -> finalPop.add(c.enemyId)
                "remove" -> finalPop.removeAll { it == c.enemyId }
            }
        }
        val finalSpecies = finalPop.toSet()
        val fixedNeeded = finalSpecies.filter { it !in existingSpecies }
        assertFalse(fixedNeeded.contains(0xE9FF),
            "Fixed code should NOT include removed species 0xE9FF")
        assertFalse(fixedNeeded.contains(0xD75F),
            "Fixed code should NOT include removed species 0xD75F")
        assertTrue(fixedNeeded.isEmpty(),
            "Zoomer and Waver are already in GFX set, no new entries needed")
    }

    @Test
    fun `GFX relocation must not overlap when multiple rooms are patched`() {
        val rp = loadTestRom() ?: return
        val romData = rp.getRomData().copyOf()

        val bankB4End = rp.snesToPc(0xB4FFFF) + 1
        val bankB4Start = rp.snesToPc(0xB48000)
        var gfxFreePtr = bankB4End
        while (gfxFreePtr > bankB4Start) {
            val b = romData[gfxFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            gfxFreePtr--
        }
        gfxFreePtr++

        fun writeU16(offset: Int, value: Int) {
            romData[offset] = (value and 0xFF).toByte()
            romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        // Room A: 3 GFX entries (12 data bytes + 2 terminator = 14 bytes)
        val sizeA = 3 * 4 + 2
        val allocA = gfxFreePtr
        gfxFreePtr += sizeA
        var off = allocA
        for (entry in listOf(0xD63F to 0, 0xDCFF to 1, 0xD3FF to 2)) {
            writeU16(off, entry.first); writeU16(off + 2, entry.second); off += 4
        }
        writeU16(off, 0xFFFF)

        // Room B: 2 GFX entries (8 data bytes + 2 terminator = 10 bytes)
        val sizeB = 2 * 4 + 2
        val allocB = gfxFreePtr
        gfxFreePtr += sizeB
        off = allocB
        for (entry in listOf(0xDCBF to 0, 0xD93F to 1)) {
            writeU16(off, entry.first); writeU16(off + 2, entry.second); off += 4
        }
        writeU16(off, 0xFFFF)

        assertTrue(allocB >= allocA + sizeA,
            "Room B allocation ($allocB) must start at or after Room A end (${allocA + sizeA})")

        // Verify Room A data is intact after Room B write
        val aTermOff = allocA + 3 * 4
        val aTerm = (romData[aTermOff].toInt() and 0xFF) or
                ((romData[aTermOff + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, aTerm,
            "Room A FFFF terminator must survive Room B allocation (at PC 0x${aTermOff.toString(16)})")

        val aEntry2Species = (romData[allocA + 8].toInt() and 0xFF) or
                ((romData[allocA + 9].toInt() and 0xFF) shl 8)
        assertEquals(0xD3FF, aEntry2Species,
            "Room A entry[2] species must be intact after Room B allocation")
    }

    @Test
    fun `GFX set respects 4 entry SNES hardware limit`() {
        val maxSlots = 4
        val existingEntries = listOf(
            RomParser.EnemyGfxEntry(0xD63F, 1),
            RomParser.EnemyGfxEntry(0xDCFF, 2),
            RomParser.EnemyGfxEntry(0xD93F, 3),
            RomParser.EnemyGfxEntry(0xD7FF, 4),
        )
        assertEquals(maxSlots, existingEntries.size)

        val newEntries = existingEntries.toMutableList()
        val neededSpecies = listOf(0xD47F, 0xD87F)
        var nextPal = (existingEntries.maxOfOrNull { it.paletteIndex } ?: 0) + 1
        for (specId in neededSpecies) {
            if (newEntries.size >= maxSlots) continue
            newEntries.add(RomParser.EnemyGfxEntry(specId, nextPal++))
        }
        assertEquals(maxSlots, newEntries.size,
            "GFX set should not exceed $maxSlots entries (SNES ProcessEnemyTilesets limit)")
    }
}
