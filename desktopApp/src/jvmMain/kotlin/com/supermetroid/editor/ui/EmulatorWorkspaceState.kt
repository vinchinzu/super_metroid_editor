package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.integration.BridgeCapabilities
import com.supermetroid.editor.integration.BridgeModelInfo
import com.supermetroid.editor.integration.BridgeResponse
import com.supermetroid.editor.integration.BridgeSessionState
import com.supermetroid.editor.integration.BridgeSnapshot
import com.supermetroid.editor.integration.BridgeStateInfo
import com.supermetroid.editor.integration.BridgeTracePoint
import com.supermetroid.editor.integration.EditorDoorExport
import com.supermetroid.editor.integration.EditorNavGraph
import com.supermetroid.editor.integration.EditorNavNode
import com.supermetroid.editor.integration.EditorRoomExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import kotlin.math.max

private const val SCREEN_PIXELS = 256f
private val ACTION_LABELS = listOf("B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R")
private val DEFAULT_SAVE_SLOTS = listOf(
    EmulatorSaveSlot("Checkpoint 1", "EditorCheckpoint01"),
    EmulatorSaveSlot("Checkpoint 2", "EditorCheckpoint02"),
    EmulatorSaveSlot("Checkpoint 3", "EditorCheckpoint03"),
    EmulatorSaveSlot("Checkpoint 4", "EditorCheckpoint04"),
)

private val AREA_ORIGINS = mapOf(
    6 to Pair(0f, 0f),    // Ceres
    0 to Pair(0f, 7f),    // Crateria
    1 to Pair(0f, 17f),   // Brinstar
    3 to Pair(18f, 10f),  // Wrecked Ship
    2 to Pair(17f, 20f),  // Norfair
    4 to Pair(33f, 18f),  // Maridia
    5 to Pair(14f, 31f),  // Tourian
)

data class WorldTracePoint(
    val roomId: Int,
    val roomName: String,
    val x: Float,
    val y: Float,
)

data class LocalRoomPoint(
    val x: Float,
    val y: Float,
)

data class EmulatorSaveSlot(
    val label: String,
    val stateName: String,
)

data class RoomMapOverlay(
    val routeLabel: String? = null,
    val plannedRoute: List<LocalRoomPoint> = emptyList(),
    val liveTrace: List<LocalRoomPoint> = emptyList(),
    val currentPosition: LocalRoomPoint? = null,
    val focusPoint: LocalRoomPoint? = null,
    val startAnchor: LocalRoomPoint? = null,
    val targetAnchor: LocalRoomPoint? = null,
)

data class AreaBounds(
    val minMapX: Int,
    val minMapY: Int,
    val maxMapX: Int,
    val maxMapY: Int,
)

data class LoadedNavGraph(
    val graph: EditorNavGraph,
) {
    val nodesByRoomId: Map<Int, EditorNavNode> = graph.nodes.associateBy { it.roomId }
    val areaBounds: Map<Int, AreaBounds> = graph.nodes
        .groupBy { it.area }
        .mapValues { (_, nodes) ->
            AreaBounds(
                minMapX = nodes.minOf { it.mapX },
                minMapY = nodes.minOf { it.mapY },
                maxMapX = nodes.maxOf { it.mapX + it.widthScreens },
                maxMapY = nodes.maxOf { it.mapY + it.heightScreens },
            )
        }

    fun roomPoint(roomId: Int, pixelX: Int, pixelY: Int): WorldTracePoint? {
        val node = nodesByRoomId[roomId] ?: return null
        val bounds = areaBounds[node.area] ?: return null
        val origin = AREA_ORIGINS[node.area] ?: Pair(node.area * 12f, 0f)
        return WorldTracePoint(
            roomId = roomId,
            roomName = node.name,
            x = origin.first + (node.mapX - bounds.minMapX) + (pixelX / SCREEN_PIXELS),
            y = origin.second + (node.mapY - bounds.minMapY) + (pixelY / SCREEN_PIXELS),
        )
    }

    fun roomCenter(roomId: Int): WorldTracePoint? {
        val node = nodesByRoomId[roomId] ?: return null
        return roomPoint(
            roomId = roomId,
            pixelX = (node.widthScreens * SCREEN_PIXELS / 2f).toInt(),
            pixelY = (node.heightScreens * SCREEN_PIXELS / 2f).toInt(),
        )
    }
}

