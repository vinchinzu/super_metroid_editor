package com.supermetroid.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.ui.DraggableDividerHorizontal
import com.supermetroid.editor.ui.DraggableDividerVertical
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import java.awt.FileDialog
import java.awt.Frame
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.WindowConfig
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.ui.TilesetPreview
import com.supermetroid.editor.ui.TilesetListPanel
import com.supermetroid.editor.ui.TilesetCanvas
import com.supermetroid.editor.ui.TilesetEditorState
import com.supermetroid.editor.ui.PatchListPanel
import com.supermetroid.editor.ui.PatchEditorCanvas
import com.supermetroid.editor.ui.PatternListPanel
import com.supermetroid.editor.ui.PatternEditorCanvas
import com.supermetroid.editor.ui.PatternThumbnailList
import com.supermetroid.editor.ui.SoundListPanel
import com.supermetroid.editor.ui.SoundEditorCanvas
import com.supermetroid.editor.ui.SoundEditorState
import com.supermetroid.editor.ui.EnemySpriteViewer
import com.supermetroid.editor.ui.EmulatorWorkspace
import com.supermetroid.editor.ui.EmulatorWorkspaceState
import com.supermetroid.editor.ui.PhantoonSpriteEditor
import com.supermetroid.editor.ui.KraidSpriteEditor
import com.supermetroid.editor.ui.LocalSwingWindow
import com.supermetroid.editor.data.RomPreferences
import com.supermetroid.editor.ui.EditorState
import com.supermetroid.editor.ui.RoomPropertiesPanel
import androidx.compose.ui.input.key.*
import java.io.File

