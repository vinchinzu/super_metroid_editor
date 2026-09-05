package com.supermetroid.editor.rom

/**
 * ROM-free library for Super Metroid metatile table operations.
 *
 * ## Metatile format
 * Each metatile = 8 bytes = 4 little-endian SNES BG tilemap words (TL, TR, BL, BR).
 *
 * ## SNES BG tilemap word (16-bit):
 * ```
 * VH0PPPTTTTTTTTTT
 * V = vertical flip   (bit 15)
 * H = horizontal flip (bit 14)
 * 0 = priority        (bit 13)
 * PPP = palette row   (bits 10-12)
 * TTTTTTTTTT = tile number (bits 0-9)
 * ```
 *
 * ## Table layout
 * - CRE table: 256 metatiles (2048 bytes) – shared across all tilesets
 * - Variable table per tileset: up to 768 metatiles (6144 bytes)
 *
 * ## Ownership
 * This library owns encoding/decoding of metatile words and raw table I/O.
 * TileGraphics delegates to these functions instead of duplicating the logic.
 */

/**
 * Encode a single SNES BG tilemap word from component fields.
 */
fun encodeMetatileWord(
    tileNum: Int,
    palette: Int,
    priority: Boolean = false,
    hFlip: Boolean = false,
    vFlip: Boolean = false,
): Int {
    return (tileNum.coerceIn(0, 1023) and 0x03FF) or
        ((palette.coerceIn(0, 7) and 7) shl 10) or
        (if (priority) 0x2000 else 0) or
        (if (hFlip) 0x4000 else 0) or
        (if (vFlip) 0x8000 else 0)
}

/**
 * Decode a single SNES BG tilemap word into component fields.
 */
data class MetatileSubtile(
    val tileNum: Int,
    val palette: Int,
    val priority: Boolean,
    val hFlip: Boolean,
    val vFlip: Boolean,
)

fun decodeMetatileWord(word: Int): MetatileSubtile =
    MetatileSubtile(
        tileNum = word and 0x03FF,
        palette = (word shr 10) and 7,
        priority = (word and 0x2000) != 0,
        hFlip = (word and 0x4000) != 0,
        vFlip = (word and 0x8000) != 0,
    )

/**
 * Parse a raw metatile table (decompressed bytes) into an array of metatile definitions.
 * Each metatile = 4 words (TL, TR, BL, BR).
 *
 * @param rawTable Raw bytes (must be non-empty and a multiple of 8)
 * @return Array of metatile definitions
 * @throws IllegalArgumentException if rawTable is empty or not a multiple of 8
 */
fun parseMetatileTable(rawTable: ByteArray): Array<IntArray> {
    require(rawTable.isNotEmpty()) { "Metatile table cannot be empty" }
    require(rawTable.size % 8 == 0) { "Metatile table size must be a multiple of 8, got ${rawTable.size}" }

    val metatileCount = rawTable.size / 8
    return Array(metatileCount) { metatileIndex ->
        val offset = metatileIndex * 8
        IntArray(4) { quadrant ->
            val lo = rawTable[offset + quadrant * 2].toInt() and 0xFF
            val hi = rawTable[offset + quadrant * 2 + 1].toInt() and 0xFF
            (hi shl 8) or lo
        }
    }
}

/**
 * Serialize metatile definitions into raw bytes (little-endian words).
 *
 * @param metatiles Array of metatile definitions (each = 4 words)
 * @param startIndex First metatile to serialize
 * @param count Number of metatiles to serialize
 * @return Raw bytes ready for LZ5 compression
 * @throws IllegalArgumentException if range is invalid or count is 0
 */
fun serializeMetatileTable(
    metatiles: Array<IntArray>,
    startIndex: Int = 0,
    count: Int = metatiles.size,
): ByteArray {
    require(count > 0) { "Cannot serialize 0 metatiles" }
    require(startIndex >= 0 && startIndex < metatiles.size) { "Invalid startIndex: $startIndex" }
    require(startIndex + count <= metatiles.size) { "Range exceeds metatiles array size" }

    val out = ByteArray(count * 8)
    for (i in 0 until count) {
        val words = metatiles[startIndex + i]
        require(words.size == 4) { "Metatile at index ${startIndex + i} has ${words.size} words, expected 4" }
        val offset = i * 8
        for (q in 0..3) {
            val word = words[q] and 0xFFFF
            out[offset + q * 2] = (word and 0xFF).toByte()
            out[offset + q * 2 + 1] = ((word shr 8) and 0xFF).toByte()
        }
    }
    return out
}

/**
 * Apply a raw metatile table to an in-memory metatile array.
 *
 * @param rawTable Raw bytes to parse
 * @param target Target metatile array to modify
 * @param targetStart First metatile index to overwrite
 * @param maxMetatiles Maximum number of metatiles to apply (for safety)
 * @return Number of metatiles actually applied
 * @throws IllegalArgumentException if rawTable is invalid or targetStart is out of range
 */
fun applyMetatileTable(
    rawTable: ByteArray,
    target: Array<IntArray>,
    targetStart: Int,
    maxMetatiles: Int,
): Int {
    require(rawTable.isNotEmpty()) { "Metatile table cannot be empty" }
    require(rawTable.size % 8 == 0) { "Metatile table size must be a multiple of 8, got ${rawTable.size}" }
    require(targetStart >= 0) { "Invalid targetStart: $targetStart" }
    require(maxMetatiles > 0) { "Invalid maxMetatiles: $maxMetatiles" }

    val count = minOf(rawTable.size / 8, maxMetatiles, target.size - targetStart)
    require(count > 0) { "No space to apply metatiles (targetStart=$targetStart, target.size=${target.size})" }

    for (i in 0 until count) {
        val offset = i * 8
        val words = IntArray(4)
        for (q in 0..3) {
            val lo = rawTable[offset + q * 2].toInt() and 0xFF
            val hi = rawTable[offset + q * 2 + 1].toInt() and 0xFF
            words[q] = (hi shl 8) or lo
        }
        target[targetStart + i] = words
    }
    return count
}

/**
 * Validate a raw metatile table for export.
 *
 * @return Error message, or null if valid
 */
fun validateMetatileTableForExport(rawTable: ByteArray): String? {
    if (rawTable.isEmpty()) return "Metatile table is empty"
    if (rawTable.size % 8 != 0) return "Metatile table size (${rawTable.size} bytes) is not a multiple of 8"
    return null
}
