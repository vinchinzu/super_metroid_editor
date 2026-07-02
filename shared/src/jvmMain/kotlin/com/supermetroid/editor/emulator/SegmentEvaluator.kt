package com.supermetroid.editor.emulator

import java.io.File

data class HillClimbConfig(
    val replayFile: File,
    val outputDir: File,
    val iterations: Int,
    val seed: Int,
    val startRoom: Int,
    val targetRoom: Int,
    val maxExtraFrames: Int,
    val evalSlack: Int,
    val appendTail: Boolean,
    val workers: Int,
) {
    val evalConfig: SegmentEvalConfig
        get() = SegmentEvalConfig(
            startRoom = startRoom,
            targetRoom = targetRoom,
            maxExtraFrames = maxExtraFrames,
            evalSlack = evalSlack,
        )

    companion object {
        private const val DEFAULT_START_ROOM = 0x96BA
        private const val DEFAULT_TARGET_ROOM = 0x92FD

        fun fromEnvironment(): HillClimbConfig {
            val replayPath = env("SMEDIT_HILL_REPLAY")
                ?: error("Set SMEDIT_HILL_REPLAY to a .smreplay or attempt .json path")
            return HillClimbConfig(
                replayFile = File(replayPath),
                outputDir = File(env("SMEDIT_HILL_OUTPUT_DIR") ?: "build/replay-hillclimb"),
                iterations = env("SMEDIT_HILL_ITERATIONS")?.toIntOrNull()?.coerceAtLeast(0) ?: 500,
                seed = env("SMEDIT_HILL_SEED")?.toIntOrNull() ?: 0x5EED,
                startRoom = env("SMEDIT_HILL_START_ROOM")?.parseIntFlexible() ?: DEFAULT_START_ROOM,
                targetRoom = env("SMEDIT_HILL_TARGET_ROOM")?.parseIntFlexible() ?: DEFAULT_TARGET_ROOM,
                maxExtraFrames = env("SMEDIT_HILL_MAX_EXTRA_FRAMES")?.toIntOrNull()?.coerceAtLeast(0) ?: 240,
                evalSlack = env("SMEDIT_HILL_EVAL_SLACK")?.toIntOrNull()?.coerceAtLeast(0) ?: 60,
                appendTail = env("SMEDIT_HILL_APPEND_TAIL")?.lowercase() != "false",
                workers = resolveWorkerCount(env("SMEDIT_HILL_WORKERS")),
            )
        }

        /**
         * snes9x_libretro is not stable with multiple concurrent instances in one JVM.
         * Default to a single worker; set SMEDIT_HILL_ALLOW_PARALLEL=1 to opt in.
         */
        private fun resolveWorkerCount(requested: String?): Int {
            val parsed = requested?.toIntOrNull()?.coerceAtLeast(1)
            if (parsed != null) return parsed
            val allowParallel = env("SMEDIT_HILL_ALLOW_PARALLEL")?.lowercase() in setOf("1", "true", "yes")
            return if (allowParallel) {
                Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            } else {
                1
            }
        }
    }
}

data class SegmentEvalConfig(
    val startRoom: Int,
    val targetRoom: Int,
    val maxExtraFrames: Int,
    val evalSlack: Int,
)

data class SegmentCheckpoint(
    val state: ByteArray,
    val frameNumber: Long,
    val startHealth: Int,
    val startY: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentCheckpoint) return false
        return frameNumber == other.frameNumber &&
            startHealth == other.startHealth &&
            startY == other.startY &&
            state.contentEquals(other.state)
    }

    override fun hashCode(): Int {
        var result = state.contentHashCode()
        result = 31 * result + frameNumber.hashCode()
        result = 31 * result + startHealth
        result = 31 * result + startY
        return result
    }
}

object SegmentCheckpointBuilder {
    fun build(
        stepper: FrameStepper,
        bundle: LoadedReplayBundle,
        segment: ReplaySegment,
    ): SegmentCheckpoint {
        stepper.loadState(bundle.initialState)
        val firstFrame = bundle.log.frames.firstOrNull()?.frameNumber ?: 0L
        stepper.resetFrameCounter(firstFrame)
        for (index in 0 until segment.startIndex) {
            stepper.runOneFrame(bundle.log.frames[index].inputBits)
        }
        val state = stepper.saveState()
        val startFrame = bundle.log.frames[segment.startIndex].frameState
        return SegmentCheckpoint(
            state = state,
            frameNumber = segment.startFrameNumber,
            startHealth = startFrame.health ?: 0,
            startY = startFrame.samusY ?: Int.MAX_VALUE,
        )
    }
}

class LibretroSegmentEvaluator(
    private val stepper: FrameStepper,
    private val checkpoint: SegmentCheckpoint,
    private val config: SegmentEvalConfig,
) {
    fun evaluate(inputs: IntArray, bestReachedInputs: Int? = null): SegmentScore {
        stepper.loadState(checkpoint.state)
        stepper.resetFrameCounter(checkpoint.frameNumber)

        var bestY = checkpoint.startY
        var health = checkpoint.startHealth
        var lastRoom: Int? = null
        var wrongRoomFrames = 0
        val maxFrames = evalHorizon(inputs.size, bestReachedInputs)

        for (index in 0 until maxFrames) {
            val state = stepper.advanceFrame(inputs.getOrElse(index) { 0 })
            lastRoom = state.roomId ?: lastRoom
            health = state.health ?: health
            state.samusY?.let { y -> bestY = minOf(bestY, y) }

            if (health <= 0) {
                return SegmentScorer.death(index, health, bestY, lastRoom)
            }
            if (state.isPlayableIn(config.targetRoom)) {
                return SegmentScorer.reached(index, health, checkpoint.startY, bestY, lastRoom)
            }
            if (SegmentScorer.isWrongRoom(state, config.startRoom, config.targetRoom)) {
                wrongRoomFrames++
            }
        }

        return SegmentScorer.incomplete(
            maxFrames = maxFrames,
            health = health,
            startY = checkpoint.startY,
            bestY = bestY,
            wrongRoomFrames = wrongRoomFrames,
            lastRoom = lastRoom,
        )
    }

    private fun evalHorizon(inputSize: Int, bestReachedInputs: Int?): Int {
        val ceiling = inputSize + config.maxExtraFrames
        if (bestReachedInputs == null) return ceiling
        return minOf(bestReachedInputs + config.evalSlack, ceiling)
    }
}
