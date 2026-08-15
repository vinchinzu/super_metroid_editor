package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ByteArrayBase64SerializerTest {

    @Test
    fun `roundtrip encode decode for 0 bytes`() {
        val original = ByteArray(0)
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 1 byte`() {
        val original = byteArrayOf(0x42)
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 2 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte())
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 3 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56)
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 4 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56, 0x78)
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 5 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56, 0x78, 0x9A.toByte())
        val encoded = kotlin.io.encoding.Base64.encode(original)
        val decoded = kotlin.io.encoding.Base64.decode(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `1 byte array has standard padding with two equals`() {
        val original = byteArrayOf(0x42)
        val encoded = kotlin.io.encoding.Base64.encode(original)
        assertTrue(encoded.endsWith("=="), "1-byte array should end with ==, got: $encoded")
    }

    @Test
    fun `2 byte array has standard padding with one equal`() {
        val original = byteArrayOf(0x12, 0x34.toByte())
        val encoded = kotlin.io.encoding.Base64.encode(original)
        assertTrue(encoded.endsWith("="), "2-byte array should end with =, got: $encoded")
    }

    @Test
    fun `decode of invalid alphabet throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            kotlin.io.encoding.Base64.decode("!!!!")
        }
    }

    @Test
    fun `serialize NewRoomAllocation through JSON roundtrip preserves compressedLevelData`() {
        val original = NewRoomAllocation(
            headerPcOffset = 0x1000,
            doorTablePtr = 0x2000,
            levelDataPtr = 0x3000,
            levelDataPcOffset = 0x4000,
            compressedLevelData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05),
            plmSetPtr = 0x5000,
            enemyPopPtr = 0x6000,
            enemyGfxPtr = 0x7000,
            scrollPtr = 0x8000,
            roomIndex = 42,
        )

        val json = Json.encodeToString(NewRoomAllocation.serializer(), original)
        val deserialized = Json.decodeFromString(NewRoomAllocation.serializer(), json)

        assertContentEquals(original.compressedLevelData, deserialized.compressedLevelData)
        assertEquals(original.headerPcOffset, deserialized.headerPcOffset)
        assertEquals(original.doorTablePtr, deserialized.doorTablePtr)
        assertEquals(original.levelDataPtr, deserialized.levelDataPtr)
        assertEquals(original.plmSetPtr, deserialized.plmSetPtr)
        assertEquals(original.roomIndex, deserialized.roomIndex)
    }
}
