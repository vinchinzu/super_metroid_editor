package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [LevelDataResize] using synthetic decompressed level-data
 * blobs. No ROM is required.
 */
class LevelDataResizeTest {

    /**
     * Build a synthetic decompressed level-data blob (header + Layer1 + BTS, no
     * stored Layer 2) for a (W × H) screen room. Each block at (bx, by) is given
     * a unique sentinel tile word `0x8000 | (by * W*16 + bx)` and a BTS byte
     * `(by * W*16 + bx) and 0xFF` so we can verify byte-exact preservation.
     */
    private fun buildBlob(w: Int, h: Int): ByteArray {
        val stride = w * 16
        val rows = h * 16
        val l1Size = w * h * 512
        val btsBytes = w * h * 256
        val out = ByteArray(2 + l1Size + btsBytes)
        out[0] = (l1Size and 0xFF).toByte()
        out[1] = ((l1Size shr 8) and 0xFF).toByte()
        var off = 2
        for (by in 0 until rows) {
            for (bx in 0 until stride) {
                val idx = by * stride + bx
                val word = 0x8000 or (idx and 0x0FFF)
                out[off] = (word and 0xFF).toByte()
                out[off + 1] = ((word shr 8) and 0xFF).toByte()
                off += 2
            }
        }
        // BTS
        for (by in 0 until rows) {
            for (bx in 0 until stride) {
                val idx = by * stride + bx
                out[2 + l1Size + idx] = (idx and 0xFF).toByte()
            }
        }
        return out
    }

