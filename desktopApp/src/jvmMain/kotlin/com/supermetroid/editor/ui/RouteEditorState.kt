package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.integration.RouteInputFrame
import com.supermetroid.editor.integration.RoutePositionSample
import com.supermetroid.editor.integration.TasRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

enum class RoutePlaybackState {
    IDLE,
    RECORDING,
    PLAYING,
    PAUSED,
}

class RouteEditorState {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    var currentRoute by mutableStateOf<TasRoute?>(null)
        private set

    var playbackState by mutableStateOf(RoutePlaybackState.IDLE)
        private set

    var currentFrame by mutableStateOf(0)
        private set

    var recordingStartFrame by mutableStateOf(0)
        private set

    var statusMessage by mutableStateOf("No route loaded")
        private set

    var routeDirectory by mutableStateOf("routes")
        private set

    var availableRoutes by mutableStateOf<List<String>>(emptyList())
        private set

    fun startRecording(stateName: String?, startFrame: Int = 0) {
        currentRoute = TasRoute(
            name = "route_${System.currentTimeMillis()}",
            startStateName = stateName,
        )
        recordingStartFrame = startFrame
        currentFrame = 0
        playbackState = RoutePlaybackState.RECORDING
        statusMessage = "Recording route from frame $startFrame"
    }

    fun stopRecording() {
        playbackState = RoutePlaybackState.IDLE
        statusMessage = currentRoute?.let {
            "Recorded ${it.inputs.size} input frames, ${it.positions.size} position samples"
        } ?: "Recording stopped"
    }

    fun recordFrame(frame: Int, buttons: List<Int>, roomId: Int?, x: Int?, y: Int?) {
        if (playbackState != RoutePlaybackState.RECORDING) return
        val route = currentRoute ?: return

        val relativeFrame = frame - recordingStartFrame
        var updatedRoute = route.withInput(relativeFrame, buttons)

        if (roomId != null && x != null && y != null) {
            updatedRoute = updatedRoute.withPosition(relativeFrame, roomId, x, y)
        }

        currentRoute = updatedRoute
        currentFrame = relativeFrame
    }

    fun startPlayback() {
        if (currentRoute == null) {
            statusMessage = "No route to play"
            return
        }
        currentFrame = 0
        playbackState = RoutePlaybackState.PLAYING
        statusMessage = "Playing route"
    }

    fun pausePlayback() {
        playbackState = RoutePlaybackState.PAUSED
        statusMessage = "Playback paused at frame $currentFrame"
    }

    fun resumePlayback() {
        playbackState = RoutePlaybackState.PLAYING
        statusMessage = "Playback resumed"
    }

    fun stopPlayback() {
        playbackState = RoutePlaybackState.IDLE
        currentFrame = 0
        statusMessage = "Playback stopped"
    }

    fun stepForward() {
        val route = currentRoute ?: return
        if (currentFrame < route.frameCount - 1) {
            currentFrame += 1
            statusMessage = "Frame $currentFrame of ${route.frameCount}"
        }
    }

    fun stepBackward() {
        if (currentFrame > 0) {
            currentFrame -= 1
            statusMessage = "Frame $currentFrame of ${currentRoute?.frameCount ?: 0}"
        }
    }

    fun seekToFrame(frame: Int) {
        val route = currentRoute ?: return
        currentFrame = frame.coerceIn(0, route.frameCount - 1)
        statusMessage = "Seeked to frame $currentFrame"
    }

    fun getCurrentInput(): List<Int>? {
        return currentRoute?.inputAt(currentFrame)
    }

    fun getCurrentPosition(): RoutePositionSample? {
        return currentRoute?.positionAt(currentFrame)
    }

    fun advancePlaybackFrame() {
        val route = currentRoute ?: return
        if (playbackState != RoutePlaybackState.PLAYING) return
        if (currentFrame >= route.frameCount - 1) {
            stopPlayback()
            statusMessage = "Route completed"
            return
        }
        currentFrame += 1
    }

    suspend fun saveRoute(name: String? = null) {
        val route = currentRoute ?: return
        val fileName = name ?: route.name
        val routeFile = File(routeDirectory, "$fileName.json")

        withContext(Dispatchers.IO) {
            routeFile.parentFile?.mkdirs()
            routeFile.writeText(json.encodeToString(TasRoute.serializer(), route.copy(name = fileName)))
        }

        statusMessage = "Saved route to ${routeFile.name}"
        refreshAvailableRoutes()
    }

    suspend fun loadRoute(name: String) {
        val routeFile = File(routeDirectory, "$name.json")
        if (!routeFile.exists()) {
            statusMessage = "Route file not found: $name"
            return
        }

        try {
            val route = withContext(Dispatchers.IO) {
                json.decodeFromString(TasRoute.serializer(), routeFile.readText())
            }
            currentRoute = route
            currentFrame = 0
            playbackState = RoutePlaybackState.IDLE
            statusMessage = "Loaded route: ${route.name} (${route.frameCount} frames)"
        } catch (e: Exception) {
            statusMessage = "Failed to load route: ${e.message}"
        }
    }

    suspend fun refreshAvailableRoutes() {
        val dir = File(routeDirectory)
        availableRoutes = withContext(Dispatchers.IO) {
            if (!dir.exists()) emptyList()
            else dir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
                ?: emptyList()
        }
    }

    fun clearRoute() {
        currentRoute = null
        currentFrame = 0
        playbackState = RoutePlaybackState.IDLE
        statusMessage = "Route cleared"
    }

    fun trimRoute(toFrame: Int) {
        val route = currentRoute ?: return
        currentRoute = route.trim(toFrame)
        if (currentFrame >= toFrame) {
            currentFrame = toFrame - 1
        }
        statusMessage = "Trimmed route to $toFrame frames"
    }

}
