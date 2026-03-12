package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RomUtilsTest {

    @Test
    fun `readU8 reads single unsigned byte`() {
        val data = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals(0x00, readU8(data, 0))
        assertEquals(0x7F, readU8(data, 1))
        assertEquals(0x80, readU8(data, 2))
        assertEquals(0xFF, readU8(data, 3))
    }

    @Test
    fun `readU16 reads little-endian 16-bit value`() {
        val data = byteArrayOf(0x34, 0x12)
        assertEquals(0x1234, readU16(data, 0))
    }

    @Test
    fun `readU16 handles high bytes correctly`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(0xFFFF, readU16(data, 0))
    }

    @Test
    fun `readU16 at offset`() {
        val data = byteArrayOf(0x00, 0x00, 0xE6.toByte(), 0xE5.toByte())
        assertEquals(0xE5E6, readU16(data, 2))
    }

    @Test
    fun `readU24 reads little-endian 24-bit value`() {
        val data = byteArrayOf(0x78, 0x56, 0x34)
        assertEquals(0x345678, readU24(data, 0))
    }

    @Test
    fun `readU24 handles SNES addresses`() {
        // A typical SNES level data pointer: $C2:8000 stored as 00 80 C2
        val data = byteArrayOf(0x00, 0x80.toByte(), 0xC2.toByte())
        assertEquals(0xC28000, readU24(data, 0))
    }

    @Test
    fun `readU24 at offset`() {
        val data = byteArrayOf(0xFF.toByte(), 0x0F, 0x17, 0xB7.toByte())
        assertEquals(0xB7170F, readU24(data, 1))
    }
}
