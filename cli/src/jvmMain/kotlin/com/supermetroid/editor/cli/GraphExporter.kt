package com.supermetroid.editor.cli

class GraphExporter(
    private val roomExporter: RoomExporter,
) {
    fun exportGraph(): NavGraph {
        val allExports = roomExporter.exportAllRooms()
        return exportGraphFrom(allExports)
    }

    fun exportGraphFrom(roomExports: List<RoomExport>): NavGraph {
        val nodes = mutableListOf<NavNode>()
        val edges = mutableListOf<NavEdge>()

        for (room in roomExports) {
            nodes.add(
                NavNode(
                    roomId = room.roomId,
                    roomIdHex = room.roomIdHex,
                    handle = room.handle,
                    name = room.name,
                    area = room.area,
                    areaName = room.areaName,
                    mapX = room.mapX,
                    mapY = room.mapY,
                    widthScreens = room.widthScreens,
                    heightScreens = room.heightScreens,
                )
            )

            for (door in room.doors) {
                edges.add(
                    NavEdge(
                        fromRoomId = room.roomId,
                        fromRoomIdHex = room.roomIdHex,
                        toRoomId = door.destRoomId,
                        toRoomIdHex = door.destRoomIdHex,
                        direction = door.direction,
                        isElevator = door.isElevator,
                        doorCapColor = door.doorCapColor,
                        requiredAbility = door.requiredAbility,
                    )
                )
            }
        }

        return NavGraph(nodes = nodes, edges = edges)
    }
}
