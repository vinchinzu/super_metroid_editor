package com.supermetroid.editor.rom

/**
 * LZ5 compressor compatible with Super Metroid's decompression engine.
 * Supports: raw copy (cmd 0), byte fill (cmd 1), word fill (cmd 2),
 * and dictionary copy (cmd 4).
 */
object LZ5Compressor {

    fun compress(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        val rawBuf = mutableListOf<Byte>()
        var pos = 0

        fun flushRaw() {
            var i = 0
            while (i < rawBuf.size) {
                val chunk = minOf(rawBuf.size - i, 1024)
                emitCmd(out, 0, chunk)
                for (j in 0 until chunk) out.add(rawBuf[i + j])
                i += chunk
            }
            rawBuf.clear()
        }

        while (pos < data.size) {
            val (dictLen, dictAddr) = findDictMatch(data, pos)
            val byteLen = countByteFill(data, pos)
            val wordLen = countWordFill(data, pos)
            val dictSaving = if (dictLen >= 3) dictLen - (if (dictLen <= 32) 3 else 4) else 0
            val byteSaving = if (byteLen >= 3) byteLen - (if (byteLen <= 32) 2 else 3) else 0
            val wordSaving = if (wordLen >= 4) wordLen - (if (wordLen <= 32) 3 else 4) else 0
            when {
                dictSaving > 0 && dictSaving >= byteSaving && dictSaving >= wordSaving -> {
                    flushRaw()
                    val len = minOf(dictLen, 1024)
                    emitCmd(out, 4, len)
                    out.add((dictAddr and 0xFF).toByte())
                    out.add(((dictAddr shr 8) and 0xFF).toByte())
                    pos += len
                }
                byteSaving > 0 && byteSaving >= wordSaving -> {
                    flushRaw()
                    val len = minOf(byteLen, 1024)
                    emitCmd(out, 1, len)
                    out.add(data[pos])
                    pos += len
                }
                wordSaving > 0 -> {
                    flushRaw()
                    val len = minOf(wordLen, 1024)
                    emitCmd(out, 2, len)
                    out.add(data[pos])
                    out.add(data[pos + 1])
                    pos += len
                }
                else -> {
                    rawBuf.add(data[pos])
                    pos++
                }
            }
        }
        flushRaw()
        out.add(0xFF.toByte())
        return out.toByteArray()
    }

    private fun findDictMatch(data: ByteArray, pos: Int): Pair<Int, Int> {
        if (pos < 3) return Pair(0, 0)
        var bestLen = 0; var bestAddr = 0
        val maxSearch = minOf(pos, 0xFFFF)
        val step = if (pos > 4000) 2 else 1
        var start = 0
        while (start < maxSearch) {
            var matchLen = 0
            val maxMatch = minOf(data.size - pos, 1024)
            while (matchLen < maxMatch && data[start + matchLen] == data[pos + matchLen]) matchLen++
            if (matchLen > bestLen) {
                bestLen = matchLen; bestAddr = start
                if (matchLen >= 64) break
            }
            start += step
        }
        return Pair(bestLen, bestAddr)
    }

    private fun countByteFill(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 0
        val b = data[pos]; var c = 1
        while (pos + c < data.size && c < 1024 && data[pos + c] == b) c++
        return c
    }

    private fun countWordFill(data: ByteArray, pos: Int): Int {
        if (pos + 1 >= data.size) return 0
        val a = data[pos]; val b = data[pos + 1]; var c = 2
        while (pos + c < data.size && c < 1024) {
            if (data[pos + c] != (if (c % 2 == 0) a else b)) break
            c++
        }
        return c
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
