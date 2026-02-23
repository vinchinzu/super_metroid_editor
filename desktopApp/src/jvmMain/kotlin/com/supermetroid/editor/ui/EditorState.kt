package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.DoorChange
import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.EnemyChange
import com.supermetroid.editor.data.PlmChange
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.data.TileEdit
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.data.PatternCell
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
        // Item containers (shootable air — player shoots to reveal PLM item)
        74  to TileDefault(0x4),              // Energy Tank
        75  to TileDefault(0x4),              // Energy Tank (alt)
        76  to TileDefault(0x4),              // Missile
        77  to TileDefault(0x4),              // Missile (alt)
        78  to TileDefault(0x4),              // Super Missile
        79  to TileDefault(0x4),              // Super Missile (alt)
        80  to TileDefault(0x4),              // Power Bomb
        81  to TileDefault(0x4),              // Power Bomb (alt)

        // Standard interactive blocks
        82  to TileDefault(0xC, 0x00),        // Shootable block (beam/bomb, reforms)
        83  to TileDefault(0xC, 0x01),        // Shootable block (beam/bomb, no reform)
        84  to TileDefault(0xC, 0x04),        // Hidden shot block (reform)
        85  to TileDefault(0xC, 0x05),        // Hidden shot block (no reform)
        86  to TileDefault(0xA),              // Spike (up-facing)
        87  to TileDefault(0xC, 0x08),        // Power bomb block (reform)
        88  to TileDefault(0xF),              // Bomb block (reform)
        89  to TileDefault(0xF, 0x04),        // Bomb block (permanent)
        90  to TileDefault(0xA),              // Spike (down-facing)
        91  to TileDefault(0xA),              // Spike (left-facing)
        92  to TileDefault(0xA),              // Spike (right-facing)

        // Chozo / special item containers
        114 to TileDefault(0x4, 0x00),        // Shootable item block (chozo)

        // Grapple blocks
        155 to TileDefault(0xE),              // Grapple block (normal)
        156 to TileDefault(0xA),              // Spike (alt)
        157 to TileDefault(0xE, 0x01),        // Crumble grapple (reform)
        158 to TileDefault(0xE, 0x02),        // Crumble grapple (permanent)
        159 to TileDefault(0xC, 0x0A),        // Super missile breakable (reform)
        160 to TileDefault(0xC, 0x0B),        // Super missile breakable (no reform)

        // Treadmill (type 0x3 — conveyor blocks that push Samus directionally)
        182 to TileDefault(0x3, 0x08),        // Treadmill (left)

        // Crumble / speed booster variants (type 0xB)
        183 to TileDefault(0xE),              // Grapple block (alt)
        188 to TileDefault(0xB, 0x00),        // Crumble block (reform)
        189 to TileDefault(0xB, 0x04),        // Crumble block (permanent)
        190 to TileDefault(0xB, 0x0E),        // Speed Booster block (reform)
        191 to TileDefault(0xB, 0x0F),        // Speed Booster block (permanent)

        // Multi-tile shot blocks
        150 to TileDefault(0xC, 0x00),        // 2×1 shot block (left, reform)
        151 to TileDefault(0xC, 0x00),        // 2×1 shot block (right, reform)
        152 to TileDefault(0xC, 0x00),        // 1×2 shot block (top, reform)
        184 to TileDefault(0xC, 0x00),        // 1×2 shot block (bottom, reform)
        153 to TileDefault(0xC, 0x00),        // 2×2 shot block (top-left, reform)
        154 to TileDefault(0xC, 0x00),        // 2×2 shot block (top-right, reform)
        185 to TileDefault(0xC, 0x00),        // 2×2 shot block (bottom-left, reform)
        186 to TileDefault(0xC, 0x00),        // 2×2 shot block (bottom-right, reform)

        // X-Ray air (type 0x2 — air block only visible with X-Ray Scope)
        192 to TileDefault(0x2),              // X-Ray air block
    )

    fun get(metatileIndex: Int): TileDefault? = defaults[metatileIndex]
}

// ─── Patch helpers ──────────────────────────────────────────────

/** UI-facing write entry (same shape as PatchWrite but not serializable). */
data class SmPatchWrite(val offset: Long, val bytes: List<Int>)

/**
 * Simple hex-tweak demo — single-byte value change.
 * All other patches load from bundled IPS in resources/patches/.
 */
val HARDCODED_PATCHES: List<SmPatch> = listOf(
    SmPatch(
        id = "hex_faster_charged_shots",
        name = "Faster Charged Shots (hex demo)",
        description = "Reduces delay between charged shots (0x3C → 0x1C). Single-byte demo at 0x83860. Source: MC hex tweaks.",
        enabled = false,
        writes = mutableListOf(PatchWrite(0x83860, listOf(0x1C)))
    ),
)

/**
 * Ceres escape timer LDA operand at $80:9E0D: `LDA #$0100 ; JSL $80:9E8C`
 * The 2-byte immediate operand at $80:9E0E stores seconds (BCD) in the low
 * byte and minutes (BCD) in the high byte. Vanilla = $0100 → 1 min 0 sec = 60s.
 */
const val CERES_TIMER_OPERAND_SNES = 0x809E0E

/** Config patch: Ceres escape time in seconds. */
val CERES_ESCAPE_PATCH = SmPatch(
    id = "config_ceres_escape_time",
    name = "Ceres Escape Time",
    description = "Sets the Ceres station escape timer in seconds (vanilla: 60). Override when enabled.",
    enabled = false,
    writes = mutableListOf(),
    configType = "ceres_escape_seconds",
    configValue = 60
)

