package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.tas.TasInput
import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasMovieMeta
import com.supermetroid.editor.tas.TasTracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class RoutePlaybackState {
    IDLE,
    RECORDING,
    PLAYING,
    PAUSED,
}

class RouteEditorState(
    private val physicsPluginFactory: PhysicsPluginFactory = SmRevPredictPluginFactory,
) {
    var currentMovie by mutableStateOf<TasMovie?>(null)

    var playbackState by mutableStateOf(RoutePlaybackState.IDLE)
        private set

    var currentFrame by mutableStateOf(0)
        private set

    var recordingStartFrame by mutableStateOf(0)
        private set

    var statusMessage by mutableStateOf("No movie loaded")
        private set

    var routeDirectory by mutableStateOf("routes")

    var availableRoutes by mutableStateOf<List<String>>(emptyList())
        private set

    var residualProfile by mutableStateOf<ResidualProfile?>(null)
        private set

    var predictedHopTrace by mutableStateOf<List<TasTracePoint>>(emptyList())
        private set

    private val recordedFrames = mutableListOf<IntArray>()
    private val recordedTrace = mutableListOf<TasTracePoint>()
    
    private var physicsPlugin: PhysicsPredictPlugin = physicsPluginFactory.create()
    
    init {
        tryLoadHopShort()
    }
    
    private fun tryLoadHopShort() {
        val hopShortFile = File(routeDirectory, "hop_short.tasmovie.json")
        if (hopShortFile.exists()) {
            val result = physicsPlugin.hydrate(null)
            if (result.isSuccess) {
                statusMessage = "Loaded hop_short.tasmovie.json for prediction"
            }
        }
    }

    fun startRecording(stateName: String?, startFrame: Int = 0) {
        recordedFrames.clear()
        recordedTrace.clear()
        recordingStartFrame = startFrame
        currentFrame = 0
        playbackState = RoutePlaybackState.RECORDING
        currentMovie = TasMovie(
            meta = TasMovieMeta(
                startState = stateName,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        statusMessage = "Recording movie from frame $startFrame"
    }

    fun stopRecording() {
        val movie = TasMovie(
            meta = currentMovie?.meta ?: TasMovieMeta(createdAtEpochMs = System.currentTimeMillis()),
            frames = recordedFrames.toList(),
            trace = recordedTrace.toList(),
        )
        currentMovie = movie
        playbackState = RoutePlaybackState.IDLE
        statusMessage = "Recorded ${movie.frameCount} frames, ${movie.trace.size} trace points"
        updatePrediction()
    }

    fun recordFrame(frame: Int, buttons: List<Int>, roomId: Int?, x: Int?, y: Int?) {
        if (playbackState != RoutePlaybackState.RECORDING) return

        val relativeFrame = frame - recordingStartFrame
        val sanitized = TasInput.sanitize(buttons)
        
        while (recordedFrames.size <= relativeFrame) {
            recordedFrames.add(TasInput.noop())
        }
        recordedFrames[relativeFrame] = sanitized

        if (roomId != null && x != null && y != null) {
            recordedTrace.add(TasTracePoint(
                frame = relativeFrame,
                x = x,
                y = y,
                roomId = roomId,
            ))
        }

        currentFrame = relativeFrame
    }

    fun startPlayback() {
        val movie = currentMovie
        if (movie == null) {
            statusMessage = "No movie to play"
            return
        }
        currentFrame = 0
        playbackState = RoutePlaybackState.PLAYING
        statusMessage = "Playing movie (${movie.frameCount} frames)"
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
        val movie = currentMovie ?: return
        if (currentFrame < movie.frameCount - 1) {
            currentFrame += 1
            statusMessage = "Frame $currentFrame of ${movie.frameCount}"
        }
    }

    fun stepBackward() {
        if (currentFrame > 0) {
            currentFrame -= 1
            statusMessage = "Frame $currentFrame of ${currentMovie?.frameCount ?: 0}"
        }
    }

    fun seekToFrame(frame: Int) {
        val movie = currentMovie ?: return
        currentFrame = frame.coerceIn(0, movie.frameCount - 1)
        statusMessage = "Seeked to frame $currentFrame"
    }

    fun getCurrentInput(): IntArray? {
        return currentMovie?.frameAt(currentFrame)
    }

    fun getCurrentTrace(): TasTracePoint? {
        return currentMovie?.trace?.lastOrNull { (it.frame ?: 0) <= currentFrame }
    }

    fun advancePlaybackFrame() {
        val movie = currentMovie ?: return
        if (playbackState != RoutePlaybackState.PLAYING) return
        if (currentFrame >= movie.frameCount - 1) {
            stopPlayback()
            statusMessage = "Movie completed"
            return
        }
        currentFrame += 1
    }

    suspend fun saveMovie(name: String? = null) {
        val movie = currentMovie ?: return
        val fileName = name ?: "movie_${System.currentTimeMillis()}"
        val movieFile = File(routeDirectory, "$fileName.tasmovie.json")

        withContext(Dispatchers.IO) {
            movie.save(movieFile, pretty = true)
        }

        statusMessage = "Saved movie to ${movieFile.name}"
        refreshAvailableRoutes()
    }

    suspend fun loadMovie(name: String) {
        val movieFile = File(routeDirectory, if (name.endsWith(".tasmovie.json")) name else "$name.tasmovie.json")
        if (!movieFile.exists()) {
            statusMessage = "Movie file not found: $name"
            return
        }

        try {
            val movie = withContext(Dispatchers.IO) {
                TasMovie.load(movieFile)
            }
            currentMovie = movie
            currentFrame = 0
            playbackState = RoutePlaybackState.IDLE
            statusMessage = "Loaded movie: ${movieFile.nameWithoutExtension} (${movie.frameCount} frames)"
            updatePrediction()
        } catch (e: Exception) {
            statusMessage = "Failed to load movie: ${e.message}"
        }
    }

    suspend fun refreshAvailableRoutes() {
        val dir = File(routeDirectory)
        availableRoutes = withContext(Dispatchers.IO) {
            if (!dir.exists()) emptyList()
            else dir.listFiles()
                ?.filter { it.name.endsWith(".tasmovie.json") }
                ?.map { it.nameWithoutExtension.removeSuffix(".tasmovie") }
                ?.sorted()
                ?: emptyList()
        }
    }

    fun clearMovie() {
        currentMovie = null
        currentFrame = 0
        playbackState = RoutePlaybackState.IDLE
        recordedFrames.clear()
        recordedTrace.clear()
        statusMessage = "Movie cleared"
    }

    fun truncateMovie(toFrame: Int) {
        val movie = currentMovie ?: return
        currentMovie = movie.truncated(toFrame)
        if (currentFrame >= toFrame) {
            currentFrame = toFrame - 1
        }
        statusMessage = "Truncated movie to $toFrame frames"
    }

    fun updateFrame(frame: Int, buttons: IntArray) {
        val movie = currentMovie ?: return
        val newFrames = movie.frames.toMutableList()
        while (newFrames.size <= frame) {
            newFrames.add(TasInput.noop())
        }
        newFrames[frame] = TasInput.sanitize(buttons.toList())
        currentMovie = TasMovie(movie.meta, newFrames, movie.trace)
        statusMessage = "Updated frame $frame"
        updatePrediction()
    }

    /**
     * Compute residual between predicted and SuperMetroidEnv harness observations.
     * 
     * Do NOT call this with desktop snes9x traces — that emulator is UI playback only.
     * Pass only SuperMetroidEnv observations. If no observations exist, residual will
     * be unmeasured (all fd_* null, all FrameTrust UNMEASURED).
     */
    fun computeResidual(superMetroidEnvObservations: List<TasTracePoint> = emptyList()) {
        val movie = currentMovie ?: return
        residualProfile = physicsPlugin.residual(movie, superMetroidEnvObservations)
    }

    /**
     * Update predicted hop overlay from current frame.
     */
    fun updatePrediction(fromFrame: Int = currentFrame) {
        val movie = currentMovie ?: return
        val hop = physicsPlugin.predictHop(movie, fromFrame)
        predictedHopTrace = hop.trace
        computeResidual()
    }

    /**
     * Get frame trust level for timeline coloring.
     */
    fun getFrameTrust(frame: Int): FrameTrust? {
        val profile = residualProfile ?: return null
        return profile.frameTrust.getOrNull(frame)
    }

    /**
     * Swap the physics plugin (for testing or switching backends).
     */
    fun setPhysicsPlugin(factory: PhysicsPluginFactory) {
        physicsPlugin = factory.create()
        tryLoadHopShort()
        updatePrediction()
    }
}
