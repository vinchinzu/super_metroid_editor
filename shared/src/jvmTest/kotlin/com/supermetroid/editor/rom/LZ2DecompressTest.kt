package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class LZ2DecompressTest {
    
    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }
    
    @Test
    fun `trace LZ2 decompression of Landing Site`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        
        val output = File("/tmp/lz2_trace.txt")
        val sb = StringBuilder()
        
        sb.appendLine("Landing Site level data pointer: 0x${room.levelDataPtr.toString(16)}")
        
        // Convert to PC offset
        val pcOffset = parser.snesToPc(room.levelDataPtr)
        sb.appendLine("PC offset: 0x${pcOffset.toString(16)}")
        
        // Show first 100 bytes of compressed data
        val romData = parser.getRomData()
        val rawBytes = romData.sliceArray(pcOffset until minOf(pcOffset + 100, romData.size))
        sb.appendLine("First 100 bytes of compressed data:")
        sb.appendLine(rawBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
        sb.appendLine()
        
        // Trace decompression
        sb.appendLine("=== Tracing decompression commands ===")
        var pos = pcOffset
        var outputSize = 0
        var cmdCount = 0
        
        while (pos < romData.size && cmdCount < 200) {
            val header = romData[pos].toInt() and 0xFF
            val cmdPos = pos
            pos++
            
            var cmdType = (header shr 5) and 0x07
            var length: Int
            
            if (cmdType == 7) {
                // Extended header
                cmdType = (header shr 2) and 0x07
                if (cmdType == 7) {
                    sb.appendLine("CMD $cmdCount at 0x${cmdPos.toString(16)}: END (ext type=7, header=0x${header.toString(16)})")
                    sb.appendLine("  Output so far: $outputSize bytes")
                    break
                }
                val byte2 = romData[pos].toInt() and 0xFF
                pos++
                length = ((header and 0x03) shl 8) or byte2
                length += 1
                sb.appendLine("CMD $cmdCount at 0x${cmdPos.toString(16)}: EXT type=$cmdType len=$length (header=0x${header.toString(16)}, byte2=0x${byte2.toString(16)})")
            } else {
                length = (header and 0x1F) + 1
                sb.appendLine("CMD $cmdCount at 0x${cmdPos.toString(16)}: type=$cmdType len=$length (header=0x${header.toString(16)})")
            }
            
            // Skip data bytes for each command type
            when (cmdType) {
                0 -> { pos += length; outputSize += length } // Direct copy
                1 -> { pos += 1; outputSize += length }      // Byte fill
                2 -> { pos += 2; outputSize += length }      // Word fill
                3 -> { pos += 1; outputSize += length }      // Increment fill
                4 -> { pos += 2; outputSize += length }      // Dictionary copy
                5 -> { pos += 2; outputSize += length }      // XOR dictionary
                6 -> { pos += 2; outputSize += length }      // Backwards dict
                else -> {
                    sb.appendLine("  UNKNOWN command type $cmdType!")
                    break
                }
            }
            
            cmdCount++
        }
        
        sb.appendLine()
        sb.appendLine("Total commands: $cmdCount")
        sb.appendLine("Total output size: $outputSize bytes")
        sb.appendLine("Expected Layer1: ${room.width * room.height * 16 * 16 * 2} bytes")
        
        // Also try actual decompression
        val decompressed = parser.decompressLZ2(room.levelDataPtr)
        sb.appendLine()
        sb.appendLine("Actual decompressed size: ${decompressed.size} bytes")
        if (decompressed.size >= 2) {
            val layer1Size = (decompressed[0].toInt() and 0xFF) or ((decompressed[1].toInt() and 0xFF) shl 8)
            sb.appendLine("Layer 1 size header: $layer1Size bytes (${layer1Size / 2} tiles)")
        }
        
        // Check conditional state pointers too
        sb.appendLine()
        sb.appendLine("=== Checking conditional state data pointers ===")
        val stateStart = pcOffset + 11
        // Parse state entries to find alternate state pointers
        var off = stateStart
        while (off + 2 < romData.size && off < stateStart + 100) {
            val w = (romData[off].toInt() and 0xFF) or ((romData[off + 1].toInt() and 0xFF) shl 8)
            if (w == 0xE5E6) {
                sb.appendLine("  ${'$'}E5E6 at offset ${off - stateStart}: DEFAULT")
                break
            }
            // Get state pointer and check its level data
            val entrySize = when (w) {
                0xE612, 0xE678 -> 5
                0xE5EB -> 5
                else -> 4
            }
            // State pointer is the last 2 bytes of the entry
            val statePtr = (romData[off + entrySize - 2].toInt() and 0xFF) or 
                ((romData[off + entrySize - 1].toInt() and 0xFF) shl 8)
            val stateSnesAddr = 0x8F0000 or statePtr
            val statePcOffset = parser.snesToPc(stateSnesAddr)
            
            sb.appendLine("  State condition 0x${w.toString(16)} at offset ${off - stateStart}, ptr=0x${statePtr.toString(16)} (PC=0x${statePcOffset.toString(16)})")
            
            // Read 3-byte level data pointer from this state
            if (statePcOffset + 3 < romData.size) {
                val ldp = (romData[statePcOffset].toInt() and 0xFF) or
                    ((romData[statePcOffset + 1].toInt() and 0xFF) shl 8) or
                    ((romData[statePcOffset + 2].toInt() and 0xFF) shl 16)
                sb.appendLine("    Level data ptr: 0x${ldp.toString(16)}")
                
                val altDecomp = parser.decompressLZ2(ldp)
                sb.appendLine("    Decompressed: ${altDecomp.size} bytes")
                if (altDecomp.size >= 2) {
                    val altL1 = (altDecomp[0].toInt() and 0xFF) or ((altDecomp[1].toInt() and 0xFF) shl 8)
                    sb.appendLine("    Layer 1 size: $altL1")
                }
            }
            
            off += entrySize
        }
        
        output.writeText(sb.toString())
        println(sb.toString())
    }
}
