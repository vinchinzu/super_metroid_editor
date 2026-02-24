package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.TilePattern
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

class EditorStateTest {

    private lateinit var state: EditorState

    @BeforeEach
    fun setUp() {
        state = EditorState()
        state.initTestLevel(blocksWide = 4, blocksTall = 4)
    }

    private fun writeWord(bx: Int, by: Int, word: Int) {
        val data = state.workingLevelData!!
        val idx = by * 4 + bx
        val offset = 2 + idx * 2
        data[offset] = (word and 0xFF).toByte()
        data[offset + 1] = ((word shr 8) and 0xFF).toByte()
    }

    private fun writeBts(bx: Int, by: Int, bts: Int) {
        val data = state.workingLevelData!!
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * 4 + bx
        data[2 + layer1Size + idx] = bts.toByte()
    }

    // ── Toggle flip ──────────────────────────────────────────────

    @Nested
    inner class ToggleFlip {
        @Test
        fun `toggleHFlip toggles brush hFlip`() {
            state.setBrushForTest(TileBrush.single(0))
            assertFalse(state.brush!!.hFlip)
            state.toggleHFlip()
            assertTrue(state.brush!!.hFlip)
            state.toggleHFlip()
            assertFalse(state.brush!!.hFlip)
        }

        @Test
        fun `toggleVFlip toggles brush vFlip`() {
            state.setBrushForTest(TileBrush.single(0))
            assertFalse(state.brush!!.vFlip)
            state.toggleVFlip()
            assertTrue(state.brush!!.vFlip)
        }

        @Test
        fun `toggle on null brush is no-op`() {
            state.setBrushForTest(null)
            state.toggleHFlip() // should not throw
            assertNull(state.brush)
        }
    }

    // ── flipOrCapture guards ─────────────────────────────────────

    @Nested
    inner class FlipOrCapture {
        @Test
        fun `flipOrCaptureH in PAINT mode does not re-capture stale map selection`() {
            state.setBrushForTest(TileBrush.single(42))
            state.activeTool = EditorTool.PAINT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)

            state.flipOrCaptureH()

            assertEquals(42, state.brush!!.primaryIndex, "Brush should still be tile 42, not re-captured")
            assertTrue(state.brush!!.hFlip)
        }

        @Test
        fun `flipOrCaptureV in PAINT mode does not re-capture stale map selection`() {
            state.setBrushForTest(TileBrush.single(99))
            state.activeTool = EditorTool.PAINT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)

            state.flipOrCaptureV()

