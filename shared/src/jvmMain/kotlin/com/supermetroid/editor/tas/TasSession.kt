package com.supermetroid.editor.tas

import com.supermetroid.editor.libretro.LibretroCore
import java.io.Closeable
import java.io.File
import java.util.TreeMap

/**
 * Headless, deterministic emulator session for TAS work.
 *
 * Wraps [LibretroCore] directly (no audio, no UI) so movies can be executed
 * as fast as the core allows — this is the evaluation primitive for hill
 * climbing, genetic search, and model rollouts, and it runs against any ROM
 * the editor exports.
 *
 * Not thread-safe: all calls must come from one thread. Run parallel
 * evaluations as separate processes or one TasSession per thread with its own
 * core instance loaded from a copied .so path.
 */
class TasSession(
    corePath: String,
    private val romPath: String,
    /** Keep a greenzone savestate every N frames (0 disables). */
    private val anchorInterval: Int = 600,
    /** Cap on retained greenzone anchors; oldest non-zero anchors are evicted. */
    private val maxAnchors: Int = 64,
) : Closeable {

    private val core = LibretroCore(corePath)
    private val greenzone = TreeMap<Int, ByteArray>()
    private var startState: ByteArray? = null

    /** Frames stepped since the session start point (state load or power-on). */
    var frame: Int = 0
        private set

    init {
        core.init()
        check(core.loadGame(romPath)) { "Failed to load ROM: $romPath" }
    }

    /** Restart from power-on (frame 0, greenzone cleared). */
    fun reset() {
        check(core.loadGame(romPath)) { "Failed to reload ROM: $romPath" }
        startState = null
        restartTimeline()
    }

    /** Load a start state (raw or gzipped .state file) and make it frame 0. */
    fun loadStateFile(file: File) = loadStateBytes(Bk2Io.loadStateFile(file))

    /** Load a serialized state and make it frame 0. */
    fun loadStateBytes(state: ByteArray) {
        core.unserializeState(state)
        startState = state
        restartTimeline()
    }

    fun saveStateBytes(): ByteArray = core.serializeState()

    fun saveStateFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(saveStateBytes())
    }

    /** Advance one frame with the given env-order buttons. */
    fun step(buttons: IntArray): Int {
        core.setInput(0, buttons.toList())
        core.run()
        frame++
        if (anchorInterval > 0 && frame % anchorInterval == 0) {
            addAnchor(frame)
        }
        return frame
    }

    /** Read the parsed game state at the current frame. */
    fun snapshot(): SmSnapshot = SmRam.parse(core.readWram(0, SmRam.SNAPSHOT_SIZE))

    fun readWram(address: Int, size: Int): ByteArray = core.readWram(address, size)

    /** Current video frame (ARGB pixels), if the core has rendered one. */
    fun videoFrame(): LibretroCore.FrameSnapshot? =
        core.captureRuntimeSnapshot(includeFrame = true, includeWram = false).frame

    /**
     * Seek to [targetFrame] by restoring the nearest greenzone anchor at or
     * before it, then replaying [movie] inputs. Enables cheap rerecording and
     * mid-movie mutation without replaying from frame 0.
     */
    fun seek(targetFrame: Int, movie: TasMovie) {
        require(targetFrame >= 0) { "Target frame must be non-negative" }
        var replayFrom = 0
        val anchor = greenzone.floorEntry(targetFrame)
        if (anchor != null && anchor.key <= targetFrame) {
            core.unserializeState(anchor.value)
            replayFrom = anchor.key
        } else {
            val start = startState
            if (start != null) core.unserializeState(start) else reset()
        }
        frame = replayFrom
        while (frame < targetFrame) {
            step(movie.frameAt(frame))
        }
    }

    /**
     * Play [movie] from the current frame to its end (or [maxFrames]),
     * invoking [onFrame] after each step. Return the last snapshot.
     */
    fun playMovie(
        movie: TasMovie,
        maxFrames: Int = movie.frameCount,
        onFrame: ((frame: Int, snapshot: SmSnapshot) -> Boolean)? = null,
    ): SmSnapshot {
        var snap = snapshot()
        while (frame < maxFrames) {
            step(movie.frameAt(frame))
            if (onFrame != null) {
                snap = snapshot()
                if (!onFrame(frame, snap)) return snap
            }
        }
        if (onFrame == null) snap = snapshot()
        return snap
    }

    /**
     * Drop greenzone anchors after [editFrame]. Must be called when movie
     * inputs before an existing anchor change, or seek() would restore a
     * state produced by the old inputs.
     */
    fun invalidateAfter(editFrame: Int) {
        greenzone.tailMap(editFrame, false).clear()
    }

    private fun restartTimeline() {
        frame = 0
        greenzone.clear()
        if (anchorInterval > 0) {
            // Anchor frame 0 so seek() never has to reload the ROM.
            greenzone[0] = core.serializeState()
        }
    }

    private fun addAnchor(atFrame: Int) {
        greenzone[atFrame] = core.serializeState()
        while (greenzone.size > maxAnchors) {
            // Evict the oldest anchor after frame 0.
            val victim = greenzone.higherKey(0) ?: break
            greenzone.remove(victim)
        }
    }

    override fun close() {
        core.close()
    }
}
