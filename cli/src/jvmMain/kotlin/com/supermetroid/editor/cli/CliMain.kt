package com.supermetroid.editor.cli

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.system.exitProcess

private val jsonPretty = Json { prettyPrint = true }
private val jsonCompact = Json { prettyPrint = false }

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(1)
    }

    var romPath: String? = null
    var compact = false
    var command: String? = null
    val commandArgs = mutableListOf<String>()

    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        when {
            arg == "--rom" && iter.hasNext() -> romPath = iter.next()
            arg == "--compact" -> compact = true
            arg == "--help" || arg == "-h" -> { printUsage(); return }
            command == null -> command = arg
            else -> commandArgs.add(arg)
        }
    }

    if (romPath == null) {
        System.err.println("Error: --rom <path> is required")
        exitProcess(1)
    }
    if (command == null) {
        System.err.println("Error: no command specified")
        printUsage()
        exitProcess(1)
    }

    val json = if (compact) jsonCompact else jsonPretty
    val parser = RomParser.loadRom(romPath)
    val repo = RoomRepository()
    val roomExporter = RoomExporter(parser, repo)

    when (command) {
        "rooms" -> cmdRooms(roomExporter, json)
        "room" -> cmdRoom(roomExporter, json, commandArgs)
        "graph" -> cmdGraph(roomExporter, json)
        "export" -> cmdExport(roomExporter, json, commandArgs)
        else -> {
            System.err.println("Unknown command: $command")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun cmdRooms(exporter: RoomExporter, json: Json) {
    val summaries = exporter.exportRoomSummaries()
    println(json.encodeToString(summaries))
}

private fun cmdRoom(exporter: RoomExporter, json: Json, args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: room <roomId|handle>")
        System.err.println("  roomId: hex like 0x91F8 or 91F8")
        System.err.println("  handle: string like landingSite")
        exitProcess(1)
    }
    val roomId = exporter.resolveRoomId(args[0]) ?: run {
        System.err.println("Room not found: ${args[0]}")
        exitProcess(1)
    }
    val export = exporter.exportRoom(roomId) ?: run {
        System.err.println("Failed to parse room 0x${roomId.toString(16)}")
        exitProcess(1)
    }
    println(json.encodeToString(export))
}

private fun cmdGraph(roomExporter: RoomExporter, json: Json) {
    val graphExporter = GraphExporter(roomExporter)
    val graph = graphExporter.exportGraph()
    println(json.encodeToString(graph))
}

private fun cmdExport(
    roomExporter: RoomExporter,
    json: Json,
    args: List<String>,
) {
    var outDir: String? = null
    val iter = args.iterator()
    while (iter.hasNext()) {
        val arg = iter.next()
        if ((arg == "-o" || arg == "--output") && iter.hasNext()) {
            outDir = iter.next()
        }
    }
    if (outDir == null) {
        System.err.println("Usage: export -o <directory>")
        exitProcess(1)
    }

    val dir = File(outDir)
    dir.mkdirs()
    val roomsDir = File(dir, "rooms")
    roomsDir.mkdirs()

    // Export all rooms once and cache results
    val summaries = roomExporter.exportRoomSummaries()
    val roomExports = summaries.mapNotNull { s -> roomExporter.exportRoom(s.roomId)?.let { s to it } }

    File(dir, "rooms.json").writeText(json.encodeToString(summaries))
    System.err.println("Wrote rooms.json (${summaries.size} rooms)")

    // Build navigation graph from cached exports
    val graphExporter = GraphExporter(roomExporter)
    val graph = graphExporter.exportGraphFrom(roomExports.map { it.second })
    File(dir, "nav_graph.json").writeText(json.encodeToString(graph))
    System.err.println("Wrote nav_graph.json (${graph.nodes.size} nodes, ${graph.edges.size} edges)")

    // Write per-room files from cache
    for ((_, roomExport) in roomExports) {
        val filename = "room_${roomExport.roomIdHex.removePrefix("0x")}.json"
        File(roomsDir, filename).writeText(json.encodeToString(roomExport))
    }
    val failed = summaries.size - roomExports.size
    System.err.println("Wrote ${roomExports.size} room files to rooms/ ($failed failed)")
}

private fun printUsage() {
    System.err.println("""
Super Metroid Editor CLI - Export structured ROM data

Usage: --rom <path.smc> [--compact] <command> [options]

Commands:
  rooms              List all rooms with metadata (JSON to stdout)
  room <id|handle>   Export single room with full collision grid
  graph              Export navigation graph (nodes + edges)
  export -o <dir>    Export everything: rooms.json, nav_graph.json, rooms/*.json

Options:
  --rom <path>       Path to Super Metroid ROM file (.smc)
  --compact          Output compact JSON (no indentation)
  -h, --help         Show this help

Examples:
  ... --rom rom.smc rooms
  ... --rom rom.smc room 0x91F8
  ... --rom rom.smc room landingSite
  ... --rom rom.smc graph
  ... --rom rom.smc export -o /tmp/sm_export
    """.trimIndent())
}
