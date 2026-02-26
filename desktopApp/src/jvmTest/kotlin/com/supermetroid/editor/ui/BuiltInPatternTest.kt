package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Validates that built-in patterns (gates, doors, stations) use correct PLM IDs
 * by cross-referencing against the actual ROM's PLM header table in bank $84.
 *
 * This catches the class of bug where PLM IDs were calculated with wrong offsets
 * (e.g., door caps at +4 instead of +6), producing IDs that point mid-header.
 */
class BuiltInPatternTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "test-resources/Super Metroid (JU) [!].smc",
            "../../../test-resources/Super Metroid (JU) [!].smc",
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    private fun readU16(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `seedBuiltInPatterns creates all expected patterns`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()
        state.seedBuiltInPatterns(parser)

        val patternIds = state.project.patterns.map { it.id }.toSet()

        val expectedIds = listOf(
            "builtin_gate_blue_left", "builtin_gate_blue_right",
            "builtin_gate_pink_left", "builtin_gate_pink_right",
            "builtin_gate_green_left", "builtin_gate_green_right",
            "builtin_gate_yellow_left", "builtin_gate_yellow_right",
            "builtin_door_blue_left", "builtin_door_blue_right",
            "builtin_door_red_left", "builtin_door_red_right",
            "builtin_door_green_left", "builtin_door_green_right",
            "builtin_door_yellow_left", "builtin_door_yellow_right",
        )

        // PLM-only items should NOT have tile patterns
        val plmOnlyIds = listOf(
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue", "builtin_ship"
        )
        for (id in plmOnlyIds) {
            assertFalse(id in patternIds, "PLM-only pattern should not exist: $id")
        }
        for (id in expectedIds) {
            assertTrue(id in patternIds, "Missing built-in pattern: $id")
        }
    }

    @Test
    fun `door pattern PLM IDs all point to valid PLM headers in ROM`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()
        state.seedBuiltInPatterns(parser)

        val romData = parser.getRomData()
        val validSetups = setOf(0xC794, 0xC7B1, 0xC7BB)

        val doorPatterns = state.project.patterns.filter { it.id.startsWith("builtin_door_") }
        assertTrue(doorPatterns.size >= 8, "Should have at least 8 door patterns")

        for (pattern in doorPatterns) {
            val plmCells = pattern.cells.filter { it.plmId != 0 }
            assertTrue(plmCells.isNotEmpty(), "${pattern.id} should have at least one PLM cell")

            for (cell in plmCells) {
                val pc = parser.snesToPc(0x840000 or cell.plmId)
                assertTrue(pc > 0 && pc + 5 < romData.size,
                    "${pattern.id}: PLM 0x${cell.plmId.toString(16)} PC out of range")

                val setup = readU16(romData, pc)
                assertTrue(setup in validSetups,
                    "${pattern.id}: PLM 0x${cell.plmId.toString(16)} has setup 0x${setup.toString(16)}, " +
                    "expected one of ${validSetups.map { "0x${it.toString(16)}" }}")
            }
        }
    }

    @Test
    fun `gate pattern PLM IDs all use C836`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()
        state.seedBuiltInPatterns(parser)

        val gatePatterns = state.project.patterns.filter { it.id.startsWith("builtin_gate_") }
        assertTrue(gatePatterns.size >= 8, "Should have at least 8 gate patterns")

        for (pattern in gatePatterns) {
            val plmCells = pattern.cells.filter { it.plmId != 0 }
            assertTrue(plmCells.isNotEmpty(), "${pattern.id} should have a PLM cell")
            for (cell in plmCells) {
                assertEquals(0xC836, cell.plmId,
                    "${pattern.id}: gate PLM should be 0xC836, got 0x${cell.plmId.toString(16)}")
            }
        }
    }

    @Test
    fun `door patterns have correct block types and dimensions`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()
        state.seedBuiltInPatterns(parser)

        val doorPatterns = state.project.patterns.filter { it.id.startsWith("builtin_door_") }

        for (pattern in doorPatterns) {
            assertEquals(1, pattern.cols, "${pattern.id} should be 1 column wide")
            assertEquals(4, pattern.rows, "${pattern.id} should be 4 rows tall")

            for (cell in pattern.cells) {
                assertEquals(0x9, cell.blockType,
                    "${pattern.id}: all cells should be block type 0x9 (door)")
            }

            assertTrue(pattern.noFlip, "${pattern.id} should have noFlip=true")
        }
    }

    @Test
    fun `gate patterns have correct block types and dimensions`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()
        state.seedBuiltInPatterns(parser)

        val gatePatterns = state.project.patterns.filter {
            it.id.startsWith("builtin_gate_") && !it.id.contains("left_gate") && !it.id.contains("right_gate")
        }

        for (pattern in gatePatterns) {
            assertEquals(1, pattern.cols, "${pattern.id} should be 1 column wide")
            assertEquals(4, pattern.rows, "${pattern.id} should be 4 rows tall")

            for (cell in pattern.cells) {
                assertEquals(0x8, cell.blockType,
                    "${pattern.id}: all cells should be block type 0x8 (solid)")
            }

            assertTrue(pattern.noFlip, "${pattern.id} should have noFlip=true")
        }
    }

    @Test
    fun `migration removes PLM-only patterns from existing projects`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()

        // Simulate old project with PLM-only patterns that shouldn't exist
        val oldSave = com.supermetroid.editor.data.TilePattern(
            id = "builtin_save_station", name = "Save Station",
            cols = 5, rows = 3, builtIn = true,
            cells = mutableListOf(
                com.supermetroid.editor.data.PatternCell(0, plmId = 0xB76F, plmParam = 0x0001),
                com.supermetroid.editor.data.PatternCell(0),
                com.supermetroid.editor.data.PatternCell(0),
            )
        )
        val oldEnergy = com.supermetroid.editor.data.TilePattern(
            id = "builtin_energy_refill", name = "Energy Refill",
            cols = 5, rows = 3, builtIn = true,
            cells = mutableListOf(
                com.supermetroid.editor.data.PatternCell(0, plmId = 0xB6DF),
                com.supermetroid.editor.data.PatternCell(0),
            )
        )
        val oldShip = com.supermetroid.editor.data.TilePattern(
            id = "builtin_ship", name = "Ship",
            cols = 8, rows = 3, builtIn = true,
            cells = mutableListOf(com.supermetroid.editor.data.PatternCell(0))
        )
        state.project.patterns.addAll(listOf(oldSave, oldEnergy, oldShip))

        state.seedBuiltInPatterns(parser)

        assertNull(state.project.patterns.find { it.id == "builtin_save_station" },
            "Save station pattern should be removed")
        assertNull(state.project.patterns.find { it.id == "builtin_energy_refill" },
            "Energy refill pattern should be removed")
        assertNull(state.project.patterns.find { it.id == "builtin_ship" },
            "Ship pattern should be removed")
    }

    @Test
    fun `migration removes patterns with wrong door PLM IDs`() {
        val parser = loadTestRom() ?: return
        val state = EditorState()

        // Simulate a project with old wrong PLM IDs
        val wrongBlueRight = com.supermetroid.editor.data.TilePattern(
            id = "builtin_door_blue_right",
            name = "Door: Blue (Right)",
            cols = 1, rows = 4,
            builtIn = true,
            cells = mutableListOf(
                com.supermetroid.editor.data.PatternCell(0x040, blockType = 0x9, plmId = 0xC8A6),
                com.supermetroid.editor.data.PatternCell(0x060, blockType = 0x9),
                com.supermetroid.editor.data.PatternCell(0x060, blockType = 0x9),
                com.supermetroid.editor.data.PatternCell(0x040, blockType = 0x9),
            )
        )
        state.project.patterns.add(wrongBlueRight)

        state.seedBuiltInPatterns(parser)

        val blueRight = state.project.patterns.find { it.id == "builtin_door_blue_right" }
        assertNotNull(blueRight, "Blue right door pattern should exist after migration")

        val plmCell = blueRight!!.cells.find { it.plmId != 0 }
        assertNotNull(plmCell, "Should have a PLM cell")
        assertEquals(0xC8A8, plmCell!!.plmId,
            "After migration, blue right door should use 0xC8A8, not 0xC8A6")
    }
}
