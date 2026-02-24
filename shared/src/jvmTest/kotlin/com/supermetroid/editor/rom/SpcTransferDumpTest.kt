package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

class SpcTransferDumpTest {

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
    fun `dump base engine transfer block structure`() {
        val parser = loadTestRom() ?: return
        val pc = parser.snesToPc(0xCF8000)
        val blocks = SpcData.parseTransferBlocks(parser.romData, pc)

        println("=== Base SPC Engine (\$CF:8000) ===")
        println("Number of blocks: ${blocks.size}")
        for ((i, b) in blocks.withIndex()) {
            val hex = b.data.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            println("  Block $i: dest=0x${b.destAddr.toString(16).padStart(4, '0')} size=${b.data.size} first32=[$hex]")
        }
        if (blocks.isNotEmpty()) {
            val last = blocks.last()
            println("Last block dest=0x${last.destAddr.toString(16).padStart(4, '0')} (potential entry)")
        }

        // Build RAM and check key addresses
        val ram = SpcData.buildInitialSpcRam(parser)
        println("\n=== Key RAM addresses ===")
        val word = { addr: Int -> (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8) }
        println("$0000-0001: 0x${word(0).toString(16).padStart(4, '0')}")
        println("$0002-0003: 0x${word(2).toString(16).padStart(4, '0')}")
        println("$0004-0005: 0x${word(4).toString(16).padStart(4, '0')}")
        println("$0008-0009: 0x${word(8).toString(16).padStart(4, '0')}")

        // Look at the code at common entry points
        for (addr in listOf(0x0400, 0x0500, 0x0600, 0x0700, 0x0800)) {
            val bytes = ram.copyOfRange(addr, addr + 16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            println("$${addr.toString(16).padStart(4, '0')}: $bytes")
        }

        // Check SONG_TABLE_ADDR area ($1500)
        println("\n=== Song table at \$1500 ===")
        for (pi in 0..7) {
            val tableAddr = 0x1500 + pi * 2
            val ptr = word(tableAddr)
            println("  PlayIndex $pi -> conductor at 0x${ptr.toString(16).padStart(4, '0')}")
        }
    }

    @Test
    fun `dump song set 03 structure`() {
        val parser = loadTestRom() ?: return
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)
        println("=== Song Set 0x03 (Title Screen) ===")
        println("Number of blocks: ${blocks.size}")
        for ((i, b) in blocks.withIndex()) {
            val hex = b.data.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            println("  Block $i: dest=0x${b.destAddr.toString(16).padStart(4, '0')} size=${b.data.size} first32=[$hex]")
        }

        // Build RAM with song set loaded and check potential song tables
        val ram = SpcData.buildInitialSpcRam(parser)
        SpcData.applyTransferBlocks(ram, blocks)
        val word = { addr: Int -> (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8) }

        // Try different potential song table addresses
        for (tableBase in listOf(0x5800, 0x5820, 0x5828, 0x5830, 0x5840)) {
            println("\n=== Trying song table at \$${tableBase.toString(16).padStart(4, '0')} ===")
            for (pi in 0..15) {
                val addr = tableBase + pi * 2
                val ptr = word(addr)
                if (ptr in 0x1500..0xFFF0) {
                    println("  PlayIndex $pi -> 0x${ptr.toString(16).padStart(4, '0')}")
                }
            }
        }

        // Also check what's at $5820 in the base RAM (before song set)
        val baseRam = SpcData.buildInitialSpcRam(parser)
        println("\n=== Base RAM at \$5820 (before song set load) ===")
        val hex5820 = baseRam.copyOfRange(0x5820, 0x5860).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        println("  $hex5820")
        println("\n=== RAM at \$5820 (after song set 0x03 load) ===")
        val hex5820after = ram.copyOfRange(0x5820, 0x5860).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        println("  $hex5820after")

        // The SPC engine has the song table pointer stored somewhere in the engine code.
        // In SM's N-SPC, the conductor/track list starts at a fixed offset.
        // Let's also look at block 4 which overwrites $5828
        println("\n=== Song Set 0x0F (Green Brinstar) for comparison ===")
        val blocks0f = SpcData.findSongSetTransferData(parser, 0x0F)
        for ((i, b) in blocks0f.withIndex()) {
            println("  Block $i: dest=0x${b.destAddr.toString(16).padStart(4, '0')} size=${b.data.size}")
        }
        val ram0f = SpcData.buildInitialSpcRam(parser)
        SpcData.applyTransferBlocks(ram0f, blocks0f)
        println("  Song table candidates at \$5820:")
        for (pi in 0..15) {
            val addr = 0x5820 + pi * 2
            val ptr = word(addr) // uses ram (title screen)
            val ptr0f = (ram0f[addr].toInt() and 0xFF) or ((ram0f[addr + 1].toInt() and 0xFF) shl 8)
            println("    [$pi] title=0x${ptr.toString(16).padStart(4, '0')}  brinstar=0x${ptr0f.toString(16).padStart(4, '0')}")
        }
    }

