package com.supermetroid.editor.emulator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

data class WrittenReplay(
    val logFile: File,
    val bundleFile: File,
)

object HillClimbReplayWriter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(
        stepper: FrameStepper,
        bundle: LoadedReplayBundle,
        segment: ReplaySegment,
        bestSegmentInputs: IntArray,
        romBytes: ByteArray,
        outputDir: File,
        appendTail: Boolean,
    ): WrittenReplay {
        val prefix = bundle.log.frames.subList(0, segment.startIndex).map { it.inputBits }
        val tail = if (appendTail) {
            bundle.log.frames.subList(segment.targetIndex + 1, bundle.log.frames.size).map { it.inputBits }
        } else {
            emptyList()
        }
        val allInputs = prefix + bestSegmentInputs.toList() + tail

        stepper.loadState(bundle.initialState)
        val firstFrame = bundle.log.frames.firstOrNull()?.frameNumber ?: 0L
        val captured = AttemptLogRecorder(stepper).record(
            romBytes = romBytes,
            inputBitsByFrame = allInputs,
            firstFrameNumber = firstFrame,
        )

        outputDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val logFile = File(outputDir, "attempt-$stamp-hillclimb.json")
        logFile.writeText(json.encodeToString(captured.log))
        AttemptReplayBundle.companionStateFile(logFile).writeBytes(captured.initialState)
        captured.finalState?.let { final ->
            AttemptReplayBundle.companionFinalStateFile(logFile).writeBytes(final)
        }
        val bundleFile = AttemptReplayBundle.writeFromRecording(
            logFile = logFile,
            title = "Hillclimb ${bundle.title}",
            description = "Mutated replay window ${segment.startFrameNumber}..${segment.targetFrameNumber}",
        )
        return WrittenReplay(logFile = logFile, bundleFile = bundleFile)
    }
}
