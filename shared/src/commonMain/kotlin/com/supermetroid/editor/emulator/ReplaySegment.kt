package com.supermetroid.editor.emulator

data class ReplaySegment(
    val startIndex: Int,
    val targetIndex: Int,
    val startFrameNumber: Long,
    val targetFrameNumber: Long,
) {
    companion object {
        fun detect(log: AttemptLog, startRoom: Int, targetRoom: Int): ReplaySegment {
            val start = log.frames.indexOfFirst { it.frameState.isPlayableIn(startRoom) }
            require(start >= 0) {
                "Could not find playable start room 0x${startRoom.toString(16).uppercase()} in replay"
            }
            val targetOffset = log.frames.drop(start + 1).indexOfFirst { it.frameState.isPlayableIn(targetRoom) }
            require(targetOffset >= 0) {
                "Could not find playable target room 0x${targetRoom.toString(16).uppercase()} after start"
            }
            val target = start + 1 + targetOffset
            return ReplaySegment(
                startIndex = start,
                targetIndex = target,
                startFrameNumber = log.frames[start].frameNumber,
                targetFrameNumber = log.frames[target].frameNumber,
            )
        }
    }
}
