package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.serialization.json.Json
import java.io.File

// ─── Tileset defaults: metatile → (block type, BTS) ─────────────

data class TileDefault(val blockType: Int, val bts: Int = 0)

/**
 * Hardcoded defaults for well-known metatiles. When a tile from the tileset
 * is placed on the map, these defaults are applied automatically so the user
 * doesn't have to right-click and set properties for every block.
 *
 * Block types: 0x0=Air, 0x3=Speed Booster, 0x4=Shot, 0x5=H-Extend, 0x8=Solid,
 *   0x9=Door, 0xA=Spike, 0xB=Crumble, 0xC=Shot(reform), 0xD=V-Extend,
 *   0xE=Grapple, 0xF=Bomb(reform)
 *
 * Type 0xC BTS: 0x00=beam/bomb(reform), 0x01=beam/bomb(no reform),
 *   0x04-0x07=hidden, 0x08-0x09=power bomb, 0x0A-0x0B=super missile
 * Type 0x3: speed booster breakable (solid, immune to shots/bombs, breaks on speed boost)
 */
object TilesetDefaults {
    val defaults: Map<Int, TileDefault> = mapOf(
        // Item containers (shot blocks — player shoots to reveal PLM item)
        74  to TileDefault(0x4),              // Energy Tank
        76  to TileDefault(0x4),              // Missile
        78  to TileDefault(0x4),              // Super Missile
        80  to TileDefault(0x4),              // Power Bomb

        // Standard interactive blocks
        82  to TileDefault(0xC, 0x00),        // Shootable block (reforms, any weapon)
        86  to TileDefault(0xA),              // Spike
        87  to TileDefault(0xC, 0x08),        // Power bombable block (reforms)
        88  to TileDefault(0xF),              // Bombable block (reforms)
        114 to TileDefault(0x4, 0x00),        // Shootable item block (chozo, one-time)
        155 to TileDefault(0xE),              // Grapple block
        156 to TileDefault(0xA),              // Spike (alt tile)
        159 to TileDefault(0xC, 0x0A),        // Super missile breakable (reforms)
        182 to TileDefault(0x3),              // Speed booster breakable (type 0x3 — solid, shot-immune)
        183 to TileDefault(0xE),              // Crumble grapple (grapple primary; user can set crumble via properties)
        188 to TileDefault(0xB),              // Crumble block

        // Multi-tile shot blocks: each tile is an independent shot block
        // Player must shoot each tile separately; no linking mechanism
        150 to TileDefault(0xC, 0x00),        // 2×1 shot block (left, reform)
        151 to TileDefault(0xC, 0x00),        // 2×1 shot block (right, reform)
        152 to TileDefault(0xC, 0x00),        // 1×2 shot block (top, reform)
        184 to TileDefault(0xC, 0x00),        // 1×2 shot block (bottom, reform)
        153 to TileDefault(0xC, 0x00),        // 2×2 shot block (top-left, reform)
        154 to TileDefault(0xC, 0x00),        // 2×2 shot block (top-right, reform)
        185 to TileDefault(0xC, 0x00),        // 2×2 shot block (bottom-left, reform)
        186 to TileDefault(0xC, 0x00),        // 2×2 shot block (bottom-right, reform)
    )

    fun get(metatileIndex: Int): TileDefault? = defaults[metatileIndex]
}

// ─── Brush: single or multi-tile selection ──────────────────────

/**
 * A brush can be 1×1 (single tile) or NxM (rectangle from tileset).
 * tiles[row][col] = metatile index. hFlip/vFlip apply to the whole grid.
 *
 * Per-tile overrides allow different block types and BTS values within
 * a multi-tile brush (e.g., shot block + H-extend pairs).
 */
