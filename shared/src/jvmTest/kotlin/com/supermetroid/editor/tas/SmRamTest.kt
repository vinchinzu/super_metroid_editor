package com.supermetroid.editor.tas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmRamTest {

    private fun wramWith(vararg words: Pair<Int, Int>): ByteArray {
        val wram = ByteArray(SmRam.SNAPSHOT_SIZE)
        for ((offset, value) in words) {
            wram[offset] = (value and 0xFF).toByte()
            wram[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return wram
    }

    @Test
    fun `parses samus and room state from wram`() {
        val snap = SmRam.parse(
            wramWith(
                SmRam.SAMUS_X to 1145,
                SmRam.SAMUS_Y to 1091,
                SmRam.ROOM_ID to 0x91F8,
                SmRam.HEALTH to 99,
                SmRam.MAX_HEALTH to 99,
                SmRam.GAME_STATE to SmRam.GAME_STATE_GAMEPLAY,
            )
        )
        assertEquals(1145, snap.samusX)
        assertEquals(1091, snap.samusY)
        assertEquals(0x91F8, snap.roomId)
        assertEquals(99, snap.health)
        assertTrue(snap.inGameplay)
        assertFalse(snap.dead)
    }

    @Test
    fun `parses signed velocity`() {
        val snap = SmRam.parse(wramWith(SmRam.VELOCITY_X to 0xFFFE)) // -2
        assertEquals(-2, snap.velocityX)
    }

    @Test
    fun `computes total igt frames`() {
        val snap = SmRam.parse(
            wramWith(
                SmRam.IGT_FRAMES to 30,
                SmRam.IGT_SECONDS to 10,
                SmRam.IGT_MINUTES to 2,
                SmRam.IGT_HOURS to 1,
            )
        )
        assertEquals(30 + 60L * (10 + 60 * (2 + 60 * 1)), snap.igtTotalFrames)
    }

    @Test
    fun `parses all eight enemy slots with stride`() {
        val words = mutableListOf<Pair<Int, Int>>()
        for (slot in 0 until SmRam.ENEMY_SLOT_COUNT) {
            words.add((SmRam.ENEMY0_HP + slot * SmRam.ENEMY_SLOT_STRIDE) to (100 + slot))
        }
        val snap = SmRam.parse(wramWith(*words.toTypedArray()))
        assertEquals(SmRam.ENEMY_SLOT_COUNT, snap.enemies.size)
        for (slot in 0 until SmRam.ENEMY_SLOT_COUNT) {
            assertEquals(100 + slot, snap.enemies[slot].hp)
        }
    }

    @Test
    fun `goal types evaluate against snapshots`() {
        val inRoom = SmRam.parse(wramWith(SmRam.ROOM_ID to 0x92FD))
        assertTrue(TasGoal(type = TasGoal.TYPE_ROOM, roomId = 0x92FD).achieved(inRoom))
        assertFalse(TasGoal(type = TasGoal.TYPE_ROOM, roomId = 0x91F8).achieved(inRoom))

        val atPos = SmRam.parse(
            wramWith(SmRam.ROOM_ID to 0x91F8, SmRam.SAMUS_X to 1000, SmRam.SAMUS_Y to 500)
        )
        assertTrue(
            TasGoal(type = TasGoal.TYPE_POSITION, roomId = 0x91F8, x = 1010, y = 495, tolerance = 16)
                .achieved(atPos)
        )
        assertFalse(
            TasGoal(type = TasGoal.TYPE_POSITION, roomId = 0x91F8, x = 1100, y = 495, tolerance = 16)
                .achieved(atPos)
        )
    }
}
