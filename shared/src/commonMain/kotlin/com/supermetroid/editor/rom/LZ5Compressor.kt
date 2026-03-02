package com.supermetroid.editor.rom

/**
 * LZ5 compressor compatible with Super Metroid's decompression engine.
 * Supports: raw copy (cmd 0), byte fill (cmd 1), word fill (cmd 2),
 * increasing fill (cmd 3), absolute dictionary copy (cmd 4),
 * and relative dictionary copy (cmd 6).
 *
 * Uses hash-chain dictionary for O(n) average-case matching.
 * Prefers relative copy (1-byte offset) over absolute (2-byte) when
 * match lengths are equal, saving 1 byte per backreference.
 */
object LZ5Compressor {

    private const val MAX_LEN = 1024
    private const val HASH_SIZE = 0x4000
    private const val HASH_MASK = HASH_SIZE - 1
    private const val MAX_CHAIN = 512

    fun compress(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        val rawBuf = mutableListOf<Byte>()
        var pos = 0

        val hashHead = IntArray(HASH_SIZE) { -1 }
        val hashPrev = IntArray(data.size) { -1 }

        fun hashAt(p: Int): Int {
            if (p + 2 >= data.size) return 0
            val h = ((data[p].toInt() and 0xFF) shl 10) xor
                    ((data[p + 1].toInt() and 0xFF) shl 5) xor
                    (data[p + 2].toInt() and 0xFF)
            return h and HASH_MASK
        }

        fun updateHash(p: Int) {
            if (p >= data.size) return
            val h = hashAt(p)
            hashPrev[p] = hashHead[h]
            hashHead[h] = p
        }

        fun flushRaw() {
            var i = 0
            while (i < rawBuf.size) {
                val chunk = minOf(rawBuf.size - i, MAX_LEN)
                emitCmd(out, 0, chunk)
                for (j in 0 until chunk) out.add(rawBuf[i + j])
                i += chunk
            }
            rawBuf.clear()
        }

        fun findMatch(curPos: Int): Triple<Int, Int, Boolean> {
            var bestLen = 0
            var bestAddr = 0
            var useRelative = false
            val maxMatch = minOf(data.size - curPos, MAX_LEN)
            if (maxMatch < 3) return Triple(0, 0, false)

            val h = hashAt(curPos)
            var candidate = hashHead[h]
            var chainLen = 0

            while (candidate >= 0 && chainLen < MAX_CHAIN) {
                var mLen = 0
                while (mLen < maxMatch && data[candidate + mLen] == data[curPos + mLen]) mLen++
                val relOff = curPos - candidate
                val isRel = relOff in 1..0xFF
                // Update if longer, OR same length but relative (saves 1 byte)
                if (mLen > bestLen || (mLen == bestLen && mLen >= 3 && isRel && !useRelative)) {
                    bestLen = mLen
                    if (isRel) {
                        bestAddr = relOff
                        useRelative = true
                    } else {
                        bestAddr = candidate
                        useRelative = false
                    }
                    if (mLen >= 256) break
                }
                candidate = hashPrev[candidate]
                chainLen++
            }
            return Triple(bestLen, bestAddr, useRelative)
        }

        while (pos < data.size) {
            val (dictLen, dictAddr, dictRelative) = findMatch(pos)
            val byteLen = countByteFill(data, pos)
            val wordLen = countWordFill(data, pos)
            val incrLen = countIncrFill(data, pos)

            val dictHeaderCost = if (dictRelative) {
                if (dictLen <= 32) 2 else 3
            } else {
                if (dictLen <= 32) 3 else 4
            }
            val dictSaving = if (dictLen >= 3) dictLen - dictHeaderCost else 0
            val byteSaving = if (byteLen >= 3) byteLen - (if (byteLen <= 32) 2 else 3) else 0
            val wordSaving = if (wordLen >= 4) wordLen - (if (wordLen <= 32) 3 else 4) else 0
            val incrSaving = if (incrLen >= 3) incrLen - (if (incrLen <= 32) 2 else 3) else 0

            when {
                dictSaving > 0 && dictSaving >= byteSaving && dictSaving >= wordSaving && dictSaving >= incrSaving -> {
                    flushRaw()
                    val len = minOf(dictLen, MAX_LEN)
                    if (dictRelative) {
                        emitCmd(out, 6, len)
                        out.add((dictAddr and 0xFF).toByte())
                    } else {
                        emitCmd(out, 4, len)
                        out.add((dictAddr and 0xFF).toByte())
                        out.add(((dictAddr shr 8) and 0xFF).toByte())
                    }
                    for (i in 0 until len) updateHash(pos + i)
                    pos += len
                }
                byteSaving > 0 && byteSaving >= wordSaving && byteSaving >= incrSaving -> {
                    flushRaw()
                    val len = minOf(byteLen, MAX_LEN)
                    emitCmd(out, 1, len)
                    out.add(data[pos])
                    for (i in 0 until len) updateHash(pos + i)
                    pos += len
                }
                incrSaving > 0 && incrSaving >= wordSaving -> {
                    flushRaw()
                    val len = minOf(incrLen, MAX_LEN)
                    emitCmd(out, 3, len)
                    out.add(data[pos])
                    for (i in 0 until len) updateHash(pos + i)
                    pos += len
                }
                wordSaving > 0 -> {
                    flushRaw()
                    val len = minOf(wordLen, MAX_LEN)
                    emitCmd(out, 2, len)
                    out.add(data[pos])
                    out.add(data[pos + 1])
                    for (i in 0 until len) updateHash(pos + i)
                    pos += len
                }
                else -> {
                    updateHash(pos)
                    rawBuf.add(data[pos])
                    pos++
                }
            }
        }
        flushRaw()
        out.add(0xFF.toByte())
        return out.toByteArray()
    }