data class TileBrush(
    val tiles: List<List<Int>>,  // [row][col] of metatile indices
    val blockType: Int = 0x8,
    val hFlip: Boolean = false,
    val vFlip: Boolean = false,
    val blockTypeOverrides: Map<Long, Int> = emptyMap(),
    val btsOverrides: Map<Long, Int> = emptyMap()
) {
    val cols get() = tiles.firstOrNull()?.size ?: 0
    val rows get() = tiles.size

    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    fun blockTypeAt(r: Int, c: Int): Int = blockTypeOverrides[key(r, c)] ?: blockType
    fun btsAt(r: Int, c: Int): Int = btsOverrides[key(r, c)] ?: 0

    /** Encode one tile at (r, c) as a 16-bit block word. */
    fun blockWordAt(r: Int, c: Int): Int {
        val idx = tiles.getOrNull(r)?.getOrNull(c) ?: return 0
        var word = idx and 0x3FF
        if (hFlip) word = word or (1 shl 10)
        if (vFlip) word = word or (1 shl 11)
        word = word or ((blockTypeAt(r, c) and 0xF) shl 12)
        return word
    }

    /** For display: the first tile's metatile index. */
    val primaryIndex get() = tiles.firstOrNull()?.firstOrNull() ?: 0

    companion object {
        fun single(metatileIndex: Int, blockType: Int = 0x8, bts: Int = 0): TileBrush {
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            return TileBrush(
                tiles = listOf(listOf(metatileIndex)),
                blockType = blockType,
                btsOverrides = btsMap
            )
        }
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
    var currentTilesetId: Int = 0
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

    /** Door entries for the current room (parsed from ROM, read-only). */
    var doorEntries: List<RomParser.DoorEntry> = emptyList()
        private set

    // ─── Tileset editor ─────────────────────────────────────────

    /** The currently-viewed tileset in the tileset editor (independent of loaded room). */
    var editorTilesetId by mutableStateOf(0)
        private set

    /** TileGraphics for the tileset editor view (may differ from room's tileGraphics). */
    var editorTileGraphics: TileGraphics? = null
        private set

    /** Currently selected metatile index in the tileset editor (-1 = none). */
    var editorSelectedMetatile by mutableStateOf(-1)
        private set

    fun loadEditorTileset(tilesetId: Int, romParser: RomParser): Boolean {
        editorTilesetId = tilesetId
        editorSelectedMetatile = -1
        val tg = TileGraphics(romParser)
        return if (tg.loadTileset(tilesetId)) {
            editorTileGraphics = tg
            true
        } else {
            editorTileGraphics = null
            false
        }
    }

    fun selectEditorMetatile(index: Int) {
        editorSelectedMetatile = index
    }

    /** Get the effective default for a metatile: project override > hardcoded > learned > solid. */
    fun getEffectiveTileDefault(tilesetId: Int, metatileIndex: Int): TileDefault {
        val projectOverride = project.getTileDefault(tilesetId, metatileIndex)
        if (projectOverride != null) return TileDefault(projectOverride.blockType, projectOverride.bts)
        val hardcoded = TilesetDefaults.get(metatileIndex)
        if (hardcoded != null) return hardcoded
        val learned = metatileBlockTypePresets[metatileIndex]
        if (learned != null) return TileDefault(learned)
        return TileDefault(0x0) // air by default (no assumption)
    }

    /** Set a project-level tile default override. */
    fun setTileDefault(tilesetId: Int, metatileIndex: Int, blockType: Int, bts: Int) {
        project.setTileDefault(tilesetId, metatileIndex, blockType, bts)
        dirty = true
    }

    /** Remove a project-level tile default override (revert to hardcoded/learned). */
    fun clearTileDefault(tilesetId: Int, metatileIndex: Int) {
        project.removeTileDefault(tilesetId, metatileIndex)
        dirty = true
    }

    /** Check if there's a project override for this metatile. */
    fun hasProjectOverride(tilesetId: Int, metatileIndex: Int): Boolean =
        project.getTileDefault(tilesetId, metatileIndex) != null

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        val eff = getEffectiveTileDefault(currentTilesetId, index)
        brush = TileBrush.single(index, eff.blockType, eff.bts)
    }

    fun beginTilesetDrag(col: Int, row: Int) {
        tilesetSelStart = Pair(col, row)
        tilesetSelEnd = Pair(col, row)
    }

    fun updateTilesetDrag(col: Int, row: Int) {
        tilesetSelEnd = Pair(col, row)
    }

    /** Finalize rectangle selection → build multi-tile brush with per-tile defaults. */
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

        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        for ((ri, row) in tiles.withIndex()) {
            for ((ci, meta) in row.withIndex()) {
                val key = (ri.toLong() shl 32) or (ci.toLong() and 0xFFFFFFFFL)
                val eff = getEffectiveTileDefault(currentTilesetId, meta)
                btOverrides[key] = eff.blockType
                if (eff.bts != 0) btsOverrides[key] = eff.bts
            }
        }
        val primaryIdx = tiles.first().first()
        val primaryEff = getEffectiveTileDefault(currentTilesetId, primaryIdx)
        val fallbackBlockType = primaryEff.blockType

        brush = TileBrush(
            tiles = tiles,
            blockType = fallbackBlockType,
            blockTypeOverrides = btOverrides,
            btsOverrides = btsOverrides
        )
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
        val sampledBts = readBts(bx, by)
        val btsMap = if (sampledBts != 0) mapOf(0L to sampledBts) else emptyMap()
        brush = TileBrush(tiles = listOf(listOf(metatileIdx)), blockType = bt, hFlip = hf, vFlip = vf, btsOverrides = btsMap)
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
        currentTilesetId = room.tileset
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

        // Parse door entries for this room
        doorEntries = romParser.parseDoorList(room.doorOut)

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
                val oldBts = readBts(tx, ty)
                val newBts = b.btsAt(r, c)
                if (oldWord == newWord && oldBts == newBts) continue
                writeBlockWord(tx, ty, newWord)
                writeBts(tx, ty, newBts)
                pendingEdits.add(TileEdit(tx, ty, oldWord, newWord, oldBts, newBts))
                pendingPositions.add(key)
                changed = true
            }
        }
        if (changed) editVersion++
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
        // Remove any existing item PLM at the same position to avoid duplicates
        if (RomParser.isItemPlm(plmId)) {
            val existing = _workingPlms.filter { it.x == x && it.y == y && RomParser.isItemPlm(it.id) }
            for (old in existing) {
                _workingPlms.remove(old)
                project.getOrCreateRoom(currentRoomId).plmChanges.add(
                    PlmChange("remove", old.id, old.x, old.y, 0)
                )
            }
        }

        // Auto-assign collection index for items when param is 0.
        // SM uses sequential indices stored in a ~20-byte collection bit array.
        // Original items use: Crateria 0x00-0x0B, Brinstar 0x0D-0x30,
        // Norfair 0x31-0x50, Wrecked Ship 0x80-0x87, Maridia 0x88-0x9A.
        // The gap 0x51-0x7F (47 slots) is unused and safe for new items.
        // WARNING: 0xA0+ overflows the collection array and corrupts save data!
        val actualParam = if (param == 0 && RomParser.isItemPlm(plmId)) {
            val usedIndices = mutableSetOf<Int>()
            for ((_, roomEdits) in project.rooms) {
                for (change in roomEdits.plmChanges) {
                    if (change.action == "add" && change.param > 0) usedIndices.add(change.param)
                }
            }
            for (plm in _workingPlms) {
                if (RomParser.isItemPlm(plm.id) && plm.param > 0) usedIndices.add(plm.param)
            }
            var idx = 0x51
            while (idx in usedIndices && idx <= 0x7F) idx++
            if (idx > 0x7F) {
                idx = 0x9B
                while (idx in usedIndices && idx <= 0x9F) idx++
            }
            if (idx > 0x9F) 0x06 else idx
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
        val newBts = b.btsAt(0, 0)
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
            val oldBts = readBts(cx, cy)
            writeBts(cx, cy, newBts)
            pendingEdits.add(TileEdit(cx, cy, targetWord, newWord, oldBts, newBts))
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

        // Free space allocator for bank $8F (PLM sets live here).
        // Only treat 0xFF as free (0x00 is valid data: PLM terminators, etc.)
        val bank8FEnd = romParser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = romParser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
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
                // Deduplicate: if multiple item PLMs at the same (x,y), keep last one
                val seen = mutableSetOf<Long>()
                val deduped = mutableListOf<RomParser.PlmEntry>()
                for (plm in modifiedPlms.reversed()) {
                    val key = (plm.x.toLong() shl 16) or plm.y.toLong()
                    if (RomParser.isItemPlm(plm.id)) {
                        if (key in seen) continue
                        seen.add(key)
                    }
                    deduped.add(plm)
                }
                deduped.reverse()
                val originalSize = originalPlms.size * 6 + 2
                val newSize = deduped.size * 6 + 2
                val plmPc = romParser.snesToPc(0x8F0000 or room.plmSetPtr)

                val writePc: Int
                if (newSize <= originalSize) {
                    writePc = plmPc
                } else if (freePtr + newSize <= bank8FEnd) {
                    writePc = freePtr
                    freePtr += newSize
                    // Update PLM set pointer in ALL state data blocks for this room.
                    // Rooms can have multiple state conditions (E629, E612, E5E6, etc.)
                    // that share the same PLM set pointer. We must update all of them
                    // or the game will read the old (now stale) location for those states.
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    val newSnes = romParser.pcToSnes(writePc)
                    val newPtr = newSnes and 0xFFFF
                    var updatedStates = 0
                    for (stateOffset in allStateOffsets) {
                        val existingPlmPtr = (romData[stateOffset + 20].toInt() and 0xFF) or
                                ((romData[stateOffset + 21].toInt() and 0xFF) shl 8)
                        if (existingPlmPtr == room.plmSetPtr) {
                            romData[stateOffset + 20] = (newPtr and 0xFF).toByte()
                            romData[stateOffset + 21] = ((newPtr shr 8) and 0xFF).toByte()
                            updatedStates++
                        }
                    }
                    // Do NOT zero the old PLM set location — other state conditions
                    // with different PLM pointers may share adjacent data, and states
                    // we didn't match above still need the original data intact.
                    println("Room 0x$roomKey: relocated PLM set to 0x${newSnes.toString(16)} (updated $updatedStates/${allStateOffsets.size} states)")
                } else {
                    println("WARN: Room 0x$roomKey no free space for expanded PLM set — skipped")
                    continue
                }

                var offset = writePc
                for (plm in deduped) {
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

    private fun lz5Compress(data: ByteArray) = LZ5Compressor.compress(data)
}
