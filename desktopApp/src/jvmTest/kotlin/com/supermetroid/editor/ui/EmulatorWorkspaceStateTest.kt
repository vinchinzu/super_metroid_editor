package com.supermetroid.editor.ui

import androidx.compose.ui.input.key.Key
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.integration.BridgeCapabilities
import com.supermetroid.editor.integration.BridgeResponse
import com.supermetroid.editor.integration.BridgeSessionState
import com.supermetroid.editor.integration.BridgeStateInfo
import com.supermetroid.editor.integration.BridgeSnapshot
import com.supermetroid.editor.integration.BridgeTracePoint
import com.supermetroid.editor.integration.EditorDoorExport
import com.supermetroid.editor.integration.EditorEnemyExport
import com.supermetroid.editor.integration.EditorNavGraph
import com.supermetroid.editor.integration.EditorNavNode
import com.supermetroid.editor.integration.EditorRoomExport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EmulatorWorkspaceStateTest {

    @Test
    fun `currentAction maps keys and clears conflicting directions`() {
        val state = EmulatorWorkspaceState()

        state.updateKey(Key.DirectionLeft, down = true)
        state.updateKey(Key.DirectionRight, down = true)
        state.updateKey(Key.Z, down = true)
        state.updateKey(Key.Enter, down = true)
        state.updateKey(Key.Tab, down = true)

        val action = state.currentAction()

        assertEquals(0, action[6], "left should be cleared when both left and right are pressed")
        assertEquals(0, action[7], "right should be cleared when both left and right are pressed")
        assertEquals(1, action[0], "Z maps to SNES B")
        assertEquals(1, action[3], "Enter maps to Start")
        assertEquals(1, action[2], "Tab maps to Select")
    }

    @Test
    fun `checkpoint slots expose expected hotkeys`() {
        val state = EmulatorWorkspaceState()

        assertEquals("F1", state.checkpointSlotHotkey(state.saveSlots[0]))
        assertEquals("F2", state.checkpointSlotHotkey(state.saveSlots[1]))
        assertEquals("F3", state.checkpointSlotHotkey(state.saveSlots[2]))
        assertEquals("F4", state.checkpointSlotHotkey(state.saveSlots[3]))
        assertEquals("Shift+F1", state.checkpointSlotReloadHotkey(state.saveSlots[0]))
        assertEquals("Shift+F2", state.checkpointSlotReloadHotkey(state.saveSlots[1]))
        assertEquals("Shift+F3", state.checkpointSlotReloadHotkey(state.saveSlots[2]))
        assertEquals("Shift+F4", state.checkpointSlotReloadHotkey(state.saveSlots[3]))
    }

    @Test
    fun `resetToSessionStart reloads the original boot state`() = runBlocking {
        val backend = FakeBackend(
            responses = ArrayDeque(
                listOf(
                    BridgeResponse(
                        capabilities = BridgeCapabilities(
                            game = "SuperMetroid-Snes",
                            gameDir = "/tmp/game",
                            bridgeVersion = "test",
                            supportsFrames = true,
                            supportsRecording = true,
                            supportsKeyboardInput = true,
                        ),
                        message = "hello",
                    ),
                    BridgeResponse(message = "configured"),
                    BridgeResponse(
                        states = listOf(
                            BridgeStateInfo("ZebesStart", "/repo/custom_integrations/SuperMetroid-Snes/ZebesStart.state"),
                            BridgeStateInfo("EditorCheckpoint02", "/repo/custom_integrations/SuperMetroid-Snes/EditorCheckpoint02.state"),
                        ),
                        message = "discover",
                    ),
                    BridgeResponse(
                        session = BridgeSessionState(active = true, currentState = "ZebesStart", frameCounter = 1),
                        snapshot = BridgeSnapshot(frameCounter = 1),
                        message = "started",
                    ),
                    BridgeResponse(
                        session = BridgeSessionState(active = true, currentState = "ZebesStart", frameCounter = 2),
                        snapshot = BridgeSnapshot(frameCounter = 2),
                        message = "reloaded",
                    ),
                )
            )
        )
        val state = EmulatorWorkspaceState(backendFactory = FakeBackendFactory(backend))

        state.connectBridge()
        state.selectedStateName = "ZebesStart"
        state.startSession()
        state.selectedStateName = "EditorCheckpoint02"
        state.resetToSessionStart()

        assertEquals("ZebesStart", state.sessionBootStateName)
        assertEquals("ZebesStart", state.selectedStateName)
        assertEquals("load_state", backend.requests.last().command)
        assertEquals("ZebesStart", backend.requests.last().state)
        assertEquals(
            "custom_integrations/SuperMetroid-Snes/ZebesStart.state",
            state.displayPath(state.sessionBootStateInfo()?.path),
        )
    }

    @Test
    fun `liveTracePoints convert local room pixels into planner world coordinates`() {
        val state = EmulatorWorkspaceState()
        state.setNavGraphForTest(sampleGraph())
        state.setSnapshotForTest(
            BridgeSnapshot(
                roomId = 0x91F8,
                trace = listOf(
                    BridgeTracePoint(frame = 1, roomId = 0x91F8, x = 128, y = 64),
                    BridgeTracePoint(frame = 2, roomId = 0x92FD, x = 64, y = 32),
                ),
            )
        )

        val points = state.liveTracePoints()

        assertEquals(2, points.size)
        assertEquals(0.5f, points[0].x, 0.001f)
        assertEquals(7.25f, points[0].y, 0.001f)
        assertEquals(1.25f, points[1].x, 0.001f)
        assertEquals(8.125f, points[1].y, 0.001f)
    }

    @Test
    fun `centerOfGravity averages live planner trace`() {
        val state = EmulatorWorkspaceState()
        state.setNavGraphForTest(sampleGraph())
        state.setSnapshotForTest(
            BridgeSnapshot(
                roomId = 0x92FD,
                trace = listOf(
                    BridgeTracePoint(frame = 1, roomId = 0x91F8, x = 0, y = 0),
                    BridgeTracePoint(frame = 2, roomId = 0x91F8, x = 256, y = 0),
                    BridgeTracePoint(frame = 3, roomId = 0x92FD, x = 0, y = 256),
                ),
            )
        )

        val cog = state.centerOfGravity()

        assertNotNull(cog)
        assertEquals((0f + 1f + 1f) / 3f, cog!!.x, 0.001f)
        assertEquals((7f + 7f + 9f) / 3f, cog.y, 0.001f)
        assertTrue(cog.roomName.isNotBlank())
    }

    @Test
    fun `roomMapOverlay anchors Landing Site route from ship to left door`() {
        val state = EmulatorWorkspaceState()
        state.setRoomExportForTest(sampleLandingSiteRoomExport())
        state.setSnapshotForTest(
            BridgeSnapshot(
                roomId = 0x91F8,
                samusX = 32,
                samusY = 1144,
                expectedTrace = listOf(
                    BridgeTracePoint(frame = 1, roomId = 0x91F8, x = 1152, y = 1144),
                    BridgeTracePoint(frame = 2, roomId = 0x91F8, x = 896, y = 896),
                    BridgeTracePoint(frame = 3, roomId = 0x91F8, x = 640, y = 896),
                    BridgeTracePoint(frame = 4, roomId = 0x91F8, x = 640, y = 1152),
                    BridgeTracePoint(frame = 5, roomId = 0x91F8, x = 384, y = 1152),
                    BridgeTracePoint(frame = 6, roomId = 0x91F8, x = 8, y = 1152),
                ),
                trace = listOf(
                    BridgeTracePoint(frame = 1, roomId = 0x91F8, x = 1152, y = 1144),
                    BridgeTracePoint(frame = 2, roomId = 0x91F8, x = 768, y = 1144),
                    BridgeTracePoint(frame = 3, roomId = 0x91F8, x = 32, y = 1144),
                ),
            )
        )

        val overlay = state.roomMapOverlay(RoomInfo(id = "0x91F8", handle = "landingSite", name = "Landing Site"))

        assertNotNull(overlay)
        assertEquals("Expected path (sm_landing_site)", overlay!!.routeLabel)
        assertEquals(6, overlay.plannedRoute.size)
        assertEquals(1152f, overlay.plannedRoute.first().x, 0.001f)
        assertEquals(1144f, overlay.plannedRoute.first().y, 0.001f)
        assertEquals(8f, overlay.plannedRoute.last().x, 0.001f)
        assertEquals(1152f, overlay.plannedRoute.last().y, 0.001f)
        assertEquals(3, overlay.liveTrace.size)
        val currentPosition = overlay.currentPosition
        assertNotNull(currentPosition)
        assertEquals(32f, currentPosition!!.x, 0.001f)
        assertEquals(1144f, currentPosition.y, 0.001f)
    }

    private fun sampleGraph(): LoadedNavGraph {
        return LoadedNavGraph(
            EditorNavGraph(
                nodes = listOf(
                    EditorNavNode(
                        roomId = 0x91F8,
                        roomIdHex = "0x91F8",
                        handle = "landingSite",
                        name = "Landing Site",
                        area = 0,
                        areaName = "Crateria",
                        mapX = 0,
                        mapY = 0,
                        widthScreens = 2,
                        heightScreens = 1,
                    ),
                    EditorNavNode(
                        roomId = 0x92FD,
                        roomIdHex = "0x92FD",
                        handle = "parlor",
                        name = "Parlor and Alcatraz",
                        area = 0,
                        areaName = "Crateria",
                        mapX = 1,
                        mapY = 1,
                        widthScreens = 1,
                        heightScreens = 2,
                    ),
                ),
                edges = emptyList(),
            )
        )
    }

    private fun sampleLandingSiteRoomExport(): EditorRoomExport {
        return EditorRoomExport(
            roomId = 0x91F8,
            roomIdHex = "0x91F8",
            handle = "landingSite",
            name = "Landing Site",
            area = 0,
            areaName = "Crateria",
            mapX = 23,
            mapY = 0,
            widthScreens = 9,
            heightScreens = 5,
            widthBlocks = 144,
            heightBlocks = 80,
            tileset = 0,
            collision = emptyList(),
            bts = emptyList(),
            doors = listOf(
                EditorDoorExport(
                    destRoomId = 0x92FD,
                    destRoomIdHex = "0x92FD",
                    destRoomHandle = "parlorAndAlcatraz",
                    direction = "Left",
                    isElevator = false,
                    screenX = 0,
                    screenY = 4,
                    sourceBlockX = 0,
                    sourceBlockY = 71,
                )
            ),
            items = emptyList(),
            enemies = listOf(
                EditorEnemyExport(
                    id = 0xD07F,
                    idHex = "0xD07F",
                    name = "Samus' Ship",
                    pixelX = 1152,
                    pixelY = 1144,
                    blockX = 72,
                    blockY = 71,
                )
            ),
            plms = emptyList(),
        )
    }

    private data class FakeRequest(
        val command: String,
        val state: String?,
    )

    private class FakeBackend(
        private val responses: ArrayDeque<BridgeResponse>,
    ) : EmulatorBackend {
        val requests = mutableListOf<FakeRequest>()

        override fun request(
            command: String,
            state: String?,
            saveName: String?,
            navExportDir: String?,
            controlMode: String?,
            selectedModel: String?,
            action: List<Int>,
            repeat: Int,
            includeFrame: Boolean,
            includeTrace: Boolean,
        ): BridgeResponse {
            requests += FakeRequest(command = command, state = state)
            return responses.removeFirstOrNull() ?: BridgeResponse(message = command)
        }

        override fun close() = Unit
    }

    private class FakeBackendFactory(
        private val backend: EmulatorBackend,
    ) : EmulatorBackendFactory {
        override fun connect(startDir: File): EmulatorBackend = backend
    }
}
