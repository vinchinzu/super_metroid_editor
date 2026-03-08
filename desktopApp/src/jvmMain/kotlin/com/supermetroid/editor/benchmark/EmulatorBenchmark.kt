package com.supermetroid.editor.benchmark

import com.supermetroid.editor.emulator.EmulatorBackend
import com.supermetroid.editor.emulator.EmulatorInput
import com.supermetroid.editor.emulator.LibretroBackend
import com.supermetroid.editor.emulator.GymRetroBackend
import com.supermetroid.editor.emulator.SessionConfig
import kotlinx.coroutines.runBlocking

/**
 * Headless benchmark comparing libretro vs stable-retro (gym-retro) backends.
 *
 * Usage:
 *   ./gradlew :desktopApp:run -PmainClass=com.supermetroid.editor.benchmark.EmulatorBenchmarkKt
 *
 * Or use the shell wrapper:
 *   ./tools/benchmark_backends.sh /path/to/rom.sfc
 *
 * Environment:
 *   SMEDIT_ROM_PATH       - path to Super Metroid ROM (.sfc)
 *   SMEDIT_LIBRETRO_CORE  - (optional) explicit core path
 *   BENCH_WARMUP_FRAMES   - warmup frames (default 60)
 *   BENCH_FRAMES          - benchmark frames (default 600)
 *   BENCH_BACKENDS        - comma-separated list (default "libretro,gym-retro")
 */
fun main() {
    val romPath = System.getenv("SMEDIT_ROM_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        ?: run {
            // Search common locations relative to CWD or project root
            val candidates = listOf(
                "custom_integrations/SuperMetroid-Snes/rom.sfc",
                "../custom_integrations/SuperMetroid-Snes/rom.sfc",
            )
            // Also walk up from CWD looking for the integrations dir
            var dir = java.io.File(System.getProperty("user.dir"))
            var found: String? = null
            for (i in 0..4) {
                val f = java.io.File(dir, "custom_integrations/SuperMetroid-Snes/rom.sfc")
                if (f.exists()) { found = f.absolutePath; break }
                dir = dir.parentFile ?: break
            }
            found ?: candidates.firstOrNull { java.io.File(it).exists() }
            ?: run {
                System.err.println("Set SMEDIT_ROM_PATH to the Super Metroid ROM")
                System.exit(1)
                ""
            }
        }

    val warmupFrames = System.getenv("BENCH_WARMUP_FRAMES")?.toIntOrNull() ?: 60
    val benchFrames = System.getenv("BENCH_FRAMES")?.toIntOrNull() ?: 600
    val backends = (System.getenv("BENCH_BACKENDS") ?: "libretro,gym-retro")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    println("╔══════════════════════════════════════════════════╗")
    println("║        Emulator Backend Benchmark                ║")
    println("╠══════════════════════════════════════════════════╣")
    println("║ ROM:     $romPath")
    println("║ Warmup:  $warmupFrames frames")
    println("║ Bench:   $benchFrames frames")
    println("║ Backends: ${backends.joinToString(", ")}")
    println("╚══════════════════════════════════════════════════╝")
    println()

    val results = mutableListOf<BenchResult>()

    for (backendName in backends) {
        println("── $backendName ──────────────────────────────────")
        try {
            val result = benchmarkBackend(backendName, romPath, warmupFrames, benchFrames)
            results.add(result)
            printResult(result)
        } catch (e: Exception) {
            println("  FAILED: ${e.message}")
            e.printStackTrace()
        }
        println()
    }

    // Also run libretro with audio disabled for raw speed comparison
    if (backends.contains("libretro")) {
        println("── libretro (no audio — raw speed) ───────────────")
        try {
            val result = benchmarkBackend("libretro-no-audio", romPath, warmupFrames, benchFrames)
            results.add(result)
            printResult(result)
        } catch (e: Exception) {
            println("  FAILED: ${e.message}")
            e.printStackTrace()
        }
        println()
    }

    if (results.size >= 2) {
        println("── Comparison (with-frame) ───────────────────────")
        val fastest = results.minByOrNull { it.avgStepMs }!!
        for (r in results) {
            val ratio = r.avgStepMs / fastest.avgStepMs
            val label = if (r == fastest) " ← fastest" else " (${String.format("%.1f", ratio)}x slower)"
            println("  ${r.backend.padEnd(20)} ${String.format("%8.2f", r.avgStepMs)} ms/step  ${String.format("%7.1f", r.fps)} FPS$label")
        }

        val libretroRaw = results.find { it.backend == "libretro-no-audio" }
        val gymRetro = results.find { it.backend == "gym-retro" }
        if (libretroRaw != null && gymRetro != null) {
            val speedup = gymRetro.avgStepMs / libretroRaw.avgStepMs
            println()
            println("  libretro (raw, no audio) is ${String.format("%.1f", speedup)}x faster than gym-retro")
            println("  libretro raw: ${String.format("%.1f", libretroRaw.fps)} FPS vs gym-retro: ${String.format("%.1f", gymRetro.fps)} FPS")
        }
        val libretro = results.find { it.backend == "libretro" }
        if (libretro != null) {
            println("  libretro (with audio pacing): ${String.format("%.1f", libretro.fps)} FPS — real-time sync via audio output")
        }
    }
}

