package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Disassemble key parts of the SM SPC engine to understand the command protocol.
 */
class SpcEngineDisasmTest {

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

    private fun byte(ram: ByteArray, addr: Int): Int = ram[addr].toInt() and 0xFF
    private fun word(ram: ByteArray, addr: Int): Int =
        (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8)

    /**
     * Simple SPC700 disassembler for the instruction lengths we need.
     * Returns (mnemonic, instruction_length)
     */
    private fun disasm(ram: ByteArray, pc: Int): Pair<String, Int> {
        val op = byte(ram, pc)
        return when (op) {
            0x00 -> "NOP" to 1
            0x0F -> "BRK" to 1
            0x10 -> "BPL \$${relTarget(pc, byte(ram, pc + 1))}" to 2
            0x20 -> "CLRP" to 1
            0x2F -> "BRA \$${relTarget(pc, byte(ram, pc + 1))}" to 2
            0x30 -> "BMI \$${relTarget(pc, byte(ram, pc + 1))}" to 2
            0x3F -> "CALL \$${word(ram, pc + 1).hex4()}" to 3
            0x5D -> "MOV X,A" to 1
            0x5F -> "JMP \$${word(ram, pc + 1).hex4()}" to 3
            0x6F -> "RET" to 1
            0x78 -> "CMP \$${byte(ram, pc + 2).hex2()},#\$${byte(ram, pc + 1).hex2()}" to 3
            0x7A -> "ADDW YA,\$${byte(ram, pc + 1).hex2()}" to 2
            0x8F -> "MOV \$${byte(ram, pc + 2).hex2()},#\$${byte(ram, pc + 1).hex2()}" to 3
            0xAB -> "INC \$${byte(ram, pc + 1).hex2()}" to 2
            0xAF -> "MOV (X)+,A" to 1
            0xBA -> "MOVW YA,\$${byte(ram, pc + 1).hex2()}" to 2
            0xBD -> "MOV SP,X" to 1
            0xC4 -> "MOV \$${byte(ram, pc + 1).hex2()},A" to 2
            0xC5 -> "MOV \$${word(ram, pc + 1).hex4()},A" to 3
            0xC6 -> "MOV (X),A" to 1
            0xC7 -> "MOV [\$${byte(ram, pc + 1).hex2()}+X],A" to 2
            0xC8 -> "CMP X,#\$${byte(ram, pc + 1).hex2()}" to 2
            0xC9 -> "MOV \$${word(ram, pc + 1).hex4()},X" to 3
            0xCA -> "MOV1 C,\$${word(ram, pc + 1).hex4()}" to 3
            0xCB -> "MOV \$${byte(ram, pc + 1).hex2()},Y" to 2
            0xCC -> "MOV \$${word(ram, pc + 1).hex4()},Y" to 3
            0xCD -> "MOV X,#\$${byte(ram, pc + 1).hex2()}" to 2
            0xD0 -> "BNE \$${relTarget(pc, byte(ram, pc + 1))}" to 2
            0xD5 -> "MOV \$${word(ram, pc + 1).hex4()}+X,A" to 3
            0xD7 -> "MOV [\$${byte(ram, pc + 1).hex2()}]+Y,A" to 2
            0xDA -> "MOVW \$${byte(ram, pc + 1).hex2()},YA" to 2
            0xDD -> "MOV A,Y" to 1
            0xE4 -> "MOV A,\$${byte(ram, pc + 1).hex2()}" to 2
            0xE5 -> "MOV A,\$${word(ram, pc + 1).hex4()}" to 3
            0xE8 -> "MOV A,#\$${byte(ram, pc + 1).hex2()}" to 2
            0xEB -> "MOV Y,\$${byte(ram, pc + 1).hex2()}" to 2
            0xF0 -> "BEQ \$${relTarget(pc, byte(ram, pc + 1))}" to 2
            0xF5 -> "MOV A,\$${word(ram, pc + 1).hex4()}+X" to 3
            0xFA -> "MOV \$${byte(ram, pc + 2).hex2()},\$${byte(ram, pc + 1).hex2()}" to 3
            0xFC -> "INC Y" to 1
            0x1D -> "DEC X" to 1
            0x1F -> "JMP [\$${word(ram, pc + 1).hex4()}+X]" to 3
            0x25 -> "AND A,\$${word(ram, pc + 1).hex4()}" to 3
            0x28 -> "AND A,#\$${byte(ram, pc + 1).hex2()}" to 2
            0x48 -> "EOR A,#\$${byte(ram, pc + 1).hex2()}" to 2
            0x64 -> "CMP A,\$${byte(ram, pc + 1).hex2()}" to 2
            0x65 -> "CMP A,\$${word(ram, pc + 1).hex4()}" to 3
            0x68 -> "CMP A,#\$${byte(ram, pc + 1).hex2()}" to 2
            0x7D -> "MOV A,X" to 1
            0x8D -> "MOV Y,#\$${byte(ram, pc + 1).hex2()}" to 2
            0x9C -> "DEC A" to 1
            0xBC -> "INC A" to 1
            0xFD -> "MOV Y,A" to 1
            0xDC -> "DEC Y" to 1
            0xEC -> "MOV Y,\$${word(ram, pc + 1).hex4()}" to 3
            0xAD -> "CMP Y,#\$${byte(ram, pc + 1).hex2()}" to 2
            0x04 -> "OR A,\$${byte(ram, pc + 1).hex2()}" to 2
            0x24 -> "AND A,\$${byte(ram, pc + 1).hex2()}" to 2
            0x44 -> "EOR A,\$${byte(ram, pc + 1).hex2()}" to 2
            0x84 -> "ADC A,\$${byte(ram, pc + 1).hex2()}" to 2
            0xA4 -> "SBC A,\$${byte(ram, pc + 1).hex2()}" to 2
            0xF4 -> "MOV A,\$${byte(ram, pc + 1).hex2()}+X" to 2
            0xD4 -> "MOV \$${byte(ram, pc + 1).hex2()}+X,A" to 2
            0xF6 -> "MOV A,\$${word(ram, pc + 1).hex4()}+Y" to 3
            0xD6 -> "MOV \$${word(ram, pc + 1).hex4()}+Y,A" to 3
            0xE6 -> "MOV A,(X)" to 1
            0xBF -> "MOV A,(X)+" to 1
            0x9F -> "XCN A" to 1
            0x4B -> "LSR \$${byte(ram, pc + 1).hex2()}" to 2
            0x0B -> "ASL \$${byte(ram, pc + 1).hex2()}" to 2
            0x1C -> "ASL A" to 1
            0x5C -> "LSR A" to 1
            0x3C -> "ROL A" to 1
            0x7C -> "ROR A" to 1
            0x02, 0x22, 0x42, 0x62, 0x82, 0xA2, 0xC2, 0xE2 ->
                "SET${(op shr 5) and 7} \$${byte(ram, pc + 1).hex2()}" to 2
            0x12, 0x32, 0x52, 0x72, 0x92, 0xB2, 0xD2, 0xF2 ->
                "CLR${(op shr 5) and 7} \$${byte(ram, pc + 1).hex2()}" to 2
            0x03, 0x23, 0x43, 0x63, 0x83, 0xA3, 0xC3, 0xE3 ->
                "BBS${(op shr 5) and 7} \$${byte(ram, pc + 1).hex2()},\$${relTarget(pc + 1, byte(ram, pc + 2))}" to 3
            0x13, 0x33, 0x53, 0x73, 0x93, 0xB3, 0xD3, 0xF3 ->
                "BBC${(op shr 5) and 7} \$${byte(ram, pc + 1).hex2()},\$${relTarget(pc + 1, byte(ram, pc + 2))}" to 3
            else -> "DB \$${op.hex2()}" to 1
        }
    }