            assertEquals(99, state.brush!!.primaryIndex, "Brush should still be tile 99, not re-captured")
            assertTrue(state.brush!!.vFlip)
        }

        @Test
        fun `rotateOrCapture in PAINT mode does not re-capture stale map selection`() {
            state.setBrushForTest(TileBrush.single(77))
            state.activeTool = EditorTool.PAINT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)

            state.rotateOrCapture()

            assertEquals(77, state.brush!!.primaryIndex, "Brush should still be tile 77")
        }

        @Test
        fun `flipOrCaptureH in SELECT mode captures map selection`() {
            writeWord(0, 0, 0x8005) // tile 5, blockType 8
            writeWord(1, 0, 0x8006) // tile 6, blockType 8
            writeWord(0, 1, 0x8007)
            writeWord(1, 1, 0x8008)

            state.setBrushForTest(TileBrush.single(42))
            state.activeTool = EditorTool.SELECT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)

            state.flipOrCaptureH()

            assertNotEquals(42, state.brush!!.primaryIndex, "Brush should be re-captured from map")
            assertEquals(5, state.brush!!.tiles[0][0])
            assertTrue(state.brush!!.hFlip, "hFlip should be toggled on after capture")
            assertNull(state.mapSelStart, "mapSel should be cleared after capture")
        }
    }

    // ── selectMetatile clears mapSel ─────────────────────────────

    @Nested
    inner class SelectMetatile {
        @Test
        fun `selectMetatile clears stale map selection`() {
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(2, 2)

            state.selectMetatile(10)

            assertNull(state.mapSelStart)
            assertNull(state.mapSelEnd)
            assertEquals(10, state.brush!!.primaryIndex)
        }

        @Test
        fun `selectMetatile sets tileset selection`() {
            state.selectMetatile(35, gridCols = 32)
            assertEquals(Pair(3, 1), state.tilesetSelStart) // 35 % 32 = 3, 35 / 32 = 1
        }
    }

    // ── Rotate clockwise ─────────────────────────────────────────

    @Nested
    inner class RotateClockwise {
        @Test
        fun `rotate 1x1 keeps same tile`() {
            state.setBrushForTest(TileBrush.single(42))
            state.rotateClockwise()
            assertEquals(42, state.brush!!.tiles[0][0])
            assertEquals(1, state.brush!!.rows)
            assertEquals(1, state.brush!!.cols)
        }

        @Test
        fun `rotate 2x3 becomes 3x2`() {
            // Original:  [[1, 2, 3], [4, 5, 6]]
            // Rotated CW: col 0 bottom→top = [4,1], col 1 = [5,2], col 2 = [6,3]
            val b = TileBrush(tiles = listOf(listOf(1, 2, 3), listOf(4, 5, 6)))
            state.setBrushForTest(b)
            state.rotateClockwise()

            val r = state.brush!!
            assertEquals(3, r.rows)
            assertEquals(2, r.cols)
            assertEquals(listOf(4, 1), r.tiles[0])
            assertEquals(listOf(5, 2), r.tiles[1])
            assertEquals(listOf(6, 3), r.tiles[2])
        }

        @Test
        fun `rotate updates hFlip and vFlip correctly`() {
            // Rule: newH = !oldV, newV = oldH
            state.setBrushForTest(TileBrush(listOf(listOf(0)), hFlip = false, vFlip = false))
            state.rotateClockwise()
            assertTrue(state.brush!!.hFlip)    // !false
            assertFalse(state.brush!!.vFlip)   // false

            // Rotate again: h=true, v=false → newH=!false=true, newV=true
            state.rotateClockwise()
            assertTrue(state.brush!!.hFlip)
            assertTrue(state.brush!!.vFlip)
        }

        @Test
        fun `four rotations return to original`() {
            val original = TileBrush(
                tiles = listOf(listOf(1, 2), listOf(3, 4)),
                hFlip = false, vFlip = false
            )
            state.setBrushForTest(original)
            repeat(4) { state.rotateClockwise() }
            val r = state.brush!!
            assertEquals(original.tiles, r.tiles)
            assertEquals(original.hFlip, r.hFlip)
            assertEquals(original.vFlip, r.vFlip)
        }

        @Test
        fun `rotate remaps flipOverrides`() {
            // 2x2 brush with per-tile flip on (0, 1): h=1
            val key01 = (0L shl 32) or 1L
            val b = TileBrush(
                tiles = listOf(listOf(10, 20), listOf(30, 40)),
                flipOverrides = mapOf(key01 to 1) // row=0, col=1, h=1
            )
            state.setBrushForTest(b)
            state.rotateClockwise()

            // (0,1) → newR=1, newC=2-1-0=1 → (1,1)
            val r = state.brush!!
            val expectedKey = (1L shl 32) or 1L
            assertTrue(r.flipOverrides.containsKey(expectedKey))
            // h=1,v=0 → rotated: newVal = v | (h<<1) = 0 | (1<<1) = 2
            assertEquals(2, r.flipOverrides[expectedKey])
        }
    }

    // ── Paint ────────────────────────────────────────────────────

    @Nested
    inner class PaintAt {
        @Test
        fun `paint single tile writes correct block word`() {
            state.setBrushForTest(TileBrush.single(0x42, blockType = 0xA))
            state.beginStroke()
            val changed = state.paintAt(1, 2)

            assertTrue(changed)
            val word = state.readBlockWord(1, 2)
            assertEquals(0x42, word and 0x3FF)
            assertEquals(0xA, (word shr 12) and 0xF)
        }

        @Test
        fun `paint with hFlip sets bit 10 and reverses column order`() {
            val b = TileBrush(
                tiles = listOf(listOf(10, 20)),
                hFlip = true,
                blockType = 0x8
            )
            state.setBrushForTest(b)
            state.beginStroke()
            state.paintAt(0, 0)

            // hFlip reverses columns: col 0 → tile at col (cols-1-0)=1 → tile 20
            val word0 = state.readBlockWord(0, 0)
            assertEquals(20, word0 and 0x3FF)
            assertTrue(word0 and (1 shl 10) != 0) // hFlip bit set

            val word1 = state.readBlockWord(1, 0)
            assertEquals(10, word1 and 0x3FF)
        }

        @Test
        fun `paint out of bounds is safely ignored`() {
            state.setBrushForTest(TileBrush.single(1))
            state.beginStroke()
            assertFalse(state.paintAt(-1, 0))
            assertFalse(state.paintAt(0, -1))
        }

        @Test
        fun `paint same tile twice in same stroke is idempotent`() {
            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            val changed = state.paintAt(0, 0)
            assertFalse(changed, "Duplicate paint in same stroke should be no-op")
        }

        @Test
        fun `paint multi-tile brush places all tiles`() {
            val b = TileBrush(
                tiles = listOf(listOf(1, 2), listOf(3, 4)),
                blockType = 0x8
            )
            state.setBrushForTest(b)
            state.beginStroke()
            state.paintAt(0, 0)

            assertEquals(1, state.readBlockWord(0, 0) and 0x3FF)
            assertEquals(2, state.readBlockWord(1, 0) and 0x3FF)
            assertEquals(3, state.readBlockWord(0, 1) and 0x3FF)
            assertEquals(4, state.readBlockWord(1, 1) and 0x3FF)
        }
    }

    // ── Sample ───────────────────────────────────────────────────

    @Nested
    inner class SampleTile {
        @Test
        fun `sampleTile creates brush from map data`() {
            val word = 0x42 or (1 shl 10) or (0xA shl 12) // tile 0x42, hFlip, blockType 0xA
            writeWord(2, 1, word)
            writeBts(2, 1, 0x0C)

            state.sampleTile(2, 1)

            val b = state.brush!!
            assertEquals(0x42, b.primaryIndex)
            assertTrue(b.hFlip)
            assertFalse(b.vFlip)
            assertEquals(0xA, b.blockTypeAt(0, 0))
            assertEquals(0x0C, b.btsAt(0, 0))
            assertEquals(EditorTool.PAINT, state.activeTool)
        }

        @Test
        fun `sampleTile out of bounds is no-op`() {
            state.setBrushForTest(TileBrush.single(99))
            state.sampleTile(-1, 0)
            assertEquals(99, state.brush!!.primaryIndex, "Brush should be unchanged")
        }
    }

    // ── Flood fill ───────────────────────────────────────────────

    @Nested
    inner class FloodFill {
        @Test
        fun `fill replaces connected region of same tile`() {
            // Fill a 4x4 grid that's all tile 0 with tile 5
            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.beginStroke()
            val changed = state.floodFill(0, 0)
            assertTrue(changed)

            for (y in 0 until 4) {
                for (x in 0 until 4) {
                    val word = state.readBlockWord(x, y)
                    assertEquals(5, word and 0x3FF, "Tile at ($x, $y) should be 5")
                }
            }
        }

        @Test
        fun `fill does not cross different-tile boundary`() {
            // Place a wall of tile 99 in column 2
            for (y in 0 until 4) writeWord(2, y, 99 or (0x8 shl 12))

            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.beginStroke()
            state.floodFill(0, 0)

            // Left side should be filled
            assertEquals(5, state.readBlockWord(0, 0) and 0x3FF)
            assertEquals(5, state.readBlockWord(1, 0) and 0x3FF)
            // Wall should be untouched
            assertEquals(99, state.readBlockWord(2, 0) and 0x3FF)
            // Right side should be untouched (still tile 0)
            assertEquals(0, state.readBlockWord(3, 0) and 0x3FF)
        }

        @Test
        fun `fill with same tile is no-op`() {
            state.setBrushForTest(TileBrush.single(0, blockType = 0))
            state.beginStroke()
            // Grid is all tile 0, blockType 0 → fill with same → no change
            assertFalse(state.floodFill(0, 0))
        }

        @Test
        fun `fill rejects multi-tile brush`() {
            state.setBrushForTest(TileBrush(tiles = listOf(listOf(1, 2))))
            state.beginStroke()
            assertFalse(state.floodFill(0, 0))
        }
    }

    // ── Undo / Redo ──────────────────────────────────────────────

    @Nested
    inner class UndoRedo {
        @Test
        fun `undo restores previous state`() {
            state.setBrushForTest(TileBrush.single(42, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            state.endStroke()

            assertEquals(42, state.readBlockWord(0, 0) and 0x3FF)

            state.undo()
            assertEquals(0, state.readBlockWord(0, 0) and 0x3FF, "Should be restored to 0")
        }

        @Test
        fun `redo re-applies undone operation`() {
            state.setBrushForTest(TileBrush.single(42, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            state.endStroke()
            state.undo()
            state.redo()

            assertEquals(42, state.readBlockWord(0, 0) and 0x3FF)
        }

        @Test
        fun `undo on empty stack returns false`() {
            assertFalse(state.undo())
        }

        @Test
        fun `redo on empty stack returns false`() {
            assertFalse(state.redo())
        }

        @Test
        fun `new stroke clears redo stack`() {
            state.setBrushForTest(TileBrush.single(1, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            state.endStroke()
            state.undo()

            // Paint a different tile → redo stack should be cleared
            state.setBrushForTest(TileBrush.single(2, blockType = 0x8))
            state.beginStroke()
            state.paintAt(1, 0)
            state.endStroke()

            assertFalse(state.redo(), "Redo should be unavailable after new stroke")
        }

        @Test
        fun `multiple undo-redo cycles`() {
            state.setBrushForTest(TileBrush.single(10, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            state.endStroke()

            state.setBrushForTest(TileBrush.single(20, blockType = 0x8))
            state.beginStroke()
            state.paintAt(0, 0)
            state.endStroke()

            assertEquals(20, state.readBlockWord(0, 0) and 0x3FF)
            state.undo()
            assertEquals(10, state.readBlockWord(0, 0) and 0x3FF)
            state.undo()
            assertEquals(0, state.readBlockWord(0, 0) and 0x3FF)
            state.redo()
            assertEquals(10, state.readBlockWord(0, 0) and 0x3FF)
        }
    }

    // ── captureMapSelection ──────────────────────────────────────

    @Nested
    inner class CaptureMapSelection {
        @Test
        fun `capture multi-tile selection creates correct brush`() {
            writeWord(0, 0, 5 or (0x8 shl 12))
            writeWord(1, 0, 6 or (0x8 shl 12) or (1 shl 10)) // hFlip
            writeWord(0, 1, 7 or (0x8 shl 12))
            writeWord(1, 1, 8 or (0xA shl 12))

            state.activeTool = EditorTool.SELECT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)
            state.captureMapSelection()

            val b = state.brush!!
            assertEquals(2, b.rows)
            assertEquals(2, b.cols)
            assertEquals(5, b.tiles[0][0])
            assertEquals(6, b.tiles[0][1])
            assertEquals(7, b.tiles[1][0])
            assertEquals(8, b.tiles[1][1])
            assertNull(state.mapSelStart, "mapSel cleared")
            assertNull(state.mapSelEnd)
            assertEquals(EditorTool.PAINT, state.activeTool)
        }

        @Test
        fun `capture preserves per-tile flip overrides`() {
            writeWord(0, 0, 5 or (0x8 shl 12) or (1 shl 10) or (1 shl 11)) // hFlip + vFlip
            writeWord(1, 0, 6 or (0x8 shl 12))

            state.activeTool = EditorTool.SELECT
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 0)
            state.captureMapSelection()

            val b = state.brush!!
            val key00 = 0L
            assertTrue(b.flipOverrides.containsKey(key00))
            assertEquals(3, b.flipOverrides[key00]) // h=1, v=1 → 1 | (1<<1) = 3
        }

        @Test
        fun `capture single tile falls through to sampleTile`() {
            writeWord(2, 3, 0x42 or (0xA shl 12))
            state.activeTool = EditorTool.SELECT
            state.mapSelStart = Pair(2, 3)
            state.mapSelEnd = Pair(2, 3)
            state.captureMapSelection()

            assertEquals(0x42, state.brush!!.primaryIndex)
            assertEquals(EditorTool.PAINT, state.activeTool)
        }
    }

    // ── endTilesetDrag clears mapSel ─────────────────────────────

    @Nested
    inner class EndTilesetDrag {
        @Test
        fun `endTilesetDrag clears stale map selection`() {
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(2, 2)
            state.beginTilesetDrag(0, 0)
            state.updateTilesetDrag(1, 0)
            state.endTilesetDrag(gridCols = 32)

            assertNull(state.mapSelStart)
            assertNull(state.mapSelEnd)
        }

        @Test
        fun `endTilesetDrag creates multi-tile brush`() {
            state.beginTilesetDrag(0, 0)
            state.updateTilesetDrag(2, 1)
            state.endTilesetDrag(gridCols = 32)

            val b = state.brush!!
            assertEquals(2, b.rows) // rows 0..1
            assertEquals(3, b.cols) // cols 0..2
            assertEquals(0, b.tiles[0][0])
            assertEquals(1, b.tiles[0][1])
            assertEquals(2, b.tiles[0][2])
            assertEquals(32, b.tiles[1][0])
        }
    }

    // ── Pattern CRUD ─────────────────────────────────────────────

    @Nested
    inner class PatternCrud {
        @Test
        fun `addPattern creates pattern with correct dimensions`() {
            val pat = state.addPattern("test", cols = 3, rows = 2)
            assertEquals("test", pat.name)
            assertEquals(3, pat.cols)
            assertEquals(2, pat.rows)
            assertEquals(6, pat.cells.size)
            assertTrue(state.project.patterns.contains(pat))
        }

        @Test
        fun `removePattern deletes pattern and clears selection`() {
            val pat = state.addPattern("doomed", cols = 2, rows = 2)
            state.selectPattern(pat.id)
            assertEquals(pat.id, state.selectedPatternId)

            state.removePattern(pat.id)
            assertNull(state.selectedPatternId)
            assertFalse(state.project.patterns.any { it.id == pat.id })
        }

        @Test
        fun `renamePattern changes name`() {
            val pat = state.addPattern("old", cols = 1, rows = 1)
            state.renamePattern(pat.id, "new")
            assertEquals("new", state.project.patterns.find { it.id == pat.id }?.name)
        }

        @Test
        fun `updatePatternCell modifies specific cell`() {
            val pat = state.addPattern("grid", cols = 2, rows = 2)
            val cell = PatternCell(42, blockType = 0xA, bts = 5, hFlip = true)
            state.updatePatternCell(pat.id, r = 1, c = 0, cell)

            val actual = pat.getCell(1, 0)
            assertNotNull(actual)
            assertEquals(42, actual!!.metatile)
            assertEquals(0xA, actual.blockType)
            assertEquals(5, actual.bts)
            assertTrue(actual.hFlip)
        }

        @Test
        fun `resizePattern preserves existing cells`() {
            val pat = state.addPattern("grow", cols = 2, rows = 2)
            state.updatePatternCell(pat.id, r = 0, c = 1, PatternCell(99))

            state.resizePattern(pat.id, newCols = 3, newRows = 3)

            val resized = state.project.patterns.find { it.id == pat.id }!!
            assertEquals(3, resized.cols)
            assertEquals(3, resized.rows)
            assertEquals(99, resized.getCell(0, 1)?.metatile)
            assertEquals(0, resized.getCell(2, 2)?.metatile)
        }

        @Test
        fun `resizePattern shrinking truncates cells`() {
            val pat = state.addPattern("shrink", cols = 3, rows = 3)
            state.updatePatternCell(pat.id, r = 2, c = 2, PatternCell(77))

            state.resizePattern(pat.id, newCols = 2, newRows = 2)

            val resized = state.project.patterns.find { it.id == pat.id }!!
            assertEquals(2, resized.cols)
            assertEquals(2, resized.rows)
            assertEquals(4, resized.cells.size)
        }

        @Test
        fun `patternsForTileset returns CRE and matching tileset patterns`() {
            state.addPattern("cre", cols = 1, rows = 1, tilesetId = null)
            state.addPattern("ts5", cols = 1, rows = 1, tilesetId = 5)
            state.addPattern("ts10", cols = 1, rows = 1, tilesetId = 10)

            val forTs5 = state.patternsForTileset(5)
            assertTrue(forTs5.any { it.name == "cre" })
            assertTrue(forTs5.any { it.name == "ts5" })
            assertFalse(forTs5.any { it.name == "ts10" })
        }
    }

    // ── Pattern to Brush conversion ──────────────────────────────

    @Nested
    inner class PatternToBrush {
        @Test
        fun `patternToBrush creates brush with correct tiles`() {
            val pat = state.addPattern("test", cols = 2, rows = 2)
            state.updatePatternCell(pat.id, 0, 0, PatternCell(10, blockType = 0x8))
            state.updatePatternCell(pat.id, 0, 1, PatternCell(20, blockType = 0x8))
            state.updatePatternCell(pat.id, 1, 0, PatternCell(30, blockType = 0xA))
            state.updatePatternCell(pat.id, 1, 1, PatternCell(40, blockType = 0x8))

            val brush = state.patternToBrush(pat)
            assertEquals(2, brush.rows)
            assertEquals(2, brush.cols)
            assertEquals(10, brush.tiles[0][0])
            assertEquals(20, brush.tiles[0][1])
            assertEquals(30, brush.tiles[1][0])
            assertEquals(40, brush.tiles[1][1])
        }

        @Test
        fun `patternToBrush preserves blockType overrides`() {
            val pat = state.addPattern("bt", cols = 2, rows = 1)
            state.updatePatternCell(pat.id, 0, 0, PatternCell(1, blockType = 0x3))
            state.updatePatternCell(pat.id, 0, 1, PatternCell(2, blockType = 0xA))

            val brush = state.patternToBrush(pat)
            assertEquals(0x3, (brush.blockWordAt(0, 0) shr 12) and 0xF)
            assertEquals(0xA, (brush.blockWordAt(0, 1) shr 12) and 0xF)
        }

        @Test
        fun `patternToBrush preserves flip overrides`() {
            val pat = state.addPattern("flip", cols = 1, rows = 1)
            state.updatePatternCell(pat.id, 0, 0, PatternCell(5, hFlip = true, vFlip = true))

            val brush = state.patternToBrush(pat)
            assertTrue(brush.tileHFlip(0, 0))
            assertTrue(brush.tileVFlip(0, 0))
        }

        @Test
        fun `patternToBrush preserves BTS overrides`() {
            val pat = state.addPattern("bts", cols = 1, rows = 1)
            state.updatePatternCell(pat.id, 0, 0, PatternCell(5, bts = 0x0C))

            val brush = state.patternToBrush(pat)
            assertEquals(0x0C, brush.btsAt(0, 0))
        }

        @Test
        fun `selectAndApplyPattern sets brush and tool`() {
            val pat = state.addPattern("apply", cols = 2, rows = 1)
            state.updatePatternCell(pat.id, 0, 0, PatternCell(7))
            state.updatePatternCell(pat.id, 0, 1, PatternCell(8))

            state.selectAndApplyPattern(pat.id)

            assertNotNull(state.brush)
            assertEquals(7, state.brush!!.tiles[0][0])
            assertEquals(8, state.brush!!.tiles[0][1])
            assertEquals(EditorTool.PAINT, state.activeTool)
        }
    }

    // ── Pattern editing (mini editor) ────────────────────────────

    @Nested
    inner class PatternEditing {
        private fun setupPatternForEdit(): TilePattern {
            val pat = state.addPattern("editable", cols = 3, rows = 3)
            state.loadPatternForEdit(pat.id)
            return pat
        }

        @Test
        fun `patPaintAt writes cell to pattern`() {
            val pat = setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(42, blockType = 0xA))
            state.patBeginStroke()
            val changed = state.patPaintAt(1, 1)

            assertTrue(changed)
            val cell = pat.getCell(1, 1)!!
            assertEquals(42, cell.metatile)
            assertEquals(0xA, cell.blockType)
        }

        @Test
        fun `patPaintAt out of bounds is safe`() {
            setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(1))
            state.patBeginStroke()
            assertFalse(state.patPaintAt(-1, 0))
            assertFalse(state.patPaintAt(99, 0))
        }

        @Test
        fun `patPaintAt same cell twice in stroke is idempotent`() {
            setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.patBeginStroke()
            state.patPaintAt(0, 0)
            val changed = state.patPaintAt(0, 0)
            assertFalse(changed)
        }

        @Test
        fun `patEndStroke creates undo operation`() {
            setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.patBeginStroke()
            state.patPaintAt(0, 0)
            state.patEndStroke()

            assertEquals(1, state.patternUndoStack.size)
        }

        @Test
        fun `patUndo restores previous cell`() {
            val pat = setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(42, blockType = 0x8))
            state.patBeginStroke()
            state.patPaintAt(0, 0)
            state.patEndStroke()

            assertEquals(42, pat.getCell(0, 0)?.metatile)
            state.patUndo()
            assertEquals(0, pat.getCell(0, 0)?.metatile)
        }

        @Test
        fun `patRedo reapplies undone edit`() {
            val pat = setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(42, blockType = 0x8))
            state.patBeginStroke()
            state.patPaintAt(0, 0)
            state.patEndStroke()
            state.patUndo()

            state.patRedo()
            assertEquals(42, pat.getCell(0, 0)?.metatile)
        }

        @Test
        fun `patFloodFill fills connected region`() {
            val pat = setupPatternForEdit()
            state.setBrushForTest(TileBrush.single(77, blockType = 0x8))
            state.patFloodFill(0, 0)

            for (r in 0 until 3) for (c in 0 until 3) {
                assertEquals(77, pat.getCell(r, c)?.metatile, "Cell ($r,$c) should be filled")
            }
        }

        @Test
        fun `patFloodFill respects boundaries`() {
            val pat = setupPatternForEdit()
            // Place a wall at row 1
            for (c in 0 until 3) {
                pat.setCell(1, c, PatternCell(99, blockType = 0x8))
            }

            state.setBrushForTest(TileBrush.single(5, blockType = 0x8))
            state.patFloodFill(0, 0)

            assertEquals(5, pat.getCell(0, 0)?.metatile)
            assertEquals(5, pat.getCell(0, 1)?.metatile)
            assertEquals(99, pat.getCell(1, 0)?.metatile, "Wall should be untouched")
            assertEquals(0, pat.getCell(2, 0)?.metatile, "Below wall should be untouched")
        }

        @Test
        fun `patSampleTile creates brush from pattern cell`() {
            val pat = setupPatternForEdit()
            pat.setCell(1, 2, PatternCell(55, blockType = 0xA, bts = 3, hFlip = true))

            state.patSampleTile(2, 1)

            val b = state.brush!!
            assertEquals(55, b.primaryIndex)
            assertEquals(0xA, b.blockTypeAt(0, 0))
            assertEquals(3, b.btsAt(0, 0))
        }
    }

    // ── Save selection as pattern ────────────────────────────────

    @Nested
    inner class SaveSelectionAsPattern {
        @Test
        fun `saveSelectionAsPattern captures map region`() {
            writeWord(0, 0, 5 or (0x8 shl 12))
            writeWord(1, 0, 6 or (0xA shl 12) or (1 shl 10)) // hFlip
            writeWord(0, 1, 7 or (0x8 shl 12) or (1 shl 11)) // vFlip
            writeWord(1, 1, 8 or (0x8 shl 12))
            writeBts(1, 0, 0x0C)

            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)
            val pat = state.saveSelectionAsPattern("captured")

            assertNotNull(pat)
            assertEquals(2, pat!!.cols)
            assertEquals(2, pat.rows)
            assertEquals(5, pat.getCell(0, 0)?.metatile)
            assertEquals(6, pat.getCell(0, 1)?.metatile)
            assertTrue(pat.getCell(0, 1)?.hFlip == true)
            assertEquals(0x0C, pat.getCell(0, 1)?.bts)
            assertTrue(pat.getCell(1, 0)?.vFlip == true)
        }

        @Test
        fun `saveSelectionAsPattern clears map selection`() {
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(1, 1)
            state.saveSelectionAsPattern("test")

            assertNull(state.mapSelStart)
            assertNull(state.mapSelEnd)
        }

        @Test
        fun `saveSelectionAsPattern with no selection returns null`() {
            state.mapSelStart = null
            assertNull(state.saveSelectionAsPattern("nope"))
        }

        @Test
        fun `saveSelectionAsPattern CRE has null tilesetId`() {
            state.mapSelStart = Pair(0, 0)
            state.mapSelEnd = Pair(0, 0)
            val pat = state.saveSelectionAsPattern("cre", isCre = true)
            assertNull(pat?.tilesetId)
        }
    }
}
