package com.supermetroid.editor.tas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Hill-climb tests against the real snes9x core and ROM: a seed run with a
 * deliberately wasteful noop prefix must get shorter, and the optimizer's own
 * linear re-verification guards greenzone bookkeeping. Skips without ROM/core.
 */
class TasOptimizerTest {

    private fun findFile(vararg candidates: String): File? =
        candidates.map { File(it) }.firstOrNull { it.isFile }

    private fun corePath(): File? = findFile(
        "cores/snes9x_libretro.so",
        "../cores/snes9x_libretro.so",
        "../tools/snes9x/libretro/snes9x_libretro.so",
    )

    private fun romPath(): File? = findFile(
        "test-resources/Super Metroid (JU) [!].smc",
        "../test-resources/Super Metroid (JU) [!].smc",
    )

    private fun startState(): File? = findFile(
        "custom_integrations/SuperMetroid-Snes/ZebesStart.state",
        "../custom_integrations/SuperMetroid-Snes/ZebesStart.state",
    )

    private fun newSession(anchorInterval: Int): TasSession? {
        val core = corePath() ?: return null
        val rom = romPath() ?: return null
        return TasSession(core.absolutePath, rom.absolutePath, anchorInterval = anchorInterval)
    }

    @Test
    fun `optimizer shortens a run with a wasteful prefix and best replays linearly`() {
        val session = newSession(anchorInterval = 60) ?: return skipped()
        val state = startState()
        assumeTrue(state != null, "ZebesStart state not available")
        session.use { s ->
            s.loadStateFile(state!!)

            // Probe where 150 frames of holding Right lands Samus.
            val walk = List(150) { TasInput.frameOf(TasInput.RIGHT) }
            val probeEnd = s.playMovie(TasMovie(frames = walk))
            val goal = TasGoal(
                type = TasGoal.TYPE_POSITION,
                roomId = probeEnd.roomId,
                x = probeEnd.samusX,
                y = probeEnd.samusY,
                tolerance = 4,
            )

            // Seed wastes 60 noop frames before walking — pure fat to trim.
            val seed = TasMovie(frames = List(60) { TasInput.noop() } + walk)
            val result = TasOptimizer(
                session = s,
                goal = goal,
                config = TasOptimizer.Config(iterations = 80, rngSeed = 42),
            ).optimize(seed)

            assertTrue(result.seedResult.achieved, "Seed must reach the probed position")
            assertTrue(
                result.bestResult.framesToGoal < result.seedResult.framesToGoal,
                "Expected an improvement over ${result.seedResult.framesToGoal}f, " +
                    "got ${result.bestResult.framesToGoal}f",
            )
            // optimize() already re-verified linearly; the invariants must agree.
            assertEquals(result.best.frameCount, result.bestResult.framesToGoal)
            assertTrue(result.improvements.isNotEmpty())
        }
    }

    @Test
    fun `optimizer rejects a seed that does not achieve the goal`() {
        val session = newSession(anchorInterval = 0) ?: return skipped()
        val state = startState()
        assumeTrue(state != null, "ZebesStart state not available")
        session.use { s ->
            s.loadStateFile(state!!)
            val seed = TasMovie(frames = List(30) { TasInput.noop() })
            val goal = TasGoal(type = TasGoal.TYPE_ROOM, roomId = 0x1234, maxFrames = 30)
            val optimizer = TasOptimizer(s, goal, TasOptimizer.Config(iterations = 1))
            assertThrows(IllegalArgumentException::class.java) { optimizer.optimize(seed) }
        }
    }

    private fun skipped() {
        assumeTrue(false, "snes9x core or ROM not available")
    }
}