data class BenchResult(
    val backend: String,
    val totalMs: Long,
    val frames: Int,
    val avgStepMs: Double,
    val minStepMs: Double,
    val maxStepMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val fps: Double,
)

fun benchmarkBackend(backendName: String, romPath: String, warmupFrames: Int, benchFrames: Int): BenchResult {
    val backend: EmulatorBackend = when (backendName) {
        "libretro" -> LibretroBackend()
        "libretro-no-audio" -> LibretroBackend(audioEnabledOverride = false)
        "gym-retro" -> GymRetroBackend()
        else -> throw IllegalArgumentException("Unknown backend: $backendName")
    }

    return runBlocking {
        try {
            print("  Connecting... ")
            val caps = backend.connect()
            println("${caps.backendName}")

            print("  Starting session... ")
            backend.startSession(SessionConfig(romPath = romPath))
            println("ok")

            // Warmup
            print("  Warming up ($warmupFrames frames)... ")
            val noFrameInput = EmulatorInput(repeat = 1, includeFrame = false, includeTrace = false)
            for (i in 0 until warmupFrames) {
                backend.step(noFrameInput)
            }
            println("done")

            // Benchmark: step WITHOUT frame (pure emulation speed)
            print("  Benchmarking no-frame ($benchFrames frames)... ")
            val noFrameInputBench = EmulatorInput(repeat = 1, includeFrame = false, includeTrace = false)
            val noFrameTimes = LongArray(benchFrames)

            val noFrameStart = System.nanoTime()
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                backend.step(noFrameInputBench)
                noFrameTimes[i] = System.nanoTime() - t0
            }
            val noFrameNanos = System.nanoTime() - noFrameStart
            val noFrameMs = noFrameTimes.map { it / 1_000_000.0 }.sorted()
            println("done")
            println("  [no-frame] Avg: ${String.format("%.3f", noFrameMs.average())} ms  " +
                    "P50: ${String.format("%.3f", noFrameMs[noFrameMs.size / 2])} ms  " +
                    "FPS: ${String.format("%.1f", benchFrames.toDouble() / (noFrameNanos / 1_000_000_000.0))}")

            // Benchmark: step WITH frame (realistic workload)
            print("  Benchmarking with-frame ($benchFrames frames)... ")
            val withFrameInput = EmulatorInput(repeat = 1, includeFrame = true, includeTrace = false)
            val stepTimes = LongArray(benchFrames)

            val totalStart = System.nanoTime()
            for (i in 0 until benchFrames) {
                val t0 = System.nanoTime()
                backend.step(withFrameInput)
                stepTimes[i] = System.nanoTime() - t0
            }
            val totalNanos = System.nanoTime() - totalStart
            println("done")

            // Compute stats
            val stepMs = stepTimes.map { it / 1_000_000.0 }.sorted()
            val totalMs = totalNanos / 1_000_000
            BenchResult(
                backend = backendName,
                totalMs = totalMs,
                frames = benchFrames,
                avgStepMs = stepMs.average(),
                minStepMs = stepMs.first(),
                maxStepMs = stepMs.last(),
                p50Ms = stepMs[stepMs.size / 2],
                p95Ms = stepMs[(stepMs.size * 0.95).toInt()],
                p99Ms = stepMs[(stepMs.size * 0.99).toInt()],
                fps = benchFrames.toDouble() / (totalNanos / 1_000_000_000.0),
            )
        } finally {
            try { backend.close() } catch (_: Exception) {}
        }
    }
}

fun printResult(r: BenchResult) {
    println("  Total:   ${r.totalMs} ms for ${r.frames} frames")
    println("  Avg:     ${String.format("%.3f", r.avgStepMs)} ms/step")
    println("  Min:     ${String.format("%.3f", r.minStepMs)} ms")
    println("  Max:     ${String.format("%.3f", r.maxStepMs)} ms")
    println("  P50:     ${String.format("%.3f", r.p50Ms)} ms")
    println("  P95:     ${String.format("%.3f", r.p95Ms)} ms")
    println("  P99:     ${String.format("%.3f", r.p99Ms)} ms")
    println("  FPS:     ${String.format("%.1f", r.fps)}")
}
