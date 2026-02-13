package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo

@Composable
fun RoomListView(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    onRoomSelected: (RoomInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "Rooms (${rooms.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            
            Divider()
            
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(rooms) { room ->
                    RoomListItem(
                        room = room,
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
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = room.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            room.comment?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
