package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import java.io.File

/**
 * Headless frame-by-frame replay verification against a `.smreplay` bundle.
 *
 * Usage:
 *   ./gradlew :shared:verifyReplay
 *
 * Environment:
 *   SMEDIT_VERIFY_REPLAY  - path to `.smreplay` or `.json` attempt log
 *   SMEDIT_ROM_PATH       - (optional) ROM override; bundle hash must match
 *   SMEDIT_LIBRETRO_CORE  - (optional) explicit libretro core path
 */
fun main() {
    val replayPath = System.getenv("SMEDIT_VERIFY_REPLAY")?.trim()?.takeIf { it.isNotEmpty() }
        ?: run {
            System.err.println("Set SMEDIT_VERIFY_REPLAY to a .smreplay or attempt .json path")
            System.exit(1)
            return
        }

    val replayFile = File(replayPath).absoluteFile
    if (!replayFile.isFile) {
        System.err.println("Replay file not found: ${replayFile.absolutePath}")
        System.exit(1)
    }

    val corePath = LibretroCoreDiscovery.findCore()
        ?: run {
            System.err.println("No SNES libretro core found. Set SMEDIT_LIBRETRO_CORE.")
            return
        }

    val bundle = AttemptReplayBundle.read(replayFile)
    val resolvedRom = RomHashResolver.resolveRom(bundle.log.romHash)
        ?: run {
            System.err.println("Could not find ROM matching bundle hash ${bundle.log.romHash}")
            return
        }

    val romBytes = resolvedRom.readBytes()
    val core = LibretroCore(corePath)
    var initialized = false
    try {
        core.init()
        initialized = true
        check(core.loadGame(resolvedRom.absolutePath)) { "Failed to load ROM: $resolvedRom" }

        val stepper = LibretroEmulatorFrameStepper(core)
        val result = AttemptLogReplayer(stepper).replayAndVerify(
            log = bundle.log,
            initialState = bundle.initialState,
            romBytes = romBytes,
        )

        println("Replay: ${replayFile.name}")
        println("Frames checked: ${result.checkedFrames} / ${bundle.log.frameCount}")
        if (result.matched) {
            println("RESULT: PASS — replay is deterministic")
            val final = result.actualFinalFrame?.frameState
            if (final != null) {
                println(
                    "Final: room=0x${final.roomId?.toString(16)?.uppercase()} " +
                        "health=${final.health} samus=(${final.samusX},${final.samusY})",
                )
            }
            System.exit(0)
        }

        println("RESULT: FAIL — ${result.failure?.kind}")
        println(result.failure?.message)
        result.failure?.expected?.let { expected ->
            result.failure?.actual?.let { actual ->
                val diffs = buildList {
                    if (expected.systemRamHash != actual.systemRamHash) add("systemRamHash")
                    addAll(
                        expected.frameState.differingFields(actual.frameState)
                            .map { "frameState.$it" },
                    )
                }
                if (diffs.isNotEmpty()) {
                    println("Diff fields: ${diffs.joinToString()}")
                }
            }
        }
        System.exit(1)
    } finally {
        if (initialized) {
            core.close()
        }
    }
}
