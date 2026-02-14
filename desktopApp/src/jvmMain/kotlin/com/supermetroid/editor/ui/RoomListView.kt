package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

// Area ID to name and color
private val areaInfo = mapOf(
    0 to ("Crateria" to Color(0xFF5078A0)),
    1 to ("Brinstar" to Color(0xFF50A058)),
    2 to ("Norfair" to Color(0xFFA05050)),
    3 to ("Wrecked Ship" to Color(0xFF7070A0)),
    4 to ("Maridia" to Color(0xFF5088B0)),
    5 to ("Tourian" to Color(0xFFA09050)),
    6 to ("Ceres" to Color(0xFF808080)),
)

@Composable
fun RoomListView(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    romParser: RomParser?,
    onRoomSelected: (RoomInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    // Build area lookup from ROM if available
    val roomAreas = remember(romParser, rooms) {
        if (romParser == null) return@remember emptyMap<String, Int>()
        rooms.associate { room ->
            val header = try { romParser.readRoomHeader(room.getRoomIdAsInt()) } catch (_: Exception) { null }
            room.handle to (header?.area ?: -1)
        }
    }
    
    // Sort rooms: by area ID, then by room ID within each area
    val sortedRooms = remember(rooms, roomAreas) {
        rooms.sortedWith(compareBy(
            { roomAreas[it.handle] ?: 99 },
            { it.getRoomIdAsInt() }
        ))
    }
    
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Rooms (${rooms.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            
            Divider()
            
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                var lastArea = -1
                
                items(sortedRooms) { room ->
                    val area = roomAreas[room.handle] ?: -1
                    
                    // Area section header
                    if (area != lastArea && area >= 0) {
                        lastArea = area
                        val (areaName, areaColor) = areaInfo[area] ?: ("Unknown" to Color.Gray)
                        
                        Surface(
                            color = areaColor.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = areaName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = areaColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    RoomListItem(
                        room = room,
                        area = area,
                        isSelected = selectedRoom?.handle == room.handle,
                        onClick = { onRoomSelected(room) }
                    )
                }
            }
        }
    }
}

@Composable
fun RoomListItem(
    room: RoomInfo,
    area: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (_, areaColor) = areaInfo[area] ?: ("" to Color.Gray)
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Area color indicator bar
            Surface(
                color = areaColor,
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
            ) {}
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp
                )
                Text(
                    text = room.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
