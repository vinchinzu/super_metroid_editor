package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplaySegmentTest {
    @Test
    fun `detect finds playable start and target rooms`() {
        val log = AttemptLog(
            emulatorCore = "test",
            romHash = "abc",
            initialStateHash = "def",
            frames = listOf(
                frame(roomId = 0x96BA, gameState = 8, frameNumber = 10),
                frame(roomId = 0x96BA, gameState = 8, frameNumber = 11),
                frame(roomId = 0x9999, gameState = 8, frameNumber = 12),
                frame(roomId = 0x92FD, gameState = 8, frameNumber = 13),
            ),
        )

        val segment = ReplaySegment.detect(log, startRoom = 0x96BA, targetRoom = 0x92FD)

        assertEquals(0, segment.startIndex)
        assertEquals(3, segment.targetIndex)
        assertEquals(10L, segment.startFrameNumber)
        assertEquals(13L, segment.targetFrameNumber)
    }

    @Test
    fun `detect skips door transitions`() {
        val log = AttemptLog(
            emulatorCore = "test",
            romHash = "abc",
            initialStateHash = "def",
            frames = listOf(
                frame(roomId = 0x96BA, gameState = 8, frameNumber = 0),
                frame(roomId = 0x92FD, gameState = 9, frameNumber = 1, doorTransition = true),
                frame(roomId = 0x92FD, gameState = 8, frameNumber = 2),
            ),
        )

        val segment = ReplaySegment.detect(log, startRoom = 0x96BA, targetRoom = 0x92FD)

        assertEquals(2, segment.targetIndex)
    }

    private fun frame(
        roomId: Int,
        gameState: Int,
        frameNumber: Long,
        doorTransition: Boolean = false,
    ): FrameRecord = FrameRecord(
        frameNumber = frameNumber,
        inputBits = 0,
        systemRamHash = "hash",
        frameState = SuperMetroidFrameState(
            roomId = roomId,
            gameState = gameState,
            doorTransition = doorTransition,
        ),
    )
}

class SegmentScoreTest {
    @Test
    fun `reached target beats incomplete`() {
        val reached = SegmentScorer.reached(
            frameIndex = 100,
            health = 99,
            startY = 200,
            bestY = 150,
            lastRoom = 0x92FD,
        )
        val incomplete = SegmentScorer.incomplete(
            maxFrames = 200,
            health = 99,
            startY = 200,
            bestY = 120,
            wrongRoomFrames = 0,
            lastRoom = 0x96BA,
        )

        assertTrue(reached.isBetterThan(incomplete))
    }

    @Test
    fun `fewer frames to target wins`() {
        val faster = SegmentScorer.reached(
            frameIndex = 50,
            health = 99,
            startY = 200,
            bestY = 150,
            lastRoom = 0x92FD,
        )
        val slower = SegmentScorer.reached(
            frameIndex = 60,
            health = 99,
            startY = 200,
            bestY = 150,
            lastRoom = 0x92FD,
        )

        assertTrue(faster.isBetterThan(slower))
    }

    @Test
    fun `more upward progress wins incomplete scores`() {
        val higher = SegmentScorer.incomplete(
            maxFrames = 200,
            health = 99,
            startY = 300,
            bestY = 100,
            wrongRoomFrames = 0,
            lastRoom = 0x96BA,
        )
        val lower = SegmentScorer.incomplete(
            maxFrames = 200,
            health = 99,
            startY = 300,
            bestY = 200,
            wrongRoomFrames = 0,
            lastRoom = 0x96BA,
        )

        assertTrue(higher.isBetterThan(lower))
    }

    @Test
    fun `isPlayableIn requires game state 8 and no door transition`() {
        val playable = SuperMetroidFrameState(roomId = 0x96BA, gameState = 8, doorTransition = false)
        val door = SuperMetroidFrameState(roomId = 0x96BA, gameState = 9, doorTransition = true)

        assertTrue(playable.isPlayableIn(0x96BA))
        assertEquals(false, door.isPlayableIn(0x96BA))
    }
}
