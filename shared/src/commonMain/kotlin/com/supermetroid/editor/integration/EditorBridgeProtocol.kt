package com.supermetroid.editor.integration

import kotlinx.serialization.Serializable

@Serializable
data class BridgeCommand(
    val id: String,
    val command: String,
    val state: String? = null,
    val saveName: String? = null,
    val navExportDir: String? = null,
    val controlMode: String? = null,
    val selectedModel: String? = null,
    val repeat: Int = 1,
    val action: List<Int> = emptyList(),
    val includeFrame: Boolean = true,
    val includeTrace: Boolean = true,
)

@Serializable
data class BridgeResponse(
    val id: String? = null,
    val ok: Boolean = true,
    val error: String? = null,
    val capabilities: BridgeCapabilities? = null,
    val session: BridgeSessionState? = null,
    val snapshot: BridgeSnapshot? = null,
    val states: List<BridgeStateInfo> = emptyList(),
    val models: List<BridgeModelInfo> = emptyList(),
    val recordingPath: String? = null,
    val message: String? = null,
)

@Serializable
data class BridgeCapabilities(
    val game: String,
    val gameDir: String,
    val bridgeVersion: String,
    val supportsFrames: Boolean,
    val supportsRecording: Boolean,
    val supportsKeyboardInput: Boolean,
    val supportsControllerInput: Boolean = false,
    val supportsAgentControl: Boolean = false,
    val supportsHotConfig: Boolean = false,
)

@Serializable
data class BridgeSessionState(
    val active: Boolean = false,
    val paused: Boolean = true,
    val currentState: String? = null,
    val frameCounter: Int = 0,
    val recording: Boolean = false,
    val controlMode: String = "manual",
    val selectedModel: String? = null,
)

@Serializable
data class BridgeStateInfo(
    val name: String,
    val path: String,
)

@Serializable
data class BridgeModelInfo(
    val name: String,
    val path: String,
    val format: String,
)

@Serializable
data class BridgeTracePoint(
    val frame: Int,
    val roomId: Int,
    val x: Int,
    val y: Int,
)

@Serializable
data class BridgeSnapshot(
    val frameCounter: Int = 0,
    val roomId: Int? = null,
    val roomName: String? = null,
    val areaName: String? = null,
    val gameState: Int? = null,
    val health: Int? = null,
    val samusX: Int? = null,
    val samusY: Int? = null,
    val doorTransition: Boolean = false,
    val terminated: Boolean = false,
    val truncated: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameRgb24Base64: String? = null,
    val traceIncluded: Boolean = true,
    val controllerConnected: Boolean = false,
    val controllerName: String? = null,
    val lastAction: List<Int> = emptyList(),
    val lastRequestedAction: List<Int> = emptyList(),
    val lastActionPreSanitize: List<Int> = emptyList(),
    val lastActionSource: String = "manual",
    val lastModelActionIndex: Int? = null,
    val expectedTraceLabel: String? = null,
    val expectedTraceSource: String? = null,
    val pathProgress: Float = 0f,
    val pathProgressMax: Float = 0f,
    val pathCompletion: Float = 0f,
    val pathErrorPx: Float? = null,
    val pathBestErrorPx: Float? = null,
    val routeCompleted: Boolean = false,
    val recordedFrames: Int = 0,
    val expectedTrace: List<BridgeTracePoint> = emptyList(),
    val trace: List<BridgeTracePoint> = emptyList(),
)
