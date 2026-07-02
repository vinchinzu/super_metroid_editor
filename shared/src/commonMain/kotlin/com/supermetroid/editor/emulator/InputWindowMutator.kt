package com.supermetroid.editor.emulator

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class MutatedInputs(
    val inputs: IntArray,
    val source: String,
)

class InputWindowMutator(
    private val rng: Random,
) {
    fun mutate(
        best: IntArray,
        baseline: IntArray,
        iteration: Int,
    ): MutatedInputs {
        val candidate = if (iteration % 5 == 0) baseline.copyOf() else best.copyOf()
        val edits = 1 + rng.nextInt(4)
        val sources = mutableListOf<String>()
        repeat(edits) {
            sources += when (rng.nextInt(7)) {
                0 -> compressSpan(candidate, rng)
                1 -> expandSpan(candidate, rng)
                2 -> replaceSpan(candidate, rng)
                3 -> toggleButtonSpan(candidate, rng)
                4 -> copyFromBaseline(candidate, baseline, rng)
                5 -> shiftSpan(candidate, rng)
                else -> swapNeighborRuns(candidate, rng)
            }
        }
        for (index in candidate.indices) {
            candidate[index] = sanitizeInput(candidate[index])
        }
        return MutatedInputs(candidate, sources.joinToString("+"))
    }

    private fun compressSpan(inputs: IntArray, rng: Random): String {
        if (inputs.size < 2) return "compress:none"
        val start = rng.nextInt(inputs.size - 1)
        val length = randomSpanLength(rng, inputs.size - start)
        val keep = inputs.size - start - length
        if (keep > 0) {
            System.arraycopy(inputs, start + length, inputs, start, keep)
        }
        val fill = if (start > 0) inputs[start - 1] else 0
        for (i in inputs.size - length until inputs.size) inputs[i] = fill
        return "compress:$start+$length"
    }

    private fun expandSpan(inputs: IntArray, rng: Random): String {
        if (inputs.size < 2) return "expand:none"
        val start = rng.nextInt(inputs.size - 1)
        val length = randomSpanLength(rng, inputs.size - start)
        val value = actionPalette.random(rng)
        val move = inputs.size - start - length
        if (move > 0) {
            System.arraycopy(inputs, start, inputs, start + length, move)
        }
        for (i in start until start + length) inputs[i] = value
        return "expand:$start+$length"
    }

    private fun replaceSpan(inputs: IntArray, rng: Random): String {
        val start = rng.nextInt(inputs.size)
        val length = randomSpanLength(rng, inputs.size - start)
        val value = actionPalette.random(rng)
        for (i in start until start + length) inputs[i] = value
        return "replace:$start+$length"
    }

    private fun toggleButtonSpan(inputs: IntArray, rng: Random): String {
        val start = rng.nextInt(inputs.size)
        val length = randomSpanLength(rng, inputs.size - start)
        val button = mutableButtonBits.random(rng)
        for (i in start until start + length) inputs[i] = inputs[i] xor button
        return "toggle:$start+$length"
    }

    private fun copyFromBaseline(inputs: IntArray, baseline: IntArray, rng: Random): String {
        val start = rng.nextInt(inputs.size)
        val length = randomSpanLength(rng, inputs.size - start)
        for (i in start until start + length) inputs[i] = baseline[i]
        return "baseline:$start+$length"
    }

    private fun shiftSpan(inputs: IntArray, rng: Random): String {
        val start = rng.nextInt(inputs.size)
        val length = randomSpanLength(rng, inputs.size - start)
        val offset = rng.nextInt(-12, 13)
        if (offset == 0) return "shift:none"
        val copy = inputs.copyOf()
        for (i in start until start + length) {
            val src = (i + offset).coerceIn(0, inputs.lastIndex)
            inputs[i] = copy[src]
        }
        return "shift:$start+$length@$offset"
    }

    private fun swapNeighborRuns(inputs: IntArray, rng: Random): String {
        if (inputs.size < 8) return "swap:none"
        val start = rng.nextInt(inputs.size - 4)
        val length = randomSpanLength(rng, min(32, inputs.size - start))
        val mid = start + length / 2
        val end = start + length
        val first = inputs.copyOfRange(start, mid)
        val second = inputs.copyOfRange(mid, end)
        second.copyInto(inputs, start)
        first.copyInto(inputs, start + second.size)
        return "swap:$start+$length"
    }

    private fun randomSpanLength(rng: Random, maxLength: Int): Int {
        val capped = max(1, min(maxLength, 90))
        val roll = rng.nextInt(100)
        return when {
            roll < 55 -> 1 + rng.nextInt(min(capped, 8))
            roll < 85 -> 1 + rng.nextInt(min(capped, 24))
            else -> 1 + rng.nextInt(capped)
        }
    }

    companion object {
        fun materializeInputs(inputs: IntArray, count: Int): IntArray =
            IntArray(count.coerceAtLeast(0)) { index ->
                inputs.getOrElse(index) { 0 }
            }

        private fun bit(button: SnesButton): Int = 1 shl button.bit

        private fun sanitizeInput(input: Int): Int {
            var normalized = SnesInputBits.normalize(input)
            val left = bit(SnesButton.LEFT)
            val right = bit(SnesButton.RIGHT)
            val up = bit(SnesButton.UP)
            val down = bit(SnesButton.DOWN)
            if ((normalized and left) != 0 && (normalized and right) != 0) {
                normalized = normalized and (left or right).inv()
            }
            if ((normalized and up) != 0 && (normalized and down) != 0) {
                normalized = normalized and (up or down).inv()
            }
            return normalized
        }

        private val mutableButtonBits = listOf(
            bit(SnesButton.B),
            bit(SnesButton.Y),
            bit(SnesButton.UP),
            bit(SnesButton.DOWN),
            bit(SnesButton.LEFT),
            bit(SnesButton.RIGHT),
            bit(SnesButton.A),
            bit(SnesButton.X),
            bit(SnesButton.L),
            bit(SnesButton.R),
        )

        private val actionPalette = buildList {
            add(0)
            val directions = listOf(
                0,
                bit(SnesButton.LEFT),
                bit(SnesButton.RIGHT),
                bit(SnesButton.UP),
                bit(SnesButton.DOWN),
            )
            val modifiers = listOf(
                0,
                bit(SnesButton.A),
                bit(SnesButton.B),
                bit(SnesButton.X),
                bit(SnesButton.R),
                bit(SnesButton.A) or bit(SnesButton.X),
                bit(SnesButton.A) or bit(SnesButton.R),
                bit(SnesButton.X) or bit(SnesButton.R),
                bit(SnesButton.B) or bit(SnesButton.A),
            )
            for (direction in directions) {
                for (modifier in modifiers) {
                    add(SnesInputBits.normalize(direction or modifier))
                }
            }
        }.distinct()
    }
}
