package com.supermetroid.editor.rom

/**
 * Re-lays-out a decompressed Super Metroid level-data blob from one
 * (width, height) in screens to a larger one. Only supports growing
 * to the right and/or down — old tile words and BTS bytes are preserved
 * at the same (blockX, blockY) position; new cells are filled with
 * `fillBlockWord` (default = air, 0x0000) and `fillBts` (default = 0).
 *
 * Decompressed layout (header + Layer 1 + BTS, no stored Layer 2):
 * ```
 *   bytes 0..1            : layer1Size (16-bit LE) = W * H * 512
 *   bytes 2..2+L1S-1      : Layer 1 tile words   (2 bytes per block)
 *   bytes 2+L1S..end      : BTS                   (1 byte per block)
 * ```
 * Within each section, blocks are row-major across the entire room with
 * row stride = `W * 16` blocks. So a block at `(bx, by)` lives at index
 * `by * (W*16) + bx`.
 *
 * Rooms with a stored Layer 2 (where the decompressed blob contains a
 * second `layer1Size`-sized block before BTS) are explicitly out of scope
 * for the first room-resize PR and produce an [UnsupportedOperationException].
 * The Crateria Landing Site uses a scrolling background and is not affected.
 */
object LevelDataResize {

    /** Maximum room area in screens (vanilla SM engine constraint). */
    const val MAX_ROOM_SCREENS = 50

    /** Maximum dimension in screens (vanilla SM engine constraint). */
    const val MAX_ROOM_DIMENSION = 16

