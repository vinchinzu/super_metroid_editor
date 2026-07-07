package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.tas.Bk2Io
import com.supermetroid.editor.tas.TasInput
import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasMovieMeta
import java.io.File

/**
 * TAS recording/playback state for the emulator workspace.
 *
 * Records every input frame sent to the backend while armed, and can replace
 * user input with movie frames during playback. Movies save to
 * `editor_recordings/` as `.tasmovie.json` (or `.bk2` for the Python stack)
 * and run headlessly via `cli tas-run`.
 */
class TasWorkspaceState(
    private val recordingsDir: File = File("editor_recordings"),
) {
    enum class Mode { IDLE, RECORDING, PLAYING }

    var mode by mutableStateOf(Mode.IDLE)
        private set
    var frameIndex by mutableStateOf(0)
        private set
    var movieName by mutableStateOf("recording")
    var loadedMovie by mutableStateOf<TasMovie?>(null)
        private set
    var startStateName by mutableStateOf<String?>(null)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set

    private val recordedFrames = mutableListOf<IntArray>()

    val isRecording: Boolean get() = mode == Mode.RECORDING
    val isPlaying: Boolean get() = mode == Mode.PLAYING
    val recordedFrameCount: Int get() = recordedFrames.size

    /** Arm recording; [startState] names the save state the movie starts from. */
    fun startRecording(startState: String?) {
        recordedFrames.clear()
        startStateName = startState
        frameIndex = 0
        mode = Mode.RECORDING
        statusText = "Recording from ${startState ?: "current frame"}"
    }

    /** Stop recording and keep the frames as the loaded movie. */
    fun stopRecording(): TasMovie {
        val movie = TasMovie(
            meta = TasMovieMeta(
                startState = startStateName,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
            frames = recordedFrames.toList(),
        )
        loadedMovie = movie
        mode = Mode.IDLE
        statusText = "Recorded ${movie.frameCount} frames"
        return movie
    }

    /** Begin feeding [movie] frames as input. Caller is responsible for loading its start state. */
    fun startPlayback(movie: TasMovie) {
        loadedMovie = movie
        frameIndex = 0
        mode = Mode.PLAYING
        statusText = "Playing ${movie.frameCount} frames"
    }

    fun stop() {
        if (mode == Mode.RECORDING) stopRecording() else mode = Mode.IDLE
    }

    /**
     * Input for the next frame during playback, or null when not playing
     * (falls through to user input). Playback auto-stops at movie end.
     */
    fun playbackAction(): List<Int>? {
        if (mode != Mode.PLAYING) return null
        val movie = loadedMovie ?: return null
        if (frameIndex >= movie.frameCount) {
            mode = Mode.IDLE
            statusText = "Playback finished (${movie.frameCount} frames)"
            return null
        }
        return movie.frameAt(frameIndex).toList()
    }

    /** Advance TAS bookkeeping after the backend stepped [repeat] frames of [buttons]. */
    fun onFrameStepped(buttons: List<Int>, repeat: Int) {
        when (mode) {
            Mode.RECORDING -> {
                val frame = TasInput.sanitize(buttons)
                repeat(repeat) { recordedFrames.add(frame) }
                frameIndex = recordedFrames.size
            }
            Mode.PLAYING -> frameIndex += repeat
            Mode.IDLE -> {}
        }
    }

    /** Save the loaded movie; extension picks the container (.bk2 or .tasmovie.json). */
    fun saveMovie(name: String = movieName): File? {
        val movie = loadedMovie ?: run {
            statusText = "No movie to save"
            return null
        }
        val safe = name.ifBlank { "recording" }
        val file = if (safe.endsWith(".bk2", ignoreCase = true)) {
            File(recordingsDir, safe).also { Bk2Io.write(it, movie) }
        } else {
            val base = safe.removeSuffix(".tasmovie.json").removeSuffix(".json")
            File(recordingsDir, "$base.${TasMovie.FILE_EXTENSION}").also { movie.save(it, pretty = false) }
        }
        statusText = "Saved ${movie.frameCount} frames to ${file.name}"
        return file
    }

    /** Load a movie from `editor_recordings/` or an absolute path (.tasmovie.json or .bk2). */
    fun loadMovie(nameOrPath: String): TasMovie? {
        val candidate = File(nameOrPath)
        val file = if (candidate.isAbsolute || candidate.isFile) candidate else File(recordingsDir, nameOrPath)
        if (!file.isFile) {
            statusText = "Movie not found: ${file.path}"
            return null
        }
        val movie = runCatching {
            if (file.extension.equals("bk2", ignoreCase = true)) Bk2Io.read(file).movie
            else TasMovie.load(file)
        }.getOrElse { e ->
            statusText = "Failed to load movie: ${e.message}"
            return null
        }
        loadedMovie = movie
        movieName = file.name
        statusText = "Loaded ${movie.frameCount} frames from ${file.name}"
        return movie
    }

    fun listMovies(): List<String> =
        recordingsDir.listFiles { f -> f.name.endsWith(".tasmovie.json") || f.extension == "bk2" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
}
