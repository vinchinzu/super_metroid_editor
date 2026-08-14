package com.supermetroid.editor.rom

/**
 * Check if a 14-byte decompression call pattern exists at patternStart and points to expectedPtr.
 * Returns true if all opcodes match and the decoded pointer equals expectedPtr.
 *
 * Pattern:
 *   pc+0:  A9 xx xx     LDA #$bankWord
 *   pc+3:  85 48        STA $48
 *   pc+5:  A9 ll hh     LDA #$lowWord
 *   pc+8:  85 47        STA $47
 *   pc+10: 22 FF B0 80  JSL $80B0FF
 *
 * Pointer decode: (((bankWord ushr 8) and 0xFF) shl 16) or lowWord
 *   where bankWord = U16 at pc+1, lowWord = U16 at pc+6
 */
fun isValidCreLoadSitePattern(romData: ByteArray, patternStart: Int, expectedPtr: Int): Boolean {
    if (patternStart < 0 || patternStart + 14 > romData.size) return false

    // Check opcodes
    if ((romData[patternStart].toInt() and 0xFF) != 0xA9) return false
    if ((romData[patternStart + 3].toInt() and 0xFF) != 0x85 ||
        (romData[patternStart + 4].toInt() and 0xFF) != 0x48) return false
    if ((romData[patternStart + 5].toInt() and 0xFF) != 0xA9) return false
    if ((romData[patternStart + 8].toInt() and 0xFF) != 0x85 ||
        (romData[patternStart + 9].toInt() and 0xFF) != 0x47) return false
    if ((romData[patternStart + 10].toInt() and 0xFF) != 0x22 ||
        (romData[patternStart + 11].toInt() and 0xFF) != 0xFF ||
        (romData[patternStart + 12].toInt() and 0xFF) != 0xB0 ||
        (romData[patternStart + 13].toInt() and 0xFF) != 0x80) {
        return false
    }

    // Decode pointer
    val bankWord = readU16(romData, patternStart + 1)
    val lowWord = readU16(romData, patternStart + 6)
    val ptr = (((bankWord ushr 8) and 0xFF) shl 16) or lowWord

    return ptr == expectedPtr
}

private fun readU16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8)