    /** Write transfer blocks to a file in sm_spc_render's expected format. */
    private fun writeTransferBlocks(blocks: List<SpcData.TransferBlock>, file: File) {
        val bos = file.outputStream().buffered()
        for (block in blocks) {
            val size = block.data.size
            bos.write(size and 0xFF)
            bos.write((size shr 8) and 0xFF)
            bos.write(block.destAddr and 0xFF)
            bos.write((block.destAddr shr 8) and 0xFF)
            bos.write(block.data)
        }
        // Terminator: size = 0
        bos.write(0); bos.write(0)
        bos.flush()
        bos.close()
    }

    @Test
    fun `render via native sm_spc_render tool`() {
        val parser = loadTestRom() ?: return
        val toolPath = "/Users/kenny/code/super_metroid_dev/tools/spc2wav/sm_spc_render"
        if (!File(toolPath).exists()) {
            println("sm_spc_render not found, skipping")
            return
        }

        // Base RAM (engine only, no song set)
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val baseRamFile = File.createTempFile("sm_base_", ".bin")
        baseRamFile.writeBytes(baseRam)

        // Song set transfer blocks
        val blocks = SpcData.findSongSetTransferData(parser, 0x03) // Title screen
        val blocksFile = File.createTempFile("sm_blocks_", ".bin")
        writeTransferBlocks(blocks, blocksFile)

        val wavFile = File.createTempFile("sm_render_", ".wav")

        println("Base RAM: ${baseRamFile.absolutePath}")
        println("Blocks: ${blocksFile.absolutePath} (${blocks.size} blocks)")
        println("WAV: ${wavFile.absolutePath}")

        val proc = ProcessBuilder(
            toolPath, baseRamFile.absolutePath, wavFile.absolutePath,
            "15", "5", blocksFile.absolutePath
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        println("Exit: $exitCode")
        println(output)

        if (wavFile.exists() && wavFile.length() > 44) {
            println("WAV size: ${wavFile.length()} bytes")
            val outDir = File("/Users/kenny/code/super_metroid_dev/shared/test-resources/spc-output")
            outDir.mkdirs()
            val outFile = File(outDir, "native_title_screen.wav")
            wavFile.copyTo(outFile, overwrite = true)
            println("Saved to: ${outFile.absolutePath}")
        }

        baseRamFile.delete()
        blocksFile.delete()
        wavFile.delete()
    }

    @Test
    fun `render multiple songs via native tool`() {
        val parser = loadTestRom() ?: return
        val toolPath = "/Users/kenny/code/super_metroid_dev/tools/spc2wav/sm_spc_render"
        if (!File(toolPath).exists()) {
            println("SKIP: sm_spc_render not found at $toolPath")
            return
        }

        val outDir = File("/Users/kenny/code/super_metroid_dev/shared/test-resources/spc-output")
        outDir.mkdirs()

        // Base RAM (engine only)
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val baseRamFile = File.createTempFile("sm_base_", ".bin")
        baseRamFile.writeBytes(baseRam)

        data class TestSong(val songSet: Int, val playIndex: Int, val name: String)
        val songs = listOf(
            TestSong(0x03, 5, "title_intro"),
            TestSong(0x03, 6, "title_after_button"),
            TestSong(0x0F, 5, "green_brinstar"),
            TestSong(0x24, 5, "boss_fight"),
            TestSong(0x3F, 5, "last_metroid"),
        )

        for (song in songs) {
            val blocks = SpcData.findSongSetTransferData(parser, song.songSet)
            val blocksFile = File.createTempFile("sm_blocks_", ".bin")
            writeTransferBlocks(blocks, blocksFile)

            val wavFile = File(outDir, "native_${song.name}.wav")

            println("Rendering ${song.name} (ss=0x${song.songSet.toString(16)}, pi=${song.playIndex})...")
            val proc = ProcessBuilder(
                toolPath, baseRamFile.absolutePath, wavFile.absolutePath,
                "15", "${song.playIndex}", blocksFile.absolutePath
            ).redirectErrorStream(true).start()
            val exitCode = proc.waitFor()
            val output = proc.inputStream.bufferedReader().readText()
            blocksFile.delete()

            val fileSize = if (wavFile.exists()) wavFile.length() else 0
            println("  exit=$exitCode size=$fileSize")
            println("  $output")
        }

        baseRamFile.delete()
        println("DONE")
    }

    @Test
    fun `build synthetic SPC file and render with spc2wav`() {
        val parser = loadTestRom() ?: return

        val spc2wavPath = "/Users/kenny/code/super_metroid_dev/tools/spc2wav/spc2wav"
        if (!File(spc2wavPath).exists()) {
            println("spc2wav not found, skipping")
            return
        }

        val ram = SpcData.buildInitialSpcRam(parser)
        val songSetBlocks = SpcData.findSongSetTransferData(parser, 0x03) // Title screen
        SpcData.applyTransferBlocks(ram, songSetBlocks)

        // Build a synthetic SPC file
        // The SPC file format: 256-byte header + 65536 bytes RAM + 128 DSP + 64 unused + 64 IPL ROM
        val spc = ByteArray(0x10200)

        // Signature: "SNES-SPC700 Sound File Data v0.30" + 0x1A 0x1A
        val sig = "SNES-SPC700 Sound File Data v0.30"
        for (i in sig.indices) spc[i] = sig[i].code.toByte()
        spc[33] = 0x1A.toByte()
        spc[34] = 0x1A.toByte()

        // CPU registers: set PC to the engine's main loop
        // The base engine code block 0 starts at $0200 typically
        // We need to figure out the right PC...
        val baseBlocks = SpcData.parseTransferBlocks(parser.romData, parser.snesToPc(0xCF8000))
        val engineStart = if (baseBlocks.isNotEmpty()) baseBlocks[0].destAddr else 0x0200
        println("Engine start addr: 0x${engineStart.toString(16).padStart(4, '0')}")

        spc[0x25] = (engineStart and 0xFF).toByte()   // PC low
        spc[0x26] = ((engineStart shr 8) and 0xFF).toByte() // PC high
        spc[0x27] = 0x00 // A
        spc[0x28] = 0x00 // X
        spc[0x29] = 0x00 // Y
        spc[0x2A] = 0x02 // PSW
        spc[0x2B] = 0xEF.toByte() // SP

        // Copy RAM at offset 0x100
        System.arraycopy(ram, 0, spc, 0x100, 0x10000)

        // DSP registers at 0x10100 (128 bytes)
        // Key: DIR register at DSP $5D = 0x6C (sample directory page)
        spc[0x10100 + 0x5D] = 0x6C.toByte()
        // FLG register at DSP $6C: enable echo, no noise, no mute, no reset
        spc[0x10100 + 0x6C] = 0x00.toByte()
        // MVOL L/R at $0C/$1C
        spc[0x10100 + 0x0C] = 0x7F.toByte()
        spc[0x10100 + 0x1C] = 0x7F.toByte()

        // IPL ROM at 0x101C0 (64 bytes) - standard IPL
        val iplRom = byteArrayOf(
            0xCD.toByte(), 0xEF.toByte(), 0xBD.toByte(), 0xE8.toByte(), 0x00.toByte(), 0xC6.toByte(), 0x1D.toByte(), 0xD0.toByte(),
            0xFC.toByte(), 0x8F.toByte(), 0xAA.toByte(), 0xF4.toByte(), 0x8F.toByte(), 0xBB.toByte(), 0xF5.toByte(), 0x78.toByte(),
            0xCC.toByte(), 0xF4.toByte(), 0xD0.toByte(), 0xFB.toByte(), 0x2F.toByte(), 0x19.toByte(), 0xEB.toByte(), 0xF4.toByte(),
            0xD0.toByte(), 0xFC.toByte(), 0x7E.toByte(), 0xF4.toByte(), 0xD0.toByte(), 0x0B.toByte(), 0xE4.toByte(), 0xF5.toByte(),
            0xCB.toByte(), 0xF4.toByte(), 0xD7.toByte(), 0x00.toByte(), 0xFC.toByte(), 0xD0.toByte(), 0xF3.toByte(), 0xAB.toByte(),
            0x01.toByte(), 0x10.toByte(), 0xEF.toByte(), 0x7E.toByte(), 0xF4.toByte(), 0x10.toByte(), 0xEB.toByte(), 0xBA.toByte(),
            0xF6.toByte(), 0xDA.toByte(), 0x00.toByte(), 0xBA.toByte(), 0xF4.toByte(), 0xC4.toByte(), 0xF4.toByte(), 0xDD.toByte(),
            0x5D.toByte(), 0xD0.toByte(), 0xDB.toByte(), 0x1F.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC0.toByte(), 0xFF.toByte()
        )
        System.arraycopy(iplRom, 0, spc, 0x101C0, iplRom.size)

        val spcFile = File.createTempFile("sm_test_", ".spc")
        val wavFile = File.createTempFile("sm_test_", ".wav")
        spcFile.writeBytes(spc)

        println("SPC file: ${spcFile.absolutePath} (${spc.size} bytes)")
        println("WAV file: ${wavFile.absolutePath}")

        val proc = ProcessBuilder(spc2wavPath, spcFile.absolutePath, wavFile.absolutePath, "10")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        println("spc2wav exit: $exitCode, output: $output")

        if (wavFile.exists() && wavFile.length() > 44) {
            println("WAV size: ${wavFile.length()} bytes")
            // Read and check if there's actual audio
            val wavData = wavFile.readBytes()
            val dataStart = 44 // Standard WAV header
            var peak = 0
            for (i in dataStart until minOf(wavData.size, dataStart + 64000) step 2) {
                if (i + 1 < wavData.size) {
                    val sample = (wavData[i].toInt() and 0xFF) or ((wavData[i + 1].toInt() and 0xFF) shl 8)
                    val signed = if (sample > 32767) sample - 65536 else sample
                    if (kotlin.math.abs(signed) > peak) peak = kotlin.math.abs(signed)
                }
            }
            println("Peak amplitude in first second: $peak")
        }

        spcFile.delete()
        wavFile.delete()
    }
}
