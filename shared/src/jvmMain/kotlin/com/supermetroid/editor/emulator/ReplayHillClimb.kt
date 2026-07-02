package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery

/**
 * Mutates the recorded input window inside a replay segment and lets the emulator
 * decide whether each edit actually beats the seed route.
 *
 * Usage:
 *   ./gradlew :shared:hillClimbReplay
 *
 * Environment:
 *   SMEDIT_HILL_REPLAY           - path to `.smreplay` or attempt `.json` (required)
 *   SMEDIT_HILL_OUTPUT_DIR       - output directory (default: build/replay-hillclimb)
 *   SMEDIT_HILL_ITERATIONS       - mutation attempts (default: 500; batched across workers)
 *   SMEDIT_HILL_WORKERS          - eval workers (default: 1; snes9x is unstable parallel)
 *   SMEDIT_HILL_ALLOW_PARALLEL   - set 1 to default workers to CPU count (max 4)
 *   SMEDIT_HILL_SEED             - RNG seed (default: 0x5EED)
 *   SMEDIT_HILL_START_ROOM       - segment start room (default: 0x96BA)
 *   SMEDIT_HILL_TARGET_ROOM      - segment target room (default: 0x92FD)
 *   SMEDIT_HILL_MAX_EXTRA_FRAMES - frames beyond input window (default: 240)
 *   SMEDIT_HILL_EVAL_SLACK       - extra frames when tightening eval horizon (default: 60)
 *   SMEDIT_HILL_APPEND_TAIL      - append post-segment inputs (default: true)
 *   SMEDIT_ROM_PATH              - ROM override; bundle hash must match
 *   SMEDIT_LIBRETRO_CORE         - explicit libretro core path
 */
fun main() {
    val config = HillClimbConfig.fromEnvironment()
    val replayFile = config.replayFile.absoluteFile
    require(replayFile.isFile) { "Replay file not found: ${replayFile.absolutePath}" }

    val bundle = AttemptReplayBundle.read(replayFile)
    val romFile = RomHashResolver.resolveRom(bundle.log.romHash)
        ?: error("Could not find ROM matching bundle hash ${bundle.log.romHash}")
    val romBytes = romFile.readBytes()

    val corePath = LibretroCoreDiscovery.findCore()
        ?: error("No SNES libretro core found. Set SMEDIT_LIBRETRO_CORE.")

    val segment = ReplaySegment.detect(
        log = bundle.log,
        startRoom = config.startRoom,
        targetRoom = config.targetRoom,
    )
    val baselineInputs = bundle.log.frames
        .subList(segment.startIndex, segment.targetIndex + 1)
        .map { it.inputBits }
        .toIntArray()

    val core = LibretroCore(corePath)
    var initialized = false
    try {
        core.init()
        initialized = true
        check(core.loadGame(romFile.absolutePath)) { "Failed to load ROM: $romFile" }

        val stepper = LibretroEmulatorFrameStepper(core)
        val checkpoint = SegmentCheckpointBuilder.build(stepper, bundle, segment)

        println("Replay: ${replayFile.name}")
        println("ROM: ${romFile.name}")
        println("Workers: ${config.workers}")
        println(
            "Mutable window: index ${segment.startIndex}..${segment.targetIndex} " +
                "frames ${segment.startFrameNumber}..${segment.targetFrameNumber} " +
                "room=0x${config.startRoom.toString(16).uppercase()} -> " +
                "0x${config.targetRoom.toString(16).uppercase()}",
        )

        ReplaySegmentOptimizer(
            corePath = corePath,
            romPath = romFile.absolutePath,
            checkpoint = checkpoint,
            config = config,
        ).use { optimizer ->
            val result = optimizer.optimize(baselineInputs) { generation, score, source, baseline ->
                val delta = (baseline.reachedAfterInputs - score.reachedAfterInputs).coerceAtLeast(0)
                println("Improved @$generation: ${score.describe()} delta=$delta source=$source")
            }

            println("Baseline: ${result.baseline.describe()}")
            println("Best: ${result.best.describe()}")

            val output = HillClimbReplayWriter.write(
                stepper = stepper,
                bundle = bundle,
                segment = segment,
                bestSegmentInputs = InputWindowMutator.materializeInputs(
                    result.bestInputs,
                    result.best.reachedAfterInputs,
                ),
                romBytes = romBytes,
                outputDir = config.outputDir,
                appendTail = config.appendTail,
            )
            println("Saved JSON: ${output.logFile.absolutePath}")
            println("Saved replay: ${output.bundleFile.absolutePath}")
        }
    } finally {
        if (initialized) core.close()
    }
}
