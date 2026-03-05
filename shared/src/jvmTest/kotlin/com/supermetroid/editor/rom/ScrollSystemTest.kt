package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File
import com.supermetroid.editor.rom.RomParser.PlmEntry

class ScrollSystemTest {

    private fun loadRom(name: String): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/$name",
            name
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    private fun decodeScrollCommands(rp: RomParser, paramPtr: Int): List<Pair<Int, Int>> {
        val snesAddr = 0x8F0000 or paramPtr
        val pc = rp.snesToPc(snesAddr)
        val commands = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        while (offset < 256) {
            val screenIdx = rp.romData[pc + offset].toInt() and 0xFF
            if (screenIdx >= 0x80) break
            val scrollVal = rp.romData[pc + offset + 1].toInt() and 0xFF
            commands.add(screenIdx to scrollVal)
            offset += 2
        }
        return commands
    }

    private fun scrollValName(v: Int) = when (v) {
        0x00 -> "Red(hidden)"
        0x01 -> "Blue(scroll)"
        0x02 -> "Green(noScroll)"
        else -> "?($v)"
    }

    @Test
    fun `dump Parlor scroll data - vanilla ROM`() {
        val rp = loadRom("test-resources/Super Metroid (JU) [!].smc") ?: run {
            println("SKIP: vanilla ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "VANILLA Parlor and Alcatraz")
    }

    @Test
    fun `dump Parlor scroll data - Spike Olympics ROM`() {
        val rp = loadRom("projects/Super Metroid Spike Olympics I.smc") ?: run {
            println("SKIP: Spike Olympics ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "SPIKE OLYMPICS Parlor and Alcatraz")
    }

    @Test
    fun `dump Parlor scroll data - Spike Olympics EDITED ROM`() {
        val rp = loadRom("projects/Super Metroid Spike Olympics I_edited.smc") ?: run {
            println("SKIP: Spike Olympics edited ROM not found")
            return
        }
        dumpRoomScrollInfo(rp, 0x92FD, "SPIKE OLYMPICS EDITED Parlor and Alcatraz")
    }

    private fun dumpRoomScrollInfo(rp: RomParser, roomId: Int, label: String) {
        println("\n${"=".repeat(70)}")
        println("  $label (Room $${roomId.toString(16).uppercase()})")
        println("${"=".repeat(70)}")

        val room = rp.readRoomHeader(roomId) ?: run {
            println("ERROR: Could not parse room header")
            return
        }
        val w = room.width
        val h = room.height
        println("\nRoom: ${room.name}, dimensions: ${w}x${h} screens")
        println("Scroll Ptr: $${room.roomScrollsPtr.toString(16).uppercase()}")
        println("PLM Set Ptr: $${room.plmSetPtr.toString(16).uppercase()}")
        println("Door Out Ptr: $${room.doorOut.toString(16).uppercase()}")

        // Dump all states and their scroll pointers
        println("\n--- ALL ROOM STATES ---")
        val stateOffsets = rp.findAllStateDataOffsets(roomId)
        for ((si, stateOff) in stateOffsets.withIndex()) {
            val stateData = rp.readStateData(stateOff)
            val scrollPtr = stateData["roomScrollsPtr"] ?: 0
            val plmPtr = stateData["plmSetPtr"] ?: 0
            println("  State[$si]: scrollPtr=$${scrollPtr.toString(16).uppercase()} plmSetPtr=$${plmPtr.toString(16).uppercase()}")
            val stateScrolls = rp.parseScrollData(scrollPtr, w, h)
            for (row in 0 until h) {
                val rowStr = (0 until w).joinToString(" ") { col ->
                    val idx = row * w + col
                    val v = stateScrolls.getOrElse(idx) { -1 }
                    when (v) { 0x00 -> "R"; 0x01 -> "B"; 0x02 -> "G"; else -> "?" } + "(${idx.toString().padStart(2)})"
                }
                println("    Row $row: $rowStr")
            }
        }

        // Static scroll data
        println("\n--- STATIC SCROLL DATA ---")
        val scrolls = rp.parseScrollData(room.roomScrollsPtr, w, h)
        for (row in 0 until h) {
            val rowStr = (0 until w).joinToString(" ") { col ->
                val idx = row * w + col
                val v = scrolls.getOrElse(idx) { -1 }
                val label2 = when (v) {
                    0x00 -> "R"
                    0x01 -> "B"
                    0x02 -> "G"
                    else -> "?"
                }
                "$label2(${idx.toString().padStart(2)})"
            }
            println("  Row $row: $rowStr")
        }

        // PLM set
        println("\n--- PLM SET ---")
        val plms = rp.parsePlmSet(room.plmSetPtr)
        val scrollPlms = mutableListOf<PlmEntry>()
        for (plm in plms) {
            val idHex = plm.id.toString(16).uppercase().padStart(4, '0')
            val pHex = plm.param.toString(16).uppercase().padStart(4, '0')
            val isScroll = plm.id == 0xB703 || plm.id == 0xB63B || plm.id == 0xB647
                    || plm.id == 0xB63F || plm.id == 0xB643
            val tag = if (isScroll) " <<< SCROLL PLM" else ""
            println("  PLM $idHex  pos=(${plm.x},${plm.y})  param=$pHex$tag")
            if (isScroll) scrollPlms.add(plm)
        }
        println("  Total PLMs: ${plms.size}, Scroll PLMs: ${scrollPlms.size}")

        // Decode scroll PLM commands
        if (scrollPlms.isNotEmpty()) {
            println("\n--- SCROLL PLM COMMANDS ---")
            for (plm in scrollPlms) {
                val idHex = plm.id.toString(16).uppercase().padStart(4, '0')
                val pHex = plm.param.toString(16).uppercase().padStart(4, '0')
                println("  PLM $idHex at (${plm.x},${plm.y}), command ptr=$pHex:")
                val cmds = decodeScrollCommands(rp, plm.param)
                if (cmds.isEmpty()) {
                    println("    (no commands or immediate terminator)")
                } else {
                    for ((screenIdx, scrollVal) in cmds) {
                        val col = screenIdx % w
                        val row = screenIdx / w
                        println("    Screen[$screenIdx] (x=$col,y=$row) -> ${scrollValName(scrollVal)}")
                    }
                }
            }
        }

        // Doors
        println("\n--- DOORS ---")
        val doors = rp.parseDoorList(room.doorOut)
        for ((i, door) in doors.withIndex()) {
            val destHex = door.destRoomPtr.toString(16).uppercase().padStart(4, '0')
            val dirName = door.directionName
            val entryHex = door.entryCode.toString(16).uppercase().padStart(4, '0')
            val capHex = door.doorCapCode.toString(16).uppercase().padStart(4, '0')
            println("  Door[$i]: dest=$destHex($dirName) spawn=(${door.screenX},${door.screenY}) " +
                    "doorCapCode=$capHex entryCode=$entryHex")
            if (door.entryCode != 0x0000) {
                println("    >>> Door ASM at \$8F:$entryHex — decoding scroll writes:")
                decodeDoorAsm(rp, door.entryCode)
            }
        }
        println()
    }

    private fun decodeDoorAsm(rp: RomParser, asmPtr: Int) {
        val pc = rp.snesToPc(0x8F0000 or asmPtr)
        val bytes = ByteArray(64) { i ->
            if (pc + i < rp.romData.size) rp.romData[pc + i] else 0
        }
        val hexDump = bytes.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        println("    Raw bytes: $hexDump")

        // Try to decode STA $7ECD20+X patterns (writes to scroll RAM)
        // Common patterns:
        //   LDA #$xx; STA $7ECDxx  (long addressing: 8F CD 7E)
        //   or STA $CDxx (direct, bank already $7E)
        var i = 0
        while (i < 60) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x6B) { // RTL
                println("    [RTL at +$i]")
                break
            }
            // LDA #imm8 (SEP #$20 mode): A9 xx
            // STA long: 8F ll hh bb
            if (b == 0xA9 && i + 5 < 60) {
                val imm = bytes[i + 1].toInt() and 0xFF
                val next = bytes[i + 2].toInt() and 0xFF
                if (next == 0x8F) { // STA long
                    val lo = bytes[i + 3].toInt() and 0xFF
                    val hi = bytes[i + 4].toInt() and 0xFF
                    val bank = bytes[i + 5].toInt() and 0xFF
                    val addr = (bank shl 16) or (hi shl 8) or lo
                    if (addr in 0x7ECD20..0x7ECD51) {
                        val screenIdx = addr - 0x7ECD20
                        println("    Screen[$screenIdx] = ${scrollValName(imm)}")
                    }
                    i += 6
                    continue
                }
            }
            // SEP/REP
            if ((b == 0xE2 || b == 0xC2) && i + 1 < 60) {
                i += 2; continue
            }
            // LDA #imm8
            if (b == 0xA9 && i + 1 < 60) {
                i += 2; continue
            }
            // STA long (skip 4 bytes)
            if (b == 0x8F && i + 3 < 60) {
                i += 4; continue
            }
            i++
        }
    }
}
