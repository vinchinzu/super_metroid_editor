#!/usr/bin/env kotlin
// Quick script to examine the SPC engine transfer blocks from the SM ROM

import java.io.File

val romPath = "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc"
val rom = File(romPath).readBytes()

fun snesToPc(snes: Int): Int {
    val bank = (snes shr 16) and 0xFF
    val addr = snes and 0xFFFF
    return ((bank and 0x7F) * 0x8000) + (addr - 0x8000)
}

fun readWord(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

data class Block(val dest: Int, val data: ByteArray)

fun parseBlocks(startPc: Int): List<Block> {
    val blocks = mutableListOf<Block>()
    var pos = startPc
    while (pos + 4 <= rom.size) {
        val size = readWord(rom, pos)
        if (size == 0) break
        val dest = readWord(rom, pos + 2)
        pos += 4
        if (pos + size > rom.size) break
        blocks.add(Block(dest, rom.copyOfRange(pos, pos + size)))
        pos += size
    }
    return blocks
}

// Base SPC engine at $CF:8000
val basePc = snesToPc(0xCF8000)
val baseBlocks = parseBlocks(basePc)
println("=== Base SPC Engine (${'$'}CF:8000) ===")
println("Number of blocks: ${baseBlocks.size}")
for ((i, b) in baseBlocks.withIndex()) {
    println("  Block $i: dest=0x${b.dest.toString(16).padStart(4, '0')} size=${b.data.size}")
    if (i == 0) {
        println("    First 32 bytes: ${b.data.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
    }
}

// Show entry point (what the last block's dest address is - that's where execution starts)
if (baseBlocks.isNotEmpty()) {
    val lastBlock = baseBlocks.last()
    println("\nLast block dest: 0x${lastBlock.dest.toString(16).padStart(4, '0')} (execution entry point)")
}

// Check a song set (e.g., 0x03 = Title screen)
val tableAddr = snesToPc(0x8FE7E1)
fun readPointer(offset: Int): Int {
    val b0 = rom[offset].toInt() and 0xFF
    val b1 = rom[offset + 1].toInt() and 0xFF
    val b2 = rom[offset + 2].toInt() and 0xFF
    return (b2 shl 16) or (b1 shl 8) or b0
}

println("\n=== Song Set 0x03 (Title Screen) ===")
val ptr03 = readPointer(tableAddr + 0x03)
println("Pointer: ${'$'}${ptr03.toString(16).uppercase().padStart(6, '0')}")
val blocks03 = parseBlocks(snesToPc(ptr03))
println("Number of blocks: ${blocks03.size}")
for ((i, b) in blocks03.withIndex()) {
    println("  Block $i: dest=0x${b.dest.toString(16).padStart(4, '0')} size=${b.data.size}")
}

println("\n=== Song Set 0x0F (Green Brinstar) ===")
val ptr0f = readPointer(tableAddr + 0x0F)
println("Pointer: ${'$'}${ptr0f.toString(16).uppercase().padStart(6, '0')}")
val blocks0f = parseBlocks(snesToPc(ptr0f))
println("Number of blocks: ${blocks0f.size}")
for ((i, b) in blocks0f.withIndex()) {
    println("  Block $i: dest=0x${b.dest.toString(16).padStart(4, '0')} size=${b.data.size}")
}
