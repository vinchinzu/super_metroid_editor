package com.supermetroid.editor.controller

import com.supermetroid.editor.libretro.LibretroConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GamepadManagerTest {

    @Test
    fun `SDL face button positions map to SNES physical labels`() {
        assertEquals(
            listOf(LibretroConstants.RETRO_DEVICE_ID_JOYPAD_B),
            pressedFaceButtonIndices(sdlA = true),
            "SDL A is the south face button and should press SNES B",
        )
        assertEquals(
            listOf(LibretroConstants.RETRO_DEVICE_ID_JOYPAD_A),
            pressedFaceButtonIndices(sdlB = true),
            "SDL B is the east face button and should press SNES A",
        )
        assertEquals(
            listOf(LibretroConstants.RETRO_DEVICE_ID_JOYPAD_Y),
            pressedFaceButtonIndices(sdlX = true),
            "SDL X is the west face button and should press SNES Y",
        )
        assertEquals(
            listOf(LibretroConstants.RETRO_DEVICE_ID_JOYPAD_X),
            pressedFaceButtonIndices(sdlY = true),
            "SDL Y is the north face button and should press SNES X",
        )
    }

    private fun pressedFaceButtonIndices(
        sdlA: Boolean = false,
        sdlB: Boolean = false,
        sdlX: Boolean = false,
        sdlY: Boolean = false,
    ): List<Int> {
        val buttons = MutableList(12) { 0 }
        applySdlPositionFaceButtonsToSnes(buttons, sdlA, sdlB, sdlX, sdlY)
        return buttons.mapIndexedNotNull { index, pressed ->
            index.takeIf { pressed == 1 }
        }
    }
}