class EmulatorWorkspaceState(
    private val backendFactory: EmulatorBackendFactory = DefaultEmulatorBackendFactory,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var client: EmulatorBackend? = null
    private var stepInFlight = false
    private val pressedKeys = mutableSetOf<Key>()
    private var lastFollowedRoomId: Int? = null
    private val roomExportCache = mutableMapOf<Int, EditorRoomExport>()

    var navExportDir by mutableStateOf(AppConfig.load().emulatorNavExportDir)
    var followLiveRoom by mutableStateOf(AppConfig.load().emulatorFollowLiveRoom)
    var requestedControlMode by mutableStateOf("manual")

    var statusMessage by mutableStateOf("Load nav graph and connect bridge to start.")
        private set
    var bridgeCapabilities by mutableStateOf<BridgeCapabilities?>(null)
        private set
    var session by mutableStateOf(BridgeSessionState())
        private set
    var snapshot by mutableStateOf<BridgeSnapshot?>(null)
        private set
    var frameBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var recordingPath by mutableStateOf<String?>(null)
        private set
    var bridgeRoundTripMs by mutableStateOf(0f)
        private set
    var emulatedFps by mutableStateOf(0f)
        private set
    var viewportFps by mutableStateOf(0f)
        private set
    var lastStepRepeat by mutableStateOf(0)
        private set
    var sessionBootStateName by mutableStateOf<String?>(null)
        private set
    var lastCheckpointSlotName by mutableStateOf<String?>(null)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var isRunning by mutableStateOf(false)
        private set
    var isBusy by mutableStateOf(false)
        private set

    var saveStates by mutableStateOf<List<BridgeStateInfo>>(emptyList())
        private set
    var models by mutableStateOf<List<BridgeModelInfo>>(emptyList())
        private set
    var selectedStateName by mutableStateOf<String?>(null)
    var selectedModelName by mutableStateOf<String?>(null)
    var navGraph by mutableStateOf<LoadedNavGraph?>(null)
        private set

    val plannedRouteRoomIds = mutableStateListOf<Int>()
    val saveSlots: List<EmulatorSaveSlot> = DEFAULT_SAVE_SLOTS

    suspend fun loadNavGraph() {
        val navFile = File(navExportDir, "nav_graph.json")
        roomExportCache.clear()
        if (!navFile.isFile) {
            navGraph = null
            statusMessage = "Nav graph not found at ${navFile.absolutePath}"
            persistConfig()
            if (isConnected) configureBridge()
            return
        }
        navGraph = withContext(Dispatchers.IO) {
            json.decodeFromString(EditorNavGraph.serializer(), navFile.readText())
        }.let(::LoadedNavGraph)
        statusMessage = "Loaded nav graph (${navGraph?.graph?.nodes?.size ?: 0} rooms)"
        persistConfig()
        if (isConnected) configureBridge()
    }

    suspend fun connectBridge() {
        if (isConnected) return
        isBusy = true
        try {
            client = withContext(Dispatchers.IO) { backendFactory.connect() }
            val response = withContext(Dispatchers.IO) { client!!.request("hello") }
            applyPreparedResponse(response)
            configureBridge()
            isConnected = true
            statusMessage = response.message ?: "Bridge connected"
            refreshInventory()
        } catch (e: Exception) {
            client = null
            isConnected = false
            statusMessage = "Bridge connection failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    fun disconnectBridge() {
        isRunning = false
        client?.close()
        client = null
        isConnected = false
        session = BridgeSessionState()
        snapshot = null
        frameBitmap = null
        bridgeRoundTripMs = 0f
        emulatedFps = 0f
        viewportFps = 0f
        lastStepRepeat = 0
        sessionBootStateName = null
        lastCheckpointSlotName = null
        statusMessage = "Bridge disconnected"
    }

    suspend fun refreshInventory() {
        val bridge = client ?: return
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    "discover",
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                    selectedModel = selectedModelName,
                    includeFrame = false,
                    includeTrace = false,
                )
            }
            applyPreparedResponse(response)
            if (selectedStateName == null) selectedStateName = saveStates.firstOrNull()?.name
            if (selectedModelName == null) selectedModelName = models.firstOrNull()?.name
            statusMessage = response.message ?: "Inventory refreshed"
        } catch (e: Exception) {
            statusMessage = "Inventory refresh failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun startSession() {
        val bridge = client ?: return
        val stateName = selectedStateName ?: saveStates.firstOrNull()?.name ?: return
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    "start_session",
                    state = stateName,
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                    selectedModel = selectedModelName,
                )
            }
            applyPreparedResponse(response)
            sessionBootStateName = stateName
            isRunning = true
            bridgeRoundTripMs = 0f
            emulatedFps = 0f
            viewportFps = 0f
            lastStepRepeat = 0
            statusMessage = response.message ?: "Session started: $stateName"
        } catch (e: Exception) {
            statusMessage = "Failed to start session: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun closeSession() {
        val bridge = client ?: return
        isRunning = false
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) { bridge.request("close_session") }
            applyPreparedResponse(response)
            statusMessage = response.message ?: "Session closed"
        } catch (e: Exception) {
            statusMessage = "Failed to close session: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun refreshSnapshot() {
        val bridge = client ?: return
        if (!session.active) return
        if (isBusy || stepInFlight) return
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    "snapshot",
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                    selectedModel = selectedModelName,
                )
            }
            applyPreparedResponse(response)
        } catch (e: Exception) {
            statusMessage = "Snapshot failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun stepFrame(
        repeat: Int = 1,
        includeFrame: Boolean = true,
        includeTrace: Boolean = true,
    ) {
        val bridge = client ?: return
        if (!session.active || isBusy || stepInFlight) return
        stepInFlight = true
        val previousFrameCounter = session.frameCounter
        val startedAt = System.nanoTime()
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    "step",
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                    selectedModel = selectedModelName,
                    action = currentAction(),
                    repeat = repeat,
                    includeFrame = includeFrame,
                    includeTrace = includeTrace,
                )
            }
            applyPreparedResponse(response)
            updateLoopMetrics(
                previousFrameCounter = previousFrameCounter,
                currentFrameCounter = response.session?.frameCounter ?: session.frameCounter,
                elapsedNanos = System.nanoTime() - startedAt,
                repeat = repeat,
                frameIncluded = includeFrame,
            )
        } catch (e: Exception) {
            statusMessage = "Step failed: ${e.message}"
            isRunning = false
        } finally {
            stepInFlight = false
        }
    }

    suspend fun saveQuickState(name: String = "EditorQuickSave") {
        val bridge = client ?: return
        if (!session.active) return
        selectedStateName = name
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request("save_state", saveName = name, includeFrame = false, includeTrace = false)
            }
            applyPreparedResponse(response)
            statusMessage = response.message ?: "Saved $name"
            refreshInventory()
        } catch (e: Exception) {
            statusMessage = "Save failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun loadSelectedState() {
        val bridge = client ?: return
        val stateName = selectedStateName ?: return
        loadStateByName(bridge, stateName)
    }

    suspend fun resetToSessionStart() {
        val bridge = client ?: return
        val stateName = sessionBootStateName ?: selectedStateName ?: return
        selectedStateName = stateName
        loadStateByName(bridge, stateName)
    }

    suspend fun saveSlot(slot: EmulatorSaveSlot) {
        selectedStateName = slot.stateName
        rememberCheckpointSlot(slot)
        saveQuickState(slot.stateName)
    }

    suspend fun loadSlot(slot: EmulatorSaveSlot) {
        val bridge = client ?: return
        if (!hasSavedSlot(slot)) {
            statusMessage = "${slot.label} is empty"
            return
        }
        selectedStateName = slot.stateName
        rememberCheckpointSlot(slot)
        loadStateByName(bridge, slot.stateName)
    }

    suspend fun saveSlot(index: Int) {
        val slot = saveSlots.getOrNull(index) ?: return
        saveSlot(slot)
    }

    suspend fun reloadLastCheckpoint() {
        val preferred = saveSlots.firstOrNull { it.stateName == lastCheckpointSlotName && hasSavedSlot(it) }
            ?: saveSlots.firstOrNull { hasSavedSlot(it) }
        if (preferred == null) {
            statusMessage = "No checkpoint slot saved yet"
            return
        }
        loadSlot(preferred)
    }

    fun hasSavedSlot(slot: EmulatorSaveSlot): Boolean {
        return saveStates.any { it.name == slot.stateName }
    }

    fun isLastCheckpointSlot(slot: EmulatorSaveSlot): Boolean {
        return slot.stateName == lastCheckpointSlotName
    }

    fun checkpointSlotHotkey(slot: EmulatorSaveSlot): String {
        val index = saveSlots.indexOfFirst { it.stateName == slot.stateName }
        return when (index) {
            0 -> "F1"
            1 -> "F2"
            2 -> "F3"
            3 -> "F4"
            else -> ""
        }
    }

    fun checkpointSlotReloadHotkey(slot: EmulatorSaveSlot): String {
        val hotkey = checkpointSlotHotkey(slot)
        return if (hotkey.isBlank()) "" else "Shift+$hotkey"
    }

    fun selectedStateInfo(): BridgeStateInfo? {
        val stateName = selectedStateName ?: return null
        return saveStates.firstOrNull { it.name == stateName }
    }

    fun sessionBootStateInfo(): BridgeStateInfo? {
        val stateName = sessionBootStateName ?: return null
        return saveStates.firstOrNull { it.name == stateName }
    }

    fun displayPath(path: String?): String? {
        val raw = path?.takeIf { it.isNotBlank() } ?: return null
        val marker = "${File.separator}custom_integrations${File.separator}"
        val markerIndex = raw.indexOf(marker)
        if (markerIndex >= 0) {
            return raw.substring(markerIndex + 1)
        }
        return raw
    }

    private fun rememberCheckpointSlot(slot: EmulatorSaveSlot) {
        lastCheckpointSlotName = slot.stateName
    }

    private suspend fun loadStateByName(bridge: EmulatorBackend, stateName: String) {
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    "load_state",
                    state = stateName,
                    navExportDir = navExportDir,
                    controlMode = requestedControlMode,
                    selectedModel = selectedModelName,
                )
            }
            applyPreparedResponse(response)
            statusMessage = response.message ?: "Loaded $stateName"
        } catch (e: Exception) {
            statusMessage = "Load failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    suspend fun setRecording(active: Boolean) {
        val bridge = client ?: return
        if (!session.active) return
        isBusy = true
        try {
            val response = withContext(Dispatchers.IO) {
                bridge.request(
                    if (active) "start_recording" else "stop_recording",
                    includeFrame = false,
                    includeTrace = false,
                )
            }
            applyPreparedResponse(response)
            statusMessage = response.message ?: if (active) "Recording started" else "Recording stopped"
        } catch (e: Exception) {
            statusMessage = "Recording toggle failed: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    fun setLoopRunning(running: Boolean) {
        isRunning = running
    }

    fun updateKey(key: Key, down: Boolean) {
        when {
            down -> pressedKeys.add(key)
            else -> pressedKeys.remove(key)
        }
    }

    fun clearCheckpointModifierKeys() {
        pressedKeys.remove(Key.ShiftLeft)
        pressedKeys.remove(Key.ShiftRight)
    }

    fun currentAction(): List<Int> {
        val action = MutableList(12) { 0 }
        fun on(key: Key) = pressedKeys.contains(key)

        if (on(Key.DirectionRight)) action[7] = 1
        if (on(Key.DirectionLeft)) action[6] = 1
        if (on(Key.DirectionUp)) action[4] = 1
        if (on(Key.DirectionDown)) action[5] = 1

        if (on(Key.Z)) action[0] = 1
        if (on(Key.X)) action[8] = 1
        if (on(Key.A)) action[1] = 1
        if (on(Key.S)) action[9] = 1
        if (on(Key.Q)) action[10] = 1
        if (on(Key.W)) action[11] = 1
        if (on(Key.Enter)) action[3] = 1
        if (on(Key.ShiftLeft) || on(Key.ShiftRight) || on(Key.Tab)) action[2] = 1

        // Stable-retro does not like conflicting D-pad directions.
        if (action[6] == 1 && action[7] == 1) {
            action[6] = 0
            action[7] = 0
        }
        if (action[4] == 1 && action[5] == 1) {
            action[4] = 0
            action[5] = 0
        }
        return action
    }

    fun activeInputSummary(): String {
        val action = snapshot?.lastAction?.takeIf { it.isNotEmpty() } ?: currentAction()
        return inputSummary(action)
    }

    fun requestedInputSummary(): String {
        val action = snapshot?.lastRequestedAction ?: return "none"
        return inputSummary(action)
    }

    fun preSanitizeInputSummary(): String {
        val action = snapshot?.lastActionPreSanitize ?: return "none"
        return inputSummary(action)
    }

    private fun inputSummary(action: List<Int>): String {
        val active = action.mapIndexedNotNull { index, value ->
            ACTION_LABELS.getOrNull(index)?.takeIf { value != 0 }
        }
        return if (active.isEmpty()) "none" else active.joinToString(" ")
    }

    fun controllerSummary(): String {
        val currentSnapshot = snapshot
        return when {
            currentSnapshot?.controllerConnected == true && !currentSnapshot.controllerName.isNullOrBlank() ->
                currentSnapshot.controllerName!!
            currentSnapshot?.controllerConnected == true -> "connected"
            else -> "none"
        }
    }

    fun appendPlannedRoom(roomId: Int) {
        if (plannedRouteRoomIds.lastOrNull() == roomId) return
        plannedRouteRoomIds.add(roomId)
    }

    fun updateFollowLiveRoom(value: Boolean) {
        followLiveRoom = value
        persistConfig()
    }

    fun updateNavExportDir(value: String) {
        navExportDir = value
    }

    fun clearPlannedRoute() {
        plannedRouteRoomIds.clear()
    }

    fun updateRequestedControlMode(value: String) {
        requestedControlMode = value
    }

    fun removeLastPlannedRoom() {
        if (plannedRouteRoomIds.isNotEmpty()) plannedRouteRoomIds.removeLast()
    }

    fun liveTracePoints(): List<WorldTracePoint> {
        val loaded = navGraph ?: return emptyList()
        val currentSnapshot = snapshot ?: return emptyList()
        return currentSnapshot.trace.mapNotNull { loaded.roomPoint(it.roomId, it.x, it.y) }
    }

    fun centerOfGravity(): WorldTracePoint? {
        val points = liveTracePoints()
        if (points.isEmpty()) return null
        val roomName = points.last().roomName
        return WorldTracePoint(
            roomId = points.last().roomId,
            roomName = roomName,
            x = points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            y = points.sumOf { it.y.toDouble() }.toFloat() / points.size,
        )
    }

    fun plannedRoutePoints(): List<WorldTracePoint> {
        val loaded = navGraph ?: return emptyList()
        return plannedRouteRoomIds.mapNotNull { loaded.roomCenter(it) }
    }

    fun roomMapOverlay(room: RoomInfo?): RoomMapOverlay? {
        val currentRoom = room ?: return null
        val roomExport = roomExport(currentRoom.getRoomIdAsInt()) ?: return null
        val currentSnapshot = snapshot
        val expectedTrace = currentSnapshot?.expectedTrace
            ?.filter { it.roomId == roomExport.roomId }
            ?.map { LocalRoomPoint(x = it.x.toFloat(), y = it.y.toFloat()) }
            .orEmpty()
        val liveTrace = currentSnapshot?.trace
            ?.filter { it.roomId == roomExport.roomId }
            ?.map { LocalRoomPoint(x = it.x.toFloat(), y = it.y.toFloat()) }
            .orEmpty()
        val currentPosition = when {
            currentSnapshot?.doorTransition == true -> null
            currentSnapshot?.roomId == roomExport.roomId -> {
                val samusX = currentSnapshot.samusX
                val samusY = currentSnapshot.samusY
                if (samusX != null && samusY != null) {
                    LocalRoomPoint(samusX.toFloat(), samusY.toFloat())
                } else {
                    null
                }
            }
            liveTrace.isNotEmpty() -> liveTrace.last()
            else -> null
        }
        val plannedRoute = when {
            expectedTrace.isNotEmpty() -> expectedTrace
            else -> landingSiteRoute(roomExport)?.second.orEmpty()
        }
        val routeLabel = when {
            expectedTrace.isNotEmpty() -> currentSnapshot?.expectedTraceLabel ?: "Expected path (sm_landing_site)"
            else -> landingSiteRoute(roomExport)?.first
        }
        val focusPoint = shipPoint(roomExport)
            ?: currentPosition
            ?: plannedRoute.firstOrNull()
        if (plannedRoute.isEmpty() && liveTrace.isEmpty() && currentPosition == null) return null
        return RoomMapOverlay(
            routeLabel = routeLabel,
            plannedRoute = plannedRoute,
            liveTrace = liveTrace,
            currentPosition = currentPosition,
            focusPoint = focusPoint,
            startAnchor = plannedRoute.firstOrNull(),
            targetAnchor = plannedRoute.lastOrNull(),
        )
    }

    fun roomToFollow(rooms: List<RoomInfo>): RoomInfo? {
        if (!followLiveRoom) return null
        val roomId = snapshot?.roomId ?: return null
        if (roomId == lastFollowedRoomId) return null
        val room = rooms.firstOrNull { it.getRoomIdAsInt() == roomId } ?: return null
        lastFollowedRoomId = roomId
        return room
    }

    suspend fun configureBridge() {
        val bridge = client ?: return
        val response = withContext(Dispatchers.IO) {
            bridge.request(
                "configure",
                navExportDir = navExportDir,
                controlMode = requestedControlMode,
                selectedModel = selectedModelName,
                includeFrame = false,
                includeTrace = false,
            )
        }
        applyPreparedResponse(response)
    }

    private suspend fun applyPreparedResponse(response: BridgeResponse) {
        val decodedFrame = response.snapshot
            ?.takeIf { it.frameRgb24Base64 != null }
            ?.let { currentSnapshot ->
                withContext(Dispatchers.Default) { decodeFrame(currentSnapshot) }
            }
        applyResponse(response, decodedFrame)
    }

    private fun applyResponse(response: BridgeResponse, decodedFrame: ImageBitmap? = null) {
        if (!response.ok) {
            statusMessage = response.error ?: "Bridge error"
            return
        }
        response.capabilities?.let { bridgeCapabilities = it }
        response.session?.let { session = it }
        response.snapshot?.let { incomingSnapshot ->
            snapshot = mergeSnapshot(snapshot, incomingSnapshot)
            if (decodedFrame != null) {
                frameBitmap = decodedFrame
            }
        }
        if (response.states.isNotEmpty()) saveStates = response.states.sortedBy { it.name.lowercase() }
        if (response.models.isNotEmpty()) models = response.models.sortedBy { it.name.lowercase() }
        if (response.recordingPath != null) recordingPath = response.recordingPath
        response.message?.let { statusMessage = it }
    }

    private fun decodeFrame(currentSnapshot: BridgeSnapshot): ImageBitmap? {
        val base64 = currentSnapshot.frameRgb24Base64 ?: return null
        if (currentSnapshot.frameWidth <= 0 || currentSnapshot.frameHeight <= 0) return null
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val width = currentSnapshot.frameWidth
        val height = currentSnapshot.frameHeight
        if (bytes.size < width * height * 3) return null

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = bytes[idx].toInt() and 0xFF
                val g = bytes[idx + 1].toInt() and 0xFF
                val b = bytes[idx + 2].toInt() and 0xFF
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                idx += 3
            }
        }
        return image.toComposeImageBitmap()
    }

    private fun mergeSnapshot(previous: BridgeSnapshot?, incoming: BridgeSnapshot): BridgeSnapshot {
        val expectedTrace = if (incoming.traceIncluded || previous == null) incoming.expectedTrace else previous.expectedTrace
        val trace = if (incoming.traceIncluded || previous == null) incoming.trace else previous.trace
        return incoming.copy(
            expectedTrace = expectedTrace,
            trace = trace,
        )
    }

    private fun updateLoopMetrics(
        previousFrameCounter: Int,
        currentFrameCounter: Int,
        elapsedNanos: Long,
        repeat: Int,
        frameIncluded: Boolean,
    ) {
        if (elapsedNanos <= 0L) return
        val elapsedSeconds = elapsedNanos / 1_000_000_000f
        if (elapsedSeconds <= 0f) return
        lastStepRepeat = repeat
        bridgeRoundTripMs = smoothMetric(bridgeRoundTripMs, elapsedNanos / 1_000_000f)
        val frameDelta = maxOf(0, currentFrameCounter - previousFrameCounter)
        if (frameDelta > 0) {
            emulatedFps = smoothMetric(emulatedFps, frameDelta / elapsedSeconds)
        }
        if (frameIncluded) {
            viewportFps = smoothMetric(viewportFps, 1f / elapsedSeconds)
        }
    }

    private fun smoothMetric(current: Float, sample: Float): Float {
        return if (current <= 0f) sample else (current * 0.75f) + (sample * 0.25f)
    }

    private fun persistConfig() {
        AppConfig.update {
            copy(
                emulatorNavExportDir = navExportDir,
                emulatorFollowLiveRoom = followLiveRoom,
            )
        }
    }

    private fun roomExport(roomId: Int): EditorRoomExport? {
        roomExportCache[roomId]?.let { return it }
        val roomFile = File(navExportDir, "rooms/room_${roomId.toString(16).uppercase().padStart(4, '0')}.json")
        if (!roomFile.isFile) return null
        return runCatching {
            json.decodeFromString(EditorRoomExport.serializer(), roomFile.readText())
        }.getOrNull()?.also { roomExportCache[roomId] = it }
    }

    private fun landingSiteRoute(room: EditorRoomExport): Pair<String, List<LocalRoomPoint>>? {
        if (room.handle != "landingSite") return null
        val ship = shipPoint(room)
            ?: return null
        val targetDoor = room.doors
            .asSequence()
            .filter { it.direction == "Left" && !it.isElevator }
            .filter { it.sourceBlockX != null && it.sourceBlockY != null }
            .sortedByDescending { it.sourceBlockY ?: -1 }
            .firstOrNull()
            ?: return null
        val doorPoint = doorPoint(targetDoor) ?: return null
        return "Ship -> left door" to listOf(ship, doorPoint)
    }

    private fun shipPoint(room: EditorRoomExport): LocalRoomPoint? {
        val ship = room.enemies
            .firstOrNull { it.name == "Samus' Ship" }
            ?: room.enemies.firstOrNull { it.name.startsWith("Samus' Ship") }
            ?: return null
        return LocalRoomPoint(ship.pixelX.toFloat(), ship.pixelY.toFloat())
    }

    private fun doorPoint(door: EditorDoorExport): LocalRoomPoint? {
        val blockX = door.sourceBlockX ?: return null
        val blockY = door.sourceBlockY ?: return null
        return LocalRoomPoint(
            x = blockX * 16f + 8f,
            y = blockY * 16f + 8f,
        )
    }

    internal fun setNavGraphForTest(graph: LoadedNavGraph) {
        navGraph = graph
    }

    internal fun setSnapshotForTest(value: BridgeSnapshot) {
        snapshot = value
    }

    internal fun setRoomExportForTest(value: EditorRoomExport) {
        roomExportCache[value.roomId] = value
    }
}

internal fun navGraphExtent(graph: LoadedNavGraph): Pair<Float, Float> {
    var maxX = 0f
    var maxY = 0f
    for (node in graph.graph.nodes) {
        val point = graph.roomCenter(node.roomId) ?: continue
        maxX = max(maxX, point.x + node.widthScreens)
        maxY = max(maxY, point.y + node.heightScreens)
    }
    return Pair(maxX, maxY)
}

internal fun tracePointFor(graph: LoadedNavGraph, point: BridgeTracePoint): WorldTracePoint? {
    return graph.roomPoint(point.roomId, point.x, point.y)
}
