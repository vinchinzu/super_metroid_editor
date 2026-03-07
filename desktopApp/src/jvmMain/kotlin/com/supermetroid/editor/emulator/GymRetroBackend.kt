package com.supermetroid.editor.emulator

import com.supermetroid.editor.ui.EmulatorBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GymRetroBackend : EmulatorBackend {

    override val name: String = "gym-retro"
    override var isConnected: Boolean = false
        private set

    private var client: EmulatorBridgeClient? = null

    override suspend fun connect(): EmulatorCapabilities = withContext(Dispatchers.IO) {
        val bridge = EmulatorBridgeClient.connect()
        client = bridge
        val response = bridge.request("hello")
        isConnected = true
        val caps = response.capabilities
        EmulatorCapabilities(
            backendName = "gym-retro",
            supportsFrames = caps?.supportsFrames ?: true,
            supportsMemoryAccess = false,
            supportsSaveStates = true,
            supportsMultiPlayer = (caps?.maxPlayers ?: 1) > 1,
            supportsRecording = caps?.supportsRecording ?: false,
            supportsAgentControl = caps?.supportsAgentControl ?: false,
        )
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        client?.close()
        client = null
    }

    override suspend fun startSession(config: SessionConfig): StepResult = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        val response = bridge.request(
            command = "start_session",
            state = config.stateName,
            navExportDir = config.navExportDir,
            controlMode = config.controlMode,
            selectedModel = config.selectedModel,
            playerCount = config.playerCount,
        )
        mapResponse(response)
    }

    override suspend fun closeSession(): StepResult = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        mapResponse(bridge.request("close_session"))
    }

    override suspend fun step(input: EmulatorInput): StepResult = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        val response = bridge.request(
            command = "step",
            action = input.buttons,
            repeat = input.repeat,
            includeFrame = input.includeFrame,
            includeTrace = input.includeTrace,
        )
        mapResponse(response)
    }

    override suspend fun snapshot(): GameSnapshot = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        val response = bridge.request("snapshot")
        mapSnapshot(response)
    }

    override suspend fun saveState(name: String) = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        bridge.request("save_state", saveName = name, includeFrame = false, includeTrace = false)
        Unit
    }

    override suspend fun loadState(name: String): StepResult = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        val response = bridge.request("load_state", state = name)
        mapResponse(response)
    }

    override suspend fun listStates(): List<StateInfo> = withContext(Dispatchers.IO) {
        val bridge = requireClient()
        val response = bridge.request("discover", includeFrame = false, includeTrace = false)
        response.states.map { StateInfo(name = it.name, path = it.path) }
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray {
        throw UnsupportedOperationException("gym-retro backend does not support direct memory access")
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        throw UnsupportedOperationException("gym-retro backend does not support direct memory access")
    }

    override fun close() {
        isConnected = false
        client?.close()
        client = null
    }

    /** Expose the underlying client for operations that need gym-retro-specific features. */
    fun configure(navExportDir: String?, controlMode: String?, selectedModel: String?) {
        client?.request(
            command = "configure",
            navExportDir = navExportDir,
            controlMode = controlMode,
            selectedModel = selectedModel,
            includeFrame = false,
            includeTrace = false,
        )
    }

    fun discover(navExportDir: String?, controlMode: String?, selectedModel: String?): StepResult {
        val bridge = requireClient()
        val response = bridge.request(
            command = "discover",
            navExportDir = navExportDir,
            controlMode = controlMode,
            selectedModel = selectedModel,
            includeFrame = false,
            includeTrace = false,
        )
        return mapResponse(response)
    }

    fun setRecording(active: Boolean): StepResult {
        val bridge = requireClient()
        val response = bridge.request(
            command = if (active) "start_recording" else "stop_recording",
            includeFrame = false,
            includeTrace = false,
        )
        return mapResponse(response)
    }

    private fun requireClient(): EmulatorBridgeClient {
        return client ?: throw IllegalStateException("GymRetro backend is not connected")
    }

    private fun mapResponse(response: com.supermetroid.editor.integration.BridgeResponse): StepResult {
        if (!response.ok) {
            throw IllegalStateException(response.error ?: "Bridge error")
        }
        return StepResult(
            session = mapSession(response.session),
            snapshot = mapBridgeSnapshot(response.snapshot),
            states = response.states.map { StateInfo(name = it.name, path = it.path) },
            models = response.models.map { ModelInfo(name = it.name, path = it.path, format = it.format) },
            recordingPath = response.recordingPath,
            message = response.message,
        )
    }

    private fun mapSnapshot(response: com.supermetroid.editor.integration.BridgeResponse): GameSnapshot {
        return mapBridgeSnapshot(response.snapshot)
    }

    private fun mapSession(session: com.supermetroid.editor.integration.BridgeSessionState?): SessionState {
        if (session == null) return SessionState()
        return SessionState(
            active = session.active,
            paused = session.paused,
            currentState = session.currentState,
            frameCounter = session.frameCounter,
            recording = session.recording,
            controlMode = session.controlMode,
            selectedModel = session.selectedModel,
            playerCount = session.playerCount,
        )
    }

    private fun mapBridgeSnapshot(snapshot: com.supermetroid.editor.integration.BridgeSnapshot?): GameSnapshot {
        if (snapshot == null) return GameSnapshot()
        return GameSnapshot(
            frameCounter = snapshot.frameCounter,
            playerCount = snapshot.playerCount,
            roomId = snapshot.roomId,
            roomName = snapshot.roomName,
            areaName = snapshot.areaName,
            gameState = snapshot.gameState,
            health = snapshot.health,
            samusX = snapshot.samusX,
            samusY = snapshot.samusY,
            doorTransition = snapshot.doorTransition,
            terminated = snapshot.terminated,
            truncated = snapshot.truncated,
            frameWidth = snapshot.frameWidth,
            frameHeight = snapshot.frameHeight,
            frameRgb24Base64 = snapshot.frameRgb24Base64,
            traceIncluded = snapshot.traceIncluded,
            controllerConnected = snapshot.controllerConnected,
            controllerName = snapshot.controllerName,
            lastAction = snapshot.lastAction,
            lastRequestedAction = snapshot.lastRequestedAction,
            lastActionPreSanitize = snapshot.lastActionPreSanitize,
            lastActionSource = snapshot.lastActionSource,
            lastModelActionIndex = snapshot.lastModelActionIndex,
            expectedTraceLabel = snapshot.expectedTraceLabel,
            expectedTraceSource = snapshot.expectedTraceSource,
            pathProgress = snapshot.pathProgress,
            pathProgressMax = snapshot.pathProgressMax,
            pathCompletion = snapshot.pathCompletion,
            pathErrorPx = snapshot.pathErrorPx,
            pathBestErrorPx = snapshot.pathBestErrorPx,
            routeCompleted = snapshot.routeCompleted,
            recordedFrames = snapshot.recordedFrames,
            expectedTrace = snapshot.expectedTrace.map { TracePoint(it.frame, it.roomId, it.x, it.y) },
            trace = snapshot.trace.map { TracePoint(it.frame, it.roomId, it.x, it.y) },
        )
    }
}
