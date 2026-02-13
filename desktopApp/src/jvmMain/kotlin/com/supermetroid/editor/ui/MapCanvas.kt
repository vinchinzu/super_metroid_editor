package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import java.awt.image.BufferedImage

@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Text(
                text = room?.name ?: "No room selected",
                style = MaterialTheme.typography.titleLarge
            )
            
            if (room != null) {
                Text(
                    text = "Room ID: ${room.id}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (romParser != null) {
                    // Render room map
                    var isLoading by remember(room?.id) { mutableStateOf(false) }
                    var errorMessage by remember(room?.id) { mutableStateOf<String?>(null) }
                    var renderResult by remember(room?.id) { mutableStateOf<com.supermetroid.editor.rom.RoomRenderData?>(null) }
                    
                    // Load and render room when it changes
                    LaunchedEffect(room?.id) {
                        if (room != null) {
                            isLoading = true
                            errorMessage = null
                            renderResult = null
                            
                            try {
                                val roomId = room.getRoomIdAsInt()
                                println("Looking for room ID: 0x${roomId.toString(16)} (${room.name})")
                                
                                val roomHeader = romParser.readRoomHeader(roomId)
                                
                                if (roomHeader != null) {
                                    println("Found room header: Index=${roomHeader.index}, Area=${roomHeader.area}, Size=${roomHeader.width}x${roomHeader.height}")
                                    println("  bgData=0x${(0x8F0000 + roomHeader.bgData).toString(16)}, roomState=0x${(0x8F0000 + roomHeader.roomState).toString(16)}")
                                    
                                    val mapRenderer = MapRenderer(romParser)
                                    val result = mapRenderer.renderRoom(roomHeader)
                                    renderResult = result
                                    
                                    if (result == null) {
                                        errorMessage = "Failed to render room map"
                                        println("  Map rendering failed")
                                    } else {
                                        println("  Map rendered: ${result.width}x${result.height} pixels")
                                    }
                                } else {
                                    errorMessage = "Room header not found in ROM for ID 0x${roomId.toString(16)}"
                                    println("  Room header NOT FOUND")
                                    
                                    // Debug: try to list some rooms
                                    val matcher = com.supermetroid.editor.rom.RoomMatcher(romParser)
                                    val allRooms = matcher.getAllRooms()
                                    println("  Total rooms found in ROM: ${allRooms.size}")
                                    if (allRooms.isNotEmpty()) {
                                        val firstRoom = allRooms.first()
                                        println("  First room: Index=${firstRoom.index}, Area=${firstRoom.area}, bgData=0x${(0x8F0000 + firstRoom.bgData).toString(16)}")
                                    }
                                }
                            } catch (e: Exception) {
                                errorMessage = "Error: ${e.message}"
                                println("Exception: ${e.message}")
                                e.printStackTrace()
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                    
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A))
                    ) {
                        when {
                            isLoading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Loading room map...",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            errorMessage != null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = errorMessage!!,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                            renderResult != null -> {
                                // Render the map directly from pixel data
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        val data = renderResult!!
                                        val scale = minOf(
                                            size.width / data.width,
                                            size.height / data.height
                                        ).coerceAtMost(1f)
                                        
                                        val scaledWidth = data.width * scale
                                        val scaledHeight = data.height * scale
                                        val x = (size.width - scaledWidth) / 2
                                        val y = (size.height - scaledHeight) / 2
                                        
                                        // Draw pixels directly
                                        val pixelSize = 1f * scale
                                        for (py in 0 until data.height) {
                                            for (px in 0 until data.width) {
                                                val pixelIndex = py * data.width + px
                                                val argb = data.pixels[pixelIndex]
                                                val color = Color(argb)
                                                
                                                drawRect(
                                                    color = color,
                                                    topLeft = Offset(x + px * pixelSize, y + py * pixelSize),
                                                    size = androidx.compose.ui.geometry.Size(pixelSize, pixelSize)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Text(
                                    text = "No map data available",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Please load a ROM file first",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "Select a room from the list to view its map",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
