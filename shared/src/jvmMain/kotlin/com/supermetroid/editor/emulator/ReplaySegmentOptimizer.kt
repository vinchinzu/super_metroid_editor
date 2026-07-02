package com.supermetroid.editor.emulator

import kotlin.random.Random

class ReplaySegmentOptimizer(
    corePath: String,
    romPath: String,
    checkpoint: SegmentCheckpoint,
    private val config: HillClimbConfig,
) : AutoCloseable {
    private val pool = ParallelSegmentEvaluatorPool(
        corePath = corePath,
        romPath = romPath,
        checkpoint = checkpoint,
        config = config.evalConfig,
        workerCount = config.workers,
    )
    private val mutator = InputWindowMutator(Random(config.seed))
    private val workers: Int = config.workers

    data class Result(
        val bestInputs: IntArray,
        val best: SegmentScore,
        val baseline: SegmentScore,
    )

    fun optimize(
        baselineInputs: IntArray,
        onImprovement: (generation: Int, score: SegmentScore, source: String, baseline: SegmentScore) -> Unit =
            { _, _, _, _ -> },
    ): Result {
        var bestInputs = baselineInputs.copyOf()
        var best = pool.evaluateAll(listOf(bestInputs), bestReachedInputs = null).single()
        val baseline = best
        var attemptsRemaining = config.iterations

        while (attemptsRemaining > 0) {
            val batchSize = minOf(workers, attemptsRemaining)
            val generation = (config.iterations - attemptsRemaining) / workers
            val mutations = List(batchSize) { worker ->
                mutator.mutate(
                    best = bestInputs,
                    baseline = baselineInputs,
                    iteration = generation * workers + worker,
                )
            }
            val reachedHint = best.reachedAfterInputs.takeIf {
                best.outcome == SegmentOutcome.REACHED_TARGET
            }
            val scores = pool.evaluateAll(
                candidates = mutations.map { it.inputs },
                bestReachedInputs = reachedHint,
            )
            for ((mutation, score) in mutations.zip(scores)) {
                if (score.isBetterThan(best)) {
                    bestInputs = mutation.inputs.copyOf()
                    best = score
                    onImprovement(generation + 1, best, mutation.source, baseline)
                }
            }
            attemptsRemaining -= batchSize
        }

        return Result(bestInputs = bestInputs, best = best, baseline = baseline)
    }

    override fun close() {
        pool.close()
    }
}