    private fun wordAt(blob: ByteArray, w: Int, bx: Int, by: Int): Int {
        val stride = w * 16
        val off = 2 + (by * stride + bx) * 2
        return (blob[off].toInt() and 0xFF) or ((blob[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun btsAt(blob: ByteArray, w: Int, h: Int, bx: Int, by: Int): Int {
        val stride = w * 16
        val l1Size = w * h * 512
        val off = 2 + l1Size + (by * stride + bx)
        return blob[off].toInt() and 0xFF
    }

    @Test
    fun `identity resize returns equivalent blob`() {
        val orig = buildBlob(2, 2)
        val out = LevelDataResize.resize(orig, 2, 2, 2, 2)
        assertArrayEquals(orig, out)
    }

    @Test
    fun `grow right preserves all old tiles and fills new column`() {
        val orig = buildBlob(1, 1)
        val out = LevelDataResize.resize(orig, 1, 1, 2, 1)

        // Header: new layer1Size = 2*1*512 = 1024
        val newL1 = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        assertEquals(1024, newL1)
        assertEquals(2 + 1024 + 512, out.size)

        // Old region (0..15, 0..15) preserved
        for (by in 0 until 16) {
            for (bx in 0 until 16) {
                assertEquals(wordAt(orig, 1, bx, by), wordAt(out, 2, bx, by),
                    "Layer1 word at ($bx,$by)")
                assertEquals(btsAt(orig, 1, 1, bx, by), btsAt(out, 2, 1, bx, by),
                    "BTS at ($bx,$by)")
            }
        }
        // New right column (16..31) all air + bts 0
        for (by in 0 until 16) {
            for (bx in 16 until 32) {
                assertEquals(0x0000, wordAt(out, 2, bx, by), "new word at ($bx,$by)")
                assertEquals(0x00, btsAt(out, 2, 1, bx, by), "new bts at ($bx,$by)")
            }
        }
    }

    @Test
    fun `grow down preserves all old tiles and fills new row`() {
        val orig = buildBlob(1, 1)
        val out = LevelDataResize.resize(orig, 1, 1, 1, 2)

        val newL1 = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        assertEquals(1024, newL1)

        for (by in 0 until 16) {
            for (bx in 0 until 16) {
                assertEquals(wordAt(orig, 1, bx, by), wordAt(out, 1, bx, by))
                assertEquals(btsAt(orig, 1, 1, bx, by), btsAt(out, 1, 2, bx, by))
            }
        }
        for (by in 16 until 32) {
            for (bx in 0 until 16) {
                assertEquals(0x0000, wordAt(out, 1, bx, by))
                assertEquals(0x00, btsAt(out, 1, 2, bx, by))
            }
        }
    }

    @Test
    fun `grow both dimensions preserves all old tiles`() {
        val orig = buildBlob(2, 2)
        val out = LevelDataResize.resize(orig, 2, 2, 3, 3)

        val newL1 = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        assertEquals(3 * 3 * 512, newL1)

        // Preserved region: (0..31, 0..31) (= 2 screens × 16 blocks each direction)
        for (by in 0 until 32) {
            for (bx in 0 until 32) {
                assertEquals(wordAt(orig, 2, bx, by), wordAt(out, 3, bx, by),
                    "preserved word at ($bx,$by)")
                assertEquals(btsAt(orig, 2, 2, bx, by), btsAt(out, 3, 3, bx, by),
                    "preserved bts at ($bx,$by)")
            }
        }
        // New right strip (bx 32..47, by 0..31)
        for (by in 0 until 32) {
            for (bx in 32 until 48) {
                assertEquals(0x0000, wordAt(out, 3, bx, by))
                assertEquals(0x00, btsAt(out, 3, 3, bx, by))
            }
        }
        // New bottom strip (by 32..47, bx 0..47)
        for (by in 32 until 48) {
            for (bx in 0 until 48) {
                assertEquals(0x0000, wordAt(out, 3, bx, by))
                assertEquals(0x00, btsAt(out, 3, 3, bx, by))
            }
        }
    }

    @Test
    fun `custom fill word and bts are honored`() {
        val orig = buildBlob(1, 1)
        val out = LevelDataResize.resize(
            orig, 1, 1, 2, 1,
            fillBlockWord = 0x8123,
            fillBts = 0x77,
        )
        assertEquals(0x8123, wordAt(out, 2, 16, 0))
        assertEquals(0x77, btsAt(out, 2, 1, 16, 0))
    }

    @Test
    fun `shrink is rejected`() {
        val orig = buildBlob(2, 2)
        assertThrows(IllegalArgumentException::class.java) {
            LevelDataResize.resize(orig, 2, 2, 1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LevelDataResize.resize(orig, 2, 2, 2, 1)
        }
    }

    @Test
    fun `oversized area is rejected`() {
        val orig = buildBlob(6, 8)  // 48 screens, valid
        assertThrows(IllegalArgumentException::class.java) {
            // 6 × 9 = 54 > 50
            LevelDataResize.resize(orig, 6, 8, 6, 9)
        }
    }

    @Test
    fun `dimensions out of range are rejected`() {
        val orig = buildBlob(1, 1)
        assertThrows(IllegalArgumentException::class.java) {
            LevelDataResize.resize(orig, 1, 1, 17, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LevelDataResize.resize(orig, 0, 1, 1, 1)
        }
    }

    @Test
    fun `mismatched layer1Size header is rejected`() {
        val orig = buildBlob(1, 1)
        // Corrupt the header
        orig[0] = 0x00
        orig[1] = 0x10
        assertThrows(IllegalArgumentException::class.java) {
            LevelDataResize.resize(orig, 1, 1, 2, 1)
        }
    }

    @Test
    fun `layer 2 stored blobs are resized correctly`() {
        // Synthetic blob with: header + L1 + L2 (=L1) + BTS
        val w = 1; val h = 1
        val l1 = w * h * 512
        val bts = w * h * 256
        val blob = ByteArray(2 + 2 * l1 + bts)
        blob[0] = (l1 and 0xFF).toByte()
        blob[1] = ((l1 shr 8) and 0xFF).toByte()
        // Put a recognizable pattern in Layer 2
        for (i in 2 + l1 until 2 + 2 * l1) blob[i] = 0x42
        val out = LevelDataResize.resize(blob, w, h, 2, 1)
        val newL1 = 2 * 1 * 512
        val expectedSize = 2 + 2 * newL1 + (2 * 1 * 256)
        assertEquals(expectedSize, out.size)
        // Layer 2 old tiles should be preserved at their positions
        assertEquals(0x42, out[2 + newL1].toInt() and 0xFF)
    }

    @Test
    fun `landing site sized resize 9x5 to 10x5 produces correct sized output`() {
        // Just sanity-check that the helper handles real-world dimensions without
        // running off the end of the buffer.
        val orig = buildBlob(9, 5)
        val out = LevelDataResize.resize(orig, 9, 5, 10, 5)

        // Header
        val newL1 = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        assertEquals(10 * 5 * 512, newL1)
        assertEquals(2 + 10 * 5 * 512 + 10 * 5 * 256, out.size)

        // Sample preserved tile at (20, 40): old idx = 40 * 144 + 20 = 5780,
        // new idx = 40 * 160 + 20 = 6420.
        assertEquals(wordAt(orig, 9, 20, 40), wordAt(out, 10, 20, 40))
        assertEquals(btsAt(orig, 9, 5, 20, 40), btsAt(out, 10, 5, 20, 40))

        // New right column (bx 144..159) — air
        for (by in 0 until 80) {
            for (bx in 144 until 160) {
                assertEquals(0x0000, wordAt(out, 10, bx, by))
                assertEquals(0x00, btsAt(out, 10, 5, bx, by))
            }
        }
    }
}
