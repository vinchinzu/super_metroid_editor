package com.supermetroid.editor.tas

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A mutated candidate movie plus the first frame index where it differs from
 * its parent — the seek/greenzone hint: everything before [firstChangedFrame]
 * replays identically, so [TasSession.seek] can skip straight to it.
 */
class TasMutation(
    val movie: TasMovie,
    val firstChangedFrame: Int,
    /** Human-readable edit description, e.g. "delete:120+8+toggle:300+4". */
    val source: String,
)

/**
 * Seeded random mutation over [TasMovie] frames for hill climbing / genetic
 * search. Pure and deterministic for a given [Random] — no emulator involved.
 *
 * Operators (span lengths biased short, spans confined to the mutable window):
 * - `delete`: remove a span (the frame-saving workhorse — shortens the movie)
 * - `insert`: insert a span of one action-palette frame
 * - `replace`: overwrite a span with one action-palette frame
 * - `toggle`: flip a single button across a span
 * - `revert`: copy a span back from the seed movie
 * - `shift`: move a span's inputs earlier/later by a small offset
 * - `swap`: exchange two adjacent half-spans
 *
 * Frames are sanitized so opposing directions (Left+Right, Up+Down) are never
 * held together, and Select/Start are never introduced.
 */
class TasMutator(
    private val seed: TasMovie,
    private val rng: Random,
    /** First mutable frame (protects e.g. a verified opening segment). */
    private val windowStart: Int = 0,
    /** Exclusive end of the mutable window; 0 means the movie end. */
    private val windowEnd: Int = 0,
    /** Probability a candidate branches off the seed instead of the current best. */
    private val restartProbability: Double = 0.15,
    /** Edits applied per candidate: 1..maxEditsPerCandidate. */
    private val maxEditsPerCandidate: Int = 3,
) {

    /** Produce one candidate derived from [best] (or occasionally the seed). */
    fun mutate(best: TasMovie): TasMutation {
        val fromSeed = rng.nextDouble() < restartProbability && seed.frameCount > 0
        val parent = if (fromSeed) seed else best
        val frames = parent.frames.mapTo(mutableListOf()) { it.copyOf() }
        val sources = mutableListOf<String>()
        if (fromSeed) sources += "seed"
        repeat(1 + rng.nextInt(maxEditsPerCandidate)) {
            sources += applyRandomEdit(frames)
        }
        for (frame in frames) sanitize(frame)
        val movie = TasMovie(parent.meta, frames)
        return TasMutation(
            movie = movie,
            firstChangedFrame = firstDifference(best, movie),
            source = sources.joinToString("+"),
        )
    }

    private fun applyRandomEdit(frames: MutableList<IntArray>): String {
        val lo = windowStart
        val hi = if (windowEnd > 0) min(windowEnd, frames.size) else frames.size
        if (hi - lo < 2) return "none"
        return when (rng.nextInt(7)) {
            0 -> deleteSpan(frames, lo, hi)
            1 -> insertSpan(frames, lo, hi)
            2 -> replaceSpan(frames, lo, hi)
            3 -> toggleButtonSpan(frames, lo, hi)
            4 -> revertSpan(frames, lo, hi)
            5 -> shiftSpan(frames, lo, hi)
            else -> swapNeighborRuns(frames, lo, hi)
        }
    }

    private fun deleteSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(hi - start)
        repeat(length) { if (start < frames.size) frames.removeAt(start) }
        return "delete:$start+$length"
    }

    private fun insertSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(24)
        val value = actionPalette[rng.nextInt(actionPalette.size)]
        repeat(length) { frames.add(start, value.copyOf()) }
        return "insert:$start+$length"
    }

    private fun replaceSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(hi - start)
        val value = actionPalette[rng.nextInt(actionPalette.size)]
        for (i in start until min(start + length, frames.size)) frames[i] = value.copyOf()
        return "replace:$start+$length"
    }

    private fun toggleButtonSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(hi - start)
        val button = MUTABLE_BUTTONS[rng.nextInt(MUTABLE_BUTTONS.size)]
        for (i in start until min(start + length, frames.size)) {
            frames[i][button] = frames[i][button] xor 1
        }
        return "toggle:$start+$length"
    }

    private fun revertSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(hi - start)
        for (i in start until min(start + length, frames.size)) {
            frames[i] = seed.frameAt(i).copyOf()
        }
        return "revert:$start+$length"
    }

    private fun shiftSpan(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        val start = lo + rng.nextInt(hi - lo)
        val length = spanLength(hi - start)
        val offset = rng.nextInt(-12, 13)
        if (offset == 0) return "shift:none"
        val copy = frames.map { it }
        for (i in start until min(start + length, frames.size)) {
            val src = (i + offset).coerceIn(0, frames.lastIndex)
            frames[i] = copy[src].copyOf()
        }
        return "shift:$start+$length@$offset"
    }

    private fun swapNeighborRuns(frames: MutableList<IntArray>, lo: Int, hi: Int): String {
        if (hi - lo < 4) return "swap:none"
        val start = lo + rng.nextInt(hi - lo - 3)
        val length = spanLength(min(32, hi - start)).coerceAtLeast(2)
        val end = min(start + length, frames.size)
        val mid = start + (end - start) / 2
        val first = frames.subList(start, mid).map { it }
        val second = frames.subList(mid, end).map { it }
        (second + first).forEachIndexed { i, frame -> frames[start + i] = frame }
        return "swap:$start+${end - start}"
    }

    /** Span length biased short: mostly 1-8 frames, sometimes up to ~90. */
    private fun spanLength(maxLength: Int): Int {
        val capped = max(1, min(maxLength, 90))
        val roll = rng.nextInt(100)
        return when {
            roll < 55 -> 1 + rng.nextInt(min(capped, 8))
            roll < 85 -> 1 + rng.nextInt(min(capped, 24))
            else -> 1 + rng.nextInt(capped)
        }
    }

    companion object {
        /** Buttons the mutator may touch — movement/action only, never Select/Start. */
        val MUTABLE_BUTTONS = intArrayOf(
            TasInput.B, TasInput.Y, TasInput.UP, TasInput.DOWN, TasInput.LEFT,
            TasInput.RIGHT, TasInput.A, TasInput.X, TasInput.SHOULDER_L, TasInput.SHOULDER_R,
        )

        /** Never hold opposing directions; the SNES pad can't and games glitch. */
        fun sanitize(frame: IntArray) {
            if (frame[TasInput.LEFT] != 0 && frame[TasInput.RIGHT] != 0) {
                frame[TasInput.LEFT] = 0
                frame[TasInput.RIGHT] = 0
            }
            if (frame[TasInput.UP] != 0 && frame[TasInput.DOWN] != 0) {
                frame[TasInput.UP] = 0
                frame[TasInput.DOWN] = 0
            }
        }

        /** First frame index where two movies differ (noop-padded past the end). */
        fun firstDifference(a: TasMovie, b: TasMovie): Int {
            val longest = max(a.frameCount, b.frameCount)
            for (i in 0 until longest) {
                if (!a.frameAt(i).contentEquals(b.frameAt(i))) return i
            }
            return longest
        }

        /**
         * Plausible SM inputs for insert/replace: direction x action combos
         * (B dash, A jump, X shoot, R angle — SM defaults), plus noop.
         */
        val actionPalette: List<IntArray> = buildList {
            add(TasInput.noop())
            val directions = listOf(
                intArrayOf(), intArrayOf(TasInput.LEFT), intArrayOf(TasInput.RIGHT),
                intArrayOf(TasInput.UP), intArrayOf(TasInput.DOWN),
            )
            val actions = listOf(
                intArrayOf(), intArrayOf(TasInput.A), intArrayOf(TasInput.B),
                intArrayOf(TasInput.X), intArrayOf(TasInput.SHOULDER_R),
                intArrayOf(TasInput.A, TasInput.B), intArrayOf(TasInput.A, TasInput.X),
                intArrayOf(TasInput.B, TasInput.X), intArrayOf(TasInput.X, TasInput.SHOULDER_R),
            )
            for (direction in directions) {
                for (action in actions) {
                    add(TasInput.frameOf(*(direction + action)))
                }
            }
        }.distinctBy { TasInput.encodeFrame(it) }
    }
}