    private fun relTarget(pc: Int, offset: Int): String {
        val signed = if (offset >= 128) offset - 256 else offset
        val target = (pc + 2 + signed) and 0xFFFF
        return target.hex4()
    }

    private fun Int.hex2() = toString(16).padStart(2, '0').uppercase()
    private fun Int.hex4() = toString(16).padStart(4, '0').uppercase()

    @Test
    fun `disassemble SM SPC engine init and main loop`() {
        val parser = loadTestRom() ?: return
        val ram = SpcData.buildInitialSpcRam(parser)

        // Disassemble key areas
        for ((label, start, end) in listOf(
            Triple("INIT+LOOP ($1500-$1610)", 0x1500, 0x1610),
            Triple("CMD HANDLER ($1EE0-$1F50)", 0x1EE0, 0x1F50),
            Triple("TRANSFER ($1E85-$1ED7)", 0x1E85, 0x1ED7),
        )) {
            println("=== $label ===")
            var pc = start
            while (pc < end) {
                val (mnemonic, len) = disasm(ram, pc)
                val bytes = (0 until len).joinToString(" ") { byte(ram, pc + it).hex2() }
                println("\$${pc.hex4()}: ${bytes.padEnd(12)} $mnemonic")
                pc += len
            }
            println()
        }
    }

