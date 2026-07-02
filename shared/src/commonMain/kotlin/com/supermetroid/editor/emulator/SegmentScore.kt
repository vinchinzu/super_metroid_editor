package com.supermetroid.editor.emulator

import kotlin.math.max

enum class SegmentOutcome {
    REACHED_TARGET,
    INCOMPLETE,
    DEATH,
}

data class SegmentScore(
    val outcome: SegmentOutcome,
    val reachedAfterInputs: Int,
    val health: Int,
    val bestY: Int,
    val upwardProgress: Int,
    val wrongRoomFrames: Int,
    val lastRoom: Int?,
) {
    fun isBetterThan(other: SegmentScore): Boolean = compareTo(other) > 0

    fun compareTo(other: SegmentScore): Int {
        val outcomeOrder = outcome.rank.compareTo(other.outcome.rank)
        if (outcomeOrder != 0) return outcomeOrder
        return when (outcome) {
            SegmentOutcome.REACHED_TARGET -> compareValuesBy(
                other,
                this,
                { it.reachedAfterInputs },
                { it.health },
            )
            SegmentOutcome.INCOMPLETE -> compareValuesBy(
                this,
                other,
                { it.upwardProgress },
                { it.health },
                { -it.wrongRoomFrames },
            )
            SegmentOutcome.DEATH -> other.reachedAfterInputs.compareTo(reachedAfterInputs)
        }
    }

    fun describe(): String =
        "reached=${outcome == SegmentOutcome.REACHED_TARGET} inputs=$reachedAfterInputs health=$health " +
            "bestY=$bestY lastRoom=${lastRoom?.let { "0x${it.toString(16).uppercase()}" } ?: "?"} " +
            "progress=$upwardProgress wrongRoom=$wrongRoomFrames"

    private val SegmentOutcome.rank: Int
        get() = when (this) {
            SegmentOutcome.REACHED_TARGET -> 2
            SegmentOutcome.INCOMPLETE -> 1
            SegmentOutcome.DEATH -> 0
        }
}

object SegmentScorer {
    fun death(
        frameIndex: Int,
        health: Int,
        bestY: Int,
        lastRoom: Int?,
    ): SegmentScore = SegmentScore(
        outcome = SegmentOutcome.DEATH,
        reachedAfterInputs = frameIndex + 1,
        health = health,
        bestY = bestY,
        upwardProgress = 0,
        wrongRoomFrames = 0,
        lastRoom = lastRoom,
    )

    fun reached(
        frameIndex: Int,
        health: Int,
        startY: Int,
        bestY: Int,
        lastRoom: Int?,
    ): SegmentScore = SegmentScore(
        outcome = SegmentOutcome.REACHED_TARGET,
        reachedAfterInputs = frameIndex + 1,
        health = health,
        bestY = bestY,
        upwardProgress = max(0, startY - bestY),
        wrongRoomFrames = 0,
        lastRoom = lastRoom,
    )

    fun incomplete(
        maxFrames: Int,
        health: Int,
        startY: Int,
        bestY: Int,
        wrongRoomFrames: Int,
        lastRoom: Int?,
    ): SegmentScore = SegmentScore(
        outcome = SegmentOutcome.INCOMPLETE,
        reachedAfterInputs = maxFrames,
        health = health,
        bestY = bestY,
        upwardProgress = max(0, startY - bestY),
        wrongRoomFrames = wrongRoomFrames,
        lastRoom = lastRoom,
    )

    fun isWrongRoom(state: SuperMetroidFrameState, startRoom: Int, targetRoom: Int): Boolean =
        state.roomId != null &&
            !state.isPlayableIn(startRoom) &&
            !state.isPlayableIn(targetRoom) &&
            !state.doorTransition
}
