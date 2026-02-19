package com.supermetroid.editor

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
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Frame
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.ui.TilesetPreview
import com.supermetroid.editor.ui.LocalSwingWindow
import com.supermetroid.editor.data.RomPreferences
import com.supermetroid.editor.ui.EditorState
import java.io.File

fun main() = application {
    val roomRepository = remember { RoomRepository() }
    var romParser by remember { mutableStateOf<RomParser?>(null) }
    var romFileName by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    val editorState = remember { EditorState() }
    
    // Load rooms on startup
    LaunchedEffect(Unit) {
        rooms = roomRepository.getAllRooms()
        
        // Auto-load last ROM if available
        val lastRomPath = RomPreferences.getLastRomPath()
        if (lastRomPath != null) {
            try {
                romParser = RomParser.loadRom(lastRomPath)
                romFileName = File(lastRomPath).nameWithoutExtension
                editorState.initForRom(lastRomPath)
            } catch (e: Exception) {
                println("Failed to auto-load ROM: ${e.message}")
            }
        }
    }
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Super Metroid Editor"
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
                            onClick = { editorState.saveProject() },
                            enabled = romParser != null
                        ) { Text(if (editorState.dirty) "Save*" else "Save") }
                        Button(
                            onClick = {
                                romParser?.let { editorState.exportToRom(it) }
                            },
                            enabled = romParser != null
                        ) { Text("Export ROM") }
                    }
                }
                
                // Main content: resizable left column + map
                var leftColumnWidthDp by remember { mutableStateOf(280f) }
                var tilesetHeightDp by remember { mutableStateOf(400f) }  // 2x default height
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val maxLeftWidth = maxWidth.value - 100f
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(leftColumnWidthDp.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            RoomListView(
                                rooms = rooms,
                                selectedRoom = selectedRoom,
                                romParser = romParser,
                                onRoomSelected = { room -> selectedRoom = room },
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
                        DraggableDividerVertical(
                            onDelta = { dx ->
                                leftColumnWidthDp = (leftColumnWidthDp + dx).coerceIn(150f, maxLeftWidth)
                            }
                        )
                        MapCanvas(
                            room = selectedRoom,
                            romParser = romParser,
                            editorState = editorState,
                            rooms = rooms,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        }
    }
}
