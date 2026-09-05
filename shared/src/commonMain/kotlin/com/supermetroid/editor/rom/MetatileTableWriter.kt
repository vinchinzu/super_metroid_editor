package com.supermetroid.editor.rom

/**
 * Testable metatile table writer for ROM export.
 *
 * Writes metatile tables to ROM with fail-closed semantics:
 * - CRE: in-place if compressed fits, abort if not (never relocate)
 * - VAR: in-place if compressed fits, relocate and update tileset table pointer if not
 *
 * All operations validate before any write. If any validation or allocation fails,
 * the entire romData array remains unchanged.
 */

/**
 * Result of a metatile table write operation.
 */
sealed class MetatileTableWriteResult {
    data class InPlace(val compressedSize: Int, val originalSize: Int) : MetatileTableWriteResult()
    data class Relocated(
        val compressedSize: Int,
        val originalSize: Int,
        val newSnesAddress: Int,
    ) : MetatileTableWriteResult()
}

/**
 * Write a CRE metatile table to ROM (in-place only, abort if compressed grows).
 *
 * @param rawTable Raw metatile table bytes (must be non-empty and multiple of 8)
 * @param romData ROM data to modify
 * @param creSnesPtr SNES address of the CRE table (from RomGraphicsCatalog.creTileTablePtr)
 * @param snesToPc Function to convert SNES address to PC offset
 * @param compress Function to compress raw bytes
 * @param decompress Function to decompress and return (data, originalCompressedSize)
 * @return Write result on success
 * @throws IllegalArgumentException if rawTable is invalid
 * @throws IllegalStateException if compressed table does not fit in original space
 */
fun writeCreMetatileTable(
    rawTable: ByteArray,
    romData: ByteArray,
    creSnesPtr: Int,
    snesToPc: (Int) -> Int,
    compress: (ByteArray) -> ByteArray,
    decompress: (Int) -> Pair<ByteArray, Int>,
): MetatileTableWriteResult {
    val validationError = validateMetatileTableForExport(rawTable)
    if (validationError != null) {
        throw IllegalArgumentException(validationError)
    }

    val compressed = compress(rawTable)
    val pcOffset = snesToPc(creSnesPtr)
    val (_, origSize) = decompress(creSnesPtr)

    if (compressed.size > origSize) {
        throw IllegalStateException(
            "CRE metatile table compressed size (${compressed.size}) exceeds original ($origSize). " +
                "CRE cannot be relocated in this PR. Abort export."
        )
    }

    System.arraycopy(compressed, 0, romData, pcOffset, compressed.size)
    for (i in compressed.size until origSize) {
        romData[pcOffset + i] = 0xFF.toByte()
    }

    return MetatileTableWriteResult.InPlace(compressed.size, origSize)
}

/**
 * Write a variable metatile table to ROM (in-place if fits, relocate if needed).
 *
 * @param rawTable Raw metatile table bytes (must be non-empty and multiple of 8)
 * @param romData ROM data to modify
 * @param varSnesPtr SNES address of the variable table from tileset entry
 * @param tilesetTableEntryOffset PC offset of the tileset table entry's tile-table pointer field
 * @param snesToPc Function to convert SNES address to PC offset
 * @param pcToSnes Function to convert PC offset to SNES address
 * @param compress Function to compress raw bytes
 * @param decompress Function to decompress and return (data, originalCompressedSize)
 * @param allocate Function to allocate free space: (bytes, banks, label) -> snesAddress or null
 * @return Write result on success
 * @throws IllegalArgumentException if rawTable is invalid
 * @throws IllegalStateException if compressed table does not fit and no free space available
 */
fun writeVarMetatileTable(
    rawTable: ByteArray,
    romData: ByteArray,
    varSnesPtr: Int,
    tilesetTableEntryOffset: Int,
    snesToPc: (Int) -> Int,
    pcToSnes: (Int) -> Int,
    compress: (ByteArray) -> ByteArray,
    decompress: (Int) -> Pair<ByteArray, Int>,
    allocate: (ByteArray, List<Int>, String) -> Int?,
): MetatileTableWriteResult {
    val validationError = validateMetatileTableForExport(rawTable)
    if (validationError != null) {
        throw IllegalArgumentException(validationError)
    }

    val compressed = compress(rawTable)
    val pcOffset = snesToPc(varSnesPtr)
    val (_, origSize) = decompress(varSnesPtr)

    if (compressed.size <= origSize) {
        System.arraycopy(compressed, 0, romData, pcOffset, compressed.size)
        for (i in compressed.size until origSize) {
            romData[pcOffset + i] = 0xFF.toByte()
        }
        return MetatileTableWriteResult.InPlace(compressed.size, origSize)
    }

    val origBank = (varSnesPtr shr 16) and 0xFF
    val banksToTry = (listOf(origBank) + (0xCE downTo 0xC0) + (0xBF downTo 0xB0))
        .distinct()
        .filter { bank ->
            val bankStart = runCatching { snesToPc((bank shl 16) or 0x8000) }.getOrNull()
            val bankEnd = runCatching { snesToPc((bank shl 16) or 0xFFFF) + 1 }.getOrNull()
            bankStart != null && bankEnd != null && bankStart >= 0 && bankEnd <= romData.size
        }

    val newSnesAddress = allocate(compressed, banksToTry, "variable metatile table")
        ?: throw IllegalStateException(
            "Variable metatile table compressed size (${compressed.size}) exceeds original ($origSize) " +
                "and no free space was found. Abort export."
        )

    // Update tileset table pointer
    writeU24(romData, tilesetTableEntryOffset, newSnesAddress)

    // FF-fill old location
    for (i in pcOffset until pcOffset + origSize) {
        romData[i] = 0xFF.toByte()
    }

    return MetatileTableWriteResult.Relocated(compressed.size, origSize, newSnesAddress)
}

private fun writeU24(romData: ByteArray, offset: Int, value: Int) {
    if (offset + 2 < romData.size) {
        romData[offset] = (value and 0xFF).toByte()
        romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
        romData[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }
}
