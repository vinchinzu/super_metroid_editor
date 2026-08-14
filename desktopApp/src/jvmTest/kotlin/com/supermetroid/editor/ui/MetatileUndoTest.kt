package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Synthetic tests for metatile word undo/redo (no ROM required).
 * Tests the integration of CRE/VAR apply isolation and EditorState undo.
 */
class MetatileUndoTest {

    private lateinit var state: EditorState
    private lateinit var mockRomParser: RomParser

    private class MockRomParser : RomParser(ByteArray(0x400000) { 0xFF.toByte() }) {
        private val mockGraphicsCatalog = object : com.supermetroid.editor.rom.RomGraphicsCatalog {
            override fun entry(tilesetId: Int) = null
            override val creGfxPtr = 0xB98000
            override val creTileTablePtr = 0xB9A09D
        }

        override val graphicsCatalog: com.supermetroid.editor.rom.RomGraphicsCatalog
            get() = mockGraphicsCatalog

        override fun decompressLZ2(snesPtr: Int): ByteArray {
            return when (snesPtr) {
                0xB9A09D -> ByteArray(256 * 8) { i -> (0xCC + (i % 16)).toByte() }
                0xB98000 -> ByteArray(384 * 32) { 0 }
                else -> ByteArray(768 * 8) { i -> (0xAA + (i % 16)).toByte() }
            }
        }
    }

    @BeforeEach
    fun setUp() {
        state = EditorState()
        state.testMode = true
        state.initTestLevel(blocksWide = 4, blocksTall = 4)
        
        mockRomParser = MockRomParser()
        val tg = TileGraphics(mockRomParser)
        tg.loadTileset(0)
        state.editorTileGraphics = tg
        state.editorTilesetId = 0
        state.editorSelectedMetatile = 100
    }

    @Nested
    inner class CreMetatileUndo {
        @Test
        fun `undo restores CRE metatile words and project override`() {
            val tg = state.editorTileGraphics!!
            val creIndex = 100
            assertTrue(tg.isCreMetatileIndex(creIndex))
            
            state.editorSelectedMetatile = creIndex
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
            state.editorSelectedMetatile = creIndex
            
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
            state.editorSelectedMetatile = creIndex
            
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
            val varIndex = 400
            assertTrue(tg.isVariableMetatileIndex(varIndex))
            
            state.editorSelectedMetatile = varIndex
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
            val varIndex = 500
            state.editorSelectedMetatile = varIndex
            
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
            val varIndex = 400
            state.editorSelectedMetatile = varIndex
            
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
            val index2 = 400
            
            state.editorSelectedMetatile = index1
            val words1 = tg.getMetatileWords(index1)!!
            state.setCurrentMetatileWords(intArrayOf(0x1111, 0x1111, 0x1111, 0x1111))
            
            state.editorSelectedMetatile = index2
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
            val varIndex = 300
            state.editorSelectedMetatile = varIndex
            
            assertNull(state.project.customGfx.tileTables["0"])
            
            state.setCurrentMetatileWords(intArrayOf(0x1234, 0x5678, 0x9ABC, 0xDEF0))
            assertNotNull(state.project.customGfx.tileTables["0"])
            
            state.undo()
            assertNull(state.project.customGfx.tileTables["0"])
        }
    }
}
