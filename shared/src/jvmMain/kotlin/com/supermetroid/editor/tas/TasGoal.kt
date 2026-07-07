package com.supermetroid.editor.tas

import kotlinx.serialization.Serializable

/**
 * A serializable success condition for a TAS run segment.
 *
 * Flat on purpose so Python optimizers can emit it as plain JSON. Set the
 * fields relevant to [type]; the rest stay null.
 *
 * Types:
 * - `room`: reach [roomId]
 * - `position`: reach [roomId] within [tolerance] px of ([x], [y])
 * - `item`: collected-items bit flags include [itemMask]
 * - `boss`: enemy slot 0 HP reaches 0 while in [roomId] (null = any room)
 * - `survive`: still alive after [maxFrames]
 */
@Serializable
data class TasGoal(
    val type: String = TYPE_ROOM,
    val roomId: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val tolerance: Int = 16,
    val itemMask: Int? = null,
    /** Hard frame budget for the run; 0 means the movie length. */
    val maxFrames: Int = 0,
    /** Fail immediately if Samus dies. */
    val failOnDeath: Boolean = true,
) {
    fun achieved(snap: SmSnapshot): Boolean = when (type) {
        TYPE_ROOM -> roomId != null && snap.roomId == roomId
        TYPE_POSITION ->
            (roomId == null || snap.roomId == roomId) &&
                x != null && y != null &&
                kotlin.math.abs(snap.samusX - x) <= tolerance &&
                kotlin.math.abs(snap.samusY - y) <= tolerance
        TYPE_ITEM -> itemMask != null && (snap.collectedItems and itemMask) == itemMask
        TYPE_BOSS ->
            (roomId == null || snap.roomId == roomId) &&
                snap.enemies.isNotEmpty() && snap.enemies[0].hp == 0 && snap.enemiesKilled > 0
        TYPE_SURVIVE -> false // resolved by the evaluator at the frame budget
        else -> false
    }

    companion object {
        const val TYPE_ROOM = "room"
        const val TYPE_POSITION = "position"
        const val TYPE_ITEM = "item"
        const val TYPE_BOSS = "boss"
        const val TYPE_SURVIVE = "survive"
    }
}

/** One recorded point of the run trace (subsampled). */
@Serializable
data class TasTracePoint(
    val frame: Int,
    val roomId: Int,
    val x: Int,
    val y: Int,
    val health: Int,
)

/** A room transition observed during a run. */
@Serializable
data class TasRoomTransition(
    val frame: Int,
    val fromRoomId: Int,
    val toRoomId: Int,
)

/**
 * Result of executing a movie against a goal. `framesToGoal` is the primary
 * fitness signal for optimizers; `trace` and `transitions` feed maps,
 * verification, and reward shaping.
 */
@Serializable
data class TasRunResult(
    val achieved: Boolean = false,
    /** Frame at which the goal was first satisfied, or -1. */
    val framesToGoal: Int = -1,
    val totalFramesRun: Int = 0,
    val died: Boolean = false,
    /** In-game time in frames at the end of the run (the speedrun clock). */
    val endIgtFrames: Long = 0,
    val endSnapshot: SmSnapshot = SmSnapshot(),
    val transitions: List<TasRoomTransition> = emptyList(),
    val trace: List<TasTracePoint> = emptyList(),
)
