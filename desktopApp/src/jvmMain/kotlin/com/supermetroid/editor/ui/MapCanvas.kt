package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.image.BufferedImage

private fun renderDataToImage(data: RoomRenderData): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    return img
}

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
            // Room info header
            Text(
                text = room?.name ?: "No room selected",
                style = MaterialTheme.typography.titleLarge
            )
            
            if (room != null) {
                Text(
                    text = "Room ID: ${room.id}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (romParser != null) {
                    var isLoading by remember(room.id) { mutableStateOf(true) }
                    var errorMessage by remember(room.id) { mutableStateOf<String?>(null) }
                    var imageBitmap by remember(room.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    var roomInfo by remember(room.id) { mutableStateOf("") }
                    
                    LaunchedEffect(room.id) {
                        isLoading = true
                        errorMessage = null
                        imageBitmap = null
                        
                        try {
                            val roomId = room.getRoomIdAsInt()
                            val roomHeader = romParser.readRoomHeader(roomId)
                            
                            if (roomHeader != null) {
                                val areaName = when (roomHeader.area) {
                                    0 -> "Crateria"
                                    1 -> "Brinstar"
                                    2 -> "Norfair"
                                    3 -> "Wrecked Ship"
                                    4 -> "Maridia"
                                    5 -> "Tourian"
                                    6 -> "Ceres"
                                    else -> "Unknown"
                                }
                                roomInfo = "Area: $areaName  •  Size: ${roomHeader.width}×${roomHeader.height} screens  •  Map: (${roomHeader.mapX}, ${roomHeader.mapY})"
                                
                                val mapRenderer = MapRenderer(romParser)
                                val result = mapRenderer.renderRoom(roomHeader)
                                
                                if (result != null) {
                                    val img = renderDataToImage(result)
                                    imageBitmap = img.toComposeImageBitmap()
                                } else {
                                    errorMessage = "Failed to render room map"
                                }
                            } else {
                                errorMessage = "Room header not found in ROM"
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.message}"
                            e.printStackTrace()
                        } finally {
                            isLoading = false
                        }
                    }
                    
                    // Room metadata
                    if (roomInfo.isNotEmpty()) {
                        Text(
                            text = roomInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Map display
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isLoading -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Loading...", color = Color.White)
                                }
                            }
                            errorMessage != null -> {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            imageBitmap != null -> {
                                Image(
                                    bitmap = imageBitmap!!,
                                    contentDescription = room.name,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Load a ROM file to view room maps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
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
