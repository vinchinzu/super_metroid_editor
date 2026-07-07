package com.supermetroid.editor.tas

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * In-process hill climb over a [TasMovie]: mutate, replay, keep improvements.
 *
 * The speed win over batch replay (`tas-batch` + external mutation) is the
 * greenzone: a candidate is identical to the current best before
 * [TasMutation.firstChangedFrame], so [TasSession.seek] restores the nearest
 * anchor and only the suffix is emulated. Late-movie edits cost a fraction of
 * a full replay.
 *
 * Scope: *improving* runs that already achieve their goal (fewer
 * `framesToGoal`, then lower in-game time). Discovering routes from scratch
 * needs shaped progress rewards — that stays in the Python stack
 * (`tas/climb_optimizer.py`).
 *
 * Greenzone discipline: anchors always describe the current best movie's
 * input prefix. After a rejected candidate, anchors created during its run
 * are dropped via [TasSession.invalidateAfter]; an accepted candidate becomes
 * the best, so its anchors are valid as-is.
 */
class TasOptimizer(
    private val session: TasSession,
    private val goal: TasGoal,
    private val config: Config = Config(),
) {

    @Serializable
    data class Config(
        val iterations: Int = 200,
        val rngSeed: Long = 0x5EED,
        /** Mutable window; frames before [windowStart] are never edited. */
        val windowStart: Int = 0,
        /** Exclusive window end; 0 tracks the (shrinking) goal frame. */
        val windowEnd: Int = 0,
        val restartProbability: Double = 0.15,
        val maxEditsPerCandidate: Int = 3,
    )

    /** One accepted improvement, in iteration order. */
    @Serializable
    data class Improvement(
        val iteration: Int,
        val framesToGoal: Int,
        val endIgtFrames: Long,
        val firstChangedFrame: Int,
        val source: String,
    )

    class Result(
        val best: TasMovie,
        val bestResult: TasRunResult,
        val seedResult: TasRunResult,
        val evaluations: Int,
        val improvements: List<Improvement>,
    )

    /**
     * Optimize [seed] from the session's current start point (the caller
     * loads the start state; it becomes frame 0). Throws if the seed does not
     * achieve [goal] — seed with a verified run.
     */
    fun optimize(
        seed: TasMovie,
        onImprovement: (Improvement) -> Unit = {},
    ): Result {
        require(goal.type != TasGoal.TYPE_SURVIVE) {
            "survive goals have no frame count to minimize"
        }
        // Anchors from anything the session ran before (probes, prior movies)
        // describe a different timeline; only the frame-0 anchor survives.
        session.invalidateAfter(0)
        session.seek(0, seed)
        val seedResult = TasEvaluator.run(session, seed, goal, traceEvery = 0)
        require(seedResult.achieved) {
            "Seed movie does not achieve the goal (ran ${seedResult.totalFramesRun} frames" +
                (if (seedResult.died) ", died" else "") +
                "); optimize needs a verified run — use the Python optimizer for discovery"
        }

        // Frames past the goal are dead weight and would dilute the window.
        var best = seed.trimmedTo(seedResult.framesToGoal)
        var bestResult = seedResult
        val mutator = TasMutator(
            seed = best,
            rng = Random(config.rngSeed),
            windowStart = config.windowStart,
            windowEnd = config.windowEnd,
            restartProbability = config.restartProbability,
            maxEditsPerCandidate = config.maxEditsPerCandidate,
        )
        val improvements = mutableListOf<Improvement>()

        for (iteration in 1..config.iterations) {
            val candidate = mutator.mutate(best)
            if (candidate.firstChangedFrame >= bestResult.framesToGoal) continue
            session.seek(candidate.firstChangedFrame, candidate.movie)
            // A candidate that hasn't achieved by the current best's goal
            // frame can never win — don't emulate past it.
            val cappedGoal = goal.copy(maxFrames = bestResult.framesToGoal)
            val result = TasEvaluator.run(session, candidate.movie, cappedGoal, traceEvery = 0)
            if (result.improvesOn(bestResult)) {
                best = candidate.movie.trimmedTo(result.framesToGoal)
                bestResult = result
                val improvement = Improvement(
                    iteration = iteration,
                    framesToGoal = result.framesToGoal,
                    endIgtFrames = result.endIgtFrames,
                    firstChangedFrame = candidate.firstChangedFrame,
                    source = candidate.source,
                )
                improvements += improvement
                onImprovement(improvement)
            } else {
                // Anchors past the divergence point belong to the rejected
                // candidate's timeline, not the best movie's.
                session.invalidateAfter(candidate.firstChangedFrame)
            }
        }

        // Re-verify the winner linearly from frame 0 so the reported result
        // never depends on greenzone bookkeeping.
        if (improvements.isNotEmpty()) {
            session.seek(0, best)
            bestResult = TasEvaluator.run(session, best, goal, traceEvery = 0)
            check(bestResult.achieved && bestResult.framesToGoal == best.frameCount) {
                "Greenzone result did not reproduce on linear replay " +
                    "(got framesToGoal=${bestResult.framesToGoal}, expected ${best.frameCount})"
            }
        }

        return Result(
            best = best,
            bestResult = bestResult,
            seedResult = seedResult,
            evaluations = config.iterations,
            improvements = improvements,
        )
    }

    /**
     * Resize to exactly [length] frames — truncating dead weight past the
     * goal, or materializing noop padding when the goal was achieved past the
     * movie's end (the evaluator noop-pads, so those frames are real inputs).
     */
    private fun TasMovie.trimmedTo(length: Int): TasMovie =
        TasMovie(meta, List(length) { frameAt(it) })

    private fun TasRunResult.improvesOn(current: TasRunResult): Boolean =
        achieved && (
            framesToGoal < current.framesToGoal ||
                (framesToGoal == current.framesToGoal && endIgtFrames < current.endIgtFrames)
            )
}
