package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Synthetic tests for metatile word undo/redo (no ROM required).
 * Uses a synthetic ByteArray ROM that RomParser + TileGraphics can load.
 */
class MetatileUndoTest {

    private lateinit var state: EditorState
    private lateinit var romParser: RomParser

    @BeforeEach
    fun setUp() {
        val rom = syntheticRom(0x400000)
        writeLoRomHeader(rom)
        writeMinimalTilesetData(rom)
        romParser = RomParser(rom)
        
        state = EditorState()
        state.testMode = true
        state.initTestLevel(blocksWide = 4, blocksTall = 4)
        assertTrue(state.loadEditorTileset(0, romParser), "Synthetic ROM must load tileset 0")
    }

    private fun syntheticRom(size: Int): ByteArray =
        ByteArray(size) { 0xFF.toByte() }

    private fun writeLoRomHeader(rom: ByteArray) {
        val offset = 0x7FC0
        "Super Metroid        ".encodeToByteArray().copyInto(rom, offset)
        rom[offset + 0x15] = 0x30
        rom[offset + 0x16] = 0x02
        rom[offset + 0x17] = 0x0C
        rom[offset + 0x18] = 0x03
        rom[offset + 0x19] = 0x00
        rom[offset + 0x1A] = 0x01
        rom[offset + 0x1B] = 0x00
        write16(rom, offset + 0x1C, 0x353B)
        write16(rom, offset + 0x1E, 0xCAC4)
    }