    /**
     * Returns a new decompressed level-data blob sized for `(newW, newH)` screens,
     * preserving every existing tile word and BTS byte at the same `(bx, by)`.
     *
     * @throws IllegalArgumentException if the new dimensions shrink the room,
     *   are out of range, exceed [MAX_ROOM_SCREENS] in area, or if `original`
     *   is too short to contain `(oldW × oldH)` blocks.
     * @throws UnsupportedOperationException if `original` contains a stored
     *   Layer 2 (PR-1 limitation).
     */
    fun resize(
        original: ByteArray,
        oldW: Int,
        oldH: Int,
        newW: Int,
        newH: Int,
        fillBlockWord: Int = 0x0000,
        fillBts: Int = 0,
    ): ByteArray {
        require(oldW in 1..MAX_ROOM_DIMENSION && oldH in 1..MAX_ROOM_DIMENSION) {
            "old dimensions out of range: ${oldW}x${oldH}"
        }
        require(newW in 1..MAX_ROOM_DIMENSION && newH in 1..MAX_ROOM_DIMENSION) {
            "new dimensions out of range: ${newW}x${newH}"
        }
        require(newW >= oldW && newH >= oldH) {
            "shrink not supported: ${oldW}x${oldH} -> ${newW}x${newH}"
        }
        require(newW * newH <= MAX_ROOM_SCREENS) {
            "new area exceeds max ${MAX_ROOM_SCREENS} screens: ${newW}x${newH} = ${newW * newH}"
        }
        require(original.size >= 2) { "level data blob too small: ${original.size}" }

        val oldL1Size = (original[0].toInt() and 0xFF) or
                ((original[1].toInt() and 0xFF) shl 8)
        val expectedOldL1 = oldW * oldH * 512
        require(oldL1Size == expectedOldL1) {
            "level data layer1Size header ${oldL1Size} does not match ${oldW}x${oldH} (${expectedOldL1})"
        }

        val oldBlocksPerScreenRow = oldW * 16
        val oldBlocksPerScreenCol = oldH * 16
        val oldBtsBytes = oldW * oldH * 256
        val expectedNoLayer2Size = 2 + oldL1Size + oldBtsBytes
        val expectedWithLayer2Size = 2 + 2 * oldL1Size + oldBtsBytes

        val hasLayer2 = original.size >= expectedWithLayer2Size
        if (!hasLayer2) {
            require(original.size >= expectedNoLayer2Size) {
                "level data blob ${original.size} bytes too small for ${oldW}x${oldH} " +
                        "(expected at least ${expectedNoLayer2Size})"
            }
        }

        val newL1Size = newW * newH * 512
        val newBlocksPerScreenRow = newW * 16
        val newBtsBytes = newW * newH * 256
        val newSize = if (hasLayer2) 2 + 2 * newL1Size + newBtsBytes else 2 + newL1Size + newBtsBytes
        val out = ByteArray(newSize)

        // Header: new layer1Size, little-endian.
        out[0] = (newL1Size and 0xFF).toByte()
        out[1] = ((newL1Size shr 8) and 0xFF).toByte()

        val oldL1Start = 2
        val newL1Start = 2
        val oldL2Start = if (hasLayer2) 2 + oldL1Size else -1
        val newL2Start = if (hasLayer2) 2 + newL1Size else -1
        val oldBtsStart = if (hasLayer2) 2 + 2 * oldL1Size else 2 + oldL1Size
        val newBtsStart = if (hasLayer2) 2 + 2 * newL1Size else 2 + newL1Size

        val fillLow = (fillBlockWord and 0xFF).toByte()
        val fillHigh = ((fillBlockWord shr 8) and 0xFF).toByte()
        val fillBtsByte = (fillBts and 0xFF).toByte()

        // 1. Copy preserved Layer 1 region row by row, accounting for the new stride.
        for (by in 0 until oldBlocksPerScreenCol) {
            val srcRowStart = oldL1Start + (by * oldBlocksPerScreenRow) * 2
            val dstRowStart = newL1Start + (by * newBlocksPerScreenRow) * 2
            original.copyInto(
                destination = out,
                destinationOffset = dstRowStart,
                startIndex = srcRowStart,
                endIndex = srcRowStart + oldBlocksPerScreenRow * 2,
            )
            var col = oldBlocksPerScreenRow
            var fillOff = newL1Start + (by * newBlocksPerScreenRow + col) * 2
            while (col < newBlocksPerScreenRow) {
                out[fillOff] = fillLow
                out[fillOff + 1] = fillHigh
                col++
                fillOff += 2
            }
        }

        // 2. Fill new rows beneath the preserved region with the fill word.
        if (newH > oldH) {
            for (by in oldBlocksPerScreenCol until newH * 16) {
                var off = newL1Start + (by * newBlocksPerScreenRow) * 2
                for (bx in 0 until newBlocksPerScreenRow) {
                    out[off] = fillLow
                    out[off + 1] = fillHigh
                    off += 2
                }
            }
        }

        // 2b. Copy preserved Layer 2 region (same layout as Layer 1, fill with 0x0000).
        if (hasLayer2) {
            for (by in 0 until oldBlocksPerScreenCol) {
                val srcRowStart = oldL2Start + (by * oldBlocksPerScreenRow) * 2
                val dstRowStart = newL2Start + (by * newBlocksPerScreenRow) * 2
                original.copyInto(
                    destination = out,
                    destinationOffset = dstRowStart,
                    startIndex = srcRowStart,
                    endIndex = srcRowStart + oldBlocksPerScreenRow * 2,
                )
                // Fill new columns with transparent (0x0000)
                var col = oldBlocksPerScreenRow
                var fillOff = newL2Start + (by * newBlocksPerScreenRow + col) * 2
                while (col < newBlocksPerScreenRow) {
                    out[fillOff] = 0
                    out[fillOff + 1] = 0
                    col++
                    fillOff += 2
                }
            }
            if (newH > oldH) {
                for (by in oldBlocksPerScreenCol until newH * 16) {
                    var off = newL2Start + (by * newBlocksPerScreenRow) * 2
                    for (bx in 0 until newBlocksPerScreenRow) {
                        out[off] = 0
                        out[off + 1] = 0
                        off += 2
                    }
                }
            }
        }

        // 3. Copy preserved BTS region row by row, then fill new columns and new rows.
        for (by in 0 until oldBlocksPerScreenCol) {
            val srcRowStart = oldBtsStart + by * oldBlocksPerScreenRow
            val dstRowStart = newBtsStart + by * newBlocksPerScreenRow
            original.copyInto(
                destination = out,
                destinationOffset = dstRowStart,
                startIndex = srcRowStart,
                endIndex = srcRowStart + oldBlocksPerScreenRow,
            )
            var col = oldBlocksPerScreenRow
            while (col < newBlocksPerScreenRow) {
                out[newBtsStart + by * newBlocksPerScreenRow + col] = fillBtsByte
                col++
            }
        }
        if (newH > oldH) {
            for (by in oldBlocksPerScreenCol until newH * 16) {
                var off = newBtsStart + by * newBlocksPerScreenRow
                for (bx in 0 until newBlocksPerScreenRow) {
                    out[off] = fillBtsByte
                    off++
                }
            }
        }

        return out
    }
}
