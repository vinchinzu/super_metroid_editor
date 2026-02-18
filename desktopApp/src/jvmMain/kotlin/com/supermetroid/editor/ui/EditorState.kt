package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import java.io.File

// ─── Brush: single or multi-tile selection ──────────────────────

/**
 * A brush can be 1×1 (single tile) or NxM (rectangle from tileset).
 * tiles[row][col] = metatile index. hFlip/vFlip apply to the whole grid.
 */
data class TileBrush(
    val tiles: List<List<Int>>,  // [row][col] of metatile indices
    val blockType: Int = 0x8,
    val hFlip: Boolean = false,
    val vFlip: Boolean = false
) {
    val cols get() = tiles.firstOrNull()?.size ?: 0
    val rows get() = tiles.size

    /** Encode one tile at (r, c) as a 16-bit block word. */
    fun blockWordAt(r: Int, c: Int): Int {
        val idx = tiles.getOrNull(r)?.getOrNull(c) ?: return 0
        var word = idx and 0x3FF
        if (hFlip) word = word or (1 shl 10)
        if (vFlip) word = word or (1 shl 11)
        word = word or ((blockType and 0xF) shl 12)
        return word
    }

    /** For display: the first tile's metatile index. */
    val primaryIndex get() = tiles.firstOrNull()?.firstOrNull() ?: 0

    companion object {
        fun single(metatileIndex: Int, blockType: Int = 0x8) =
            TileBrush(tiles = listOf(listOf(metatileIndex)), blockType = blockType)
    }
}

enum class EditorTool { PAINT, FILL, SAMPLE }

// ─── Editor State ───────────────────────────────────────────────

class EditorState {
    var brush by mutableStateOf<TileBrush?>(null)
        private set

    var activeTool by mutableStateOf(EditorTool.PAINT)

    /** Selection rectangle in tileset: (startCol, startRow, endCol, endRow). */
    var tilesetSelStart by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var tilesetSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val undoStack = mutableStateListOf<EditOperation>()
    val redoStack = mutableStateListOf<EditOperation>()

    private val pendingEdits = mutableListOf<TileEdit>()
    private val pendingPositions = mutableSetOf<Long>()

    var project by mutableStateOf(SmEditProject(romPath = ""))
        private set
    var projectFilePath: String = ""
        private set

    var workingLevelData: ByteArray? = null
        private set
    var workingBlocksWide: Int = 0
        private set
    var workingBlocksTall: Int = 0
        private set
    var currentRoomId: Int = 0
        private set
    var dirty by mutableStateOf(false)
        private set

    /** Incremented on every edit to trigger map re-render. */
    var editVersion by mutableStateOf(0)
        private set

    /** Mouse position on the map in block coordinates (for cursor preview). -1 = off map. */
    var hoverBlockX by mutableStateOf(-1)
    var hoverBlockY by mutableStateOf(-1)

    /** Tile info at hover position (metatile index, block type). */
    var hoverTileWord by mutableStateOf(0)
        private set

    /** TileGraphics for rendering brush preview. Set when room loads. */
    var tileGraphics: TileGraphics? = null
        private set

    /** Metatile index → most common block type, learned from room data + user edits. */
    var metatileBlockTypePresets: Map<Int, Int> = emptyMap()
        private set

