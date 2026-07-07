package com.supermetroid.editor.tas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end determinism tests against the real snes9x core and ROM.
 * Skip gracefully when either is missing (CI without ROM/core).
 */
class TasSessionTest {

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

    private fun newSession(anchorInterval: Int = 0): TasSession? {
        val core = corePath() ?: return null
        val rom = romPath() ?: return null
        return TasSession(core.absolutePath, rom.absolutePath, anchorInterval = anchorInterval)
    }

    @Test
    fun `same inputs from same state produce identical results`() {
        val session = newSession() ?: return skipped()
        val state = startState()
        assumeTrue(state != null, "ZebesStart state not available")
        session.use { s ->
            s.loadStateFile(state!!)
            val movie = TasMovie(frames = List(120) { TasInput.frameOf(TasInput.RIGHT) })
            val anchor = s.saveStateBytes()

            s.playMovie(movie)
            val first = s.snapshot()

            s.loadStateBytes(anchor)
            s.playMovie(movie)
            val second = s.snapshot()

            assertEquals(first, second, "Emulation must be deterministic")
            assertTrue(first.samusX > 1145, "Holding Right should move Samus right of spawn")
        }
    }

    @Test
    fun `seek restores mid-movie position via greenzone`() {
        val session = newSession(anchorInterval = 30) ?: return skipped()
        val state = startState()
        assumeTrue(state != null, "ZebesStart state not available")
        session.use { s ->
            s.loadStateFile(state!!)
            val movie = TasMovie(frames = List(90) { TasInput.frameOf(TasInput.RIGHT) })

            s.playMovie(movie)
            s.seek(45, movie)
            assertEquals(45, s.frame)
            val atSeek = s.snapshot()

            // Replaying linearly to the same frame must agree with the seek.
            s.seek(0, movie)
            repeat(45) { s.step(movie.frameAt(s.frame)) }
            assertEquals(atSeek, s.snapshot(), "Greenzone seek must match linear replay")
        }
    }

    @Test
    fun `evaluator detects goal and reports igt`() {
        val session = newSession() ?: return skipped()
        val state = startState()
        assumeTrue(state != null, "ZebesStart state not available")
        session.use { s ->
            s.loadStateFile(state!!)
            val movie = TasMovie(frames = List(180) { TasInput.frameOf(TasInput.RIGHT) })
            val goal = TasGoal(
                type = TasGoal.TYPE_POSITION,
                roomId = 0x91F8,
                x = 1300,
                y = 1091,
                tolerance = 64,
                maxFrames = 180,
            )
            val result = TasEvaluator.run(s, movie, goal, traceEvery = 30)
            assertTrue(result.achieved, "Samus should pass x=1300 while running right")
            assertTrue(result.framesToGoal in 1..180)
            assertTrue(result.endIgtFrames > 0, "IGT should be ticking during gameplay")
            assertTrue(result.trace.isNotEmpty())
        }
    }

    private fun skipped() {
        assumeTrue(false, "snes9x core or ROM not available")
    }
}
