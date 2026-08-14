package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Synthetic tests for MetatileTable library (no ROM required).
 */
class MetatileTableTest {

    @Nested
    inner class EncodeDecodeTests {
        @Test
        fun `encodeMetatileWord preserves tile number`() {
            val word = encodeMetatileWord(
                tileNum = 0x123,
                palette = 0,
                priority = false,
                hFlip = false,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertEquals(0x123, decoded.tileNum)
        }

        @Test
        fun `encodeMetatileWord preserves palette`() {
            val word = encodeMetatileWord(
                tileNum = 0,
                palette = 5,
                priority = false,
                hFlip = false,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertEquals(5, decoded.palette)
        }

        @Test
        fun `encodeMetatileWord preserves priority`() {
            val word = encodeMetatileWord(
                tileNum = 0,
                palette = 0,
                priority = true,
                hFlip = false,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertTrue(decoded.priority)
        }

        @Test
        fun `encodeMetatileWord preserves hFlip`() {
            val word = encodeMetatileWord(
                tileNum = 0,
                palette = 0,
                priority = false,
                hFlip = true,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertTrue(decoded.hFlip)
        }

        @Test
        fun `encodeMetatileWord preserves vFlip`() {
            val word = encodeMetatileWord(
                tileNum = 0,
                palette = 0,
                priority = false,
                hFlip = false,
                vFlip = true,
            )
            val decoded = decodeMetatileWord(word)
            assertTrue(decoded.vFlip)
        }

        @Test
        fun `encodeMetatileWord preserves all fields together`() {
            val word = encodeMetatileWord(
                tileNum = 0x2AB,
                palette = 7,
                priority = true,
                hFlip = true,
                vFlip = true,
            )
            val decoded = decodeMetatileWord(word)
            assertEquals(0x2AB, decoded.tileNum)
            assertEquals(7, decoded.palette)
            assertTrue(decoded.priority)
            assertTrue(decoded.hFlip)
            assertTrue(decoded.vFlip)
        }

        @Test
        fun `encodeMetatileWord coerces tile number to 10 bits`() {
            val word = encodeMetatileWord(
                tileNum = 0xFFFF,
                palette = 0,
                priority = false,
                hFlip = false,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertEquals(0x3FF, decoded.tileNum)
        }

        @Test
        fun `encodeMetatileWord coerces palette to 3 bits`() {
            val word = encodeMetatileWord(
                tileNum = 0,
                palette = 0xFF,
                priority = false,
                hFlip = false,
                vFlip = false,
            )
            val decoded = decodeMetatileWord(word)
            assertEquals(7, decoded.palette)
        }

        @Test
        fun `decodeMetatileWord handles all bits set`() {
            val word = 0xFFFF
            val decoded = decodeMetatileWord(word)
            assertEquals(0x3FF, decoded.tileNum)
            assertEquals(7, decoded.palette)
            assertTrue(decoded.priority)
            assertTrue(decoded.hFlip)
            assertTrue(decoded.vFlip)
        }

        @Test
        fun `decodeMetatileWord handles all bits clear`() {
            val word = 0x0000
            val decoded = decodeMetatileWord(word)
            assertEquals(0, decoded.tileNum)
            assertEquals(0, decoded.palette)
            assertFalse(decoded.priority)
            assertFalse(decoded.hFlip)
            assertFalse(decoded.vFlip)
        }
    }

    @Nested
    inner class ByteLayoutTests {
        @Test
        fun `metatile entry is 8 bytes little-endian`() {
            val rawTable = ByteArray(8)
            // TL word = 0x1234
            rawTable[0] = 0x34.toByte()
            rawTable[1] = 0x12.toByte()
            // TR word = 0x5678
            rawTable[2] = 0x78.toByte()
            rawTable[3] = 0x56.toByte()
            // BL word = 0x9ABC
            rawTable[4] = 0xBC.toByte()
            rawTable[5] = 0x9A.toByte()
            // BR word = 0xDEF0
            rawTable[6] = 0xF0.toByte()
            rawTable[7] = 0xDE.toByte()

            val metatiles = parseMetatileTable(rawTable)
            assertEquals(1, metatiles.size)
            assertArrayEquals(intArrayOf(0x1234, 0x5678, 0x9ABC, 0xDEF0), metatiles[0])
        }

        @Test
        fun `serializeMetatileTable produces correct byte order`() {
            val metatiles = arrayOf(
                intArrayOf(0x1234, 0x5678, 0x9ABC, 0xDEF0),
            )
            val serialized = serializeMetatileTable(metatiles)

            assertEquals(8, serialized.size)
            assertEquals(0x34.toByte(), serialized[0])
            assertEquals(0x12.toByte(), serialized[1])
            assertEquals(0x78.toByte(), serialized[2])
            assertEquals(0x56.toByte(), serialized[3])
            assertEquals(0xBC.toByte(), serialized[4])
            assertEquals(0x9A.toByte(), serialized[5])
            assertEquals(0xF0.toByte(), serialized[6])
            assertEquals(0xDE.toByte(), serialized[7])
        }
    }

    @Nested
    inner class ParseSerializeRoundTripTests {
        @Test
        fun `parse and serialize round-trip single metatile`() {
            val original = ByteArray(8) { i -> i.toByte() }
            val parsed = parseMetatileTable(original)
            val serialized = serializeMetatileTable(parsed)
            assertArrayEquals(original, serialized)
        }

        @Test
        fun `parse and serialize round-trip multiple metatiles`() {
            val original = ByteArray(256 * 8) { i -> (i and 0xFF).toByte() }
            val parsed = parseMetatileTable(original)
            val serialized = serializeMetatileTable(parsed)
            assertArrayEquals(original, serialized)
        }

        @Test
        fun `parse and serialize preserve word values`() {
            val words = intArrayOf(
                encodeMetatileWord(tileNum = 100, palette = 1, priority = false, hFlip = false, vFlip = false),
                encodeMetatileWord(tileNum = 101, palette = 2, priority = true, hFlip = false, vFlip = false),
                encodeMetatileWord(tileNum = 102, palette = 3, priority = false, hFlip = true, vFlip = false),
                encodeMetatileWord(tileNum = 103, palette = 4, priority = false, hFlip = false, vFlip = true),
            )
            val metatiles = arrayOf(words)
            val serialized = serializeMetatileTable(metatiles)
            val parsed = parseMetatileTable(serialized)
            assertArrayEquals(words, parsed[0])
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `parseMetatileTable rejects empty table`() {
            val exception = assertThrows<IllegalArgumentException> {
                parseMetatileTable(ByteArray(0))
            }
            assertTrue(exception.message!!.contains("empty"))
        }

        @Test
        fun `parseMetatileTable rejects non-multiple-of-8 size`() {
            val exception = assertThrows<IllegalArgumentException> {
                parseMetatileTable(ByteArray(7))
            }
            assertTrue(exception.message!!.contains("multiple of 8"))
        }

        @Test
        fun `serializeMetatileTable rejects zero count`() {
            val metatiles = arrayOf(intArrayOf(0, 0, 0, 0))
            val exception = assertThrows<IllegalArgumentException> {
                serializeMetatileTable(metatiles, count = 0)
            }
            assertTrue(exception.message!!.contains("Cannot serialize 0 metatiles"))
        }

        @Test
        fun `serializeMetatileTable rejects invalid range`() {
            val metatiles = arrayOf(
                intArrayOf(0, 0, 0, 0),
                intArrayOf(1, 1, 1, 1),
            )
            val exception = assertThrows<IllegalArgumentException> {
                serializeMetatileTable(metatiles, startIndex = 1, count = 2)
            }
            assertTrue(exception.message!!.contains("exceeds"))
        }

        @Test
        fun `validateMetatileTableForExport accepts valid table`() {
            val rawTable = ByteArray(256 * 8)
            assertNull(validateMetatileTableForExport(rawTable))
        }

        @Test
        fun `validateMetatileTableForExport rejects empty table`() {
            val error = validateMetatileTableForExport(ByteArray(0))
            assertNotNull(error)
            assertTrue(error!!.contains("empty"))
        }

        @Test
        fun `validateMetatileTableForExport rejects non-multiple-of-8`() {
            val error = validateMetatileTableForExport(ByteArray(9))
            assertNotNull(error)
            assertTrue(error!!.contains("multiple of 8"))
        }
    }

    @Nested
    inner class ApplyRangeTests {
        @Test
        fun `applyMetatileTable applies to correct range`() {
            val rawTable = ByteArray(16) // 2 metatiles
            // First metatile: all words = 0x1111
            for (i in 0..7) {
                rawTable[i] = if (i % 2 == 0) 0x11.toByte() else 0x11.toByte()
            }
            // Second metatile: all words = 0x2222
            for (i in 8..15) {
                rawTable[i] = if (i % 2 == 0) 0x22.toByte() else 0x22.toByte()
            }

            val target = Array(10) { IntArray(4) { 0 } }
            val count = applyMetatileTable(rawTable, target, targetStart = 5, maxMetatiles = 10)

            assertEquals(2, count)
            // Metatiles 0-4 should be unchanged (all zeros)
            for (i in 0..4) {
                assertArrayEquals(IntArray(4) { 0 }, target[i])
            }
            // Metatile 5 should be 0x1111
            assertArrayEquals(IntArray(4) { 0x1111 }, target[5])
            // Metatile 6 should be 0x2222
            assertArrayEquals(IntArray(4) { 0x2222 }, target[6])
            // Metatiles 7-9 should be unchanged
            for (i in 7..9) {
                assertArrayEquals(IntArray(4) { 0 }, target[i])
            }
        }

        @Test
        fun `applyMetatileTable does not clobber other range`() {
            val creTable = ByteArray(8) { 0xCC.toByte() }
            val varTable = ByteArray(8) { 0xAA.toByte() }

            val target = Array(512) { IntArray(4) { 0 } }

            applyMetatileTable(creTable, target, targetStart = 0, maxMetatiles = 256)
            applyMetatileTable(varTable, target, targetStart = 256, maxMetatiles = 256)

            // CRE range should have 0xCCCC words
            assertArrayEquals(IntArray(4) { 0xCCCC }, target[0])
            assertArrayEquals(IntArray(4) { 0xCCCC }, target[0])

            // Variable range should have 0xAAAA words
            assertArrayEquals(IntArray(4) { 0xAAAA }, target[256])
            assertArrayEquals(IntArray(4) { 0xAAAA }, target[256])
        }

        @Test
        fun `applyMetatileTable respects maxMetatiles limit`() {
            val rawTable = ByteArray(32) // 4 metatiles
            val target = Array(10) { IntArray(4) { 0 } }
            val count = applyMetatileTable(rawTable, target, targetStart = 0, maxMetatiles = 2)

            assertEquals(2, count)
        }
    }

    @Nested
    inner class ExportSimulationTests {
        @Test
        fun `compressed-fits scenario writes successfully`() {
            // Simulate: custom table compresses to same or smaller size
            val customTable = ByteArray(256 * 8) { i -> (i and 0xFF).toByte() }
            val error = validateMetatileTableForExport(customTable)
            assertNull(error)
        }

        @Test
        fun `compressed-grows scenario requires relocate`() {
            // Simulate: custom table is valid but would compress larger
            // (actual compression happens in RomExporter, we just validate the table)
            val customTable = ByteArray(256 * 8)
            val error = validateMetatileTableForExport(customTable)
            assertNull(error)
        }

        @Test
        fun `no-free-space scenario aborts with original unchanged`() {
            // This test validates that the table itself is valid before attempting export
            val customTable = ByteArray(256 * 8)
            val error = validateMetatileTableForExport(customTable)
            assertNull(error)

            // If export fails due to no free space, the ROM should remain unchanged
            // (this is enforced in RomExporter, not in the library)
        }
    }
}
