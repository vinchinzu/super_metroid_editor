package com.supermetroid.editor.ui

import com.supermetroid.editor.integration.BridgeCommand
import com.supermetroid.editor.integration.BridgeResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

class EmulatorBridgeClient private constructor(
    private val process: Process,
    private val input: BufferedReader,
    private val output: BufferedWriter,
    private val json: Json,
) : Closeable {

    @Synchronized
    fun request(
        command: String,
        state: String? = null,
        saveName: String? = null,
        navExportDir: String? = null,
        controlMode: String? = null,
        selectedModel: String? = null,
        playerCount: Int? = null,
        action: List<Int> = emptyList(),
        repeat: Int = 1,
        includeFrame: Boolean = true,
        includeTrace: Boolean = true,
    ): BridgeResponse {
        val payload = BridgeCommand(
            id = UUID.randomUUID().toString(),
            command = command,
            state = state,
            saveName = saveName,
            navExportDir = navExportDir,
            controlMode = controlMode,
            selectedModel = selectedModel,
            playerCount = playerCount,
            action = action,
            repeat = repeat,
            includeFrame = includeFrame,
            includeTrace = includeTrace,
        )
        output.write(json.encodeToString(payload))
        output.newLine()
        output.flush()

        val line = input.readLine() ?: throw IllegalStateException("Bridge closed unexpectedly")
        return json.decodeFromString(BridgeResponse.serializer(), line)
    }

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        process.destroy()
    }

    companion object {
        private val protocolJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun connect(startDir: File = File(System.getProperty("user.dir"))): EmulatorBridgeClient {
            val script = detectBridgeScript(startDir)
                ?: throw IllegalStateException("Could not locate an SM editor bridge script")
            val python = detectPythonExecutable(script)
            val process = ProcessBuilder(python, script.absolutePath, "--stdio")
                .directory(script.parentFile)
                .start()
            Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> System.err.println("[SM Bridge] $line") }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val input = BufferedReader(InputStreamReader(process.inputStream))
            val output = process.outputStream.bufferedWriter()
            return EmulatorBridgeClient(process, input, output, protocolJson)
        }

        private fun detectPythonExecutable(script: File): String {
            var current: File? = script.parentFile.absoluteFile
            repeat(6) {
                val venvPython = current?.resolve(".venv/bin/python")
                if (venvPython?.isFile == true && venvPython.canExecute()) return venvPython.absolutePath
                current = current?.parentFile
            }
            val configured = System.getenv("PYTHON")?.trim()
            if (!configured.isNullOrEmpty()) return configured
            return "python"
        }

        private fun detectBridgeScript(startDir: File): File? {
            var current: File? = startDir.absoluteFile
            repeat(8) {
                val editorLocalCandidate = current?.resolve("runtime/sm_bridge/editor_bridge.py")
                if (editorLocalCandidate?.isFile == true) return editorLocalCandidate
                val rootCandidate = current?.resolve("super_metroid_rl/editor_bridge.py")
                if (rootCandidate?.isFile == true) return rootCandidate
                val nestedCandidate = current?.resolve("editor_bridge.py")
                if (nestedCandidate?.isFile == true && current?.name == "super_metroid_rl") {
                    return nestedCandidate
                }
                current = current?.parentFile
            }
            return null
        }
    }
}
