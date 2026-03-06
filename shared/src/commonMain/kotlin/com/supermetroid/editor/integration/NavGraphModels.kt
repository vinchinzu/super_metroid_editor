package com.supermetroid.editor.integration

import kotlinx.serialization.Serializable

@Serializable
data class EditorNavGraph(
    val nodes: List<EditorNavNode>,
    val edges: List<EditorNavEdge>,
)

@Serializable
data class EditorNavNode(
    val roomId: Int,
    val roomIdHex: String,
    val handle: String,
    val name: String,
    val area: Int,
    val areaName: String,
    val mapX: Int,
    val mapY: Int,
    val widthScreens: Int,
    val heightScreens: Int,
)

@Serializable
data class EditorNavEdge(
    val fromRoomId: Int,
    val fromRoomIdHex: String,
    val toRoomId: Int,
    val toRoomIdHex: String,
    val direction: String,
    val isElevator: Boolean,
    val doorCapColor: String? = null,
    val requiredAbility: String? = null,
)
