package com.supermetroid.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Frame
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.RoomListView
import com.supermetroid.editor.ui.MapCanvas
import com.supermetroid.editor.data.RomPreferences
import java.io.File

fun main() = application {
    val roomRepository = remember { RoomRepository() }
    var romParser by remember { mutableStateOf<RomParser?>(null) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    
    // Load rooms on startup
    LaunchedEffect(Unit) {
        rooms = roomRepository.getAllRooms()
        
        // Auto-load last ROM if available
        val lastRomPath = RomPreferences.getLastRomPath()
        if (lastRomPath != null) {
            try {
                romParser = RomParser.loadRom(lastRomPath)
            } catch (e: Exception) {
                println("Failed to auto-load ROM: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Super Metroid Editor"
    ) {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp)
            ) {
                // Menu bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    // Save ROM path for auto-loading next time
                                    RomPreferences.setLastRomPath(file.absolutePath)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    ) {
                        Text("Open ROM...")
                    }
                    
                    if (romParser != null) {
                        Text(
                            "ROM Loaded",
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
                
                // Main content
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Room list (left side)
                    RoomListView(
                        rooms = rooms,
                        selectedRoom = selectedRoom,
                        onRoomSelected = { room ->
                            selectedRoom = room
                        },
                        modifier = Modifier.width(300.dp).fillMaxHeight()
                    )
                    
                    // Map canvas (right side)
                    MapCanvas(
                        room = selectedRoom,
                        romParser = romParser,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
