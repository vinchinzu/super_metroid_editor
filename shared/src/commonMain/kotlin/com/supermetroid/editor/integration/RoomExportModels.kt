package com.supermetroid.editor.integration

import kotlinx.serialization.Serializable

@Serializable
data class EditorRoomExport(
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
    val widthBlocks: Int,
    val heightBlocks: Int,
    val tileset: Int,
    val collision: List<List<Int>>,
    val bts: List<List<Int>>,
    val doors: List<EditorDoorExport>,
    val items: List<EditorItemExport>,
    val enemies: List<EditorEnemyExport>,
    val plms: List<EditorPlmExport>,
)

@Serializable
data class EditorDoorExport(
    val destRoomId: Int,
    val destRoomIdHex: String,
    val destRoomHandle: String? = null,
    val direction: String,
    val isElevator: Boolean,
    val doorCapColor: String? = null,
    val requiredAbility: String? = null,
    val screenX: Int? = null,
    val screenY: Int? = null,
    val sourceBlockX: Int? = null,
    val sourceBlockY: Int? = null,
)

@Serializable
data class EditorItemExport(
    val name: String,
    val blockX: Int,
    val blockY: Int,
    val plmId: Int,
    val plmIdHex: String,
)

@Serializable
data class EditorEnemyExport(
    val id: Int,
    val idHex: String,
    val name: String,
    val pixelX: Int,
    val pixelY: Int,
    val blockX: Int,
    val blockY: Int,
)

@Serializable
data class EditorPlmExport(
    val id: Int,
    val idHex: String,
    val blockX: Int,
    val blockY: Int,
    val param: Int,
    val category: String,
)
