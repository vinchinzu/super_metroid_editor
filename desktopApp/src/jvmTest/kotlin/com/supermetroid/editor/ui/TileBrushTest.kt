package com.supermetroid.editor.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TileBrushTest {

    // ── Factory ──────────────────────────────────────────────────

    @Test
    fun `single creates 1x1 brush with correct values`() {
        val b = TileBrush.single(42, blockType = 0x3, bts = 0x0C)
        assertEquals(1, b.rows)
        assertEquals(1, b.cols)
        assertEquals(42, b.tiles[0][0])
        assertEquals(0x3, b.blockTypeAt(0, 0))
        assertEquals(0x0C, b.btsAt(0, 0))
        assertFalse(b.hFlip)
        assertFalse(b.vFlip)
    }

    @Test
    fun `single with zero bts creates empty btsOverrides`() {
        val b = TileBrush.single(10)
        assertEquals(0, b.btsAt(0, 0))
        assertTrue(b.btsOverrides.isEmpty())
    }

    // ── Flip state ───────────────────────────────────────────────

    @Test
    fun `tileHFlip without overrides returns brush-level hFlip`() {
        val plain = TileBrush(listOf(listOf(0)))
        assertFalse(plain.tileHFlip(0, 0))

        val flipped = plain.copy(hFlip = true)
        assertTrue(flipped.tileHFlip(0, 0))
    }

    @Test
    fun `tileVFlip without overrides returns brush-level vFlip`() {
        val plain = TileBrush(listOf(listOf(0)))
        assertFalse(plain.tileVFlip(0, 0))

        val flipped = plain.copy(vFlip = true)
        assertTrue(flipped.tileVFlip(0, 0))
    }

    @Test
    fun `tileHFlip XORs per-tile override with brush-level`() {
        val key = 0L
        // per-tile h=1, brush h=false → true
        val b1 = TileBrush(listOf(listOf(0)), flipOverrides = mapOf(key to 1))
        assertTrue(b1.tileHFlip(0, 0))

        // per-tile h=1, brush h=true → false (XOR)
        val b2 = b1.copy(hFlip = true)
        assertFalse(b2.tileHFlip(0, 0))
    }

    @Test
    fun `tileVFlip XORs per-tile override with brush-level`() {
        val key = 0L
        // per-tile v=1 (bit 1), brush v=false → true
        val b1 = TileBrush(listOf(listOf(0)), flipOverrides = mapOf(key to 2))
        assertTrue(b1.tileVFlip(0, 0))

        // per-tile v=1, brush v=true → false
        val b2 = b1.copy(vFlip = true)
        assertFalse(b2.tileVFlip(0, 0))
    }

    // ── blockWordAt encoding ─────────────────────────────────────

    @Test
    fun `blockWordAt encodes metatile index in low 10 bits`() {
        val b = TileBrush.single(0x1A3)
        val word = b.blockWordAt(0, 0)
        assertEquals(0x1A3, word and 0x3FF)
    }

    @Test
    fun `blockWordAt encodes hFlip at bit 10`() {
        val b = TileBrush(listOf(listOf(0)), hFlip = true, blockType = 0)
        val word = b.blockWordAt(0, 0)
        assertTrue(word and (1 shl 10) != 0)
        assertFalse(word and (1 shl 11) != 0)
    }

    @Test
    fun `blockWordAt encodes vFlip at bit 11`() {
        val b = TileBrush(listOf(listOf(0)), vFlip = true, blockType = 0)
        val word = b.blockWordAt(0, 0)
        assertFalse(word and (1 shl 10) != 0)
        assertTrue(word and (1 shl 11) != 0)
    }

    @Test
    fun `blockWordAt encodes block type in bits 12-15`() {
        val b = TileBrush.single(0, blockType = 0xA)
        val word = b.blockWordAt(0, 0)
        assertEquals(0xA, (word shr 12) and 0xF)
    }

    @Test
    fun `blockWordAt uses blockTypeOverrides when present`() {
        val key = (1L shl 32) or 2L // row=1, col=2
        val b = TileBrush(
            tiles = listOf(listOf(0, 0, 0), listOf(0, 0, 5)),
            blockType = 0x8,
            blockTypeOverrides = mapOf(key to 0x3)
        )
        assertEquals(0x3, (b.blockWordAt(1, 2) shr 12) and 0xF)
        assertEquals(0x8, (b.blockWordAt(0, 0) shr 12) and 0xF) // fallback
    }

    // ── Multi-tile brush ─────────────────────────────────────────

    @Test
    fun `multi-tile brush reports correct rows and cols`() {
        val b = TileBrush(tiles = listOf(listOf(1, 2, 3), listOf(4, 5, 6)))
        assertEquals(2, b.rows)
        assertEquals(3, b.cols)
    }

    @Test
    fun `primaryIndex returns first tile`() {
        val b = TileBrush(tiles = listOf(listOf(42, 99), listOf(7, 8)))
        assertEquals(42, b.primaryIndex)
    }
}
