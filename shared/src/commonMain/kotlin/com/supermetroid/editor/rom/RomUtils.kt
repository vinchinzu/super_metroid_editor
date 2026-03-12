package com.supermetroid.editor.rom

/**
 * Shared little-endian byte-reading utilities for ROM data.
 *
 * Super Metroid (and the SNES in general) stores multi-byte values
 * in little-endian order. These helpers avoid duplicating the same
 * 2–3 line read pattern across every ROM parsing class and test.
 */

/** Read an unsigned 16-bit little-endian value from [data] at [offset]. */
fun readU16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8)

/** Read an unsigned 24-bit little-endian value from [data] at [offset]. */
fun readU24(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16)

/** Read a single unsigned byte from [data] at [offset]. */
fun readU8(data: ByteArray, offset: Int): Int =
    data[offset].toInt() and 0xFF