fun main() = application {
    val launchConfig = remember { EditorLaunchConfig.fromEnvironment() }
    val roomRepository = remember { RoomRepository() }
    var romParser by remember { mutableStateOf<RomParser?>(null) }
    var romFileName by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    val editorState = remember { EditorState() }

    fun pickDefaultRoom(allRooms: List<RoomInfo>, romPath: String): RoomInfo? {
        val romKey = File(romPath).name
        val lastRoomId = AppConfig.load().lastRoomPerRom[romKey]
        if (lastRoomId != null) {
            val found = allRooms.firstOrNull { it.id == lastRoomId }
            if (found != null) return found
        }
        return allRooms
            .filter { it.handle != "debugRoom" }
            .minByOrNull { it.getRoomIdAsInt() }
    }

    fun saveLastRoom(romPath: String, room: RoomInfo) {
        val romKey = File(romPath).name
        AppConfig.update {
            copy(lastRoomPerRom = lastRoomPerRom + (romKey to room.id))
        }
    }

    // Load rooms on startup
    LaunchedEffect(Unit) {
        rooms = roomRepository.getAllRooms()

        // Auto-load requested ROM first, then fall back to last ROM if available.
        val bootRomPath = launchConfig.romPath ?: RomPreferences.getLastRomPath()
        if (bootRomPath != null) {
            try {
                romParser = RomParser.loadRom(bootRomPath)
                romFileName = File(bootRomPath).nameWithoutExtension
                RomPreferences.setLastRomPath(bootRomPath)
                editorState.initForRom(bootRomPath)
                if (selectedRoom == null) {
                    selectedRoom = rooms.firstOrNull { it.handle == launchConfig.roomHandle }
                        ?: pickDefaultRoom(rooms, bootRomPath)
                }
            } catch (e: Exception) {
                println("Failed to auto-load ROM: ${e.message}")
            }
        }
    }
    
    val appSettings = remember { AppConfig.load() }
    val windowState = rememberWindowState(
        width = appSettings.window.width.dp,
        height = appSettings.window.height.dp,
        position = if (appSettings.window.x >= 0 && appSettings.window.y >= 0)
            WindowPosition(appSettings.window.x.dp, appSettings.window.y.dp)
        else WindowPosition.PlatformDefault
    )

    Window(
        onCloseRequest = {
            AppConfig.update {
                copy(window = WindowConfig(
                    x = windowState.position.x.value.toInt(),
                    y = windowState.position.y.value.toInt(),
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt()
                ))
            }
            exitApplication()
        },
        state = windowState,
        title = "Super Metroid Editor",
        icon = painterResource("app_icon.png"),
        onPreviewKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.S &&
                (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)) {
                editorState.saveProject(romParser)
                true
            } else false
        }
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSwingWindow provides window) {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                // Top bar: Open ROM + status (left) | Save + Export (right)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val fileDialog = FileDialog(null as Frame?, "Open Super Metroid ROM", FileDialog.LOAD)
                                fileDialog.setFilenameFilter { _, name ->
                                    name.endsWith(".smc", ignoreCase = true) || 
                                    name.endsWith(".sfc", ignoreCase = true)
                                }
                                fileDialog.isVisible = true
                                val selectedFile = fileDialog.file
                                if (selectedFile != null) {
                                    val file = File(fileDialog.directory, selectedFile)
                                    try {
                                        romParser = RomParser.loadRom(file.absolutePath)
                                        romFileName = file.nameWithoutExtension
                                        RomPreferences.setLastRomPath(file.absolutePath)
                                        editorState.initForRom(file.absolutePath)
                                        selectedRoom = pickDefaultRoom(rooms, file.absolutePath)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                        ) { Text("Open ROM...") }
                        if (romFileName != null) {
                            Text("Loaded: $romFileName", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Right side: Save + Export
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { editorState.saveProject(romParser) },
                            enabled = romParser != null
                        ) { Text(if (editorState.dirty) "Save*" else "Save") }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToRom(it) }
                            },
                            enabled = romParser != null
                        ) { Text("Export ROM") }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToIps(it) }
                            },
                            enabled = romParser != null
                        ) { Text("Export IPS") }
                    }
                }
                
                // Main content: resizable left column + right canvas
                var leftColumnWidthDp by remember { mutableStateOf(280f) }
                var tilesetHeightDp by remember { mutableStateOf(400f) }
                var leftTab by remember { mutableStateOf(if (launchConfig.openEmulatorWorkspace) 5 else 0) } // 0 = Rooms, 1 = Tilesets, 2 = Patches, 3 = Sound, 4 = Sprites
                var selectedSpriteIdx by remember { mutableStateOf(0) }
                val tilesetEditorState = remember { TilesetEditorState() }
                val soundEditorState = remember { SoundEditorState() }
                val emulatorWorkspaceState = remember { EmulatorWorkspaceState() }
                var bottomPaneTab by remember { mutableStateOf(0) } // 0 = Tileset, 1 = Patterns (in Rooms bottom pane)
                var tilesetSubTab by remember { mutableStateOf(0) } // 0 = Tilesets, 1 = Patterns (in Tilesets left column)
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val maxLeftWidth = maxWidth.value - 100f
                    if (leftTab == 5) {
                        EmulatorWorkspace(
                            room = selectedRoom,
                            rooms = rooms,
                            romParser = romParser,
                            editorState = editorState,
                            workspaceState = emulatorWorkspaceState,
                            launchConfig = launchConfig,
                            onRoomSelected = { room ->
                                selectedRoom = room
                                val romPath = RomPreferences.getLastRomPath()
                                if (romPath != null) saveLastRoom(romPath, room)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        return@BoxWithConstraints
                    }
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // ── Left column ──
                        Column(
                            modifier = Modifier
                                .width(leftColumnWidthDp.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            TabRow(
                                selectedTabIndex = leftTab,
                                modifier = Modifier.fillMaxWidth().height(32.dp)
                            ) {
                                Tab(selected = leftTab == 0, onClick = { leftTab = 0 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Rooms", fontSize = 11.sp)
                                }
                                Tab(selected = leftTab == 1, onClick = { leftTab = 1 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Tiles", fontSize = 11.sp)
                                }
                                Tab(selected = leftTab == 2, onClick = {
                                    leftTab = 2
                                    editorState.seedDefaultPatches()
                                }, modifier = Modifier.height(32.dp)) {
                                    Text("Patches", fontSize = 11.sp)
                                }
                                Tab(selected = leftTab == 3, onClick = { leftTab = 3 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Sound", fontSize = 11.sp)
                                }
                                Tab(selected = leftTab == 4, onClick = { leftTab = 4 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Sprites", fontSize = 11.sp)
                                }
                                Tab(selected = leftTab == 5, onClick = { leftTab = 5 },
                                    modifier = Modifier.height(32.dp)) {
                                    Text("Emu", fontSize = 11.sp)
                                }
                            }

                            key(leftTab) {
                            when (leftTab) {
                                0 -> {
                                    // Top: room list
                                    RoomListView(
                                        rooms = rooms,
                                        selectedRoom = selectedRoom,
                                        romParser = romParser,
                                        editorState = editorState,
                                        onRoomSelected = { room ->
                                            selectedRoom = room
                                            val romPath = RomPreferences.getLastRomPath()
                                            if (romPath != null) saveLastRoom(romPath, room)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    DraggableDividerHorizontal(
                                        onDelta = { dy ->
                                            tilesetHeightDp = (tilesetHeightDp - dy).coerceIn(120f, 700f)
                                        }
                                    )
                                    // Bottom: sub-tabs [Tileset | Patterns]
                                    Column(
                                        modifier = Modifier.fillMaxWidth().height(tilesetHeightDp.dp)
                                    ) {
                                        TabRow(
                                            selectedTabIndex = bottomPaneTab,
                                            modifier = Modifier.fillMaxWidth().height(26.dp)
                                        ) {
                                            Tab(selected = bottomPaneTab == 0, onClick = { bottomPaneTab = 0 },
                                                modifier = Modifier.height(26.dp)) {
                                                Text("Tileset", fontSize = 10.sp)
                                            }
                                            Tab(selected = bottomPaneTab == 1, onClick = {
                                                bottomPaneTab = 1
                                                editorState.seedBuiltInPatterns(romParser)
                                            }, modifier = Modifier.height(26.dp)) {
                                                Text("Patterns", fontSize = 10.sp)
                                            }
                                            Tab(selected = bottomPaneTab == 2, onClick = { bottomPaneTab = 2 },
                                                modifier = Modifier.height(26.dp)) {
                                                Text("Room Info", fontSize = 10.sp)
                                            }
                                        }
                                        key(bottomPaneTab) {
                                        when (bottomPaneTab) {
                                            0 -> TilesetPreview(
                                                room = selectedRoom,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            1 -> PatternThumbnailList(
                                                editorState = editorState,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            2 -> {
                                                val rp = romParser
                                                val sr = selectedRoom
                                                if (rp != null && sr != null) {
                                                    val roomHeader = remember(sr) { rp.readRoomHeader(sr.getRoomIdAsInt()) }
                                                    if (roomHeader != null) {
                                                        RoomPropertiesPanel(
                                                            room = roomHeader,
                                                            romParser = rp,
                                                            editorState = editorState,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Text("Could not parse room header", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                                        }
                                                    }
                                                } else {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text("Select a room", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }
                                        }
                                        }
                                    }
                                }
                                1 -> {
                                    // Tilesets tab: sub-tabs [Tilesets | Patterns]
                                    TabRow(
                                        selectedTabIndex = tilesetSubTab,
                                        modifier = Modifier.fillMaxWidth().height(26.dp)
                                    ) {
                                        Tab(selected = tilesetSubTab == 0, onClick = { tilesetSubTab = 0 },
                                            modifier = Modifier.height(26.dp)) {
                                            Text("Tilesets", fontSize = 10.sp)
                                        }
                                        Tab(selected = tilesetSubTab == 1, onClick = {
                                            tilesetSubTab = 1
                                            editorState.seedBuiltInPatterns(romParser)
                                        }, modifier = Modifier.height(26.dp)) {
                                            Text("Patterns", fontSize = 10.sp)
                                        }
                                    }
                                    key(tilesetSubTab) {
                                    when (tilesetSubTab) {
                                        0 -> TilesetListPanel(
                                            romParser = romParser,
                                            editorState = editorState,
                                            tilesetEditorState = tilesetEditorState,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        1 -> {
                                            PatternListPanel(
                                                editorState = editorState,
                                                modifier = Modifier.weight(1f)
                                            )
                                            DraggableDividerHorizontal(
                                                onDelta = { dy ->
                                                    tilesetHeightDp = (tilesetHeightDp - dy).coerceIn(120f, 700f)
                                                }
                                            )
                                            TilesetPreview(
                                                room = selectedRoom,
                                                romParser = romParser,
                                                editorState = editorState,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(tilesetHeightDp.dp)
                                            )
                                        }
                                    }
                                    }
                                }
                                2 -> {
                                    PatchListPanel(
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                3 -> {
                                    SoundListPanel(
                                        romParser = romParser,
                                        editorState = editorState,
                                        soundEditorState = soundEditorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                4 -> {
                                    val entries = com.supermetroid.editor.rom.EnemySpriteGraphics.EDITOR_ENEMIES
                                    val grouped = entries.groupBy { it.category }
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(8.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        for ((category, items) in grouped) {
                                            Text(category, fontSize = 12.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(2.dp))
                                            for (entry in items) {
                                                val idx = entries.indexOf(entry)
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .clickable { selectedSpriteIdx = idx },
                                                    color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.primaryContainer
                                                            else MaterialTheme.colorScheme.surface,
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(entry.name, fontSize = 11.sp,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                        color = if (selectedSpriteIdx == idx) MaterialTheme.colorScheme.onPrimaryContainer
                                                                else MaterialTheme.colorScheme.onSurface)
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
                            }
                            }
                        }

                        DraggableDividerVertical(
                            onDelta = { dx ->
                                leftColumnWidthDp = (leftColumnWidthDp + dx).coerceIn(150f, maxLeftWidth)
                            }
                        )

                        // ── Right canvas ──
                        key(leftTab, tilesetSubTab, selectedSpriteIdx) {
                        when (leftTab) {
                            0 -> MapCanvas(
                                room = selectedRoom,
                                romParser = romParser,
                                editorState = editorState,
                                rooms = rooms,
                                modifier = Modifier.fillMaxSize()
                            )
                            1 -> {
                                if (tilesetSubTab == 1) {
                                    PatternEditorCanvas(
                                        editorState = editorState,
                                        romParser = romParser,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    TilesetCanvas(
                                        romParser = romParser,
                                        editorState = editorState,
                                        tilesetEditorState = tilesetEditorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            2 -> PatchEditorCanvas(
                                editorState = editorState,
                                romParser = romParser,
                                modifier = Modifier.fillMaxSize()
                            )
                            3 -> SoundEditorCanvas(
                                romParser = romParser,
                                editorState = editorState,
                                soundEditorState = soundEditorState,
                                modifier = Modifier.fillMaxSize()
                            )
                            4 -> {
                                val entries = com.supermetroid.editor.rom.EnemySpriteGraphics.EDITOR_ENEMIES
                                val selected = entries.getOrNull(selectedSpriteIdx) ?: entries.first()
                                if (selected.speciesId == 0xE4BF) {
                                    PhantoonSpriteEditor(
                                        editorState = editorState,
                                        romParser = romParser,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (selected.speciesId == 0xE2BF) {
                                    KraidSpriteEditor(
                                        editorState = editorState,
                                        romParser = romParser,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    EnemySpriteViewer(
                                        entry = selected,
                                        romParser = romParser,
                                        editorState = editorState,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        }
    }
}
