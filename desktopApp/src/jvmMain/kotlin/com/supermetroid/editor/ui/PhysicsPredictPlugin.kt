package com.supermetroid.editor.ui

import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasTracePoint

/**
 * Physics prediction plugin for TAS timeline editing.
 *
 * Mirrors [EmulatorBackend] design: a swappable plugin that provides prediction hints
 * (hop overlays) and residual analysis (trust metrics) without replacing the emulator
 * as the source of truth. Desktop snes9x is UI playback only; recorded traces are truth.
 *
 * This plugin exists so a broken hydrate/load-state implementation (sm_rev_predict now,
 * future Haskell port) can be replaced without ripping the timeline.
 */
interface PhysicsPredictPlugin {
    /** Unique plugin identifier (e.g., "null", "sm_rev_predict", "haskell_mini"). */
    val id: String

    /**
     * Hydrate internal state from a starting save state.
     *
     * @param startStateName Name of the starting state (null for power-on)
     * @return Success or failure with error message
     */
    fun hydrate(startStateName: String?): Result<Unit>

    /**
     * Predict a "hop" (short trajectory segment) from a movie frame.
     *
     * Returns a hint track overlay - NOT emulator-legal, NOT final.
     * hop_short.tasmovie.json notes say "Overlay Y illustrative. Not emulator-legal."
     *
     * @param movie The input movie
     * @param fromFrame Starting frame index
     * @return Predicted trace points (hint only)
     */
    fun predictHop(movie: TasMovie, fromFrame: Int): PredictedHop

    /**
     * Compute residual between predicted and observed traces.
     *
     * Returns frame-by-frame trust metrics: TRUSTWORTHY (keep editing),
     * SPOT_CHECK (subpixel disagreement only), or DEAD (roomId mismatch, lag desync).
     *
     * @param movie The input movie with predicted frames
     * @param observed Observed trace points from emulator
     * @return Residual profile with trust levels
     */
    fun residual(movie: TasMovie, observed: List<TasTracePoint>): ResidualProfile
}

/**
 * Predicted hop result (hint track).
 */
data class PredictedHop(
    val trace: List<TasTracePoint>,
    val notes: String? = null,
)

/**
 * Residual profile: difference between predicted and observed traces.
 *
 * R(τ) = (fd_σ+, fd_σ, fd_π, fd_†)
 * - fd_σ+ : Oσ plus optional enemy/i-frame ($0F8C/$18A8)
 * - fd_σ  : Oπ plus subpixels
 * - fd_π  : pixels/pose/room ($0AF6/$0AFA/$0A1C/$079B) — Oπ = pixels, pose, room
 * - fd_†  : energy/death $09C2, not roomId
 *
 * Note: firstDifferingRoom represents roomId $079B, which is a component of fd_π (Oπ).
 */
data class ResidualProfile(
    /** First frame with subpixel+pixel disagreement (nullable). */
    val firstDifferingSubpixel: Int? = null,
    /** First frame with pixel-only disagreement (nullable). */
    val firstDifferingPixel: Int? = null,
    /** First frame with pixels/pose/room disagreement (fd_π component, nullable). */
    val firstDifferingPose: Int? = null,
    /** First frame with roomId $079B disagreement (fd_π component, nullable). */
    val firstDifferingRoom: Int? = null,
    /** Name of the first differing field. */
    val firstDifferingField: String? = null,
    /** Human-readable cause of divergence. */
    val cause: String? = null,
    /** Per-frame trust levels (indexed by movie frame). */
    val frameTrust: List<FrameTrust> = emptyList(),
)

/**
 * Frame trust level for timeline coloring.
 *
 * - TRUSTWORTHY (Oσ/Oπ) — pixel + pose match, keep editing
 * - SPOT_CHECK — pure subpixel disagreement only, mostly safe
 * - NEEDS_EMU — Oπ broke (pixel x/y or pose mismatch) — kinematics drift, not death
 * - DEAD — $079B roomId mismatch, O† energy/death, or lag desync — critical failure
 * - UNMEASURED — no SuperMetroidEnv harness observation available
 */
enum class FrameTrust {
    TRUSTWORTHY,
    SPOT_CHECK,
    NEEDS_EMU,
    DEAD,
    UNMEASURED,
}

/**
 * Factory for creating physics plugins.
 *
 * Design: swapping the backend is a one-liner (factory), not a timeline rewrite.
 */
interface PhysicsPluginFactory {
    fun create(): PhysicsPredictPlugin
}

/**
 * Null physics plugin: no-op, all frames UNMEASURED.
 *
 * Use as the default when no prediction backend is available.
 */
class NullPhysicsPlugin : PhysicsPredictPlugin {
    override val id: String = "null"

    override fun hydrate(startStateName: String?): Result<Unit> = Result.success(Unit)

    override fun predictHop(movie: TasMovie, fromFrame: Int): PredictedHop =
        PredictedHop(emptyList(), "NullPhysicsPlugin: no prediction")

    override fun residual(movie: TasMovie, observed: List<TasTracePoint>): ResidualProfile =
        ResidualProfile(
            frameTrust = List(movie.frameCount) { FrameTrust.UNMEASURED },
            cause = "No physics plugin configured",
        )
}

object NullPhysicsPluginFactory : PhysicsPluginFactory {
    override fun create(): PhysicsPredictPlugin = NullPhysicsPlugin()
}
