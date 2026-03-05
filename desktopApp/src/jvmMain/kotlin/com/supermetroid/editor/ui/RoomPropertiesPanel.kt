package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

private val AREA_NAMES = arrayOf("Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres")

private val CRE_BITFLAG_NAMES = mapOf(
    0x00 to "Default",
    0x01 to "Black out during transition",
    0x02 to "Reload CRE tiles",
    0x05 to "Disable CRE tiles",
)


private val FX_TYPE_OPTIONS = listOf(
    0x00 to "None",
    0x02 to "Fog",
    0x04 to "Water",
    0x06 to "Lava",
    0x08 to "Acid",
    0x0A to "Rain",
    0x0C to "Spores",
    0x0E to "Haze",
    0x10 to "Dense Fog",
    0x16 to "Firefleas",
    0x18 to "Lightning",
    0x1A to "Smoke",
    0x1C to "Heat Shimmer",
    0x24 to "BG3 Transparent",
    0x26 to "Sandstorm",
    0x28 to "Dark Visor",
    0x2A to "Darker Visor",
    0x2C to "Black",
)

private val SCROLL_COLORS = mapOf(
    0x00 to Color(0xFFCC3030),
    0x01 to Color(0xFF3060CC),
    0x02 to Color(0xFF30AA40),
)
private val SCROLL_LABELS = mapOf(0x00 to "R", 0x01 to "B", 0x02 to "G")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPropertiesPanel(
    room: Room,
    romParser: RomParser,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val states = remember(room.roomId) { romParser.parseRoomStates(room.roomId) }
    var selectedStateIdx by remember(room.roomId) { mutableStateOf(states.size - 1) }
    val currentState = states.getOrNull(selectedStateIdx)
    val stateData = remember(currentState) {
        currentState?.let { romParser.readStateData(it.stateDataPcOffset) } ?: emptyMap()
    }
    val fxPtr = stateData["fxPtr"] ?: room.fxPtr
    val fxEntries = remember(fxPtr) { romParser.parseFxEntries(fxPtr) }
    val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }

    // Use working scrolls from EditorState (includes edits)
    val scrollVer = editorState.scrollVersion
    val scrollData = remember(scrollVer, room.roomId) { editorState.workingScrolls.copyOf() }

    // Track FX edit state locally, sync to EditorState
    val roomEdits = editorState.project.rooms[editorState.project.roomKey(room.roomId)]
    val savedFx = roomEdits?.fxChange
    val savedState = roomEdits?.stateDataChange

    // FX edit state
    var editFxType by remember(room.roomId) { mutableStateOf(savedFx?.fxType ?: defaultFx?.fxType ?: 0) }
    var editLiquidStart by remember(room.roomId) { mutableStateOf(savedFx?.liquidSurfaceStart ?: defaultFx?.liquidSurfaceStart ?: 0xFFFF) }
    var editLiquidNew by remember(room.roomId) { mutableStateOf(savedFx?.liquidSurfaceNew ?: defaultFx?.liquidSurfaceNew ?: 0xFFFF) }
    var editLiquidSpeed by remember(room.roomId) { mutableStateOf(savedFx?.liquidSpeed ?: defaultFx?.liquidSpeed ?: 0) }
    var editLiquidDelay by remember(room.roomId) { mutableStateOf(savedFx?.liquidDelay ?: defaultFx?.liquidDelay ?: 0) }
    var editFxBitA by remember(room.roomId) { mutableStateOf(savedFx?.fxBitA ?: defaultFx?.fxBitA ?: 0x02) }
    var editFxBitB by remember(room.roomId) { mutableStateOf(savedFx?.fxBitB ?: defaultFx?.fxBitB ?: 0x02) }
    var editPaletteBlend by remember(room.roomId) { mutableStateOf(savedFx?.paletteBlend ?: defaultFx?.paletteBlend ?: 0) }

    // State data edit state
    val origTileset = stateData["tileset"] ?: room.tileset
    val origMusicData = stateData["musicData"] ?: room.musicData
    val origMusicTrack = stateData["musicTrack"] ?: room.musicTrack
    val origBgScrolling = stateData["bgScrolling"] ?: room.bgScrolling
    var editTileset by remember(room.roomId) { mutableStateOf(savedState?.tileset ?: origTileset) }
    var editMusicData by remember(room.roomId) { mutableStateOf(savedState?.musicData ?: origMusicData) }
    var editMusicTrack by remember(room.roomId) { mutableStateOf(savedState?.musicTrack ?: origMusicTrack) }
    var editBgScrolling by remember(room.roomId) { mutableStateOf(savedState?.bgScrolling ?: origBgScrolling) }

    fun syncFxToState() {
        val change = FxChange(
            fxType = editFxType.takeIf { it != (defaultFx?.fxType ?: 0) },
            liquidSurfaceStart = editLiquidStart.takeIf { it != (defaultFx?.liquidSurfaceStart ?: 0xFFFF) },
            liquidSurfaceNew = editLiquidNew.takeIf { it != (defaultFx?.liquidSurfaceNew ?: 0xFFFF) },
            liquidSpeed = editLiquidSpeed.takeIf { it != (defaultFx?.liquidSpeed ?: 0) },
            liquidDelay = editLiquidDelay.takeIf { it != (defaultFx?.liquidDelay ?: 0) },
            fxBitA = editFxBitA.takeIf { it != (defaultFx?.fxBitA ?: 0x02) },
            fxBitB = editFxBitB.takeIf { it != (defaultFx?.fxBitB ?: 0x02) },
            paletteBlend = editPaletteBlend.takeIf { it != (defaultFx?.paletteBlend ?: 0) },
        )
        if (change == FxChange()) {
            editorState.project.getOrCreateRoom(room.roomId).fxChange = null
        } else {
            editorState.setFxChange(change)
        }
    }

    fun syncStateDataToState() {
        val change = StateDataChange(
            tileset = editTileset.takeIf { it != origTileset },
            musicData = editMusicData.takeIf { it != origMusicData },
            musicTrack = editMusicTrack.takeIf { it != origMusicTrack },
            bgScrolling = editBgScrolling.takeIf { it != origBgScrolling },
        )
        if (change == StateDataChange()) {
            editorState.project.getOrCreateRoom(room.roomId).stateDataChange = null
        } else {
            editorState.setStateDataChange(change)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Room Header (read-only) ──
        SectionHeader("Room Header")
        PropertyRow("Room ID", "0x${room.roomId.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Area", "${room.area} — ${AREA_NAMES.getOrElse(room.area) { "Unknown" }}")
        PropertyRow("Map Position", "(${room.mapX}, ${room.mapY})")
        PropertyRow("Dimensions", "${room.width} x ${room.height} screens")
        PropertyRow("Room Index", "0x${room.index.toString(16).uppercase().padStart(2, '0')}")
        PropertyRow("Up Scroller", "0x${room.upScroller.toString(16).uppercase().padStart(2, '0')} " +
                when (room.upScroller) {
                    0x70 -> "(default)"
                    0x90 -> "(grapple rooms)"
                    0x99 -> "(ascent rooms)"
                    else -> ""
                })
        PropertyRow("Down Scroller", "0x${room.downScroller.toString(16).uppercase().padStart(2, '0')} " +
                if (room.downScroller == 0xA0) "(default)" else "")
        PropertyRow("CRE Bitflag", "0x${room.creBitflag.toString(16).uppercase().padStart(2, '0')} — " +
                (CRE_BITFLAG_NAMES[room.creBitflag] ?: "Custom"))
        PropertyRow("Door Out Ptr", "\$8F:${room.doorOut.toString(16).uppercase().padStart(4, '0')}")

        Spacer(modifier = Modifier.height(4.dp))

        // ── Room States ──
        SectionHeader("Room States (${states.size})")
        if (states.size > 1) {
            var stateDropExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                        .clickable { stateDropExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            states.getOrNull(selectedStateIdx)?.conditionName ?: "?",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", fontSize = 10.sp)
                    }
                }
                DropdownMenu(expanded = stateDropExpanded, onDismissRequest = { stateDropExpanded = false }) {
                    for ((idx, state) in states.withIndex()) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButton(selected = selectedStateIdx == idx, onClick = null, modifier = Modifier.size(16.dp))
                                    Text(state.conditionName, fontSize = 11.sp)
                                }
                            },
                            onClick = { stateDropExpanded = false; selectedStateIdx = idx },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        } else if (states.size == 1) {
            Text(states[0].conditionName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("No states found", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── State Data (editable fields) ──
        SectionHeader("State Data")
        val levelDataPtr = stateData["levelDataPtr"] ?: room.levelDataPtr
        val mainAsmPtr = stateData["mainAsmPtr"] ?: room.mainAsmPtr
        val setupAsmPtr = stateData["setupAsmPtr"] ?: room.setupAsmPtr
        val bgDataPtr = stateData["bgDataPtr"] ?: room.bgDataPtr
        val enemySetPtr = stateData["enemySetPtr"] ?: room.enemySetPtr
        val enemyGfxPtr = stateData["enemyGfxPtr"] ?: room.enemyGfxPtr
        val plmSetPtr = stateData["plmSetPtr"] ?: room.plmSetPtr

        // Editable: Tileset
        EditableIntRow("Tileset", editTileset, 0, 29) { editTileset = it; syncStateDataToState() }

        // Editable: Music
        MusicDropdown(
            musicData = editMusicData,
            musicTrack = editMusicTrack,
            onMusicChange = { data, track ->
                editMusicData = data
                editMusicTrack = track
                syncStateDataToState()
            }
        )

        // Read-only pointers
        PropertyRow("Level Data", snesAddr24(levelDataPtr))

        // Editable: BG Scrolling
        EditableHexRow("BG Scrolling", editBgScrolling, 2) { editBgScrolling = it; syncStateDataToState() }

        PropertyRow("BG Data Ptr", if (bgDataPtr == 0) "None (layer 2 in level data)" else "\$8F:${bgDataPtr.toString(16).uppercase().padStart(4, '0')}")
        val enemyCount = remember(enemySetPtr) { romParser.parseEnemyPopulation(enemySetPtr).size }
        val plmCount = remember(plmSetPtr) { romParser.parsePlmSet(plmSetPtr).size }
        PropertyRow("PLM Set", "\$8F:${plmSetPtr.toString(16).uppercase().padStart(4, '0')} ($plmCount PLMs)")
        PropertyRow("Enemy Set", "\$A1:${enemySetPtr.toString(16).uppercase().padStart(4, '0')} ($enemyCount enemies)")
        PropertyRow("Enemy GFX", "\$B4:${enemyGfxPtr.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Main ASM", if (mainAsmPtr == 0) "None" else "\$8F:${mainAsmPtr.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Setup ASM", if (setupAsmPtr == 0) "None" else "\$8F:${setupAsmPtr.toString(16).uppercase().padStart(4, '0')}")

        Spacer(modifier = Modifier.height(4.dp))

        // ── FX Data (editable) ──
        SectionHeader("FX Data")
        if (defaultFx == null && fxEntries.isEmpty()) {
            Text("No FX data", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        } else {
            // Show door-specific entries as read-only
            for (fx in fxEntries) {
                if (fx.doorSelect != 0) {
                    Text(
                        "Door-Specific FX (door \$${fx.doorSelect.toString(16).uppercase()})",
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    PropertyRow("FX Type", fx.fxTypeName)
                    if (fx.hasLiquid) {
                        PropertyRow("Liquid Start", hex16(fx.liquidSurfaceStart))
                        PropertyRow("Liquid Target", hex16(fx.liquidSurfaceNew))
                    }
                }
            }

            // Default FX — editable
            if (defaultFx != null) {
                if (fxEntries.size > 1) {
                    Text("Default FX", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }

                // FX Type dropdown
                FxTypeDropdown(editFxType) { editFxType = it; syncFxToState() }

                // Liquid properties
                val isLiquid = editFxType in listOf(0x04, 0x06, 0x08, 0x12, 0x14)
                if (isLiquid) {
                    EditableHexRow("Liquid Start", editLiquidStart, 2) { editLiquidStart = it; syncFxToState() }
                    EditableHexRow("Liquid Target", editLiquidNew, 2) { editLiquidNew = it; syncFxToState() }
                    EditableHexRow("Liquid Speed", editLiquidSpeed, 2) { editLiquidSpeed = it; syncFxToState() }
                    EditableHexRow("Liquid Delay", editLiquidDelay, 1) { editLiquidDelay = it; syncFxToState() }
                }

                // Transparency
                EditableHexRow("FX Trans. A", editFxBitA, 1) { editFxBitA = it; syncFxToState() }
                EditableHexRow("FX Trans. B", editFxBitB, 1) { editFxBitB = it; syncFxToState() }

                // Palette blend
                EditableHexRow("Palette Blend", editPaletteBlend, 1) { editPaletteBlend = it; syncFxToState() }

                // Read-only complex fields
                val animBits = defaultFx.tileAnimBitflags
                val animList = buildList {
                    if (animBits and 0x01 != 0) add("Spikes (H)")
                    if (animBits and 0x02 != 0) add("Spikes (V)")
                    if (animBits and 0x04 != 0) add("Ocean/Sand")
                    if (animBits and 0x08 != 0) add("Lava/Sandfall")
                }
                if (animList.isNotEmpty()) {
                    PropertyRow("Animated Tiles", animList.joinToString(", "))
                }
                val palBits = defaultFx.paletteFxBitflags
                if (palBits != 0) {
                    val palList = (0..7).filter { palBits and (1 shl it) != 0 }.map { "Pal ${it + 1}" }
                    PropertyRow("Palette FX", palList.joinToString(", "))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Scroll Data (editable) ──
        SectionHeader("Room Scrolls")
        val scrollsPtr = stateData["roomScrollsPtr"] ?: room.roomScrollsPtr
        PropertyRow("Scrolls Ptr", when (scrollsPtr) {
            0x0000 -> "All Blue (\$0000)"
            0x0001 -> "All Green (\$0001)"
            else -> "\$8F:${scrollsPtr.toString(16).uppercase().padStart(4, '0')}"
        })

        if (scrollData.isNotEmpty()) {
            EditableScrollGrid(scrollData, room.width, room.height) { col, row, newVal ->
                editorState.setScroll(col, row, newVal, room.width)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── Music Dropdown ────────────────────────────────────────────────

private data class MusicOption(
    val songSet: Int,
    val playIndex: Int,
    val label: String
)

private val MUSIC_OPTIONS: List<MusicOption> by lazy {
    val options = mutableListOf(
        MusicOption(0x00, 0x00, "No change"),
        MusicOption(0x00, 0x03, "No music (silence)"),
    )
    for (track in SpcData.KNOWN_TRACKS) {
        val hexLabel = String.format("%02X:%02X", track.songSet, track.playIndex)
        val area = if (track.area.isNotEmpty()) " [${track.area}]" else ""
        options.add(MusicOption(track.songSet, track.playIndex, "${track.name}$area ($hexLabel)"))
    }
    options
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicDropdown(
    musicData: Int,
    musicTrack: Int,
    onMusicChange: (Int, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = MUSIC_OPTIONS.firstOrNull { it.songSet == musicData && it.playIndex == musicTrack }
    val displayText = current?.label ?: String.format("Custom (%02X:%02X)", musicData, musicTrack)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Music", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .clickable { expanded = true },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.requiredSizeIn(maxHeight = 400.dp)
            ) {
                for (option in MUSIC_OPTIONS) {
                    val isSelected = option.songSet == musicData && option.playIndex == musicTrack
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onMusicChange(option.songSet, option.playIndex)
                            expanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

// ── Shared UI Components ──────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
    Divider()
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EditableHexRow(
    label: String,
    value: Int,
    byteCount: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    val hexDigits = byteCount * 2
    val maxVal = (1 shl (byteCount * 8)) - 1
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(hexDigits, '0')) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    val filtered = newText.uppercase().filter { it in "0123456789ABCDEF" }.take(hexDigits)
                    text = filtered
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull(16) ?: value
                    onValueChange(parsed.coerceIn(0, maxVal))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = 9.sp) }
        } else {
            Text(
                "0x${value.toString(16).uppercase().padStart(hexDigits, '0')}$suffix",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
        }
    }
}

@Composable
private fun EditableIntRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText -> text = newText.filter { it.isDigit() }.take(4) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull() ?: value
                    onValueChange(parsed.coerceIn(min, max))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = 9.sp) }
        } else {
            Text(
                "$value",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FxTypeDropdown(selectedType: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val typeName = FX_TYPE_OPTIONS.firstOrNull { it.first == selectedType }?.second ?: "Unknown (${hex8(selectedType)})"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("FX Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(24.dp).clickable { expanded = true },
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(typeName, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("▾", fontSize = 9.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((code, name) in FX_TYPE_OPTIONS) {
                    DropdownMenuItem(
                        text = { Text("${hex8(code)} — $name", fontSize = 10.sp) },
                        onClick = { expanded = false; onSelect(code) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableScrollGrid(
    scrollData: IntArray,
    width: Int,
    height: Int,
    onScrollChange: (col: Int, row: Int, newValue: Int) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text("Click to cycle: Blue → Green → Red → Blue", fontSize = 8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp))
        for (row in 0 until height) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until width) {
                    val idx = row * width + col
                    val scrollVal = scrollData.getOrElse(idx) { 0x01 }
                    val bgColor = SCROLL_COLORS[scrollVal] ?: Color.Gray
                    val label = SCROLL_LABELS[scrollVal] ?: "?"
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(bgColor, MaterialTheme.shapes.extraSmall)
                            .clickable {
                                val next = when (scrollVal) {
                                    0x01 -> 0x02  // Blue → Green
                                    0x02 -> 0x00  // Green → Red
                                    else -> 0x01  // Red → Blue
                                }
                                onScrollChange(col, row, next)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, lbl) in listOf(0x00 to "Red (hidden)", 0x01 to "Blue (explorable)", 0x02 to "Green (PLM-gated)")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(SCROLL_COLORS[code]!!, MaterialTheme.shapes.extraSmall))
                    Text(lbl, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun hex8(v: Int) = "0x${v.toString(16).uppercase().padStart(2, '0')}"
private fun hex16(v: Int) = "0x${v.toString(16).uppercase().padStart(4, '0')}"
private fun snesAddr24(v: Int): String {
    val bank = (v shr 16) and 0xFF
    val addr = v and 0xFFFF
    return "\$${bank.toString(16).uppercase().padStart(2, '0')}:${addr.toString(16).uppercase().padStart(4, '0')}"
}
