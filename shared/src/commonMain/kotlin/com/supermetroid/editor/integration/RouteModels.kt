package com.supermetroid.editor.integration

import kotlinx.serialization.Serializable

@Serializable
data class RouteInputFrame(
    val frame: Int,
    val buttons: List<Int>,
)

@Serializable
data class RoutePositionSample(
    val frame: Int,
    val roomId: Int,
    val x: Int,
    val y: Int,
)

@Serializable
data class TasRoute(
    val name: String,
    val description: String = "",
    val startStateName: String? = null,
    val frameCount: Int = 0,
    val inputs: List<RouteInputFrame> = emptyList(),
    val positions: List<RoutePositionSample> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    fun withInput(frame: Int, buttons: List<Int>): TasRoute {
        val newInputs = inputs.toMutableList()
        val existingIndex = newInputs.indexOfFirst { it.frame == frame }
        val inputFrame = RouteInputFrame(frame, buttons)
        if (existingIndex >= 0) {
            newInputs[existingIndex] = inputFrame
        } else {
            newInputs.add(inputFrame)
            newInputs.sortBy { it.frame }
        }
        return copy(
            inputs = newInputs,
            frameCount = maxOf(frameCount, frame + 1),
        )
    }

    fun withPosition(frame: Int, roomId: Int, x: Int, y: Int): TasRoute {
        val newPositions = positions.toMutableList()
        val sample = RoutePositionSample(frame, roomId, x, y)
        newPositions.add(sample)
        return copy(
            positions = newPositions,
            frameCount = maxOf(frameCount, frame + 1),
        )
    }

    fun inputAt(frame: Int): List<Int>? {
        return inputs.lastOrNull { it.frame <= frame }?.buttons
    }

    fun positionAt(frame: Int): RoutePositionSample? {
        return positions.lastOrNull { it.frame <= frame }
    }

    fun trim(maxFrame: Int): TasRoute {
        return copy(
            inputs = inputs.filter { it.frame < maxFrame },
            positions = positions.filter { it.frame < maxFrame },
            frameCount = maxFrame,
        )
    }
}