    @Test
    fun `dump song table at 2AED and verify patching`() {
        val parser = loadTestRom() ?: return
        val baseRam = SpcData.buildInitialSpcRam(parser)

        println("=== Song table at \$2AED (base engine, song set 0x00) ===")
        for (playIdx in 1..10) {
            val offset = (playIdx - 1) * 2
            val addr = word(baseRam, 0x2AED + offset)
            println("  playIndex=$playIdx -> conductor at \$${addr.hex4()}")
        }

        println()
        println("=== How song set patches affect the RAM ===")
        for (ss in listOf(0x03, 0x0F, 0x12, 0x27)) {
            val blocks = SpcData.findSongSetTransferData(parser, ss)
            println("\nSong set 0x${ss.hex2()} has ${blocks.size} blocks:")
            for ((i, b) in blocks.withIndex()) {
                val endAddr = b.destAddr + b.data.size - 1
                println("  Block $i: \$${b.destAddr.hex4()}-\$${endAddr.hex4()} (${b.data.size} bytes)")
            }

            // Build full RAM with this song set applied on top of base
            val fullRam = baseRam.copyOf()
            SpcData.applyTransferBlocks(fullRam, blocks)

            // Check if the song table at $2AED changed
            var tableChanged = false
            for (playIdx in 1..10) {
                val offset = (playIdx - 1) * 2
                val baseAddr = word(baseRam, 0x2AED + offset)
                val fullAddr = word(fullRam, 0x2AED + offset)
                if (baseAddr != fullAddr) {
                    println("  TABLE CHANGED: playIndex=$playIdx: base=\$${baseAddr.hex4()} -> patched=\$${fullAddr.hex4()}")
                    tableChanged = true
                }
            }
            if (!tableChanged) {
                println("  Song table at \$2AED UNCHANGED by this song set's patches")
            }

            // Show conductor pointer for playIndex 5
            val condAddr = word(fullRam, 0x2AED + 8) // play_index=5, offset=8
            println("  playIndex=5 conductor at \$${condAddr.hex4()}, first bytes: " +
                (0..7).joinToString(" ") { byte(fullRam, condAddr + it).hex2() })
        }
    }

