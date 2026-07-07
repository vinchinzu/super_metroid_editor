package com.supermetroid.editor.tas

/**
 * SNES controller input encoding for TAS movies.
 *
 * Everything in this package uses "env order" — the 12-button order used by
 * stable-retro and the SM editor bridge (`editor_runtime.BUTTON_ORDER`):
 * B, Y, Select, Start, Up, Down, Left, Right, A, X, L, R.
 *
 * BK2 input logs store the same buttons in reverse order
 * (R, L, X, A, Right, Left, Down, Up, Start, Select, Y, B); see [Bk2Io].
 */
object TasInput {

    const val NUM_BUTTONS = 12

    /** Button names in env order (matches stable-retro SNES and editor bridge). */
    val BUTTON_ORDER = listOf("B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R")

    /** One mnemonic char per button in env order; '.' means unpressed. */
    val MNEMONICS = charArrayOf('B', 'Y', 's', 'S', 'u', 'd', 'l', 'r', 'A', 'X', 'L', 'R')

    val B = 0; val Y = 1; val SELECT = 2; val START = 3
    val UP = 4; val DOWN = 5; val LEFT = 6; val RIGHT = 7
    val A = 8; val X = 9; val SHOULDER_L = 10; val SHOULDER_R = 11

    /** A frame with no buttons pressed. */
    fun noop(): IntArray = IntArray(NUM_BUTTONS)

    /** Build a frame from pressed button indices. */
    fun frameOf(vararg pressed: Int): IntArray {
        val frame = IntArray(NUM_BUTTONS)
        for (idx in pressed) {
            require(idx in 0 until NUM_BUTTONS) { "Button index out of range: $idx" }
            frame[idx] = 1
        }
        return frame
    }

    /** Encode a frame as a 12-char mnemonic string, e.g. "B......r...." for B+Right. */
    fun encodeFrame(frame: IntArray): String {
        val chars = CharArray(NUM_BUTTONS)
        for (i in 0 until NUM_BUTTONS) {
            chars[i] = if (i < frame.size && frame[i] != 0) MNEMONICS[i] else '.'
        }
        return String(chars)
    }

    /** Decode a 12-char mnemonic string into a frame. Any char other than '.' counts as pressed. */
    fun decodeFrame(encoded: String): IntArray {
        require(encoded.length == NUM_BUTTONS) { "Frame string must be $NUM_BUTTONS chars, got ${encoded.length}" }
        val frame = IntArray(NUM_BUTTONS)
        for (i in 0 until NUM_BUTTONS) {
            if (encoded[i] != '.') frame[i] = 1
        }
        return frame
    }

    /** Normalize an arbitrary-length button list to a 12-element frame. */
    fun sanitize(buttons: List<Int>): IntArray {
        val frame = IntArray(NUM_BUTTONS)
        for (i in 0 until minOf(buttons.size, NUM_BUTTONS)) {
            frame[i] = if (buttons[i] != 0) 1 else 0
        }
        return frame
    }
}