    /** Working PLMs for the current room (includes edits). */
    private val _workingPlms = mutableListOf<RomParser.PlmEntry>()
    val workingPlms: List<RomParser.PlmEntry> get() = _workingPlms
    private var originalPlmCount = 0

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        val blockType = metatileBlockTypePresets[index] ?: 0x8
        brush = TileBrush.single(index, blockType)
    }

    fun beginTilesetDrag(col: Int, row: Int) {
        tilesetSelStart = Pair(col, row)
        tilesetSelEnd = Pair(col, row)
    }

    fun updateTilesetDrag(col: Int, row: Int) {
        tilesetSelEnd = Pair(col, row)
    }

    /** Finalize rectangle selection → build multi-tile brush. */
    fun endTilesetDrag(gridCols: Int) {
        val s = tilesetSelStart ?: return
        val e = tilesetSelEnd ?: return
        val c0 = minOf(s.first, e.first)
        val c1 = maxOf(s.first, e.first)
        val r0 = minOf(s.second, e.second)
        val r1 = maxOf(s.second, e.second)
        val tiles = (r0..r1).map { r ->
            (c0..c1).map { c -> r * gridCols + c }
        }
        val primaryIdx = tiles.first().first()
        val blockType = metatileBlockTypePresets[primaryIdx] ?: 0x8
        brush = TileBrush(tiles = tiles, blockType = blockType)
    }

    fun toggleHFlip() { brush = brush?.copy(hFlip = !brush!!.hFlip) }
    fun toggleVFlip() { brush = brush?.copy(vFlip = !brush!!.vFlip) }

    fun rotateClockwise() {
        val b = brush ?: return
        // Transpose + reverse rows = 90° CW for the tile grid
        val oldTiles = b.tiles
        val newRows = oldTiles[0].indices.map { c -> oldTiles.indices.reversed().map { r -> oldTiles[r][c] } }
        brush = b.copy(tiles = newRows, hFlip = !b.vFlip, vFlip = b.hFlip)
    }

    fun setBlockType(type: Int) { brush = brush?.copy(blockType = type) }

    /** Update hover info when mouse moves on map. */
    fun updateHover(bx: Int, by: Int) {
        hoverBlockX = bx; hoverBlockY = by
        hoverTileWord = if (bx >= 0 && by >= 0) readBlockWord(bx, by) else 0
    }

    /** Sample (eyedropper): pick the tile at (bx, by) from the map as the current brush. */
    fun sampleTile(bx: Int, by: Int, gridCols: Int = 32) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val word = readBlockWord(bx, by)
        val metatileIdx = word and 0x3FF
        val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0
        val bt = (word shr 12) and 0xF
        brush = TileBrush(tiles = listOf(listOf(metatileIdx)), blockType = bt, hFlip = hf, vFlip = vf)
        tilesetSelStart = Pair(metatileIdx % gridCols, metatileIdx / gridCols)
        tilesetSelEnd = tilesetSelStart
        activeTool = EditorTool.PAINT
    }

    // ─── Project lifecycle ──────────────────────────────────────

    fun initForRom(romPath: String) {
        projectFilePath = romPath.replaceAfterLast('.', "smedit")
        val file = File(projectFilePath)
        if (file.exists()) {
            try {
                project = json.decodeFromString(SmEditProject.serializer(), file.readText())
                println("Loaded project: ${file.absolutePath} (${project.rooms.size} rooms)")
            } catch (e: Exception) {
                println("Failed to load project: ${e.message}")
                project = SmEditProject(romPath = romPath)
            }
        } else {
            project = SmEditProject(romPath = romPath)
        }
        dirty = false
    }

    // ─── Working level data ─────────────────────────────────────

    fun loadRoom(roomId: Int, romParser: RomParser, room: com.supermetroid.editor.data.Room) {
        currentRoomId = roomId
        // Load tile graphics for brush preview rendering
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(room.tileset)) tileGraphics = tg
        val levelData = romParser.decompressLZ2(room.levelDataPtr)
        workingLevelData = levelData.copyOf()
        workingBlocksWide = room.width * 16
        workingBlocksTall = room.height * 16

        // Build metatile → block type presets by scanning room data
        val typeCounts = mutableMapOf<Int, MutableMap<Int, Int>>()
        for (y in 0 until workingBlocksTall) {
            for (x in 0 until workingBlocksWide) {
                val word = readBlockWord(x, y)
                val meta = word and 0x3FF
                val bt = (word shr 12) and 0xF
                if (bt != 0x0 && bt != 0x5 && bt != 0xD) {
                    typeCounts.getOrPut(meta) { mutableMapOf() }.merge(bt, 1, Int::plus)
                }
            }
        }
        val roomPresets = typeCounts.mapValues { (_, counts) ->
            counts.maxByOrNull { it.value }?.key ?: 0x8
        }
        // Merge with existing presets: prefer non-Solid (non-0x8) types
        val merged = metatileBlockTypePresets.toMutableMap()
        for ((meta, bt) in roomPresets) {
            val existing = merged[meta]
            if (existing == null || (bt != 0x8 && existing == 0x8)) {
                merged[meta] = bt
            }
        }
        metatileBlockTypePresets = merged

        undoStack.clear()
        redoStack.clear()
        pendingEdits.clear()
        pendingPositions.clear()

        // Load PLMs for this room
        _workingPlms.clear()
        val plms = romParser.parsePlmSet(room.plmSetPtr)
        _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        val roomKey = project.roomKey(roomId)
        val savedRoom = project.rooms[roomKey]
        if (savedRoom != null) {
            // Replay saved tile edits
            if (savedRoom.operations.isNotEmpty()) {
                var count = 0
                for (op in savedRoom.operations) {
                    for (edit in op.edits) {
                        writeBlockWord(edit.blockX, edit.blockY, edit.newBlockWord)
                        if (edit.newBts != edit.oldBts) writeBts(edit.blockX, edit.blockY, edit.newBts)
                        count++
                    }
                    undoStack.add(op)
                }
                println("Replayed $count saved edits for room 0x$roomKey")
            }
            // Replay saved PLM changes
            for (change in savedRoom.plmChanges) {
                when (change.action) {
                    "add" -> _workingPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> _workingPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                }
            }
        }
        // Bump version so map renders from working data immediately
        editVersion++
    }

    fun readBlockWord(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    fun readBts(bx: Int, by: Int): Int {
        val data = workingLevelData ?: return 0
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset >= data.size) return 0
        return data[btsOffset].toInt() and 0xFF
    }

    private fun writeBts(bx: Int, by: Int, bts: Int) {
        val data = workingLevelData ?: return
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        val btsOffset = 2 + layer1Size + idx
        if (btsOffset < data.size) data[btsOffset] = bts.toByte()
    }

    private fun writeBlockWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData ?: return
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        if (offset + 1 >= data.size) return
        data[offset] = (word and 0xFF).toByte()
        data[offset + 1] = ((word shr 8) and 0xFF).toByte()
    }

    // ─── Paint / Erase / Fill ───────────────────────────────────

    fun beginStroke() {
        pendingEdits.clear()
        pendingPositions.clear()
    }

    /** Paint the full brush at map position (bx, by). Returns true if anything changed. */
    fun paintAt(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= workingBlocksWide || ty >= workingBlocksTall) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPositions.contains(key)) continue
                val oldWord = readBlockWord(tx, ty)
                val newWord = b.blockWordAt(r, c)
                if (oldWord == newWord) continue
                writeBlockWord(tx, ty, newWord)
                val bts = readBts(tx, ty)
                pendingEdits.add(TileEdit(tx, ty, oldWord, newWord, bts, bts))
                pendingPositions.add(key)
                changed = true
            }
        }
        return changed
    }

    /** Set block type and BTS for a single tile (used by right-click properties).
     *  Coalesces consecutive changes on the same tile into one undo entry. */
    fun setTileProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return
        val oldWord = readBlockWord(bx, by)
        val oldBts = readBts(bx, by)
        val metatile = oldWord and 0x3FF
        val flips = oldWord and 0x0C00
        val newWord = metatile or flips or ((blockType and 0xF) shl 12)
        if (oldWord == newWord && oldBts == bts) return

        writeBlockWord(bx, by, newWord)
        writeBts(bx, by, bts)

        val roomOps = project.getOrCreateRoom(currentRoomId).operations
        val lastOp = undoStack.lastOrNull()
        if (lastOp != null && lastOp.edits.size == 1 &&
            lastOp.edits[0].blockX == bx && lastOp.edits[0].blockY == by &&
            lastOp.description.startsWith("Properties")) {
            val origEdit = lastOp.edits[0]
            val merged = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, origEdit.oldBlockWord, newWord, origEdit.oldBts, bts)
            ))
            undoStack[undoStack.lastIndex] = merged
            if (roomOps.isNotEmpty()) roomOps[roomOps.lastIndex] = merged
        } else {
            val op = EditOperation("Properties ($bx,$by)", listOf(
                TileEdit(bx, by, oldWord, newWord, oldBts, bts)
            ))
            undoStack.add(op)
            roomOps.add(op)
        }
        redoStack.clear()
        dirty = true
        editVersion++
        // Learn this metatile → block type mapping for future brush presets
        if (blockType != 0x0) {
            val meta = (readBlockWord(bx, by) and 0x3FF)
            metatileBlockTypePresets = metatileBlockTypePresets + (meta to blockType)
        }
    }

    // ─── PLM editing ────────────────────────────────────────────

    fun getPlmsAt(x: Int, y: Int): List<RomParser.PlmEntry> =
        _workingPlms.filter { it.x == x && it.y == y }

    fun addPlm(plmId: Int, x: Int, y: Int, param: Int) {
        // Auto-assign collection bit for items when param is 0
        val actualParam = if (param == 0 && RomParser.isItemPlm(plmId)) {
            val usedBits = _workingPlms.filter { RomParser.isItemPlm(it.id) }.map { it.param }.toSet()
            var bit = 1
            while (bit in usedBits && bit < 256) bit = bit shl 1
            if (bit >= 256) 1 else bit
        } else param
        _workingPlms.add(RomParser.PlmEntry(plmId, x, y, actualParam))
        project.getOrCreateRoom(currentRoomId).plmChanges.add(
            PlmChange("add", plmId, x, y, actualParam)
        )
        dirty = true
        editVersion++
    }

    fun removePlm(x: Int, y: Int, plmId: Int) {
        _workingPlms.removeAll { it.x == x && it.y == y && it.id == plmId }
        project.getOrCreateRoom(currentRoomId).plmChanges.add(
            PlmChange("remove", plmId, x, y, 0)
        )
        dirty = true
        editVersion++
    }

    /** Flood fill: replace all connected tiles matching the one at (bx, by) with brush. */
    fun floodFill(bx: Int, by: Int): Boolean {
        val b = brush ?: return false
        if (b.cols != 1 || b.rows != 1) return false  // fill only works with 1×1 brush
        if (bx < 0 || by < 0 || bx >= workingBlocksWide || by >= workingBlocksTall) return false
        val targetWord = readBlockWord(bx, by)
        val newWord = b.blockWordAt(0, 0)
        if (targetWord == newWord) return false

        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        var changed = false
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (cx < 0 || cy < 0 || cx >= workingBlocksWide || cy >= workingBlocksTall) continue
            val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (visited.contains(key)) continue
            visited.add(key)
            if (readBlockWord(cx, cy) != targetWord) continue
            writeBlockWord(cx, cy, newWord)
            val bts = readBts(cx, cy)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, bts, bts))
            changed = true
            queue.add(Pair(cx - 1, cy))
            queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1))
            queue.add(Pair(cx, cy + 1))
        }
        return changed
    }

    fun endStroke() {
        if (pendingEdits.isEmpty()) return
        val desc = when (activeTool) {
            EditorTool.PAINT -> "Paint ${pendingEdits.size} tile(s)"
            EditorTool.FILL -> "Fill ${pendingEdits.size} tile(s)"
            EditorTool.SAMPLE -> "Sample"
        }
        val op = EditOperation(desc, pendingEdits.toList())
        undoStack.add(op)
        redoStack.clear()
        project.getOrCreateRoom(currentRoomId).operations.add(op)
        dirty = true
        editVersion++
        pendingEdits.clear()
        pendingPositions.clear()
    }

    // ─── Undo / Redo ────────────────────────────────────────────

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val op = undoStack.removeAt(undoStack.lastIndex)
        for (edit in op.edits.reversed()) {
            writeBlockWord(edit.blockX, edit.blockY, edit.oldBlockWord)
            writeBts(edit.blockX, edit.blockY, edit.oldBts)
        }
        redoStack.add(op)
        val roomEdits = project.rooms[project.roomKey(currentRoomId)]
        if (roomEdits != null && roomEdits.operations.isNotEmpty())
            roomEdits.operations.removeAt(roomEdits.operations.lastIndex)
        dirty = true
        editVersion++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val op = redoStack.removeAt(redoStack.lastIndex)
        for (edit in op.edits) {
            writeBlockWord(edit.blockX, edit.blockY, edit.newBlockWord)
            writeBts(edit.blockX, edit.blockY, edit.newBts)
        }
        undoStack.add(op)
        project.getOrCreateRoom(currentRoomId).operations.add(op)
        dirty = true
        editVersion++
        return true
    }

    // ─── Project file I/O ───────────────────────────────────────

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun saveProject(): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            File(projectFilePath).writeText(json.encodeToString(SmEditProject.serializer(), project))
            dirty = false
            println("Project saved: $projectFilePath")
            true
        } catch (e: Exception) { println("Save failed: ${e.message}"); false }
    }

    // ─── Export: patch ROM ──────────────────────────────────────

    fun exportToRom(romParser: RomParser): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        val romData = romParser.getRomData().copyOf()
        var patchedRooms = 0

        // Free space allocator for bank $8F (PLM sets live here)
        val bank8FEnd = romParser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = romParser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF && b != 0x00) break
            freePtr--
        }
        freePtr++ // first free byte after last used data

        for ((roomKey, roomEdits) in project.rooms) {
            val hasTileEdits = roomEdits.operations.isNotEmpty()
            val hasPlmEdits = roomEdits.plmChanges.isNotEmpty()
            if (!hasTileEdits && !hasPlmEdits) continue
            val roomId = roomKey.toIntOrNull(16) ?: continue
            val room = romParser.readRoomHeader(roomId) ?: continue

            // Patch tile data
            if (hasTileEdits && room.levelDataPtr != 0) {
                val (originalData, origSize) = romParser.decompressLZ2WithSize(room.levelDataPtr)
                val editedData = originalData.copyOf()
                val bw = room.width * 16
                val layer1Size = (editedData[0].toInt() and 0xFF) or ((editedData[1].toInt() and 0xFF) shl 8)
                for (op in roomEdits.operations) for (edit in op.edits) {
                    val idx = edit.blockY * bw + edit.blockX; val off = 2 + idx * 2
                    if (off + 1 < editedData.size) { editedData[off] = (edit.newBlockWord and 0xFF).toByte(); editedData[off + 1] = ((edit.newBlockWord shr 8) and 0xFF).toByte() }
                    if (edit.newBts != edit.oldBts) { val btsOff = 2 + layer1Size + idx; if (btsOff < editedData.size) editedData[btsOff] = edit.newBts.toByte() }
                }
                val compressed = lz5Compress(editedData)
                val pcOff = romParser.snesToPc(room.levelDataPtr)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, pcOff, compressed.size)
                    for (i in compressed.size until origSize) romData[pcOff + i] = 0xFF.toByte()
                    patchedRooms++
                } else println("WARN: Room 0x$roomKey compressed ${compressed.size} > orig $origSize — skipped tiles")
            }

            // Patch PLM set
            if (hasPlmEdits && room.plmSetPtr != 0 && room.plmSetPtr != 0xFFFF) {
                val originalPlms = romParser.parsePlmSet(room.plmSetPtr)
                val modifiedPlms = originalPlms.toMutableList()
                for (change in roomEdits.plmChanges) {
                    when (change.action) {
                        "add" -> modifiedPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                        "remove" -> modifiedPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                    }
                }
                val originalSize = originalPlms.size * 6 + 2
                val newSize = modifiedPlms.size * 6 + 2
                val plmPc = romParser.snesToPc(0x8F0000 or room.plmSetPtr)

                val writePc: Int
                if (newSize <= originalSize) {
                    writePc = plmPc
                } else if (freePtr + newSize <= bank8FEnd) {
                    // Reallocate: write to free space at end of bank $8F
                    writePc = freePtr
                    freePtr += newSize
                    // Update PLM set pointer in state data
                    val stateDataPc = romParser.getStateDataPcOffset(roomId)
                    if (stateDataPc != null) {
                        val newSnes = romParser.pcToSnes(writePc)
                        val newPtr = newSnes and 0xFFFF
                        romData[stateDataPc + 20] = (newPtr and 0xFF).toByte()
                        romData[stateDataPc + 21] = ((newPtr shr 8) and 0xFF).toByte()
                    }
                    // Clear old location
                    for (i in plmPc until plmPc + originalSize) romData[i] = 0
                    println("Room 0x$roomKey: relocated PLM set to 0x${romParser.pcToSnes(writePc).toString(16)}")
                } else {
                    println("WARN: Room 0x$roomKey no free space for expanded PLM set — skipped")
                    continue
                }

                var offset = writePc
                for (plm in modifiedPlms) {
                    romData[offset] = (plm.id and 0xFF).toByte()
                    romData[offset + 1] = ((plm.id shr 8) and 0xFF).toByte()
                    romData[offset + 2] = plm.x.toByte()
                    romData[offset + 3] = plm.y.toByte()
                    romData[offset + 4] = (plm.param and 0xFF).toByte()
                    romData[offset + 5] = ((plm.param shr 8) and 0xFF).toByte()
                    offset += 6
                }
                romData[offset] = 0; romData[offset + 1] = 0
                if (writePc == plmPc) {
                    for (i in offset + 2 until plmPc + originalSize) romData[i] = 0
                }
                patchedRooms++
            }
        }
        if (patchedRooms == 0) return null
        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}_edited.${orig.extension}")
        out.writeBytes(romData)
        println("Exported: ${out.absolutePath} ($patchedRooms rooms)")
        return out.absolutePath
    }

    // ─── LZ5 Compressor ─────────────────────────────────────────

    private fun lz5Compress(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>(); val rawBuf = mutableListOf<Byte>(); var pos = 0
        fun flushRaw() { var i = 0; while (i < rawBuf.size) { val c = minOf(rawBuf.size - i, 1024); emitCmd(out, 0, c); for (j in 0 until c) out.add(rawBuf[i + j]); i += c }; rawBuf.clear() }
        while (pos < data.size) {
            val (dL, dA) = findDictMatch(data, pos); val bL = countByteFill(data, pos); val wL = countWordFill(data, pos)
            val dS = if (dL >= 3) dL - (if (dL <= 32) 3 else 4) else 0; val bS = if (bL >= 3) bL - (if (bL <= 32) 2 else 3) else 0; val wS = if (wL >= 4) wL - (if (wL <= 32) 3 else 4) else 0
            when { dS > 0 && dS >= bS && dS >= wS -> { flushRaw(); val l = minOf(dL, 1024); emitCmd(out, 4, l); out.add((dA and 0xFF).toByte()); out.add(((dA shr 8) and 0xFF).toByte()); pos += l }
                bS > 0 && bS >= wS -> { flushRaw(); val l = minOf(bL, 1024); emitCmd(out, 1, l); out.add(data[pos]); pos += l }
                wS > 0 -> { flushRaw(); val l = minOf(wL, 1024); emitCmd(out, 2, l); out.add(data[pos]); out.add(data[pos + 1]); pos += l }
                else -> { rawBuf.add(data[pos]); pos++ } }
        }; flushRaw(); out.add(0xFF.toByte()); return out.toByteArray()
    }
    private fun findDictMatch(d: ByteArray, p: Int): Pair<Int, Int> {
        if (p < 3) return Pair(0, 0); var bL = 0; var bA = 0; val mx = minOf(p, 0xFFFF); val st = if (p > 4000) 2 else 1; var s = 0
        while (s < mx) { var m = 0; val mm = minOf(d.size - p, 1024); while (m < mm && d[s + m] == d[p + m]) m++; if (m > bL) { bL = m; bA = s; if (m >= 64) break }; s += st }; return Pair(bL, bA)
    }
    private fun countByteFill(d: ByteArray, p: Int): Int { if (p >= d.size) return 0; val b = d[p]; var c = 1; while (p + c < d.size && c < 1024 && d[p + c] == b) c++; return c }
    private fun countWordFill(d: ByteArray, p: Int): Int { if (p + 1 >= d.size) return 0; val a = d[p]; val b = d[p + 1]; var c = 2; while (p + c < d.size && c < 1024) { if (d[p + c] != (if (c % 2 == 0) a else b)) break; c++ }; return c }
    private fun emitCmd(o: MutableList<Byte>, cmd: Int, len: Int) { if (len <= 32) o.add(((cmd shl 5) or (len - 1)).toByte()) else { val l = len - 1; o.add((0xE0 or ((cmd and 7) shl 2) or ((l shr 8) and 0x03)).toByte()); o.add((l and 0xFF).toByte()) } }
}
