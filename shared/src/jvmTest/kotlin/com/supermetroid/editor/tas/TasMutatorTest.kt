package com.supermetroid.editor.tas

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TasMutatorTest {

    /** A varied but deterministic seed movie: alternating runs of real inputs. */
    private fun seedMovie(frames: Int = 400): TasMovie = TasMovie(
        frames = List(frames) { i ->
            when ((i / 20) % 4) {
                0 -> TasInput.frameOf(TasInput.RIGHT, TasInput.B)
                1 -> TasInput.frameOf(TasInput.RIGHT, TasInput.A)
                2 -> TasInput.frameOf(TasInput.LEFT)
                else -> TasInput.noop()
            }
        },
    )

    private fun newMutator(seed: TasMovie, rngSeed: Long = 7, windowStart: Int = 0, windowEnd: Int = 0) =
        TasMutator(seed, Random(rngSeed), windowStart = windowStart, windowEnd = windowEnd)

    @Test
    fun `same rng seed produces identical candidate sequences`() {
        val seed = seedMovie()
        val a = newMutator(seed)
        val b = newMutator(seed)
        var bestA = seed
        var bestB = seed
        repeat(50) {
            val ma = a.mutate(bestA)
            val mb = b.mutate(bestB)
            assertEquals(ma.source, mb.source)
            assertEquals(ma.firstChangedFrame, mb.firstChangedFrame)
            assertEquals(ma.movie.frameCount, mb.movie.frameCount)
            for (i in 0 until ma.movie.frameCount) {
                assertArrayEquals(ma.movie.frameAt(i), mb.movie.frameAt(i))
            }
            // Alternate accepting candidates so mutation parents vary too.
            if (it % 3 == 0) {
                bestA = ma.movie
                bestB = mb.movie
            }
        }
    }

    @Test
    fun `candidates never hold opposing directions`() {
        val seed = seedMovie()
        val mutator = newMutator(seed, rngSeed = 99)
        var best = seed
        repeat(200) {
            val mutation = mutator.mutate(best)
            for (frame in mutation.movie.frames) {
                assertEquals(TasInput.NUM_BUTTONS, frame.size)
                assertFalse(
                    frame[TasInput.LEFT] != 0 && frame[TasInput.RIGHT] != 0,
                    "Left+Right held together",
                )
                assertFalse(
                    frame[TasInput.UP] != 0 && frame[TasInput.DOWN] != 0,
                    "Up+Down held together",
                )
                assertEquals(0, frame[TasInput.SELECT], "Select must never be introduced")
                assertEquals(0, frame[TasInput.START], "Start must never be introduced")
            }
            if (it % 5 == 0) best = mutation.movie
        }
    }

    @Test
    fun `firstChangedFrame is exact - frames before it match the parent`() {
        val seed = seedMovie()
        val mutator = newMutator(seed, rngSeed = 3)
        var changed = 0
        repeat(200) {
            val mutation = mutator.mutate(seed)
            for (i in 0 until mutation.firstChangedFrame) {
                assertArrayEquals(
                    seed.frameAt(i),
                    mutation.movie.frameAt(i),
                    "Frame $i differs before firstChangedFrame=${mutation.firstChangedFrame}",
                )
            }
            if (mutation.firstChangedFrame < seed.frameCount) {
                assertFalse(
                    seed.frameAt(mutation.firstChangedFrame)
                        .contentEquals(mutation.movie.frameAt(mutation.firstChangedFrame)),
                    "firstChangedFrame must point at a real difference",
                )
                changed++
            }
        }
        assertTrue(changed > 150, "Most candidates should actually differ from the parent")
    }

    @Test
    fun `window confines edits`() {
        val seed = seedMovie()
        val mutator = newMutator(seed, rngSeed = 11, windowStart = 100, windowEnd = 300)
        repeat(200) {
            val mutation = mutator.mutate(seed)
            assertTrue(
                mutation.firstChangedFrame >= 100,
                "Edit at frame ${mutation.firstChangedFrame} escaped windowStart=100",
            )
        }
    }

    @Test
    fun `firstDifference handles length changes and identity`() {
        val seed = seedMovie(10)
        assertEquals(10, TasMutator.firstDifference(seed, seedMovie(10)))
        // Truncation diverges where frames stop matching the noop padding.
        val truncated = seed.truncated(6)
        val diff = TasMutator.firstDifference(seed, truncated)
        assertTrue(diff in 6 until 10)
        val spliced = seed.spliced(4, listOf(TasInput.frameOf(TasInput.START)))
        assertEquals(4, TasMutator.firstDifference(seed, spliced))
    }
}
