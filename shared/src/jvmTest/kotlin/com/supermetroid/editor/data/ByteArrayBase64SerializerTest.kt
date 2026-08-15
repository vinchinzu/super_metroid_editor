package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ByteArrayBase64SerializerTest {

    // Simple wrapper to test serializer through JSON
    private fun serializeThroughSerializer(bytes: ByteArray): String {
        val encoder = TestStringEncoder()
        ByteArrayBase64Serializer.serialize(encoder, bytes)
        return encoder.encoded
    }
    
    private fun deserializeThroughSerializer(encoded: String): ByteArray {
        val decoder = TestStringDecoder(encoded)
        return ByteArrayBase64Serializer.deserialize(decoder)
    }

    @Test
    fun `roundtrip encode decode for 0 bytes`() {
        val original = ByteArray(0)
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 1 byte`() {
        val original = byteArrayOf(0x42)
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 2 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte())
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 3 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56)
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 4 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56, 0x78)
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `roundtrip encode decode for 5 bytes`() {
        val original = byteArrayOf(0x12, 0x34.toByte(), 0x56, 0x78, 0x9A.toByte())
        val encoded = serializeThroughSerializer(original)
        val decoded = deserializeThroughSerializer(encoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `1 byte array has standard padding with two equals`() {
        val original = byteArrayOf(0x42)
        val encoded = serializeThroughSerializer(original)
        assertTrue(encoded.endsWith("=="), "1-byte array should end with ==, got: $encoded")
    }

    @Test
    fun `2 byte array has standard padding with one equal`() {
        val original = byteArrayOf(0x12, 0x34.toByte())
        val encoded = serializeThroughSerializer(original)
        assertTrue(encoded.endsWith("="), "2-byte array should end with =, got: $encoded")
    }

    @Test
    fun `decode of invalid alphabet throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            deserializeThroughSerializer("!!!!")
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
    
    // Test encoder/decoder that just capture/provide strings
    private class TestStringEncoder : Encoder {
        var encoded: String = ""
        override val serializersModule get() = kotlinx.serialization.modules.EmptySerializersModule()
        override fun encodeString(value: String) { encoded = value }
        override fun encodeBoolean(value: Boolean) = throw UnsupportedOperationException()
        override fun encodeByte(value: Byte) = throw UnsupportedOperationException()
        override fun encodeChar(value: Char) = throw UnsupportedOperationException()
        override fun encodeDouble(value: Double) = throw UnsupportedOperationException()
        override fun encodeEnum(enumDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, index: Int) = throw UnsupportedOperationException()
        override fun encodeFloat(value: Float) = throw UnsupportedOperationException()
        override fun encodeInline(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = this
        override fun encodeInt(value: Int) = throw UnsupportedOperationException()
        override fun encodeLong(value: Long) = throw UnsupportedOperationException()
        override fun encodeNull() = throw UnsupportedOperationException()
        override fun encodeShort(value: Short) = throw UnsupportedOperationException()
        override fun beginStructure(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = throw UnsupportedOperationException()
    }
    
    private class TestStringDecoder(private val value: String) : Decoder {
        override val serializersModule get() = kotlinx.serialization.modules.EmptySerializersModule()
        override fun decodeString(): String = value
        override fun decodeBoolean() = throw UnsupportedOperationException()
        override fun decodeByte() = throw UnsupportedOperationException()
        override fun decodeChar() = throw UnsupportedOperationException()
        override fun decodeDouble() = throw UnsupportedOperationException()
        override fun decodeEnum(enumDescriptor: kotlinx.serialization.descriptors.SerialDescriptor) = throw UnsupportedOperationException()
        override fun decodeFloat() = throw UnsupportedOperationException()
        override fun decodeInline(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = this
        override fun decodeInt() = throw UnsupportedOperationException()
        override fun decodeLong() = throw UnsupportedOperationException()
        override fun decodeNotNullMark() = throw UnsupportedOperationException()
        override fun decodeNull() = throw UnsupportedOperationException()
        override fun decodeShort() = throw UnsupportedOperationException()
        override fun beginStructure(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = throw UnsupportedOperationException()
    }
}
