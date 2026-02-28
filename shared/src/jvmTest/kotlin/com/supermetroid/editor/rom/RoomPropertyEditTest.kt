package com.supermetroid.editor.rom

import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.ScrollChange
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.data.RoomEdits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Round-trip tests for room property editing: scroll data, FX data, and state data fields.
 */
class RoomPropertyEditTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    // ─── Scroll data parsing ────────────────────────────────────────

    @Test
    fun `Landing Site has valid scroll data`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val scrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)

        assertEquals(room.width * room.height, scrolls.size,
            "Scroll data should have one byte per screen")
        for ((i, s) in scrolls.withIndex()) {
            assertTrue(s in 0x00..0x02,
                "Scroll value at index $i should be 0x00-0x02, got 0x${s.toString(16)}")
        }
        println("Landing Site scrolls (${room.width}x${room.height}): ${scrolls.map { 
            when (it) { 0 -> "R"; 1 -> "B"; 2 -> "G"; else -> "?" }
        }}")
    }

    @Test
    fun `special scroll pointers return correct defaults`() {
        val parser = loadTestRom() ?: return
        val allBlue = parser.parseScrollData(0x0000, 3, 2)
        assertEquals(6, allBlue.size)
        assertTrue(allBlue.all { it == 0x01 }, "scrollsPtr=0x0000 should give all Blue")

        val allGreen = parser.parseScrollData(0x0001, 4, 3)
        assertEquals(12, allGreen.size)
        assertTrue(allGreen.all { it == 0x02 }, "scrollsPtr=0x0001 should give all Green")
    }

    @Test
    fun `scroll edit round-trip - modify scroll, write, read back`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!

        // Skip if special pointer (can't write in-place)
        if (room.roomScrollsPtr <= 1) {
            println("Landing Site uses special scroll pointer, skipping in-place test")
            return
        }

        val originalScrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        val romData = parser.getRomData().copyOf()

        // Flip the first screen's scroll value
        val origVal = originalScrolls[0]
        val newVal = when (origVal) { 0x01 -> 0x00; 0x00 -> 0x02; else -> 0x01 }

        val scrollPc = parser.snesToPc(0x8F0000 or room.roomScrollsPtr)
        romData[scrollPc] = newVal.toByte()

        // Read back
        val patchedParser = RomParser(romData)
        val readBack = patchedParser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
        assertEquals(newVal, readBack[0],
            "Screen (0,0) scroll should be 0x${newVal.toString(16)}, got 0x${readBack[0].toString(16)}")

        // Other screens should be unchanged
        for (i in 1 until originalScrolls.size) {
            assertEquals(originalScrolls[i], readBack[i],
                "Screen $i should be unchanged: expected 0x${originalScrolls[i].toString(16)}")
        }
        println("Scroll edit round-trip: PASS (screen 0: 0x${origVal.toString(16)} → 0x${newVal.toString(16)})")
    }

    // ─── FX data parsing and editing ────────────────────────────────

    @Test
    fun `Landing Site has FX entries`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val fxEntries = parser.parseFxEntries(room.fxPtr)

        assertTrue(fxEntries.isNotEmpty(), "Landing Site should have at least one FX entry")
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        assertNotNull(defaultFx, "Should have a default (doorSelect=0) FX entry")
        println("Landing Site FX: ${fxEntries.size} entries, default type=${defaultFx!!.fxTypeName}")
    }

    @Test
    fun `FX edit round-trip - modify FX type and palette blend`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        if (room.fxPtr == 0 || room.fxPtr == 0xFFFF) {
            println("Landing Site has no FX data, skipping")
            return
        }

        val fxEntries = parser.parseFxEntries(room.fxPtr)
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 } ?: return
        val romData = parser.getRomData().copyOf()

        // Find the default FX entry's PC offset
        val fxSnesAddr = 0x830000 or room.fxPtr
        var fxPc = parser.snesToPc(fxSnesAddr)
        for (entry in fxEntries) {
            if (entry.doorSelect == 0) break
            fxPc += 16
        }

        // Modify FX type to Rain (0x0A) and palette blend to 0x42
        val origFxType = defaultFx.fxType
        val origPaletteBlend = defaultFx.paletteBlend
        romData[fxPc + 9] = 0x0A.toByte()    // fxType
        romData[fxPc + 15] = 0x42.toByte()   // paletteBlend

        // Read back
        val patchedParser = RomParser(romData)
        val readFx = patchedParser.parseFxEntries(room.fxPtr)
        val readDefault = readFx.lastOrNull { it.doorSelect == 0 }!!
        assertEquals(0x0A, readDefault.fxType, "FX type should be Rain (0x0A)")
        assertEquals("Rain", readDefault.fxTypeName)
        assertEquals(0x42, readDefault.paletteBlend, "Palette blend should be 0x42")

        println("FX edit round-trip: PASS (type: 0x${origFxType.toString(16)} → 0x0A, " +
                "blend: 0x${origPaletteBlend.toString(16)} → 0x42)")
    }

    @Test
    fun `FX liquid properties round-trip`() {
        val parser = loadTestRom() ?: return
        // Use a room with water/lava — Maridia room (aqueduct area)
        val room = parser.readRoomHeader(0x91F8)!!
        if (room.fxPtr == 0 || room.fxPtr == 0xFFFF) return

        val romData = parser.getRomData().copyOf()
        val fxEntries = parser.parseFxEntries(room.fxPtr)
        var fxPc = parser.snesToPc(0x830000 or room.fxPtr)
        for (entry in fxEntries) {
            if (entry.doorSelect == 0) break
            fxPc += 16
        }

        // Set liquid start=0x0100, target=0x0080, speed=0x0004
        romData[fxPc + 2] = 0x00; romData[fxPc + 3] = 0x01  // liquidStart = 0x0100
        romData[fxPc + 4] = 0x80.toByte(); romData[fxPc + 5] = 0x00  // liquidNew = 0x0080
        romData[fxPc + 6] = 0x04; romData[fxPc + 7] = 0x00  // liquidSpeed = 0x0004
        romData[fxPc + 8] = 0x10  // liquidDelay = 0x10
        romData[fxPc + 9] = 0x04  // fxType = Water

        val patchedParser = RomParser(romData)
        val readFx = patchedParser.parseFxEntries(room.fxPtr).lastOrNull { it.doorSelect == 0 }!!
        assertEquals(0x0100, readFx.liquidSurfaceStart)
        assertEquals(0x0080, readFx.liquidSurfaceNew)
        assertEquals(0x0004, readFx.liquidSpeed)
        assertEquals(0x10, readFx.liquidDelay)
        assertEquals(0x04, readFx.fxType)
        assertTrue(readFx.hasLiquid)
        println("FX liquid round-trip: PASS")
    }

    // ─── State data field editing ───────────────────────────────────

    @Test
    fun `state data tileset edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)
        assertTrue(allStates.isNotEmpty())

        val origTileset = room.tileset
        val newTileset = if (origTileset == 0) 1 else 0

        // Write to all state data blocks
        for (stateOff in allStates) {
            romData[stateOff + 3] = newTileset.toByte()
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(newTileset, readRoom.tileset,
            "Tileset should be $newTileset after edit")
        println("Tileset edit round-trip: PASS ($origTileset → $newTileset)")
    }

    @Test
    fun `state data music edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)

        val origMusicData = room.musicData
        val origMusicTrack = room.musicTrack

        for (stateOff in allStates) {
            romData[stateOff + 4] = 0x12.toByte()  // musicData
            romData[stateOff + 5] = 0x05.toByte()  // musicTrack
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(0x12, readRoom.musicData)
        assertEquals(0x05, readRoom.musicTrack)
        println("Music edit round-trip: PASS (data: 0x${origMusicData.toString(16)} → 0x12, " +
                "track: 0x${origMusicTrack.toString(16)} → 0x05)")
    }

    @Test
    fun `state data BG scrolling edit round-trip`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val romData = parser.getRomData().copyOf()
        val allStates = parser.findAllStateDataOffsets(0x91F8)

        val origBg = room.bgScrolling
        val newBg = 0x0001  // layer 2 follows layer 1

        for (stateOff in allStates) {
            romData[stateOff + 12] = (newBg and 0xFF).toByte()
            romData[stateOff + 13] = ((newBg shr 8) and 0xFF).toByte()
        }

        val patchedParser = RomParser(romData)
        val readRoom = patchedParser.readRoomHeader(0x91F8)!!
        assertEquals(newBg, readRoom.bgScrolling,
            "BG scrolling should be 0x${newBg.toString(16)}")
        println("BG scrolling edit round-trip: PASS (0x${origBg.toString(16)} → 0x${newBg.toString(16)})")
    }

    // ─── Data model tests ───────────────────────────────────────────

    @Test
    fun `ScrollChange stores before and after values`() {
        val sc = ScrollChange(screenX = 2, screenY = 1, oldValue = 0x01, newValue = 0x00)
        assertEquals(2, sc.screenX)
        assertEquals(1, sc.screenY)
        assertEquals(0x01, sc.oldValue)
        assertEquals(0x00, sc.newValue)
    }

    @Test
    fun `FxChange only stores non-null diff fields`() {
        val fx = FxChange(fxType = 0x0A, paletteBlend = 0x42)
        assertEquals(0x0A, fx.fxType)
        assertEquals(0x42, fx.paletteBlend)
        assertNull(fx.liquidSurfaceStart)
        assertNull(fx.liquidSpeed)
        assertNull(fx.fxBitA)
    }

    @Test
    fun `StateDataChange only stores non-null diff fields`() {
        val sd = StateDataChange(tileset = 5, bgScrolling = 0x0001)
        assertEquals(5, sd.tileset)
        assertEquals(0x0001, sd.bgScrolling)
        assertNull(sd.musicData)
        assertNull(sd.musicTrack)
    }

    @Test
    fun `RoomEdits includes scroll, FX, and state data changes`() {
        val edits = RoomEdits(roomId = 0x91F8)
        assertTrue(edits.scrollChanges.isEmpty())
        assertNull(edits.fxChange)
        assertNull(edits.stateDataChange)

        edits.scrollChanges.add(ScrollChange(0, 0, 0x01, 0x00))
        edits.fxChange = FxChange(fxType = 0x06)
        edits.stateDataChange = StateDataChange(tileset = 3)

        assertEquals(1, edits.scrollChanges.size)
        assertEquals(0x06, edits.fxChange!!.fxType)
        assertEquals(3, edits.stateDataChange!!.tileset)
    }

    @Test
    fun `empty FxChange equals default`() {
        assertEquals(FxChange(), FxChange(),
            "Two empty FxChanges should be equal (no changes to apply)")
    }

    @Test
    fun `empty StateDataChange equals default`() {
        assertEquals(StateDataChange(), StateDataChange(),
            "Two empty StateDataChanges should be equal (no changes to apply)")
    }

    // ─── Multi-room scroll analysis ─────────────────────────────────

    @Test
    fun `rooms with hidden screens have Red scroll values`() {
        val parser = loadTestRom() ?: return
        val repo = com.supermetroid.editor.data.RoomRepository()
        val allRooms = repo.getAllRooms()
        var roomsWithRedScrolls = 0

        for (roomInfo in allRooms) {
            val roomId = try { roomInfo.getRoomIdAsInt() } catch (_: Exception) { continue }
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.roomScrollsPtr <= 1) continue
            val scrolls = parser.parseScrollData(room.roomScrollsPtr, room.width, room.height)
            if (scrolls.any { it == 0x00 }) {
                roomsWithRedScrolls++
            }
        }
        assertTrue(roomsWithRedScrolls > 0,
            "Should find at least one room with hidden (Red) scroll screens")
        println("Rooms with Red (hidden) scroll screens: $roomsWithRedScrolls")
    }
}
