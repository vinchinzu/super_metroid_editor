package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

private val AREA_NAMES = arrayOf("Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres")

private val CRE_BITFLAG_NAMES = mapOf(
    0x00 to "Default",
    0x01 to "Black out during transition",
    0x02 to "Reload CRE tiles",
    0x05 to "Disable CRE tiles",
)

private val MUSIC_TRACK_NAMES = mapOf(
    0x00 to "No change",
    0x03 to "No music",
    0x05 to "Song 1",
    0x06 to "Song 2",
    0x09 to "Song 3",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPropertiesPanel(
    room: Room,
    romParser: RomParser,
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
    val scrollsPtr = stateData["roomScrollsPtr"] ?: room.roomScrollsPtr
    val scrollData = remember(scrollsPtr, room.width, room.height) {
        romParser.parseScrollData(scrollsPtr, room.width, room.height)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Room Header ──
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

        // ── State Data Pointers ──
        SectionHeader("State Data")
        val tileset = stateData["tileset"] ?: room.tileset
        val musicData = stateData["musicData"] ?: room.musicData
        val musicTrack = stateData["musicTrack"] ?: room.musicTrack
        val levelDataPtr = stateData["levelDataPtr"] ?: room.levelDataPtr
        val bgScrolling = stateData["bgScrolling"] ?: room.bgScrolling
        val mainAsmPtr = stateData["mainAsmPtr"] ?: room.mainAsmPtr
        val setupAsmPtr = stateData["setupAsmPtr"] ?: room.setupAsmPtr
        val bgDataPtr = stateData["bgDataPtr"] ?: room.bgDataPtr
        val enemySetPtr = stateData["enemySetPtr"] ?: room.enemySetPtr
        val enemyGfxPtr = stateData["enemyGfxPtr"] ?: room.enemyGfxPtr
        val plmSetPtr = stateData["plmSetPtr"] ?: room.plmSetPtr

        PropertyRow("Tileset", "$tileset")
        val trackName = SpcData.KNOWN_TRACKS.firstOrNull { it.songSet == musicData && it.playIndex == musicTrack }?.name
        PropertyRow("Music Set", "0x${musicData.toString(16).uppercase().padStart(2, '0')}")
        PropertyRow("Play Index", "0x${musicTrack.toString(16).uppercase().padStart(2, '0')}" +
                (if (trackName != null) " — $trackName" else (MUSIC_TRACK_NAMES[musicTrack]?.let { " — $it" } ?: "")))
        PropertyRow("Level Data", snesAddr24(levelDataPtr))
        PropertyRow("BG Scrolling", hex16(bgScrolling))
        PropertyRow("BG Data Ptr", if (bgDataPtr == 0) "None (layer 2 in level data)" else "\$8F:${bgDataPtr.toString(16).uppercase().padStart(4, '0')}")
        val enemyCount = remember(enemySetPtr) { romParser.parseEnemyPopulation(enemySetPtr).size }
        val plmCount = remember(plmSetPtr) { romParser.parsePlmSet(plmSetPtr).size }
        PropertyRow("PLM Set", "\$8F:${plmSetPtr.toString(16).uppercase().padStart(4, '0')} ($plmCount PLMs)")
        PropertyRow("Enemy Set", "\$A1:${enemySetPtr.toString(16).uppercase().padStart(4, '0')} ($enemyCount enemies)")
        PropertyRow("Enemy GFX", "\$B4:${enemyGfxPtr.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Main ASM", if (mainAsmPtr == 0) "None" else "\$8F:${mainAsmPtr.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Setup ASM", if (setupAsmPtr == 0) "None" else "\$8F:${setupAsmPtr.toString(16).uppercase().padStart(4, '0')}")

        Spacer(modifier = Modifier.height(4.dp))

        // ── FX Data ──
        SectionHeader("FX Data")
        if (fxEntries.isEmpty()) {
            Text("No FX data", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        } else {
            for ((fxIdx, fx) in fxEntries.withIndex()) {
                if (fxEntries.size > 1) {
                    Text(
                        if (fx.doorSelect == 0) "Default FX" else "Door-Specific FX (door \$${fx.doorSelect.toString(16).uppercase()})",
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (fxIdx > 0) 4.dp else 0.dp)
                    )
                }
                PropertyRow("FX Type", fx.fxTypeName)
                if (fx.hasLiquid) {
                    PropertyRow("Liquid Start", hex16(fx.liquidSurfaceStart))
                    PropertyRow("Liquid Target", hex16(fx.liquidSurfaceNew))
                    PropertyRow("Liquid Speed", hex16(fx.liquidSpeed))
                    PropertyRow("Liquid Delay", "0x${fx.liquidDelay.toString(16).uppercase().padStart(2, '0')}")
                }
                PropertyRow("FX Transparency A", hex8(fx.fxBitA) + when (fx.fxBitA) {
                    0x02 -> " (normal)"
                    0x28 -> " (dark visor)"
                    0x2A -> " (darker visor)"
                    else -> ""
                })
                PropertyRow("FX Transparency B", hex8(fx.fxBitB) + when (fx.fxBitB) {
                    0x02 -> " (default)"
                    0x14 -> " (subtract/darken)"
                    0x18 -> " (over everything)"
                    else -> ""
                })

                // Liquid options (bitC)
                val activeOptions = RomParser.FxEntry.LIQUID_OPTION_NAMES.filter { (bit, _) -> fx.fxBitC and bit != 0 }
                if (activeOptions.isNotEmpty()) {
                    PropertyRow("Liquid Options", activeOptions.values.joinToString(", "))
                }

                // Animated tiles
                val animBits = fx.tileAnimBitflags
                val animList = buildList {
                    if (animBits and 0x01 != 0) add("Spikes (H)")
                    if (animBits and 0x02 != 0) add("Spikes (V)")
                    if (animBits and 0x04 != 0) add("Ocean/Sand")
                    if (animBits and 0x08 != 0) add("Lava/Sandfall")
                }
                if (animList.isNotEmpty()) {
                    PropertyRow("Animated Tiles", animList.joinToString(", "))
                }

                // Palette options
                val palBits = fx.paletteFxBitflags
                if (palBits != 0) {
                    val palList = (0..7).filter { palBits and (1 shl it) != 0 }.map { "Pal ${it + 1}" }
                    PropertyRow("Palette FX", palList.joinToString(", "))
                }

                if (fx.paletteBlend != 0) {
                    PropertyRow("Palette Blend", hex8(fx.paletteBlend))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Scroll Data ──
        SectionHeader("Room Scrolls")
        PropertyRow("Scrolls Ptr", when (scrollsPtr) {
            0x0000 -> "All Blue (\$0000)"
            0x0001 -> "All Green (\$0001)"
            else -> "\$8F:${scrollsPtr.toString(16).uppercase().padStart(4, '0')}"
        })

        if (scrollData.isNotEmpty()) {
            ScrollGrid(scrollData, room.width, room.height)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

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
private fun ScrollGrid(scrollData: IntArray, width: Int, height: Int) {
    val scrollColors = mapOf(
        0x00 to Color(0xFFCC3030),   // Red
        0x01 to Color(0xFF3060CC),   // Blue
        0x02 to Color(0xFF30AA40),   // Green
    )
    val scrollLabels = mapOf(0x00 to "R", 0x01 to "B", 0x02 to "G")

    Column(modifier = Modifier.padding(top = 4.dp)) {
        for (row in 0 until height) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until width) {
                    val idx = row * width + col
                    val scrollVal = scrollData.getOrElse(idx) { 0x01 }
                    val bgColor = scrollColors[scrollVal] ?: Color.Gray
                    val label = scrollLabels[scrollVal] ?: "?"
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(bgColor, MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, label) in listOf(0x00 to "Red (hidden)", 0x01 to "Blue (explorable)", 0x02 to "Green (show floor)")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(scrollColors[code]!!, MaterialTheme.shapes.extraSmall))
                    Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
