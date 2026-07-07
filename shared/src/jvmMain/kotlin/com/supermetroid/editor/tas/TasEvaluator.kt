package com.supermetroid.editor.tas

/**
 * Executes a movie on a [TasSession] and scores it against a [TasGoal].
 *
 * This is the single evaluation path shared by the CLI runner, the editor's
 * playback verifier, and (via `tas-run` JSON output) the Python hill-climb /
 * genetic optimizers.
 */
object TasEvaluator {

    /**
     * Run [movie] from the session's current frame. The session must already
     * be positioned at the intended start (state loaded or power-on).
     *
     * @param goal success condition; null just plays the movie and reports the trace
     * @param traceEvery record a trace point every N frames (0 disables)
     * @param stopAtGoal stop stepping once the goal is achieved (fitness runs);
     *   false plays the whole movie (verification runs)
     */
    fun run(
        session: TasSession,
        movie: TasMovie,
        goal: TasGoal? = null,
        traceEvery: Int = 30,
        stopAtGoal: Boolean = true,
    ): TasRunResult {
        val budget = if (goal != null && goal.maxFrames > 0) goal.maxFrames else movie.frameCount
        val transitions = mutableListOf<TasRoomTransition>()
        val trace = mutableListOf<TasTracePoint>()

        var snap = session.snapshot()
        var lastRoom = snap.roomId
        var achievedFrame = -1
        var died = false

        while (session.frame < budget) {
            session.step(movie.frameAt(session.frame))
            snap = session.snapshot()
            val frame = session.frame

            if (snap.roomId != lastRoom) {
                transitions.add(TasRoomTransition(frame, lastRoom, snap.roomId))
                lastRoom = snap.roomId
            }
            if (traceEvery > 0 && frame % traceEvery == 0) {
                trace.add(TasTracePoint(frame, snap.roomId, snap.samusX, snap.samusY, snap.health))
            }
            if (goal != null) {
                if (goal.failOnDeath && snap.dead) {
                    died = true
                    break
                }
                if (achievedFrame < 0 && goal.achieved(snap)) {
                    achievedFrame = frame
                    if (stopAtGoal) break
                }
            }
        }

        val surviveAchieved = goal?.type == TasGoal.TYPE_SURVIVE &&
            !died && session.frame >= budget
        if (surviveAchieved && achievedFrame < 0) achievedFrame = session.frame

        return TasRunResult(
            achieved = achievedFrame >= 0,
            framesToGoal = achievedFrame,
            totalFramesRun = session.frame,
            died = died,
            endIgtFrames = snap.igtTotalFrames,
            endSnapshot = snap,
            transitions = transitions,
            trace = trace,
        )
    }
}
