package com.supermetroid.editor.ui

import com.supermetroid.editor.data.NewRoomAllocation
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorStateSessionRoomsTest {

    @Test
    fun `cleanupConsumedSessionRooms removes rooms with null allocation`() {
        // Create a fake project with some room edits
        val project = SmEditProject(romPath = "/fake/path.smc")
        
        // Add a room with an allocation (not yet exported)
        val roomId1 = 0x91F8
        val key1 = project.roomKey(roomId1)
        project.rooms[key1] = RoomEdits(roomId = roomId1).apply {
            newRoomAllocation = NewRoomAllocation(
                headerPcOffset = 0x1000,
                doorTablePtr = 0x2000,
                levelDataPtr = 0x3000,
                levelDataPcOffset = 0x4000,
                compressedLevelData = byteArrayOf(0x01, 0x02),
                plmSetPtr = 0x5000,
                enemyPopPtr = 0x6000,
                enemyGfxPtr = 0x7000,
                scrollPtr = 0x8000,
                roomIndex = 1,
            )
        }
        
        // Add a room with null allocation (already exported)
        val roomId2 = 0x9200
        val key2 = project.roomKey(roomId2)
        project.rooms[key2] = RoomEdits(roomId = roomId2).apply {
            newRoomAllocation = null
        }
        
        // Create EditorState with fake project
        val editorState = EditorState(project)
        
        // Manually add session rooms (simulating what createNewRoom does)
        editorState._sessionRooms.add(
            com.supermetroid.editor.data.RoomInfo(
                id = "0x${roomId1.toString(16).uppercase()}",
                handle = "new_room_${roomId1.toString(16).lowercase()}",
                name = "New Room 0x${roomId1.toString(16).uppercase()}",
            )
        )
        editorState._sessionRooms.add(
            com.supermetroid.editor.data.RoomInfo(
                id = "0x${roomId2.toString(16).uppercase()}",
                handle = "new_room_${roomId2.toString(16).lowercase()}",
                name = "New Room 0x${roomId2.toString(16).uppercase()}",
            )
        )
        
        // Verify we have 2 session rooms before cleanup
        assertEquals(2, editorState.sessionRooms.size)
        
        // Clean up consumed rooms
        editorState.cleanupConsumedSessionRooms()
        
        // After cleanup, only the room with non-null allocation should remain
        assertEquals(1, editorState.sessionRooms.size)
        val remaining = editorState.sessionRooms.first()
        assertEquals("0x91F8", remaining.id)
    }

    @Test
    fun `cleanupConsumedSessionRooms keeps all rooms with allocations`() {
        val project = SmEditProject(romPath = "/fake/path.smc")
        
        // Add two rooms, both with allocations
        val roomId1 = 0x91F8
        val key1 = project.roomKey(roomId1)
        project.rooms[key1] = RoomEdits(roomId = roomId1).apply {
            newRoomAllocation = NewRoomAllocation(
                headerPcOffset = 0x1000,
                doorTablePtr = 0x2000,
                levelDataPtr = 0x3000,
                levelDataPcOffset = 0x4000,
                compressedLevelData = byteArrayOf(0x01),
                plmSetPtr = 0x5000,
                enemyPopPtr = 0x6000,
                enemyGfxPtr = 0x7000,
                scrollPtr = 0x8000,
                roomIndex = 1,
            )
        }
        
        val roomId2 = 0x9200
        val key2 = project.roomKey(roomId2)
        project.rooms[key2] = RoomEdits(roomId = roomId2).apply {
            newRoomAllocation = NewRoomAllocation(
                headerPcOffset = 0x2000,
                doorTablePtr = 0x3000,
                levelDataPtr = 0x4000,
                levelDataPcOffset = 0x5000,
                compressedLevelData = byteArrayOf(0x02),
                plmSetPtr = 0x6000,
                enemyPopPtr = 0x7000,
                enemyGfxPtr = 0x8000,
                scrollPtr = 0x9000,
                roomIndex = 2,
            )
        }
        
        val editorState = EditorState(project)
        
        // Add both rooms to session
        editorState._sessionRooms.add(
            com.supermetroid.editor.data.RoomInfo(
                id = "0x${roomId1.toString(16).uppercase()}",
                handle = "new_room_${roomId1.toString(16).lowercase()}",
                name = "New Room 0x${roomId1.toString(16).uppercase()}",
            )
        )
        editorState._sessionRooms.add(
            com.supermetroid.editor.data.RoomInfo(
                id = "0x${roomId2.toString(16).uppercase()}",
                handle = "new_room_${roomId2.toString(16).lowercase()}",
                name = "New Room 0x${roomId2.toString(16).uppercase()}",
            )
        )
        
        assertEquals(2, editorState.sessionRooms.size)
        
        // Clean up - should keep both since both have allocations
        editorState.cleanupConsumedSessionRooms()
        
        assertEquals(2, editorState.sessionRooms.size)
    }

    @Test
    fun `cleanupConsumedSessionRooms removes all rooms when all allocations consumed`() {
        val project = SmEditProject(romPath = "/fake/path.smc")
        
        // Add a room with null allocation
        val roomId1 = 0x91F8
        val key1 = project.roomKey(roomId1)
        project.rooms[key1] = RoomEdits(roomId = roomId1).apply {
            newRoomAllocation = null
        }
        
        val editorState = EditorState(project)
        
        editorState._sessionRooms.add(
            com.supermetroid.editor.data.RoomInfo(
                id = "0x${roomId1.toString(16).uppercase()}",
                handle = "new_room_${roomId1.toString(16).lowercase()}",
                name = "New Room 0x${roomId1.toString(16).uppercase()}",
            )
        )
        
        assertEquals(1, editorState.sessionRooms.size)
        
        // Clean up - should remove the room since allocation is null
        editorState.cleanupConsumedSessionRooms()
        
        assertTrue(editorState.sessionRooms.isEmpty())
    }
}
