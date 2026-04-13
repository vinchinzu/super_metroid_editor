package com.supermetroid.editor.cli

import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.LevelDataResize
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * CLI command: enlarge a room, optionally carve edits, export patched ROM + IPS.
 *
 * This is a self-contained pipeline that mirrors the EditorState export logic
 * but runs headlessly for reproducible scripted ROM hacks.
 */
fun cmdEnlarge(parser: RomParser, args: List<String>) {
    val usage = """
        Usage: enlarge --room <id> [--width <W>] [--height <H>] [--carve <spec>] -o <output>
          --room    Room ID (hex, e.g. 91F8 or 0x91F8)
          --width   New width in screens (must be >= current)
          --height  New height in screens (must be >= current)
          --carve   Carve a rectangular hole: x,y,w,h (in blocks, 0-indexed)
                    Can be repeated. Uses air tiles (type 0x0).
          --fill    Fill a rectangular area with solid: x,y,w,h (in blocks)
                    Uses block word 0x8000 (solid type 8). Can be repeated.
          -o        Output path (without extension). Writes .sfc and .ips
    """.trimIndent()

    var roomIdStr: String? = null
    var newW: Int? = null
    var newH: Int? = null
    var outputPath: String? = null
    val carveSpecs = mutableListOf<IntArray>()
    val fillSpecs = mutableListOf<IntArray>()

    val iter = args.iterator()
    while (iter.hasNext()) {
        when (val arg = iter.next()) {
            "--room" -> roomIdStr = if (iter.hasNext()) iter.next() else null
            "--width" -> newW = if (iter.hasNext()) iter.next().toIntOrNull() else null
            "--height" -> newH = if (iter.hasNext()) iter.next().toIntOrNull() else null
            "-o", "--output" -> outputPath = if (iter.hasNext()) iter.next() else null
            "--carve" -> {
                val spec = if (iter.hasNext()) parseRect(iter.next()) else null
                if (spec != null) carveSpecs.add(spec) else {
                    System.err.println("Invalid --carve spec (expected x,y,w,h)")
                    return
                }
            }
            "--fill" -> {
                val spec = if (iter.hasNext()) parseRect(iter.next()) else null
                if (spec != null) fillSpecs.add(spec) else {
                    System.err.println("Invalid --fill spec (expected x,y,w,h)")
                    return
                }
            }
            else -> {
                System.err.println("Unknown arg: $arg")
                println(usage)
                return
            }
        }
    }

    if (roomIdStr == null || outputPath == null) {
        println(usage)
        return
    }

    val roomId = roomIdStr.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
    if (roomId == null) {
        System.err.println("Invalid room ID: $roomIdStr")
        return
    }

    val room = parser.readRoomHeader(roomId)
    if (room == null) {
        System.err.println("Room not found: 0x${roomId.toString(16).uppercase()}")
        return
    }

    val oldW = room.width
    val oldH = room.height
    val targetW = newW ?: oldW
    val targetH = newH ?: oldH

    println("Room 0x${roomId.toString(16).uppercase()}: ${oldW}x${oldH} -> ${targetW}x${targetH} screens")

    if (targetW < oldW || targetH < oldH) {
        System.err.println("Error: shrinking not supported")
        return
    }
    if (targetW * targetH > LevelDataResize.MAX_ROOM_SCREENS) {
        System.err.println("Error: area ${targetW * targetH} exceeds max ${LevelDataResize.MAX_ROOM_SCREENS}")
        return
    }

    val romData = parser.getRomData().copyOf()
    val needsResize = targetW != oldW || targetH != oldH

    // --- Resize level data for all states ---
    val allStateOffsets = parser.findAllStateDataOffsets(roomId)
    println("  States: ${allStateOffsets.size}")

    val ptrToStates = mutableMapOf<Int, MutableList<Int>>()
    for (stateOffset in allStateOffsets) {
        val lvlPtr = readU24(romData, stateOffset)
        if (lvlPtr != 0) ptrToStates.getOrPut(lvlPtr) { mutableListOf() }.add(stateOffset)
    }

    // Free space tracker for level data banks ($C0-$CE)
    val levelBankFree = mutableMapOf<Int, Int>()
    fun getLevelBankFreePtr(bank: Int): Int {
        return levelBankFree.getOrPut(bank) {
            val bankEnd = parser.snesToPc((bank shl 16) or 0xFFFF) + 1
            val bankStart = parser.snesToPc((bank shl 16) or 0x8000)
            var ptr = bankEnd
            while (ptr > bankStart) {
                if ((romData[ptr - 1].toInt() and 0xFF) != 0xFF) break
                ptr--
            }
            ptr + 1
        }
    }

    println("  Level data pointers: ${ptrToStates.size}")
    for ((lvlPtr, stateOffs) in ptrToStates) {
        val (origDecomp, origCompSize) = parser.decompressLZ2WithSize(lvlPtr)

        val resized = if (needsResize) {
            LevelDataResize.resize(origDecomp, oldW, oldH, targetW, targetH)
        } else {
            origDecomp.copyOf()
        }

        // Apply carve edits (set to air = 0x0000 word, BTS = 0x00)
        val stride = targetW * 16
        val l1Size = (resized[0].toInt() and 0xFF) or ((resized[1].toInt() and 0xFF) shl 8)
        for (rect in carveSpecs) {
            val (rx, ry, rw, rh) = rect
            for (by in ry until ry + rh) {
                for (bx in rx until rx + rw) {
                    val idx = by * stride + bx
                    val off = 2 + idx * 2
                    resized[off] = 0x00
                    resized[off + 1] = 0x00
                    // BTS
                    resized[2 + l1Size + idx] = 0x00
                }
            }
            println("  Carved hole at ($rx,$ry) size ${rw}x${rh} blocks")
        }

        // Apply fill edits (solid = 0x8000 word, BTS = 0x00)
        for (rect in fillSpecs) {
            val (rx, ry, rw, rh) = rect
            for (by in ry until ry + rh) {
                for (bx in rx until rx + rw) {
                    val idx = by * stride + bx
                    val off = 2 + idx * 2
                    resized[off] = 0x00
                    resized[off + 1] = 0x80.toByte()
                    resized[2 + l1Size + idx] = 0x00
                }
            }
            println("  Filled solid at ($rx,$ry) size ${rw}x${rh} blocks")
        }

        val compressed = LZ5Compressor.compress(resized)
        val pcOff = parser.snesToPc(lvlPtr)

        if (compressed.size <= origCompSize) {
            System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
            for (i in compressed.size until origCompSize) romData[pcOff + i] = 0xFF.toByte()
        } else {
            val origBank = (lvlPtr shr 16) and 0xFF
            val banksToTry = listOf(origBank) + (0xCE downTo 0xC0).filter { it != origBank }
            var relocated = false
            for (tryBank in banksToTry) {
                val bEnd = parser.snesToPc((tryBank shl 16) or 0xFFFF) + 1
                val freeStart = getLevelBankFreePtr(tryBank)
                if (freeStart + compressed.size <= bEnd) {
                    System.arraycopy(compressed, 0, romData, freeStart, compressed.size)
                    val newSnes = parser.pcToSnes(freeStart)
                    levelBankFree[tryBank] = freeStart + compressed.size
                    for (stateOffset in stateOffs) {
                        romData[stateOffset] = (newSnes and 0xFF).toByte()
                        romData[stateOffset + 1] = ((newSnes shr 8) and 0xFF).toByte()
                        romData[stateOffset + 2] = ((newSnes shr 16) and 0xFF).toByte()
                    }
                    for (i in pcOff until pcOff + origCompSize) romData[i] = 0xFF.toByte()
                    println("  Relocated level data to bank \$${tryBank.toString(16)} (${compressed.size} bytes)")
                    relocated = true
                    break
                }
            }
            if (!relocated) {
                System.err.println("Error: no free space for resized level data (${compressed.size} bytes)")
                return
            }
        }
    }

    // --- Resize scroll data ---
    if (needsResize) {
        val scrollPtrToStates = mutableMapOf<Int, MutableList<Int>>()
        for (stateOffset in allStateOffsets) {
            val sp = readU16(romData, stateOffset + 14)
            scrollPtrToStates.getOrPut(sp) { mutableListOf() }.add(stateOffset)
        }

        val bank8FEnd = parser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = parser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            if ((romData[freePtr - 1].toInt() and 0xFF) != 0xFF) break
            freePtr--
        }
        freePtr++

        for ((scrollPtr, stateOffs) in scrollPtrToStates) {
            if (scrollPtr <= 1) continue
            val scrollPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or scrollPtr)
            val oldSize = oldW * oldH
            val newSize = targetW * targetH
            val newScrolls = ByteArray(newSize) { 0x01 }  // blue/explorable
            for (sy in 0 until oldH) {
                for (sx in 0 until oldW) {
                    newScrolls[sy * targetW + sx] = romData[scrollPc + sy * oldW + sx]
                }
            }
            if (freePtr + newSize > bank8FEnd) {
                System.err.println("Error: no free space in \$8F for scroll data")
                return
            }
            System.arraycopy(newScrolls, 0, romData, freePtr, newSize)
            val newSnes = parser.pcToSnes(freePtr)
            val newPtr = newSnes and 0xFFFF
            freePtr += newSize
            for (stateOffset in stateOffs) {
                romData[stateOffset + 14] = (newPtr and 0xFF).toByte()
                romData[stateOffset + 15] = ((newPtr shr 8) and 0xFF).toByte()
            }
            for (i in 0 until oldSize) romData[scrollPc + i] = 0
        }
        println("  Scroll data relocated")
    }

    // --- Update header ---
    if (needsResize) {
        val headerPc = parser.snesToPc(RomConstants.BANK_ROOM_DATA or roomId)
        romData[headerPc + 4] = targetW.toByte()
        romData[headerPc + 5] = targetH.toByte()
        println("  Header updated: width=${targetW}, height=${targetH}")
    }

    // --- Write output files ---
    val outSfc = File("${outputPath}.sfc")
    outSfc.parentFile?.mkdirs()
    outSfc.writeBytes(romData)
    println("Wrote: ${outSfc.absolutePath} (${romData.size} bytes)")

    // IPS patch
    val original = parser.getRomData()
    val ipsData = buildIpsPatch(original, romData)
    val outIps = File("${outputPath}.ips")
    outIps.writeBytes(ipsData)
    println("Wrote: ${outIps.absolutePath} (${ipsData.size} bytes)")
}

private fun parseRect(s: String): IntArray? {
    val parts = s.split(",").mapNotNull { it.trim().toIntOrNull() }
    return if (parts.size == 4) parts.toIntArray() else null
}

private fun readU16(data: ByteArray, off: Int): Int =
    (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

private fun readU24(data: ByteArray, off: Int): Int =
    (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16)

private fun buildIpsPatch(original: ByteArray, patched: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("PATCH".toByteArray(Charsets.US_ASCII))
    var i = 0
    val len = minOf(original.size, patched.size)
    while (i < len) {
        if (original[i] != patched[i]) {
            val start = i
            while (i < len && original[i] != patched[i] && (i - start) < 0xFFFF) i++
            val size = i - start
            out.write((start shr 16) and 0xFF)
            out.write((start shr 8) and 0xFF)
            out.write(start and 0xFF)
            out.write((size shr 8) and 0xFF)
            out.write(size and 0xFF)
            out.write(patched, start, size)
        } else {
            i++
        }
    }
    out.write("EOF".toByteArray(Charsets.US_ASCII))
    return out.toByteArray()
}