/** Legacy/superseded patch IDs — removed on seed to avoid duplicates from old configs. */
private val LEGACY_PATCH_IDS = setOf(
    "respin", "fast_doors", "no_fanfare", "blue_speed_air", "no_walljump_kick", "instant_stop",
    "no_beeping", "energy_free_shinesparks", "fast_saves", "enable_moonwalk", "skip_ceres", "fast_mb_cutscene",
    "hex_no_spin_speed_loss", "hex_keep_blue_speed", "hex_no_walljump_kick", "hex_no_skid",
)

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
    val btsOverrides: Map<Long, Int> = emptyMap(),
    val flipOverrides: Map<Long, Int> = emptyMap(),  // per-tile: bit0=hflip, bit1=vflip
    val plmOverrides: Map<Long, Pair<Int, Int>> = emptyMap()  // per-tile: (plmId, plmParam)
) {
    val cols get() = tiles.firstOrNull()?.size ?: 0
    val rows get() = tiles.size

    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    fun blockTypeAt(r: Int, c: Int): Int = blockTypeOverrides[key(r, c)] ?: blockType
    fun btsAt(r: Int, c: Int): Int = btsOverrides[key(r, c)] ?: 0
    fun plmAt(r: Int, c: Int): Pair<Int, Int>? = plmOverrides[key(r, c)]

    /** Per-tile flip state: original flips XOR'd with brush-level flips. */
    fun tileHFlip(r: Int, c: Int): Boolean {
        val perTile = (flipOverrides[key(r, c)] ?: 0) and 1 != 0
        return perTile xor hFlip
    }
    fun tileVFlip(r: Int, c: Int): Boolean {
        val perTile = (flipOverrides[key(r, c)] ?: 0) and 2 != 0
        return perTile xor vFlip
    }

    /** Encode one tile at (r, c) as a 16-bit block word. */
    fun blockWordAt(r: Int, c: Int): Int {
        val idx = tiles.getOrNull(r)?.getOrNull(c) ?: return 0
        var word = idx and 0x3FF
        if (tileHFlip(r, c)) word = word or (1 shl 10)
        if (tileVFlip(r, c)) word = word or (1 shl 11)
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

enum class EditorTool { PAINT, FILL, SAMPLE, SELECT }

// ─── Editor State ───────────────────────────────────────────────

class EditorState {
    var brush by mutableStateOf<TileBrush?>(null)
        internal set

    var activeTool by mutableStateOf(EditorTool.PAINT)

    /** Map selection rectangle in block coordinates (inclusive). */
    var mapSelStart by mutableStateOf<Pair<Int, Int>?>(null)
    var mapSelEnd by mutableStateOf<Pair<Int, Int>?>(null)

    /** Selection rectangle in tileset: (startCol, startRow, endCol, endRow). */
    var tilesetSelStart by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var tilesetSelEnd by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val undoStack = mutableListOf<EditOperation>()
    val redoStack = mutableListOf<EditOperation>()
    var undoVersion by mutableStateOf(0)
        private set

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

    fun markDirty() { dirty = true }

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

    /** Door entries for the current room (mutable for editing). */
    private val _workingDoors = mutableListOf<RomParser.DoorEntry>()
    var doorEntries: List<RomParser.DoorEntry>
        get() = _workingDoors
        private set(value) { _workingDoors.clear(); _workingDoors.addAll(value) }

    /** Working enemy population for the current room (includes edits). */
    private val _workingEnemies = mutableListOf<RomParser.EnemyEntry>()
    val workingEnemies: List<RomParser.EnemyEntry> get() = _workingEnemies

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
        if (!tg.loadTileset(tilesetId)) { editorTileGraphics = null; return false }
        applyCustomGfxToTileGraphics(tg, tilesetId)
        editorTileGraphics = tg
        return true
    }

    /** Apply any project-stored custom graphics to a TileGraphics instance. */
    internal fun applyCustomGfxToTileGraphics(tg: TileGraphics, tilesetId: Int) {
        val gfxData = project.customGfx
        val varB64 = gfxData.varGfx[tilesetId.toString()]
        if (varB64 != null) {
            try { tg.applyCustomVarGfx(java.util.Base64.getDecoder().decode(varB64)) }
            catch (_: Exception) {}
        }
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try { tg.applyCustomCreGfx(java.util.Base64.getDecoder().decode(creB64)) }
            catch (_: Exception) {}
        }
    }

    // ─── Tileset graphics export / import ────────────────────────────

    /**
     * Export an 8x8 tile sheet as a PNG file.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun exportTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val result = tg.renderTileSheet(startTile, numTiles) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /** Export the palette as a PNG reference image. */
    fun exportPalette(filePath: String): Boolean {
        val tg = editorTileGraphics ?: return false
        val result = tg.renderPaletteImage(16) ?: return false
        val (pixels, w, h) = result
        return writePng(filePath, pixels, w, h)
    }

    /**
     * Import an edited tile sheet PNG and store in the project.
     * @param isCre true = CRE (common), false = URE (area-specific)
     */
    fun importTileSheet(filePath: String, isCre: Boolean): Boolean {
        val tg = editorTileGraphics ?: return false
        val img = try { javax.imageio.ImageIO.read(java.io.File(filePath)) } catch (_: Exception) { return false }
            ?: return false
        val w = img.width; val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)

        val startTile = if (isCre) tg.getCreOffset() else 0
        val numTiles = if (isCre) tg.getCreTileCount() else tg.getVarTileCount()
        val raw4bpp = tg.importTileSheet(pixels, w, startTile, numTiles)

        val b64 = java.util.Base64.getEncoder().encodeToString(raw4bpp)
        if (isCre) {
            project.customGfx.creGfx = b64
            tg.applyCustomCreGfx(raw4bpp)
        } else {
            project.customGfx.varGfx[editorTilesetId.toString()] = b64
            tg.applyCustomVarGfx(raw4bpp)
        }
        dirty = true
        return true
    }

    /** Check whether custom graphics exist for the current tileset. */
    fun hasCustomVarGfx(): Boolean = project.customGfx.varGfx.containsKey(editorTilesetId.toString())
    fun hasCustomCreGfx(): Boolean = project.customGfx.creGfx != null

    private fun writePng(filePath: String, pixels: IntArray, w: Int, h: Int): Boolean {
        return try {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, pixels, 0, w)
            javax.imageio.ImageIO.write(img, "png", java.io.File(filePath))
        } catch (_: Exception) { false }
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

    // ─── Patch management ───────────────────────────────────────

    /** Currently selected patch in the patch editor. */
    var selectedPatchId by mutableStateOf<String?>(null)
        private set

    /** Compose-observable version counter for patch list changes. */
    var patchVersion by mutableStateOf(0)
        private set

    fun selectPatch(id: String?) { selectedPatchId = id }

    fun addPatch(name: String, description: String = ""): SmPatch {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val patch = SmPatch(id = id, name = name, description = description)
        project.patches.add(patch)
        dirty = true; patchVersion++
        selectedPatchId = id
        return patch
    }

    fun removePatch(id: String) {
        project.patches.removeAll { it.id == id }
        if (selectedPatchId == id) selectedPatchId = null
        dirty = true; patchVersion++
    }

    fun togglePatch(id: String) {
        project.patches.find { it.id == id }?.let { it.enabled = !it.enabled }
        dirty = true; patchVersion++
    }

    fun updatePatch(id: String, name: String? = null, description: String? = null, enabled: Boolean? = null) {
        val patch = project.patches.find { it.id == id } ?: return
        if (name != null) patch.name = name
        if (description != null) patch.description = description
        if (enabled != null) patch.enabled = enabled
        dirty = true; patchVersion++
    }

    fun setPatchWrites(id: String, writes: List<SmPatchWrite>) {
        val patch = project.patches.find { it.id == id } ?: return
        patch.writes.clear()
        patch.writes.addAll(writes.map { PatchWrite(it.offset, it.bytes) })
        dirty = true; patchVersion++
    }

    fun setPatchConfigValue(id: String, value: Int) {
        project.patches.find { it.id == id }?.let { it.configValue = value }
        dirty = true; patchVersion++
    }

    // ─── Pattern management ──────────────────────────────────────

    var patternVersion by mutableStateOf(0)
        private set
    var selectedPatternId by mutableStateOf<String?>(null)
        private set
    var activePattern by mutableStateOf<TilePattern?>(null)
        private set

    fun selectPattern(id: String?) {
        selectedPatternId = id
        activePattern = if (id != null) project.patterns.find { it.id == id } else null
    }

    fun addPattern(name: String, cols: Int, rows: Int, tilesetId: Int? = null): TilePattern {
        val id = "pattern_${System.currentTimeMillis()}"
        val cells = MutableList(rows * cols) { PatternCell(0) }
        val pattern = TilePattern(id, name, cols, rows, tilesetId, cells)
        project.patterns.add(pattern)
        dirty = true; patternVersion++
        return pattern
    }

    fun removePattern(id: String) {
        project.patterns.removeAll { it.id == id }
        if (selectedPatternId == id) selectPattern(null)
        dirty = true; patternVersion++
    }

    fun updatePatternCell(patternId: String, r: Int, c: Int, cell: PatternCell) {
        project.patterns.find { it.id == patternId }?.setCell(r, c, cell)
        dirty = true; patternVersion++
    }

    fun renamePattern(id: String, name: String) {
        project.patterns.find { it.id == id }?.name = name
        dirty = true; patternVersion++
    }

    /** Convert a pattern to a TileBrush for placement on the map. */
    fun patternToBrush(pattern: TilePattern, hFlip: Boolean = false, vFlip: Boolean = false): TileBrush {
        val tiles = List(pattern.rows) { r ->
            List(pattern.cols) { c ->
                pattern.getCell(r, c)?.metatile ?: 0
            }
        }
        val btOverrides = mutableMapOf<Long, Int>()
        val btsOverrides = mutableMapOf<Long, Int>()
        val flipOverrides = mutableMapOf<Long, Int>()
        val plmOverrides = mutableMapOf<Long, Pair<Int, Int>>()
        for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
            val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
            val cell = pattern.getCell(r, c) ?: continue
            btOverrides[key] = cell.blockType
            if (cell.bts != 0) btsOverrides[key] = cell.bts
            val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
            if (flipBits != 0) flipOverrides[key] = flipBits
            if (cell.plmId != 0) plmOverrides[key] = Pair(cell.plmId, cell.plmParam)
        }
        return TileBrush(tiles, blockType = 0x8, hFlip = hFlip, vFlip = vFlip,
            blockTypeOverrides = btOverrides, btsOverrides = btsOverrides,
            flipOverrides = flipOverrides, plmOverrides = plmOverrides)
    }

    fun selectAndApplyPattern(id: String) {
        val pattern = project.patterns.find { it.id == id } ?: return
        selectedPatternId = id
        activePattern = pattern
        brush = patternToBrush(pattern)
        activeTool = EditorTool.PAINT
    }

    /** Patterns visible for the current tileset: CRE patterns + current tileset patterns. */
    fun patternsForTileset(tilesetId: Int): List<TilePattern> {
        return project.patterns.filter { it.tilesetId == null || it.tilesetId == tilesetId }
    }

    // ─── Pattern editing (mini room editor for patterns) ────────────

    data class PatternEdit(val r: Int, val c: Int, val old: PatternCell, val new: PatternCell)
    data class PatternOperation(val edits: List<PatternEdit>)

    val patternUndoStack = mutableListOf<PatternOperation>()
    val patternRedoStack = mutableListOf<PatternOperation>()
    private val pendingPatEdits = mutableListOf<PatternEdit>()
    private val pendingPatPositions = mutableSetOf<Long>()

    var patternEditVersion by mutableStateOf(0)
        private set
    var patUndoVersion by mutableStateOf(0)
        private set
    var patHoverX by mutableStateOf(-1)
    var patHoverY by mutableStateOf(-1)

    fun loadPatternForEdit(patternId: String) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        selectedPatternId = patternId
        activePattern = pat
        patternUndoStack.clear()
        patternRedoStack.clear()
        patternEditVersion++; patUndoVersion++
    }

    fun resizePattern(patternId: String, newCols: Int, newRows: Int) {
        val pat = project.patterns.find { it.id == patternId } ?: return
        if (newCols == pat.cols && newRows == pat.rows) return
        val newCells = MutableList(newRows * newCols) { idx ->
            val r = idx / newCols; val c = idx % newCols
            if (r < pat.rows && c < pat.cols) pat.getCell(r, c) ?: PatternCell(0)
            else PatternCell(0)
        }
        val idx = project.patterns.indexOfFirst { it.id == patternId }
        if (idx >= 0) {
            project.patterns[idx] = pat.copy(cols = newCols, rows = newRows, cells = newCells)
            activePattern = project.patterns[idx]
        }
        patternUndoStack.clear(); patternRedoStack.clear()
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patBeginStroke() {
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patPaintAt(bx: Int, by: Int): Boolean {
        val pat = activePattern ?: return false
        val b = brush ?: return false
        var changed = false
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val tx = bx + if (b.hFlip) (b.cols - 1 - c) else c
                val ty = by + if (b.vFlip) (b.rows - 1 - r) else r
                if (tx < 0 || ty < 0 || tx >= pat.cols || ty >= pat.rows) continue
                val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                if (pendingPatPositions.contains(key)) continue
                val oldCell = pat.getCell(ty, tx) ?: PatternCell(0)
                val word = b.blockWordAt(r, c)
                val mt = word and 0x3FF
                val hf = (word shr 10) and 1 != 0
                val vf = (word shr 11) and 1 != 0
                val bt = (word shr 12) and 0xF
                val bts = b.btsAt(r, c)
                val newCell = PatternCell(mt, bt, bts, hf, vf)
                if (oldCell == newCell) continue
                pat.setCell(ty, tx, newCell)
                pendingPatEdits.add(PatternEdit(ty, tx, oldCell, newCell))
                pendingPatPositions.add(key)
                changed = true
            }
        }
        if (changed) patternEditVersion++
        return changed
    }

    fun patEndStroke() {
        if (pendingPatEdits.isEmpty()) return
        val op = PatternOperation(pendingPatEdits.toList())
        patternUndoStack.add(op)
        patternRedoStack.clear()
        dirty = true; patternVersion++; patUndoVersion++
        pendingPatEdits.clear(); pendingPatPositions.clear()
    }

    fun patFloodFill(bx: Int, by: Int) {
        val pat = activePattern ?: return
        val b = brush ?: return
        if (b.rows != 1 || b.cols != 1) return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val targetCell = pat.getCell(by, bx) ?: PatternCell(0)
        val word = b.blockWordAt(0, 0)
        val mt = word and 0x3FF; val hf = (word shr 10) and 1 != 0
        val vf = (word shr 11) and 1 != 0; val bt = (word shr 12) and 0xF
        val bts = b.btsAt(0, 0)
        val fillCell = PatternCell(mt, bt, bts, hf, vf)
        if (targetCell == fillCell) return
        val edits = mutableListOf<PatternEdit>()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(Pair(bx, by))
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            val k = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            if (k in visited) continue
            if (cx < 0 || cy < 0 || cx >= pat.cols || cy >= pat.rows) continue
            val cell = pat.getCell(cy, cx) ?: PatternCell(0)
            if (cell != targetCell) continue
            visited.add(k)
            edits.add(PatternEdit(cy, cx, cell, fillCell))
            pat.setCell(cy, cx, fillCell)
            queue.add(Pair(cx - 1, cy)); queue.add(Pair(cx + 1, cy))
            queue.add(Pair(cx, cy - 1)); queue.add(Pair(cx, cy + 1))
        }
        if (edits.isNotEmpty()) {
            patternUndoStack.add(PatternOperation(edits))
            patternRedoStack.clear()
            dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        }
    }

    fun patSampleTile(bx: Int, by: Int) {
        val pat = activePattern ?: return
        if (bx < 0 || by < 0 || bx >= pat.cols || by >= pat.rows) return
        val cell = pat.getCell(by, bx) ?: return
        val btsMap = if (cell.bts != 0) mapOf(0L to cell.bts) else emptyMap()
        val flipBits = (if (cell.hFlip) 1 else 0) or (if (cell.vFlip) 2 else 0)
        val flipMap = if (flipBits != 0) mapOf(0L to flipBits) else emptyMap()
        brush = TileBrush(
            tiles = listOf(listOf(cell.metatile)),
            blockType = cell.blockType,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
        tilesetSelStart = Pair(cell.metatile % 32, cell.metatile / 32)
        tilesetSelEnd = tilesetSelStart
        activeTool = EditorTool.PAINT
    }

    fun patUndo() {
        if (patternUndoStack.isEmpty()) return
        val pat = activePattern ?: return
        val op = patternUndoStack.removeAt(patternUndoStack.lastIndex)
        for (edit in op.edits.reversed()) {
            pat.setCell(edit.r, edit.c, edit.old)
        }
        patternRedoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
    }

    fun patRedo(): Boolean {
        if (patternRedoStack.isEmpty()) return false
        val pat = activePattern ?: return false
        val op = patternRedoStack.removeAt(patternRedoStack.lastIndex)
        for (edit in op.edits) {
            pat.setCell(edit.r, edit.c, edit.new)
        }
        patternUndoStack.add(op)
        dirty = true; patternVersion++; patternEditVersion++; patUndoVersion++
        return true
    }

    fun patReadCell(bx: Int, by: Int): PatternCell {
        val pat = activePattern ?: return PatternCell(0)
        return pat.getCell(by, bx) ?: PatternCell(0)
    }

    fun patCellWord(bx: Int, by: Int): Int {
        val cell = patReadCell(bx, by)
        var w = cell.metatile and 0x3FF
        if (cell.hFlip) w = w or (1 shl 10)
        if (cell.vFlip) w = w or (1 shl 11)
        w = w or ((cell.blockType and 0xF) shl 12)
        return w
    }

    fun patSetCellProperties(bx: Int, by: Int, blockType: Int, bts: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0)
        val newCell = old.copy(blockType = blockType, bts = bts)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patSetCellPlm(bx: Int, by: Int, plmId: Int, plmParam: Int) {
        val pat = activePattern ?: return
        val old = pat.getCell(by, bx) ?: PatternCell(0)
        val newCell = old.copy(plmId = plmId, plmParam = plmParam)
        if (old == newCell) return
        pat.setCell(by, bx, newCell)
        val edit = PatternEdit(bx, by, old, newCell)
        val op = PatternOperation(listOf(edit))
        patternUndoStack.add(op)
        patternRedoStack.clear()
        patUndoVersion++
        dirty = true
        patternEditVersion++
    }

    fun patRemoveCellPlm(bx: Int, by: Int) {
        patSetCellPlm(bx, by, 0, 0)
    }

    /** Save the current map selection rectangle as a new pattern. */
    fun saveSelectionAsPattern(name: String, isCre: Boolean = true): TilePattern? {
        val s = mapSelStart ?: return null
        val e = mapSelEnd ?: return null
        val minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val cols = maxX - minX + 1
        val rows = maxY - minY + 1
        val cells = mutableListOf<PatternCell>()
        for (by in minY..maxY) {
            for (bx in minX..maxX) {
                val word = readBlockWord(bx, by)
                val metatile = word and 0x3FF
                val hFlip = (word shr 10) and 1 != 0
                val vFlip = (word shr 11) and 1 != 0
                val blockType = (word shr 12) and 0xF
                val bts = readBts(bx, by)
                val plmsAtTile = getPlmsAt(bx, by)
                val plm = plmsAtTile.firstOrNull()
                cells.add(PatternCell(metatile, blockType, bts, hFlip, vFlip,
                    plm?.id ?: 0, plm?.param ?: 0))
            }
        }
        val id = "pattern_${System.currentTimeMillis()}"
        val tsId = if (isCre) null else currentTilesetId
        val pat = TilePattern(id, name, cols, rows, tsId, cells)
        project.patterns.add(pat)
        dirty = true; patternVersion++
        mapSelStart = null; mapSelEnd = null
        return pat
    }

    /**
     * Seed built-in patterns by extracting tile data from known vanilla ROM rooms.
     * Only adds patterns whose IDs don't already exist in the project.
     */
    fun seedBuiltInPatterns(romParser: RomParser?) {
        val builtInIds = listOf(
            "builtin_left_gate", "builtin_right_gate",
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue",
            "builtin_ship"
        )
        val existing = project.patterns.map { it.id }.toSet()
        if (builtInIds.all { it in existing }) return

        if (romParser == null) return

        fun extractRegion(roomId: Int, startX: Int, startY: Int, cols: Int, rows: Int): List<PatternCell> {
            val header = romParser.readRoomHeader(roomId) ?: return emptyList()
            val levelData = romParser.decompressLZ2(header.levelDataPtr)
            val blocksWide = header.width * 16
            val layer1Size = ((levelData[0].toInt() and 0xFF) or
                ((levelData[1].toInt() and 0xFF) shl 8))
            val cells = mutableListOf<PatternCell>()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val bx = startX + c
                    val by = startY + r
                    val off = 2 + (by * blocksWide + bx) * 2
                    if (off + 1 < 2 + layer1Size) {
                        val word = (levelData[off].toInt() and 0xFF) or
                            ((levelData[off + 1].toInt() and 0xFF) shl 8)
                        val metatile = word and 0x3FF
                        val hFlip = (word shr 10) and 1 != 0
                        val vFlip = (word shr 11) and 1 != 0
                        val blockType = (word shr 12) and 0xF
                        val btsOff = 2 + layer1Size + (by * blocksWide + bx)
                        val bts = if (btsOff < levelData.size) levelData[btsOff].toInt() and 0xFF else 0
                        cells.add(PatternCell(metatile, blockType, bts, hFlip, vFlip))
                    } else {
                        cells.add(PatternCell(0))
                    }
                }
            }
            return cells
        }

        fun addBuiltIn(id: String, name: String, cols: Int, rows: Int, cells: List<PatternCell>,
                       tilesetId: Int? = null) {
            if (id in existing) return
            val pat = TilePattern(id, name, cols, rows, tilesetId, cells.toMutableList(), builtIn = true)
            project.patterns.add(pat)
        }

        try {
            // Left Gate: from Crateria room 0x91F8 (Landing Site) - gates are 1x4 CRE
            // Standard left-facing gate cap at a known gate position
            val leftGateCells = listOf(
                PatternCell(0x342, blockType = 0x9, bts = 0x40),
                PatternCell(0x343, blockType = 0x9, bts = 0x40),
                PatternCell(0x343, blockType = 0x9, bts = 0x40),
                PatternCell(0x342, blockType = 0x9, bts = 0x40, vFlip = true)
            )
            addBuiltIn("builtin_left_gate", "Left Gate", 1, 4, leftGateCells)

            val rightGateCells = leftGateCells.map { it.copy(hFlip = !it.hFlip) }
            addBuiltIn("builtin_right_gate", "Right Gate", 1, 4, rightGateCells)

            // Save Station: extract from Crateria Save Room (0x93D5), save platform at ~(3,3) 5x2
            val saveCells = extractRegion(0x93D5, 3, 4, 5, 2)
            if (saveCells.isNotEmpty()) {
                addBuiltIn("builtin_save_station", "Save Station", 5, 2, saveCells)
            }

            // Energy Refill: extract from Crateria energy recharge room (0x9AD9) if present
            val energyCells = extractRegion(0x9AD9, 3, 4, 4, 2)
            if (energyCells.isNotEmpty()) {
                addBuiltIn("builtin_energy_refill", "Energy Refill", 4, 2, energyCells)
            }

            // Missile Refill: extract from Brinstar missile recharge (0x9E11) if present
            val missileCells = extractRegion(0x9E11, 3, 4, 4, 2)
            if (missileCells.isNotEmpty()) {
                addBuiltIn("builtin_missile_refill", "Missile Refill", 4, 2, missileCells)
            }

            // Chozo Statue: extract from first power bomb room (Crateria 0x93AA) ~(7,5) 3x4
            val chozoCells = extractRegion(0x93AA, 7, 3, 3, 4)
            if (chozoCells.isNotEmpty()) {
                addBuiltIn("builtin_chozo_statue", "Chozo Statue", 3, 4, chozoCells)
            }

            // Ship: extract from Landing Site (0x91F8), ship is around (39,3) 8x3
            val shipCells = extractRegion(0x91F8, 39, 3, 8, 3)
            if (shipCells.isNotEmpty()) {
                addBuiltIn("builtin_ship", "Ship", 8, 3, shipCells)
            }
        } catch (e: Exception) {
            println("Failed to seed some built-in patterns: ${e.message}")
        }

        dirty = true
        patternVersion++
    }

    fun getSelectedPatch(): SmPatch? = project.patches.find { it.id == selectedPatchId }

    /** Ensure all default patches exist; loads bundled IPS + hardcoded hex demos. */
    fun seedDefaultPatches() {
        // Remove legacy/duplicate patches from old configs
        val removed = project.patches.removeAll { it.id in LEGACY_PATCH_IDS }
        if (removed) {
            if (selectedPatchId in LEGACY_PATCH_IDS) selectedPatchId = null
            dirty = true
            patchVersion++
        }

        val existingIds = project.patches.map { it.id }.toSet()
        var added = 0

        // Load bundled IPS patches from resources/patches/
        try {
            for (patch in PatchRepository.loadBundledPatches()) {
                if (patch.id !in existingIds) {
                    project.patches.add(patch)
                    added++
                }
            }
        } catch (e: Exception) {
            println("Failed to load bundled patches: ${e.message}")
        }

        // Add hardcoded hex-tweak demos
        for (def in HARDCODED_PATCHES) {
            if (def.id !in existingIds) {
                project.patches.add(def.copy(writes = def.writes.toMutableList()))
                added++
            }
        }

        // Add GUI config patches
        if (CERES_ESCAPE_PATCH.id !in existingIds) {
            project.patches.add(SmPatch(
                id = CERES_ESCAPE_PATCH.id,
                name = CERES_ESCAPE_PATCH.name,
                description = CERES_ESCAPE_PATCH.description,
                enabled = CERES_ESCAPE_PATCH.enabled,
                writes = mutableListOf(),
                configType = CERES_ESCAPE_PATCH.configType,
                configValue = CERES_ESCAPE_PATCH.configValue
            ))
            added++
        }

        if (added > 0) { dirty = true; patchVersion++ }
    }

    // ─── Tile selection ─────────────────────────────────────────

    fun selectMetatile(index: Int, gridCols: Int = 32) {
        tilesetSelStart = Pair(index % gridCols, index / gridCols)
        tilesetSelEnd = tilesetSelStart
        mapSelStart = null
        mapSelEnd = null
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
        mapSelStart = null
        mapSelEnd = null
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

    fun flipOrCaptureH() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
        toggleHFlip()
    }
    fun flipOrCaptureV() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
        toggleVFlip()
    }
    fun rotateOrCapture() {
        if (activeTool == EditorTool.SELECT && mapSelStart != null && mapSelEnd != null) captureMapSelection()
        rotateClockwise()
    }

    fun rotateClockwise() {
        val b = brush ?: return
        val oldTiles = b.tiles
        val oldRowCount = oldTiles.size
        if (oldRowCount == 0) return
        val newTiles = oldTiles[0].indices.map { c ->
            oldTiles.indices.reversed().map { r -> oldTiles[r][c] }
        }

        fun remapKeys(old: Map<Long, Int>): Map<Long, Int> = buildMap {
            for ((key, value) in old) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                put((newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL), value)
            }
        }

        val newFlipOverrides = buildMap {
            for ((key, value) in b.flipOverrides) {
                val oldR = (key shr 32).toInt()
                val oldC = (key and 0xFFFFFFFFL).toInt()
                val newR = oldC
                val newC = oldRowCount - 1 - oldR
                val newKey = (newR.toLong() shl 32) or (newC.toLong() and 0xFFFFFFFFL)
                val h = value and 1
                val v = (value shr 1) and 1
                put(newKey, v or (h shl 1))
            }
        }

        brush = b.copy(
            tiles = newTiles,
            hFlip = !b.vFlip,
            vFlip = b.hFlip,
            blockTypeOverrides = remapKeys(b.blockTypeOverrides),
            btsOverrides = remapKeys(b.btsOverrides),
            flipOverrides = newFlipOverrides
        )
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

    /** Convert the current map selection rectangle into a multi-tile brush. */
    fun captureMapSelection() {
        val s = mapSelStart ?: return
        val e = mapSelEnd ?: return
        val minX = minOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val maxX = maxOf(s.first, e.first).coerceIn(0, workingBlocksWide - 1)
        val minY = minOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        val maxY = maxOf(s.second, e.second).coerceIn(0, workingBlocksTall - 1)
        if (minX == maxX && minY == maxY) {
            sampleTile(minX, minY)
            return
        }
        val rows = mutableListOf<List<Int>>()
        val btsMap = mutableMapOf<Long, Int>()
        val btMap = mutableMapOf<Long, Int>()
        val flipMap = mutableMapOf<Long, Int>()
        for (by in minY..maxY) {
            val row = mutableListOf<Int>()
            for (bx in minX..maxX) {
                val word = readBlockWord(bx, by)
                row.add(word and 0x3FF)
                val r = by - minY
                val c = bx - minX
                val key = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                val tileH = (word shr 10) and 1
                val tileV = (word shr 11) and 1
                if (tileH != 0 || tileV != 0) {
                    flipMap[key] = tileH or (tileV shl 1)
                }
                val bt = (word shr 12) and 0xF
                if (r != 0 || c != 0) btMap[key] = bt
                val bts = readBts(bx, by)
                if (bts != 0) btsMap[key] = bts
            }
            rows.add(row)
        }
        val primaryBt = (readBlockWord(minX, minY) shr 12) and 0xF
        brush = TileBrush(
            tiles = rows,
            blockType = primaryBt,
            blockTypeOverrides = btMap,
            btsOverrides = btsMap,
            flipOverrides = flipMap
        )
        mapSelStart = null
        mapSelEnd = null
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

    internal fun initTestLevel(blocksWide: Int, blocksTall: Int) {
        val totalTiles = blocksWide * blocksTall
        val layer1Bytes = totalTiles * 2
        workingLevelData = ByteArray(2 + layer1Bytes + totalTiles).also {
            it[0] = (layer1Bytes and 0xFF).toByte()
            it[1] = ((layer1Bytes shr 8) and 0xFF).toByte()
        }
        workingBlocksWide = blocksWide
        workingBlocksTall = blocksTall
        undoStack.clear()
        redoStack.clear()
        undoVersion++
    }

    internal fun setBrushForTest(b: TileBrush?) { brush = b }

    // ─── Working level data ─────────────────────────────────────

    fun loadRoom(roomId: Int, romParser: RomParser, room: com.supermetroid.editor.data.Room) {
        currentRoomId = roomId
        currentTilesetId = room.tileset
        val tg = TileGraphics(romParser)
        if (tg.loadTileset(room.tileset)) {
            applyCustomGfxToTileGraphics(tg, room.tileset)
            tileGraphics = tg
        }
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
        undoVersion++
        pendingEdits.clear()
        pendingPositions.clear()

        // Load PLMs for this room
        _workingPlms.clear()
        val plms = romParser.parsePlmSet(room.plmSetPtr)
        _workingPlms.addAll(plms)
        originalPlmCount = plms.size

        // Parse door entries for this room
        doorEntries = romParser.parseDoorList(room.doorOut)

        // Load enemies for this room
        _workingEnemies.clear()
        _workingEnemies.addAll(romParser.parseEnemyPopulation(room.enemySetPtr))

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
                undoVersion++
                println("Replayed $count saved edits for room 0x$roomKey")
            }
            // Replay saved PLM changes
            for (change in savedRoom.plmChanges) {
                when (change.action) {
                    "add" -> _workingPlms.add(RomParser.PlmEntry(change.plmId, change.x, change.y, change.param))
                    "remove" -> _workingPlms.removeAll { it.id == change.plmId && it.x == change.x && it.y == change.y }
                }
            }
            // Replay saved door changes (last change per index wins)
            for (dc in savedRoom.doorChanges) {
                if (dc.doorIndex in 0 until _workingDoors.size) {
                    _workingDoors[dc.doorIndex] = RomParser.DoorEntry(
                        destRoomPtr = dc.destRoomPtr,
                        bitflag = dc.bitflag,
                        doorCapCode = dc.doorCapCode,
                        screenX = dc.screenX,
                        screenY = dc.screenY,
                        distFromDoor = dc.distFromDoor,
                        entryCode = dc.entryCode
                    )
                }
            }
            // Replay saved enemy changes (including extra fields)
            for (ec in savedRoom.enemyChanges) {
                when (ec.action) {
                    "add" -> _workingEnemies.add(
                        RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                            ec.extra1, ec.extra2, ec.extra3)
                    )
                    "remove" -> _workingEnemies.removeAll {
                        it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                    }
                    "update" -> {
                        val idx = _workingEnemies.indexOfFirst {
                            it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                        }
                        if (idx >= 0) {
                            _workingEnemies[idx] = RomParser.EnemyEntry(
                                ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3
                            )
                        }
                    }
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
                // Apply pattern PLMs if present
                val plm = b.plmAt(r, c)
                if (plm != null && plm.first != 0) {
                    addPlm(plm.first, tx, ty, plm.second)
                }
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
        undoVersion++
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

    // ─── Enemy editing ────────────────────────────────────────

    fun getEnemiesNear(pixelX: Int, pixelY: Int, radius: Int = 16): List<RomParser.EnemyEntry> =
        _workingEnemies.filter { kotlin.math.abs(it.x - pixelX) < radius && kotlin.math.abs(it.y - pixelY) < radius }

    fun addEnemy(enemyId: Int, pixelX: Int, pixelY: Int, initParam: Int = 0, properties: Int = 0x0800) {
        val entry = RomParser.EnemyEntry(enemyId, pixelX, pixelY, initParam, properties)
        _workingEnemies.add(entry)
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(
            EnemyChange("add", enemyId, pixelX, pixelY, initParam, properties)
        )
        dirty = true
        editVersion++
    }

    fun removeEnemy(enemy: RomParser.EnemyEntry) {
        _workingEnemies.removeAll { it.id == enemy.id && it.x == enemy.x && it.y == enemy.y }
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(
            EnemyChange("remove", enemy.id, enemy.x, enemy.y, enemy.initParam, enemy.properties,
                enemy.extra1, enemy.extra2, enemy.extra3, origX = enemy.x, origY = enemy.y)
        )
        dirty = true
        editVersion++
    }

    fun updateEnemy(old: RomParser.EnemyEntry, new: RomParser.EnemyEntry) {
        val idx = _workingEnemies.indexOfFirst { it.id == old.id && it.x == old.x && it.y == old.y }
        if (idx < 0) return
        _workingEnemies[idx] = new
        project.getOrCreateRoom(currentRoomId).enemyChanges.add(
            EnemyChange("update", new.id, new.x, new.y, new.initParam, new.properties,
                new.extra1, new.extra2, new.extra3, origX = old.x, origY = old.y)
        )
        dirty = true
        editVersion++
    }

    // ─── Door editing ──────────────────────────────────────────

    fun updateDoor(index: Int, entry: RomParser.DoorEntry) {
        if (index < 0 || index >= _workingDoors.size) return
        _workingDoors[index] = entry
        val roomEdits = project.getOrCreateRoom(currentRoomId)
        val dc = DoorChange(
            doorIndex = index,
            destRoomPtr = entry.destRoomPtr,
            bitflag = entry.bitflag,
            doorCapCode = entry.doorCapCode,
            screenX = entry.screenX,
            screenY = entry.screenY,
            distFromDoor = entry.distFromDoor,
            entryCode = entry.entryCode
        )
        roomEdits.doorChanges.removeAll { it.doorIndex == index }
        roomEdits.doorChanges.add(dc)
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
            EditorTool.SELECT -> "Select"
        }
        val op = EditOperation(desc, pendingEdits.toList())
        undoStack.add(op)
        redoStack.clear()
        undoVersion++
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
        undoVersion++
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
        undoVersion++
        project.getOrCreateRoom(currentRoomId).operations.add(op)
        dirty = true
        editVersion++
        return true
    }

    // ─── Project file I/O ───────────────────────────────────────

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Save project to .smedit file. When romParser is provided, also export
     * custom tileset graphics as PNGs to a project-specific folder
     * ({romBase}_smedit/) so different ROMs don't overwrite each other.
     */
    fun saveProject(romParser: RomParser? = null): Boolean {
        if (projectFilePath.isEmpty()) return false
        return try {
            File(projectFilePath).writeText(json.encodeToString(SmEditProject.serializer(), project))
            dirty = false
            println("Project saved: $projectFilePath")
            romParser?.let { exportCustomGfxPngs(it) }
            true
        } catch (e: Exception) { println("Save failed: ${e.message}"); false }
    }

    /** Export custom tileset graphics as PNGs to project folder. Folder is per-ROM so projects don't overwrite each other. */
    private fun exportCustomGfxPngs(romParser: RomParser) {
        val gfx = project.customGfx
        val hasVar = gfx.varGfx.isNotEmpty()
        val hasCre = gfx.creGfx != null
        if (!hasVar && !hasCre) return
        val projectFile = File(projectFilePath)
        val folder = File(projectFile.parentFile, "${projectFile.nameWithoutExtension}_smedit")
        folder.mkdirs()
        val tg = TileGraphics(romParser)
        for ((tilesetId, b64) in gfx.varGfx) {
            if (!tg.loadTileset(tilesetId.toIntOrNull() ?: continue)) continue
            try {
                tg.applyCustomVarGfx(java.util.Base64.getDecoder().decode(b64))
                val result = tg.renderTileSheet(0, tg.getVarTileCount())
                if (result != null) {
                    val (pixels, w, h) = result
                    val out = File(folder, "ure_$tilesetId.png")
                    if (writePng(out.absolutePath, pixels, w, h)) println("Exported $out")
                }
            } catch (_: Exception) {}
        }
        if (hasCre && gfx.creGfx != null) {
            if (tg.loadTileset(0)) {
                try {
                    tg.applyCustomCreGfx(java.util.Base64.getDecoder().decode(gfx.creGfx))
                    val result = tg.renderTileSheet(tg.getCreOffset(), tg.getCreTileCount())
                    if (result != null) {
                        val (pixels, w, h) = result
                        val out = File(folder, "cre.png")
                        if (writePng(out.absolutePath, pixels, w, h)) println("Exported $out")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ─── Export: patch ROM ──────────────────────────────────────

    fun exportToRom(romParser: RomParser): String? {
        val romPath = project.romPath
        if (romPath.isEmpty()) return null
        val romData = romParser.getRomData().copyOf()
        var patchedRooms = 0

        // Free space allocator for bank $8F (PLM sets live here).
        val bank8FEnd = romParser.snesToPc(0x8FFFFF) + 1
        val bank8FStart = romParser.snesToPc(0x8F8000)
        var freePtr = bank8FEnd
        while (freePtr > bank8FStart) {
            val b = romData[freePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            freePtr--
        }
        freePtr++

        // Free space allocator for bank $A1 (enemy population sets).
        val bankA1End = romParser.snesToPc(0xA1FFFF) + 1
        val bankA1Start = romParser.snesToPc(0xA18000)
        var enemyFreePtr = bankA1End
        while (enemyFreePtr > bankA1Start) {
            val b = romData[enemyFreePtr - 1].toInt() and 0xFF
            if (b != 0xFF) break
            enemyFreePtr--
        }
        enemyFreePtr++

        // Free space tracker for level data banks ($C0-$CE).
        // Each bank is scanned from the end to find trailing 0xFF padding.
        val levelBankFree = mutableMapOf<Int, Int>()  // bank -> next free PC offset
        fun getLevelBankFreePtr(bank: Int): Int {
            return levelBankFree.getOrPut(bank) {
                val bankEnd = romParser.snesToPc((bank shl 16) or 0xFFFF) + 1
                val bankStart = romParser.snesToPc((bank shl 16) or 0x8000)
                var ptr = bankEnd
                while (ptr > bankStart) {
                    if ((romData[ptr - 1].toInt() and 0xFF) != 0xFF) break
                    ptr--
                }
                ptr + 1
            }
        }

        for ((roomKey, roomEdits) in project.rooms) {
            val hasTileEdits = roomEdits.operations.isNotEmpty()
            val hasPlmEdits = roomEdits.plmChanges.isNotEmpty()
            val hasDoorEdits = roomEdits.doorChanges.isNotEmpty()
            val hasEnemyEdits = roomEdits.enemyChanges.isNotEmpty()
            if (!hasTileEdits && !hasPlmEdits && !hasDoorEdits && !hasEnemyEdits) continue
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
                } else {
                    // Relocate: find free space, trying same bank first, then $CE-$C0
                    val origBank = (room.levelDataPtr shr 16) and 0xFF
                    val banksToTry = listOf(origBank) +
                            (0xCE downTo 0xC0).filter { it != origBank }
                    var relocated = false
                    for (tryBank in banksToTry) {
                        val bEnd = romParser.snesToPc((tryBank shl 16) or 0xFFFF) + 1
                        val freeStart = getLevelBankFreePtr(tryBank)
                        if (freeStart + compressed.size <= bEnd) {
                            System.arraycopy(compressed, 0, romData, freeStart, compressed.size)
                            val newSnes = romParser.pcToSnes(freeStart)
                            levelBankFree[tryBank] = freeStart + compressed.size

                            val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                            var updatedStates = 0
                            for (stateOffset in allStateOffsets) {
                                val existingPtr = (romData[stateOffset].toInt() and 0xFF) or
                                        ((romData[stateOffset + 1].toInt() and 0xFF) shl 8) or
                                        ((romData[stateOffset + 2].toInt() and 0xFF) shl 16)
                                if (existingPtr == room.levelDataPtr) {
                                    romData[stateOffset] = (newSnes and 0xFF).toByte()
                                    romData[stateOffset + 1] = ((newSnes shr 8) and 0xFF).toByte()
                                    romData[stateOffset + 2] = ((newSnes shr 16) and 0xFF).toByte()
                                    updatedStates++
                                }
                            }
                            for (i in pcOff until pcOff + origSize) romData[i] = 0xFF.toByte()
                            println("Room 0x$roomKey: relocated level data to \$${tryBank.toString(16).uppercase()}:${(newSnes and 0xFFFF).toString(16).uppercase()} (${compressed.size} bytes, updated $updatedStates/${allStateOffsets.size} states)")
                            patchedRooms++
                            relocated = true
                            break
                        }
                    }
                    if (!relocated) {
                        println("WARN: Room 0x$roomKey compressed ${compressed.size} > orig $origSize and no free space in any bank — skipped tiles")
                    }
                }
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

            // Patch door entries (last change per index wins)
            if (hasDoorEdits && room.doorOut != 0 && room.doorOut != 0xFFFF) {
                val byIndex = roomEdits.doorChanges.groupBy { it.doorIndex }
                for ((doorIndex, changes) in byIndex) {
                    val dc = changes.last()
                    val entryPc = romParser.doorEntryPcOffset(room.doorOut, doorIndex) ?: continue
                    if (entryPc + 11 >= romData.size) continue
                    romData[entryPc] = (dc.destRoomPtr and 0xFF).toByte()
                    romData[entryPc + 1] = ((dc.destRoomPtr shr 8) and 0xFF).toByte()
                    romData[entryPc + 2] = (dc.bitflag and 0xFF).toByte()
                    romData[entryPc + 3] = ((dc.bitflag shr 8) and 0xFF).toByte()
                    romData[entryPc + 4] = (dc.doorCapCode and 0xFF).toByte()
                    romData[entryPc + 5] = ((dc.doorCapCode shr 8) and 0xFF).toByte()
                    romData[entryPc + 6] = (dc.screenX and 0xFF).toByte()
                    romData[entryPc + 7] = (dc.screenY and 0xFF).toByte()
                    romData[entryPc + 8] = (dc.distFromDoor and 0xFF).toByte()
                    romData[entryPc + 9] = ((dc.distFromDoor shr 8) and 0xFF).toByte()
                    romData[entryPc + 10] = (dc.entryCode and 0xFF).toByte()
                    romData[entryPc + 11] = ((dc.entryCode shr 8) and 0xFF).toByte()
                }
                patchedRooms++
            }

            // Patch enemy population
            if (hasEnemyEdits && room.enemySetPtr != 0 && room.enemySetPtr != 0xFFFF) {
                val originalEnemies = romParser.parseEnemyPopulation(room.enemySetPtr)
                val modified = originalEnemies.toMutableList()
                for (ec in roomEdits.enemyChanges) {
                    when (ec.action) {
                        "add" -> modified.add(
                            RomParser.EnemyEntry(ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3)
                        )
                        "remove" -> modified.removeAll {
                            it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                        }
                        "update" -> {
                            val idx = modified.indexOfFirst {
                                it.id == ec.enemyId && it.x == ec.origX && it.y == ec.origY
                            }
                            if (idx >= 0) modified[idx] = RomParser.EnemyEntry(
                                ec.enemyId, ec.x, ec.y, ec.initParam, ec.properties,
                                ec.extra1, ec.extra2, ec.extra3
                            )
                        }
                    }
                }
                // +2 for the FFFF terminator word
                val originalSize = originalEnemies.size * 16 + 2
                val newSize = modified.size * 16 + 2
                val enemyPc = romParser.snesToPc(0xA10000 or room.enemySetPtr)

                val writePc: Int
                if (newSize <= originalSize) {
                    writePc = enemyPc
                } else if (enemyFreePtr + newSize <= bankA1End) {
                    writePc = enemyFreePtr
                    enemyFreePtr += newSize
                    val allStateOffsets = romParser.findAllStateDataOffsets(roomId)
                    val newSnes = romParser.pcToSnes(writePc)
                    val newPtr = newSnes and 0xFFFF
                    for (stateOffset in allStateOffsets) {
                        val existingPtr = (romData[stateOffset + 8].toInt() and 0xFF) or
                                ((romData[stateOffset + 9].toInt() and 0xFF) shl 8)
                        if (existingPtr == room.enemySetPtr) {
                            romData[stateOffset + 8] = (newPtr and 0xFF).toByte()
                            romData[stateOffset + 9] = ((newPtr shr 8) and 0xFF).toByte()
                        }
                    }
                    println("Room 0x$roomKey: relocated enemy set to 0x${newSnes.toString(16)}")
                } else {
                    println("WARN: Room 0x$roomKey no free space for expanded enemy set — skipped")
                    continue
                }

                fun writeU16(offset: Int, value: Int) {
                    romData[offset] = (value and 0xFF).toByte()
                    romData[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                var off = writePc
                for (e in modified) {
                    writeU16(off, e.id)
                    writeU16(off + 2, e.x)
                    writeU16(off + 4, e.y)
                    writeU16(off + 6, e.initParam)
                    writeU16(off + 8, e.properties)
                    writeU16(off + 10, e.extra1)
                    writeU16(off + 12, e.extra2)
                    writeU16(off + 14, e.extra3)
                    off += 16
                }
                // Terminator: 0xFFFF at Species position
                writeU16(off, 0xFFFF)
                off += 2
                // Zero any leftover space if writing in-place
                if (writePc == enemyPc) {
                    while (off < enemyPc + originalSize) { romData[off] = 0; off++ }
                }
                patchedRooms++
            }
        }
        // Apply enabled patches (hex write operations + config patches)
        var patchesApplied = 0
        for (patch in project.patches) {
            if (!patch.enabled) continue
            if (patch.configType == "ceres_escape_seconds") {
                val totalSecs = (patch.configValue ?: 60).coerceIn(15, 300)
                val mins = totalSecs / 60
                val secs = totalSecs % 60
                val secsBcd = ((secs / 10) shl 4) or (secs % 10)
                val minsBcd = ((mins / 10) shl 4) or (mins % 10)
                val off = romParser.snesToPc(CERES_TIMER_OPERAND_SNES)
                if (off + 1 < romData.size) {
                    romData[off] = secsBcd.toByte()
                    romData[off + 1] = minsBcd.toByte()
                }
            } else {
                for (write in patch.writes) {
                    val off = write.offset.toInt()
                    for ((i, b) in write.bytes.withIndex()) {
                        if (off + i < romData.size) romData[off + i] = b.toByte()
                    }
                }
            }
            patchesApplied++
        }

        // Apply custom tileset graphics
        var gfxPatched = 0
        val gfxData = project.customGfx

        // Custom CRE graphics (shared, always at $B9:8000)
        val creB64 = gfxData.creGfx
        if (creB64 != null) {
            try {
                val rawCre = java.util.Base64.getDecoder().decode(creB64)
                val compressed = lz5Compress(rawCre)
                val crePc = romParser.snesToPc(TileGraphics.CRE_GFX_SNES)
                val (_, origSize) = romParser.decompressLZ2WithSize(TileGraphics.CRE_GFX_SNES)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, crePc, compressed.size)
                    for (i in compressed.size until origSize) romData[crePc + i] = 0xFF.toByte()
                    gfxPatched++
                    println("Patched CRE graphics in-place (${compressed.size}/$origSize bytes)")
                } else {
                    println("WARN: Compressed CRE gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { println("WARN: CRE gfx patch failed: ${e.message}") }
        }

        // Custom variable (URE) graphics per tileset
        val tablePC = romParser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        for ((tsIdStr, varB64) in gfxData.varGfx) {
            val tsId = tsIdStr.toIntOrNull() ?: continue
            try {
                val rawVar = java.util.Base64.getDecoder().decode(varB64)
                val compressed = lz5Compress(rawVar)
                val entryOffset = tablePC + tsId * 9
                val gfxSnes = (romData[entryOffset + 3].toInt() and 0xFF) or
                        ((romData[entryOffset + 4].toInt() and 0xFF) shl 8) or
                        ((romData[entryOffset + 5].toInt() and 0xFF) shl 16)
                val gfxPc = romParser.snesToPc(gfxSnes)
                val (_, origSize) = romParser.decompressLZ2WithSize(gfxSnes)
                if (compressed.size <= origSize) {
                    System.arraycopy(compressed, 0, romData, gfxPc, compressed.size)
                    for (i in compressed.size until origSize) romData[gfxPc + i] = 0xFF.toByte()
                    gfxPatched++
                    println("Patched tileset $tsId variable gfx in-place (${compressed.size}/$origSize bytes)")
                } else {
                    println("WARN: Compressed tileset $tsId gfx (${compressed.size}) exceeds original ($origSize) — skipped")
                }
            } catch (e: Exception) { println("WARN: Tileset $tsId gfx patch failed: ${e.message}") }
        }

        if (patchedRooms == 0 && patchesApplied == 0 && gfxPatched == 0) return null
        val orig = File(romPath)
        val out = File(orig.parent, "${orig.nameWithoutExtension}_edited.${orig.extension}")
        out.writeBytes(romData)
        println("Exported: ${out.absolutePath} ($patchedRooms rooms, $patchesApplied patches, $gfxPatched gfx)")
        return out.absolutePath
    }

    private fun lz5Compress(data: ByteArray) = LZ5Compressor.compress(data)
}