    @Test
    fun `analyze conductor at 2BD1 and song set dispatch`() {
        val parser = loadTestRom() ?: return

        // Build full RAM with song set 0x03 applied on top
        val ram03 = SpcData.buildInitialSpcRam(parser)
        val blocks03 = SpcData.findSongSetTransferData(parser, 0x03)
        SpcData.applyTransferBlocks(ram03, blocks03)

        val ram0F = SpcData.buildInitialSpcRam(parser)
        val blocks0F = SpcData.findSongSetTransferData(parser, 0x0F)
        SpcData.applyTransferBlocks(ram0F, blocks0F)

        println("=== Conductor at \$2BD1 (play_index=5) ===")
        println("First 32 bytes:")
        for (i in 0 until 32) {
            print("${byte(ram03, 0x2BD1 + i).hex2()} ")
            if ((i + 1) % 16 == 0) println()
        }

        // Look for references to the $5828+ area within the conductor
        println("\n=== Scanning conductor at \$2BD1 for references to \$5828+ ===")
        for (i in 0 until 256 step 2) {
            val addr = word(ram03, 0x2BD1 + i)
            if (addr in 0x5800..0x6FFF) {
                println("  Offset +${i.hex2()}: word = \$${addr.hex4()} (in song set area!)")
            }
        }

        // Dump the jump table at $1F4D (return address from CALL $2875)
        println("\n=== Jump table at \$1F4D (dispatched by start-playback $2875) ===")
        for (playIdx in 1..10) {
            val tableOffset = (playIdx - 1) * 2
            val funcAddr = word(ram03, 0x1F4D + tableOffset)
            println("  play_index=$playIdx (Y=$tableOffset) -> function at \$${funcAddr.hex4()}")
        }

        // Raw bytes at $1F4D
        println("\nRaw bytes at \$1F4D: ${(0..23).joinToString(" ") { byte(ram03, 0x1F4D + it).hex2() }}")

        // Disassemble the function for play_index 5
        val funcAddr5 = word(ram03, 0x1F4D + 8)
        println("\n=== Function for play_index=5 at \$${funcAddr5.hex4()} ===")
        var pc = funcAddr5
        var lines = 0
        while (pc < funcAddr5 + 80 && lines < 30) {
            val (mnemonic, len) = disasm(ram03, pc)
            val bytes = (0 until len).joinToString(" ") { byte(ram03, pc + it).hex2() }
            println("\$${pc.hex4()}: ${bytes.padEnd(12)} $mnemonic")
            pc += len
            lines++
        }

        // Also disassemble for play_index 6 to compare
        val funcAddr6 = word(ram03, 0x1F4D + 10)
        println("\n=== Function for play_index=6 at \$${funcAddr6.hex4()} ===")
        pc = funcAddr6
        lines = 0
        while (pc < funcAddr6 + 80 && lines < 30) {
            val (mnemonic, len) = disasm(ram03, pc)
            val bytes = (0 until len).joinToString(" ") { byte(ram03, pc + it).hex2() }
            println("\$${pc.hex4()}: ${bytes.padEnd(12)} $mnemonic")
            pc += len
            lines++
        }

        // Check if data at $5828+ differs between song sets
        println("\n=== Data at \$5828 comparison (song set 0x03 vs 0x0F) ===")
        println("SS 0x03 at \$5828: ${(0..15).joinToString(" ") { byte(ram03, 0x5828 + it).hex2() }}")
        println("SS 0x0F at \$5828: ${(0..15).joinToString(" ") { byte(ram0F, 0x5828 + it).hex2() }}")
        println("SS 0x03 at \$5838: ${(0..15).joinToString(" ") { byte(ram03, 0x5838 + it).hex2() }}")
        println("SS 0x0F at \$5838: ${(0..15).joinToString(" ") { byte(ram0F, 0x5838 + it).hex2() }}")

        // Dump the "song pointers" at $5828 as words
        println("\n=== Song set 0x03 - word table at \$5828 ===")
        for (i in 0 until 16) {
            val w = word(ram03, 0x5828 + i * 2)
            println("  [\$${(0x5828 + i * 2).hex4()}] = \$${w.hex4()}")
        }
        println("\n=== Song set 0x0F - word table at \$5828 ===")
        for (i in 0 until 16) {
            val w = word(ram0F, 0x5828 + i * 2)
            println("  [\$${(0x5828 + i * 2).hex4()}] = \$${w.hex4()}")
        }
    }

    @Test
    fun `find port F4 references in engine code`() {
        val parser = loadTestRom() ?: return
        val ram = SpcData.buildInitialSpcRam(parser)

        println("=== References to \$F4-\$F7 (APU ports) in engine code ===")
        var pc = 0x1500
        while (pc < 0x5800) {
            val (mnemonic, len) = disasm(ram, pc)
            if (mnemonic.contains("F4") || mnemonic.contains("F5") ||
                mnemonic.contains("F6") || mnemonic.contains("F7")) {
                val bytes = (0 until len).joinToString(" ") { byte(ram, pc + it).hex2() }
                println("\$${pc.hex4()}: ${bytes.padEnd(12)} $mnemonic")
            }
            pc += len
        }
    }
}
