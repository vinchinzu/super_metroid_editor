package com.supermetroid.editor.ui

import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasTracePoint
import java.io.File

/**
 * File-based physics plugin for sm_rev_predict.
 *
 * Loads hop_short.tasmovie.json as a HINT track (NOT emulator-legal). Residual
 * computation requires SuperMetroidEnv harness observations; without them, residual
 * is unmeasured and all FrameTrust values are UNMEASURED.
 *
 * The plugin exists so a broken hydrate/load-state implementation can be replaced
 * without ripping the timeline. Stub the residual call until CLI --load-state ships.
 */
class SmRevPredictPlugin(private val routeDirectory: File = File("routes")) : PhysicsPredictPlugin {
    override val id: String = "sm_rev_predict"

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
            "HINT TRACK ONLY — NOT EMULATOR-LEGAL",
        )
    }

    override fun residual(movie: TasMovie, observed: List<TasTracePoint>): ResidualProfile {
        if (observed.isEmpty()) {
            return ResidualProfile(
                frameTrust = List(movie.frameCount) { FrameTrust.UNMEASURED },
                cause = "No SuperMetroidEnv harness observation available",
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
        val predictedByFrame = movie.trace.associateBy { it.frame ?: 0 }

        for (frameIdx in 0 until movie.frameCount) {
            val obs = observedByFrame[frameIdx]
            val pred = predictedByFrame[frameIdx]

            if (obs == null || pred == null) {
                frameTrust.add(FrameTrust.UNMEASURED)
                continue
            }

            if (obs.roomId != null && pred.roomId != null && obs.roomId != pred.roomId) {
                frameTrust.add(FrameTrust.DEAD)
                if (firstDifferingRoom == null) {
                    firstDifferingRoom = frameIdx
                    firstDifferingField = "roomId"
                    cause = "$079B roomId mismatch: expected ${pred.roomId}, got ${obs.roomId}"
                }
                continue
            }

            val pixelMatch = obs.x == pred.x && obs.y == pred.y
            val subpixelMatch = (obs.subX == pred.subX && obs.subY == pred.subY) ||
                    (obs.subX == null || pred.subX == null)

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

object SmRevPredictPluginFactory : PhysicsPluginFactory {
    override fun create(): PhysicsPredictPlugin = SmRevPredictPlugin()
}