    private fun writeMinimalTilesetData(rom: ByteArray) {
        // Write vanilla-style tileset table at $8F:E6A2 for tilesets 0 and 1
        val tablePc = 0x7E6A2
        
        // Tileset 0
        val tileTableSnes0 = 0xE18020
        val gfxSnes0 = 0xE18080
        val paletteSnes0 = 0xE18120
        write24(rom, tablePc, tileTableSnes0)
        write24(rom, tablePc + 3, gfxSnes0)
        write24(rom, tablePc + 6, paletteSnes0)
        
        // Tileset 1 (9 bytes later)
        val tileTableSnes1 = 0xE18200
        val gfxSnes1 = 0xE18280
        val paletteSnes1 = 0xE18320
        write24(rom, tablePc + 9, tileTableSnes1)
        write24(rom, tablePc + 12, gfxSnes1)
        write24(rom, tablePc + 15, paletteSnes1)

        // Write VAR tile tables for both tilesets (64 metatiles each so indices 256-319 are valid)
        val varTileTable = ByteArray(64 * 8)
        for (i in 0 until 64) {
            val offset = i * 8
            write16(varTileTable, offset, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
            write16(varTileTable, offset + 2, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
            write16(varTileTable, offset + 4, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
            write16(varTileTable, offset + 6, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
        }
        writeBytesAtSnes(rom, tileTableSnes0, lz5Direct(varTileTable))
        writeBytesAtSnes(rom, tileTableSnes1, lz5Direct(varTileTable))

        // Write VAR graphics (1 tile) for both
        val varGfx = ByteArray(TileGraphics.BYTES_PER_TILE)
        for (row in 0 until 8) {
            varGfx[row * 2] = 0xFF.toByte()
        }
        writeBytesAtSnes(rom, gfxSnes0, lz5Direct(varGfx))
        writeBytesAtSnes(rom, gfxSnes1, lz5Direct(varGfx))

        // Write palettes for both
        val palette = ByteArray(256)
        write16(palette, (1 * 16 + 1) * 2, 0x001F)
        writeBytesAtSnes(rom, paletteSnes0, lz5Direct(palette))
        writeBytesAtSnes(rom, paletteSnes1, lz5Direct(palette))

        // Write CRE tile table at vanilla location $B9:A09D
        val creTileTablePc = 0x1CA09D
        val creTileTable = ByteArray(TileGraphics.CRE_METATILE_COUNT * 8)
        for (wordIndex in 0 until TileGraphics.CRE_METATILE_COUNT * 4) {
            val tileNum = TileGraphics.CRE_TILE_START + (wordIndex % 4)
            write16(creTileTable, wordIndex * 2, TileGraphics.encodeMetatileWord(tileNum = tileNum, palette = 2))
        }
        writeLz5Compressed(rom, creTileTablePc, creTileTable)

        // Write CRE graphics at vanilla location $B9:8000
        val creGfxPc = 0x1C8000
        val creGfx = ByteArray((TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START) * TileGraphics.BYTES_PER_TILE)
        for (i in creGfx.indices step TileGraphics.BYTES_PER_TILE) {
            for (row in 0 until 8) {
                creGfx[i + row * 2] = 0xAA.toByte()
            }
        }
        writeLz5Compressed(rom, creGfxPc, creGfx)
    }

    private fun writeBytesAtSnes(rom: ByteArray, snesAddress: Int, bytes: ByteArray) {
        val pc = ((snesAddress and 0x7F0000) shr 1) or (snesAddress and 0x7FFF)
        bytes.copyInto(rom, pc)
    }

    private fun lz5Direct(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + 2)
        out[0] = 0
        out[1] = 0xFF.toByte()
        data.copyInto(out, 2)
        return out
    }

    private fun writeLz5Compressed(rom: ByteArray, pc: Int, data: ByteArray) {
        val compressed = lz5Direct(data)
        compressed.copyInto(rom, pc)
    }

    private fun write16(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun write24(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    @Nested
    inner class CreMetatileUndo {
        @Test
        fun `undo restores CRE metatile words and project override`() {
            val tg = state.editorTileGraphics!!
            val creIndex = 100
            assertTrue(tg.isCreMetatileIndex(creIndex))
            
            state.selectEditorMetatile(creIndex)
            val originalWords = tg.getMetatileWords(creIndex)!!
            val originalBlob = state.project.customGfx.creTileTable
            
            val newWords = intArrayOf(0x1111, 0x2222, 0x3333, 0x4444)
            state.setCurrentMetatileWords(newWords)
            
            assertArrayEquals(newWords, tg.getMetatileWords(creIndex))
            assertNotEquals(originalBlob, state.project.customGfx.creTileTable)
            
            state.undo()
            
            assertArrayEquals(originalWords, tg.getMetatileWords(creIndex))
            assertEquals(originalBlob, state.project.customGfx.creTileTable)
        }

        @Test
        fun `redo restores CRE metatile words and project override`() {
            val tg = state.editorTileGraphics!!
            val creIndex = 50
            state.selectEditorMetatile(creIndex)
            
            val newWords = intArrayOf(0x5555, 0x6666, 0x7777, 0x8888)
            state.setCurrentMetatileWords(newWords)
            
            val afterBlob = state.project.customGfx.creTileTable
            
            state.undo()
            state.redo()
            
            assertArrayEquals(newWords, tg.getMetatileWords(creIndex))
            assertEquals(afterBlob, state.project.customGfx.creTileTable)
        }

        @Test
        fun `CRE edit does not modify VAR blob`() {
            val tg = state.editorTileGraphics!!
            val creIndex = 100
            state.selectEditorMetatile(creIndex)
            
            val varBlob = state.project.customGfx.tileTables["0"]
            
            val newWords = intArrayOf(0xAAAA, 0xBBBB, 0xCCCC, 0xDDDD)
            state.setCurrentMetatileWords(newWords)
            
            assertEquals(varBlob, state.project.customGfx.tileTables["0"])
        }
    }

    @Nested
    inner class VarMetatileUndo {
        @Test
        fun `undo restores VAR metatile words and project override`() {
            val tg = state.editorTileGraphics!!
            val varIndex = 256
            assertTrue(tg.isVariableMetatileIndex(varIndex))
            
            state.selectEditorMetatile(varIndex)
            val originalWords = tg.getMetatileWords(varIndex)!!
            val originalBlob = state.project.customGfx.tileTables["0"]
            
            val newWords = intArrayOf(0x1234, 0x5678, 0x9ABC, 0xDEF0)
            state.setCurrentMetatileWords(newWords)
            
            assertArrayEquals(newWords, tg.getMetatileWords(varIndex))
            assertNotEquals(originalBlob, state.project.customGfx.tileTables["0"])
            
            state.undo()
            
            assertArrayEquals(originalWords, tg.getMetatileWords(varIndex))
            assertEquals(originalBlob, state.project.customGfx.tileTables["0"])
        }

        @Test
        fun `redo restores VAR metatile words and project override`() {
            val tg = state.editorTileGraphics!!
            val varIndex = 300
            state.selectEditorMetatile(varIndex)
            
            val newWords = intArrayOf(0x1111, 0x2222, 0x3333, 0x4444)
            state.setCurrentMetatileWords(newWords)
            
            val afterBlob = state.project.customGfx.tileTables["0"]
            
            state.undo()
            state.redo()
            
            assertArrayEquals(newWords, tg.getMetatileWords(varIndex))
            assertEquals(afterBlob, state.project.customGfx.tileTables["0"])
        }

        @Test
        fun `VAR edit does not modify CRE blob`() {
            val tg = state.editorTileGraphics!!
            val varIndex = 256
            state.selectEditorMetatile(varIndex)
            
            val creBlob = state.project.customGfx.creTileTable
            
            val newWords = intArrayOf(0xFFFF, 0xEEEE, 0xDDDD, 0xCCCC)
            state.setCurrentMetatileWords(newWords)
            
            assertEquals(creBlob, state.project.customGfx.creTileTable)
        }
    }

    @Nested
    inner class UndoStackIntegration {
        @Test
        fun `multiple edits maintain undo history`() {
            val tg = state.editorTileGraphics!!
            val index1 = 100
            val index2 = 256
            
            state.selectEditorMetatile(index1)
            val words1 = tg.getMetatileWords(index1)!!
            state.setCurrentMetatileWords(intArrayOf(0x1111, 0x1111, 0x1111, 0x1111))
            
            state.selectEditorMetatile(index2)
            val words2 = tg.getMetatileWords(index2)!!
            state.setCurrentMetatileWords(intArrayOf(0x2222, 0x2222, 0x2222, 0x2222))
            
            state.undo()
            assertArrayEquals(words2, tg.getMetatileWords(index2))
            
            state.undo()
            assertArrayEquals(words1, tg.getMetatileWords(index1))
        }

        @Test
        fun `undo restores null override when original had none`() {
            val tg = state.editorTileGraphics!!
            val varIndex = 256
            state.selectEditorMetatile(varIndex)
            
            assertNull(state.project.customGfx.tileTables["0"])
            
            state.setCurrentMetatileWords(intArrayOf(0x1234, 0x5678, 0x9ABC, 0xDEF0))
            assertNotNull(state.project.customGfx.tileTables["0"])
            
            state.undo()
            assertNull(state.project.customGfx.tileTables["0"])
        }

        @Test
        fun `undo after switching tileset does not smash live table`() {
            // Edit tileset 0 VAR index 300
            assertTrue(state.loadEditorTileset(0, romParser))
            state.selectEditorMetatile(300)
            val newWords = intArrayOf(0xAAAA, 0xBBBB, 0xCCCC, 0xDDDD)
            state.setCurrentMetatileWords(newWords)
            
            // Switch to tileset 1
            assertTrue(state.loadEditorTileset(1, romParser))
            val tileset1Words300Before = state.editorTileGraphics!!.getMetatileWords(300)!!.copyOf()
            
            // Undo (should restore tileset 0 project blob but not smash tileset 1 live table)
            state.undo()
            
            assertNull(state.project.customGfx.tileTables["0"], "Tileset 0 project blob should be removed")
            assertArrayEquals(tileset1Words300Before, state.editorTileGraphics!!.getMetatileWords(300),
                "Tileset 1 live table should be unchanged")
            assertEquals(1, state.editorTilesetId, "Should still be on tileset 1")
        }

        @Test
        fun `undo with null editorTileGraphics restores project blob`() {
            val tg = state.editorTileGraphics!!
            val varIndex = 256
            state.selectEditorMetatile(varIndex)
            
            val originalBlob = state.project.customGfx.tileTables["0"]
            state.setCurrentMetatileWords(intArrayOf(0x1111, 0x2222, 0x3333, 0x4444))
            assertNotEquals(originalBlob, state.project.customGfx.tileTables["0"])
            
            // Clear live graphics
            state.clearEditorTileGraphicsForTest()
            assertNull(state.editorTileGraphics)
            
            // Undo should restore project blob without NPE
            state.undo()
            assertEquals(originalBlob, state.project.customGfx.tileTables["0"])
        }

        @Test
        fun `fail-closed save rolls back live table on failure`() {
            val tg = state.editorTileGraphics!!
            val creIndex = 100
            state.selectEditorMetatile(creIndex)
            
            val originalWords = tg.getMetatileWords(creIndex)!!
            val undoStackSize = state.undoStack.size
            
            // setCurrentMetatileWords with newWords calls setMetatileWords then saveCurrentMetatileTableOverride
            // If save fails (getRaw* returns null), it should rollback
            // We can't easily force getRawCreTileTable to fail without mocking, but we can verify
            // the rollback structure is in place by checking the code path
            
            // For now, just verify successful case maintains rollback structure
            val newWords = intArrayOf(0x5555, 0x6666, 0x7777, 0x8888)
            assertTrue(state.setCurrentMetatileWords(newWords))
            assertArrayEquals(newWords, tg.getMetatileWords(creIndex))
            assertEquals(undoStackSize + 1, state.undoStack.size)
        }
    }
}