    private fun countByteFill(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 0
        val b = data[pos]; var c = 1
        while (pos + c < data.size && c < MAX_LEN && data[pos + c] == b) c++
        return c
    }

    private fun countWordFill(data: ByteArray, pos: Int): Int {
        if (pos + 1 >= data.size) return 0
        val a = data[pos]; val b = data[pos + 1]; var c = 2
        while (pos + c < data.size && c < MAX_LEN) {
            if (data[pos + c] != (if (c % 2 == 0) a else b)) break
            c++
        }
        return c
    }

    private fun countIncrFill(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 0
        var expected = (data[pos].toInt() and 0xFF) + 1
        var c = 1
        while (pos + c < data.size && c < MAX_LEN) {
            if ((data[pos + c].toInt() and 0xFF) != (expected and 0xFF)) break
            expected++
            c++
        }
        return c
    }

    /**
     * Standalone LZ5 decompression from a raw compressed byte array.
     * Used for round-trip verification during export.
     */
    fun decompress(compressed: ByteArray): ByteArray {
        val dst = ByteArray(0x20000)
        var dstPos = 0
        var pos = 0

        while (pos < compressed.size) {
            val nextCmd = compressed[pos].toInt() and 0xFF
            if (nextCmd == 0xFF) break

            val cmdCode: Int
            val length: Int
            val topBits = (nextCmd shr 5) and 7
            if (topBits == 7) {
                if (pos + 1 >= compressed.size) break
                cmdCode = (nextCmd shr 2) and 7
                val highBits = nextCmd and 0x03
                val lowBits = compressed[pos + 1].toInt() and 0xFF
                length = ((highBits shl 8) or lowBits) + 1
                pos += 2
            } else {
                cmdCode = topBits
                length = (nextCmd and 0x1F) + 1
                pos += 1
            }

            when (cmdCode) {
                0 -> for (i in 0 until length) {
                    if (pos >= compressed.size) break
                    dst[dstPos++] = compressed[pos++]
                }
                1 -> {
                    val b = compressed[pos++]
                    for (i in 0 until length) dst[dstPos++] = b
                }
                2 -> {
                    val b1 = compressed[pos++]; val b2 = compressed[pos++]
                    for (i in 0 until length) dst[dstPos++] = if (i % 2 == 0) b1 else b2
                }
                3 -> {
                    var b = compressed[pos++].toInt() and 0xFF
                    for (i in 0 until length) { dst[dstPos++] = (b and 0xFF).toByte(); b++ }
                }
                4 -> {
                    val addr = (compressed[pos].toInt() and 0xFF) or
                            ((compressed[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    for (i in 0 until length) {
                        val si = addr + i
                        dst[dstPos + i] = if (si < dstPos + i) dst[si] else 0
                    }
                    dstPos += length
                }
                5 -> {
                    val addr = (compressed[pos].toInt() and 0xFF) or
                            ((compressed[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    for (i in 0 until length) {
                        val si = addr + i
                        dst[dstPos + i] = if (si < dstPos + i) (dst[si].toInt() xor 0xFF).toByte() else 0xFF.toByte()
                    }
                    dstPos += length
                }
                6 -> {
                    val relOffset = compressed[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    for (i in 0 until length) {
                        val si = srcAddr + i
                        dst[dstPos + i] = if (si >= 0 && si < dstPos + i) dst[si] else 0
                    }
                    dstPos += length
                }
                7 -> {
                    val relOffset = compressed[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    for (i in 0 until length) {
                        val si = srcAddr + i
                        dst[dstPos + i] = if (si >= 0 && si < dstPos + i) (dst[si].toInt() xor 0xFF).toByte() else 0xFF.toByte()
                    }
                    dstPos += length
                }
            }
            if (dstPos >= dst.size) break
        }
        return dst.copyOf(dstPos)
    }

    private fun emitCmd(out: MutableList<Byte>, cmd: Int, length: Int) {
        if (length <= 32) {
            out.add(((cmd shl 5) or (length - 1)).toByte())
        } else {
            val len = length - 1
            out.add((0xE0 or ((cmd and 7) shl 2) or ((len shr 8) and 0x03)).toByte())
            out.add((len and 0xFF).toByte())
        }
    }
}
