package com.supermetroid.editor.ui

import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasTracePoint
import java.io.File

/**
 * File-based stub physics plugin for sm_rev_predict.
 *
 * Loads hop_short.tasmovie.json as a hint track if available. If the sidecar predict
 * JSON exists, uses it; otherwise falls back to hop_short as the HINT track.
 *
 * This is labeled as HINT — not emulator-legal, not final. The interface allows
 * swapping in a full sm_rev_predict or Haskell implementation without timeline rewrites.
 */
class SmRevPredictStubPlugin(private val routeDirectory: File = File("routes")) : PhysicsPredictPlugin {
    override val id: String = "sm_rev_predict_stub"

    private var hopShortMovie: TasMovie? = null

    override fun hydrate(startStateName: String?): Result<Unit> {
        val hopShortFile = File(routeDirectory, "hop_short.tasmovie.json")
        if (!hopShortFile.exists()) {
            return Result.failure(Exception("hop_short.tasmovie.json not found in $routeDirectory"))
        }

        return try {
            hopShortMovie = TasMovie.load(hopShortFile)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to load hop_short.tasmovie.json: ${e.message}"))
        }
    }

    override fun predictHop(movie: TasMovie, fromFrame: Int): PredictedHop {
        val hop = hopShortMovie
        if (hop == null) {
            return PredictedHop(
                emptyList(),
                "hop_short.tasmovie.json not loaded (call hydrate first)",
            )
        }

        val traceSegment = hop.trace.filter { (it.frame ?: 0) >= fromFrame }
        return PredictedHop(
            traceSegment,
            "Overlay Y illustrative. Not emulator-legal.",
        )
    }

    override fun residual(movie: TasMovie, observed: List<TasTracePoint>): ResidualProfile {
        if (observed.isEmpty()) {
            return ResidualProfile(
                frameTrust = List(movie.frameCount) { FrameTrust.TRUSTWORTHY },
            )
        }

        val frameTrust = mutableListOf<FrameTrust>()
        var firstDifferingSubpixel: Int? = null
        var firstDifferingPixel: Int? = null
        var firstDifferingPose: Int? = null
        var firstDifferingRoom: Int? = null
        var firstDifferingField: String? = null
        var cause: String? = null

        val observedByFrame = observed.associateBy { it.frame ?: 0 }

        for (frameIdx in 0 until movie.frameCount) {
            val obs = observedByFrame[frameIdx]
            if (obs == null) {
                frameTrust.add(FrameTrust.TRUSTWORTHY)
                continue
            }

            if (obs.roomId != null) {
                val expectedRoomId = movie.trace.lastOrNull { (it.frame ?: 0) <= frameIdx }?.roomId
                if (expectedRoomId != null && obs.roomId != expectedRoomId) {
                    frameTrust.add(FrameTrust.DEAD)
                    if (firstDifferingRoom == null) {
                        firstDifferingRoom = frameIdx
                        firstDifferingField = "roomId"
                        cause = "$079B roomId mismatch: expected $expectedRoomId, got ${obs.roomId}"
                    }
                    continue
                }
            }

            val expectedTrace = movie.trace.lastOrNull { (it.frame ?: 0) <= frameIdx }
            if (expectedTrace == null) {
                frameTrust.add(FrameTrust.TRUSTWORTHY)
                continue
            }

            val pixelMatch = obs.x == expectedTrace.x && obs.y == expectedTrace.y
            val subpixelMatch = (obs.subX == expectedTrace.subX && obs.subY == expectedTrace.subY) ||
                    (obs.subX == null || expectedTrace.subX == null)

            if (!pixelMatch) {
                frameTrust.add(FrameTrust.DEAD)
                if (firstDifferingPixel == null) {
                    firstDifferingPixel = frameIdx
                    firstDifferingField = "x/y"
                    cause = "Pixel position mismatch"
                }
            } else if (!subpixelMatch) {
                frameTrust.add(FrameTrust.SPOT_CHECK)
                if (firstDifferingSubpixel == null) {
                    firstDifferingSubpixel = frameIdx
                    if (firstDifferingField == null) {
                        firstDifferingField = "subX/subY"
                        cause = "Subpixel disagreement"
                    }
                }
            } else {
                frameTrust.add(FrameTrust.TRUSTWORTHY)
            }
        }

        return ResidualProfile(
            firstDifferingSubpixel = firstDifferingSubpixel,
            firstDifferingPixel = firstDifferingPixel,
            firstDifferingPose = firstDifferingPose,
            firstDifferingRoom = firstDifferingRoom,
            firstDifferingField = firstDifferingField,
            cause = cause,
            frameTrust = frameTrust,
        )
    }
}

object SmRevPredictStubPluginFactory : PhysicsPluginFactory {
    override fun create(): PhysicsPredictPlugin = SmRevPredictStubPlugin()
}
