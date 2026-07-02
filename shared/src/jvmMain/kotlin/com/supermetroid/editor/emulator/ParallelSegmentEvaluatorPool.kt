package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ParallelSegmentEvaluatorPool(
    corePath: String,
    romPath: String,
    checkpoint: SegmentCheckpoint,
    config: SegmentEvalConfig,
    workerCount: Int,
) : AutoCloseable {
    private val workers: List<Worker>
    private val executor = Executors.newFixedThreadPool(workerCount.coerceAtLeast(1))

    init {
        val count = workerCount.coerceAtLeast(1)
        workers = List(count) { Worker(corePath, romPath, checkpoint, config) }
    }

    fun evaluateAll(candidates: List<IntArray>, bestReachedInputs: Int?): List<SegmentScore> {
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size == 1) {
            return listOf(workers[0].evaluate(candidates[0], bestReachedInputs))
        }

        val results = arrayOfNulls<SegmentScore>(candidates.size)
        val latch = CountDownLatch(candidates.size)
        candidates.forEachIndexed { index, inputs ->
            val worker = workers[index % workers.size]
            executor.submit(
                Callable {
                    try {
                        synchronized(worker) {
                            results[index] = worker.evaluate(inputs, bestReachedInputs)
                        }
                    } finally {
                        latch.countDown()
                    }
                },
            )
        }
        latch.await()
        return results.map { it!! }
    }

    override fun close() {
        executor.shutdown()
        workers.forEach { it.close() }
    }

    private class Worker(
        corePath: String,
        romPath: String,
        checkpoint: SegmentCheckpoint,
        config: SegmentEvalConfig,
    ) : AutoCloseable {
        private val core = LibretroCore(corePath)
        private val stepper = LibretroEmulatorFrameStepper(core)
        private val evaluator = LibretroSegmentEvaluator(stepper, checkpoint, config)

        init {
            core.init()
            check(core.loadGame(romPath)) { "Failed to load ROM: $romPath" }
        }

        fun evaluate(inputs: IntArray, bestReachedInputs: Int?): SegmentScore =
            evaluator.evaluate(inputs, bestReachedInputs)

        override fun close() {
            core.close()
        }
    }
}
