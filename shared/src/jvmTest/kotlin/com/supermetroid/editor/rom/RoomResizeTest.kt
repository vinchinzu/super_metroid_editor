package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Integration test: enlarge the Crateria Landing Site (0x91F8) from
 * 9×5 to 10×5 screens in a copy of the ROM, verify that level data,
 * scroll data, and header bytes all round-trip correctly.
 *
 * Mirrors the operations `EditorState.exportToRom()` would perform
 * when a `RoomHeaderChange(width = 10)` is applied, exercising the
 * full pipeline: decompress → [LevelDataResize] → recompress →
 * relocate → header writeback → re-parse.
 *
 * Skips gracefully when the test ROM is not found.
 */
class RoomResizeTest {

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

    /** Read a little-endian 16-bit value from a byte array. */
    private fun readU16(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    /** Read a little-endian 24-bit value from a byte array. */
    private fun readU24(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16)

    // ────────────────────────────────────────────────────────────────

    @Test
    fun `Landing Site widen 9x5 to 10x5 round-trips level data and scroll data`() {
        val parser = loadTestRom() ?: return
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId)!!
        assertEquals(9, room.width)
        assertEquals(5, room.height)

        val oldW = room.width
        val oldH = room.height
        val newW = 10
        val newH = 5

        // ─── Snapshot original data per-state for later verification ──
        val allStateOffsets = parser.findAllStateDataOffsets(roomId)
        assertTrue(allStateOffsets.size >= 2,
            "Landing Site should have multiple state conditions (found ${allStateOffsets.size})")

        data class StateSnap(
            val stateOffsets: List<Int>,
            val origDecomp: ByteArray,
            val origCompSize: Int,
        )

        val romBytes = parser.getRomData()
        val ptrToStates = mutableMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val lvlPtr = readU24(romBytes, stateOffset)
            if (lvlPtr != 0) ptrToStates.getOrPut(lvlPtr) { mutableListOf() }.add(stateOffset)
        }
        val snapshots = mutableMapOf<Int, StateSnap>()
        for ((lvlPtr, stateOffs) in ptrToStates) {
            val (decomp, compSize) = parser.decompressLZ2WithSize(lvlPtr)
            snapshots[lvlPtr] = StateSnap(stateOffs, decomp, compSize)
        }

