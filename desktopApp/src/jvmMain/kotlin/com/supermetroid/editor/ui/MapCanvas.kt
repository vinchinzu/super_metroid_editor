package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A))
                    ) {
                        Canvas(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Placeholder rendering
                            // TODO: Implement actual tile rendering
                            drawRect(
                                color = Color(0xFF333333),
                                topLeft = Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width, size.height)
                            )
                            
                            // Draw placeholder text
                            // Note: Text drawing requires a different approach in Canvas
                            // For now, we'll show a placeholder
                        }
                        
                        // Placeholder text overlay
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Map rendering coming soon",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Room: ${room.name}",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyMedium
                            )
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
