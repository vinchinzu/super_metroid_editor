package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class RomParserTest {
    
    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "../../../test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }
    
    @Test
    fun `test ROM loading`() {
        val parser = loadTestRom() ?: return
        val romData = parser.getRomData()
        assertTrue(romData.size == 0x300000 || romData.size == 0x300200, 
            "ROM size should be 3MB or 3MB+512 bytes, got ${romData.size}")
    }
    
    @Test
    fun `test LoROM SNES to PC address conversion`() {
        val parser = loadTestRom() ?: return
        
        // LoROM: $8F:91F8 -> PC offset ((0x0F) * 0x8000) + (0x91F8 - 0x8000) = 0x78000 + 0x11F8 = 0x791F8
        val pcAddress = parser.snesToPc(0x8F91F8)
        val expectedPc = if (parser.getRomData().size == 0x300200) 0x200 + 0x791F8 else 0x791F8
        assertEquals(expectedPc, pcAddress, "LoROM conversion for \$8F:91F8 should be 0x${expectedPc.toString(16)}")
        
        // $8F:0000 -> PC offset (0x0F * 0x8000) + (0x0000 & 0x7FFF) = 0x78000
        val pcBank8F = parser.snesToPc(0x8F0000)
        val expectedBank = if (parser.getRomData().size == 0x300200) 0x200 + 0x78000 else 0x78000
        assertEquals(expectedBank, pcBank8F, "LoROM conversion for \$8F:0000")
    }
    
    @Test
    fun `test roomIdToPc conversion`() {
        val parser = loadTestRom() ?: return
        
        // roomIdToPc(0x91F8) should equal snesToPc(0x8F91F8)
        assertEquals(parser.snesToPc(0x8F91F8), parser.roomIdToPc(0x91F8))
        assertEquals(parser.snesToPc(0x8FA59F), parser.roomIdToPc(0xA59F))
    }
    
    @Test
    fun `test read Landing Site room header`() {
        val parser = loadTestRom() ?: return
        
        val room = parser.readRoomHeader(0x91F8)
        assertNotNull(room, "Landing Site room header should be found")
        assertEquals(0, room!!.area, "Landing Site should be in area 0 (Crateria)")
        assertEquals(9, room.width, "Landing Site should be 9 screens wide")
        assertEquals(5, room.height, "Landing Site should be 5 screens tall")
    }
    
    @Test
    fun `test read all known rooms`() {
        val parser = loadTestRom() ?: return
        
        // Room ID -> (name, area, expectedWidth, expectedHeight)
        val knownRooms = mapOf(
            0x91F8 to Triple("Landing Site", 0, 9 to 5),
            0x93AA to Triple("Crateria Power Bomb Room", 0, 2 to 1),
            0x9804 to Triple("Bomb Torizo", 0, 1 to 1),
            0xA59F to Triple("Kraid", 1, 2 to 2),
            0xB32E to Triple("Ridley", 2, 1 to 2),
            0xCD13 to Triple("Phantoon", 3, 1 to 1),
            0xDA60 to Triple("Draygon", 4, 2 to 2),
            0xDD58 to Triple("Mother Brain", 5, 4 to 1),
        )
        
        for ((roomId, expected) in knownRooms) {
            val (name, expectedArea, dims) = expected
            val (expectedWidth, expectedHeight) = dims
            
            val room = parser.readRoomHeader(roomId)
            assertNotNull(room, "$name (0x${roomId.toString(16)}) should be found in ROM")
            assertEquals(expectedArea, room!!.area, "$name should be in area $expectedArea")
            assertEquals(expectedWidth, room.width, "$name should be ${expectedWidth} screens wide")
            assertEquals(expectedHeight, room.height, "$name should be ${expectedHeight} screens tall")
        }
    }
    
    @Test
    fun `test RLE decompression`() {
        val parser = RomParser(ByteArray(0x300000))
        
        val compressed = byteArrayOf(
            0x82.toByte(), 0xAA.toByte(), // RLE: repeat 0xAA 3 times
            0x01, 0xBB.toByte(), 0xCC.toByte(), // Literal: 2 bytes (0xBB, 0xCC)
            0xFF.toByte() // End marker
        )
        
        val decompressed = parser.decompressLevelData(compressed)
        
        assertEquals(5, decompressed.size, "Decompressed size should be 5 bytes")
        assertEquals(0xAA.toByte(), decompressed[0])
        assertEquals(0xAA.toByte(), decompressed[1])
        assertEquals(0xAA.toByte(), decompressed[2])
        assertEquals(0xBB.toByte(), decompressed[3])
        assertEquals(0xCC.toByte(), decompressed[4])
    }
}