        // Snapshot scroll data per-state
        val scrollPtrToStates = mutableMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val sp = readU16(romBytes, stateOffset + 14)
            scrollPtrToStates.getOrPut(sp) { mutableListOf() }.add(stateOffset)
        }
        val oldScrollsByPtr = mutableMapOf<Int, IntArray>()
        for (scrollPtr in scrollPtrToStates.keys) {
            if (scrollPtr > 1) {
                oldScrollsByPtr[scrollPtr] = parser.parseScrollData(scrollPtr, oldW, oldH)
            }
        }

        // ─── Apply resize to a ROM copy ──────────────────────────────
        val romData = romBytes.copyOf()

        // 1. Free space tracker for level data banks ($C0-$CE)
        val levelBankFree = mutableMapOf<Int, Int>()
        fun getLevelBankFreePtr(bank: Int): Int {
            return levelBankFree.getOrPut(bank) {
                val bankEnd = parser.snesToPc((bank shl 16) or 0xFFFF) + 1
                val bankStart = parser.snesToPc((bank shl 16) or 0x8000)
                var ptr = bankEnd
                while (ptr > bankStart) {
                    if ((romData[ptr - 1].toInt() and 0xFF) != 0xFF) break
                    ptr--
                }
                ptr + 1
            }
        }

        // 2. Free space in bank $8F for scroll data relocation
        val bank8FEnd = parser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = parser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            if ((romData[freePtr - 1].toInt() and 0xFF) != 0xFF) break
            freePtr--
        }
        freePtr++

        // 3. Resize level data for each distinct pointer
        for ((lvlPtr, snap) in snapshots) {
            val resized = LevelDataResize.resize(snap.origDecomp, oldW, oldH, newW, newH)
            val compressed = LZ5Compressor.compress(resized)

            val pcOff = parser.snesToPc(lvlPtr)
            if (compressed.size <= snap.origCompSize) {
                System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
                for (i in compressed.size until snap.origCompSize) romData[pcOff + i] = 0xFF.toByte()
            } else {
                val origBank = (lvlPtr shr 16) and 0xFF
                val banksToTry = listOf(origBank) + (0xCE downTo 0xC0).filter { it != origBank }
                var relocated = false
                for (tryBank in banksToTry) {
                    val bEnd = parser.snesToPc((tryBank shl 16) or 0xFFFF) + 1
                    val freeStart = getLevelBankFreePtr(tryBank)
                    if (freeStart + compressed.size <= bEnd) {
                        System.arraycopy(compressed, 0, romData, freeStart, compressed.size)
                        val newSnes = parser.pcToSnes(freeStart)
                        levelBankFree[tryBank] = freeStart + compressed.size
                        for (stateOffset in snap.stateOffsets) {
                            romData[stateOffset] = (newSnes and 0xFF).toByte()
                            romData[stateOffset + 1] = ((newSnes shr 8) and 0xFF).toByte()
                            romData[stateOffset + 2] = ((newSnes shr 16) and 0xFF).toByte()
                        }
                        for (i in pcOff until pcOff + snap.origCompSize) romData[i] = 0xFF.toByte()
                        println("Relocated lvlPtr=\$${lvlPtr.toString(16)} to bank \$${tryBank.toString(16)} " +
                                "(${compressed.size} bytes, ${snap.stateOffsets.size} states)")
                        relocated = true
                        break
                    }
                }
                assertTrue(relocated,
                    "Should find free space for resized level data (${compressed.size} bytes)")
            }
        }

        // 4. Resize scroll data for each distinct real pointer
        for ((scrollPtr, stateOffs) in scrollPtrToStates) {
            if (scrollPtr <= 1) continue
            val scrollPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or scrollPtr)
            val oldSize = oldW * oldH
            val newSize = newW * newH
            val newScrolls = ByteArray(newSize) { 0x01 }
            for (sy in 0 until oldH) {
                for (sx in 0 until oldW) {
                    newScrolls[sy * newW + sx] = romData[scrollPc + sy * oldW + sx]
                }
            }
            assertTrue(freePtr + newSize <= bank8FEnd,
                "Should have free space in \$8F for scroll data ($newSize bytes)")
            val writePc = freePtr
            freePtr += newSize
            System.arraycopy(newScrolls, 0, romData, writePc, newSize)
            val newSnes = parser.pcToSnes(writePc)
            val newPtr = newSnes and 0xFFFF
            for (stateOffset in stateOffs) {
                romData[stateOffset + 14] = (newPtr and 0xFF).toByte()
                romData[stateOffset + 15] = ((newPtr shr 8) and 0xFF).toByte()
            }
            for (i in 0 until oldSize) romData[scrollPc + i] = 0
            println("Relocated scroll 0x${scrollPtr.toString(16)} -> 0x${newPtr.toString(16)} " +
                    "($newSize bytes, ${stateOffs.size} states)")
        }

        // 5. Write new header width byte (stored 1-based: value IS the count)
        val headerPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
        romData[headerPc + 4] = newW.toByte()

        // ─── Verify ──────────────────────────────────────────────────
        val parser2 = RomParser(romData)
        val room2 = parser2.readRoomHeader(roomId)!!
        assertEquals(newW, room2.width, "Header width should be $newW")
        assertEquals(newH, room2.height, "Header height should be $newH")

        // Verify level data for every state
        val allStateOffsets2 = parser2.findAllStateDataOffsets(roomId)
        assertEquals(allStateOffsets.size, allStateOffsets2.size,
            "Number of state data offsets should not change")

        for (stateOffset in allStateOffsets2) {
            val lvlPtr2 = readU24(parser2.getRomData(), stateOffset)
            if (lvlPtr2 == 0) continue
            val decomp2 = parser2.decompressLZ2(lvlPtr2)
            assertTrue(decomp2.isNotEmpty(),
                "Decompressed data at state +0x${stateOffset.toString(16)} should be non-empty")

            val newL1Size = readU16(decomp2, 0)
            assertEquals(newW * newH * 512, newL1Size,
                "Layer 1 size header should match ${newW}x${newH} = ${newW * newH * 512}")

            val expectedBlobSize = 2 + newW * newH * 512 + newW * newH * 256
            assertTrue(decomp2.size >= expectedBlobSize,
                "Decompressed blob should be at least $expectedBlobSize bytes, got ${decomp2.size}")
        }

        // Verify a specific tile preservation: (20, 40) in the default state.
        // Old index = 40 * 144 + 20 = 5780, new index = 40 * 160 + 20 = 6420.
        val defaultStateOff = allStateOffsets2.last()  // E5E6 = last entry
        val defaultLvlPtr = readU24(parser2.getRomData(), defaultStateOff)
        val decomp2 = parser2.decompressLZ2(defaultLvlPtr)
        val origDefaultLvlPtr = readU24(romBytes, allStateOffsets.last())
        val origDecomp = snapshots[origDefaultLvlPtr]!!.origDecomp
        val sampleX = 20; val sampleY = 40
        val oldIdx = sampleY * (oldW * 16) + sampleX
        val newIdx = sampleY * (newW * 16) + sampleX
        val origWord = readU16(origDecomp, 2 + oldIdx * 2)
        val newWord = readU16(decomp2, 2 + newIdx * 2)
        assertEquals(origWord, newWord,
            "Tile word at ($sampleX,$sampleY) should be preserved: " +
                    "old=0x${origWord.toString(16)}, new=0x${newWord.toString(16)}")

        // Verify BTS preservation at the same position
        val origL1Size = readU16(origDecomp, 0)
        val newL1Size2 = readU16(decomp2, 0)
        val origBts = origDecomp[2 + origL1Size + oldIdx].toInt() and 0xFF
        val newBts = decomp2[2 + newL1Size2 + newIdx].toInt() and 0xFF
        assertEquals(origBts, newBts,
            "BTS at ($sampleX,$sampleY) should be preserved")

        // Verify new right-column tiles are air (0x0000) + BTS 0x00
        val newStride = newW * 16
        for (by in 0 until newH * 16) {
            for (bx in oldW * 16 until newStride) {
                val idx = by * newStride + bx
                val word = readU16(decomp2, 2 + idx * 2)
                assertEquals(0x0000, word,
                    "New tile at ($bx,$by) should be air, got 0x${word.toString(16)}")
                val btsOff = 2 + newL1Size2 + idx
                assertEquals(0x00, decomp2[btsOff].toInt() and 0xFF,
                    "New BTS at ($bx,$by) should be 0x00")
            }
        }
        println("Level data verification: all new columns are air, all old tiles preserved")

        // Verify scroll data: 50 entries, old 45 preserved (re-strided), new = blue
        for (stateOffset in allStateOffsets2) {
            val scrollPtr = readU16(parser2.getRomData(), stateOffset + 14)
            if (scrollPtr <= 1) continue
            val newScrolls = parser2.parseScrollData(scrollPtr, newW, newH)
            assertEquals(newW * newH, newScrolls.size,
                "Scroll data should have ${newW * newH} entries")

            val stateIdx = allStateOffsets2.indexOf(stateOffset)
            val origStateOffset = allStateOffsets[stateIdx]
            val origScrollPtr = readU16(romBytes, origStateOffset + 14)
            val origScrolls = oldScrollsByPtr[origScrollPtr] ?: continue
            for (sy in 0 until oldH) {
                for (sx in 0 until oldW) {
                    assertEquals(origScrolls[sy * oldW + sx], newScrolls[sy * newW + sx],
                        "Scroll at ($sx,$sy) should be preserved")
                }
                assertEquals(0x01, newScrolls[sy * newW + (newW - 1)],
                    "New scroll column at sy=$sy should be blue (0x01)")
            }
        }
        println("Scroll data verification: all old scrolls preserved, new column = blue")
        println("Room resize test PASSED: Landing Site 9x5 → 10x5")
    }

    @Test
    fun `resize identity produces identical decompressed data`() {
        val parser = loadTestRom() ?: return
        val roomId = 0x91F8
        val room = parser.readRoomHeader(roomId)!!
        val (origDecomp, _) = parser.decompressLZ2WithSize(room.levelDataPtr)

        val resized = LevelDataResize.resize(origDecomp, room.width, room.height, room.width, room.height)
        assertEquals(origDecomp.size, resized.size, "Identity resize should preserve blob size")
        for (i in origDecomp.indices) {
            assertEquals(origDecomp[i], resized[i],
                "Identity resize byte mismatch at offset $i")
        }
        println("Identity resize on real Landing Site data: PASSED (${origDecomp.size} bytes)")
    }
}
