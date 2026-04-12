package com.supermetroid.editor.emulator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.Socket
import java.util.Base64

private const val WORKER_PORT_ENV = "SMEDIT_WORKER_PORT"
private const val WORKER_AUDIO_ENV = "SMEDIT_WORKER_AUDIO_ENABLED"

fun main() {
    val port = System.getenv(WORKER_PORT_ENV)?.toIntOrNull()
        ?: error("Missing $WORKER_PORT_ENV")
    val audioEnabledOverride = parseWorkerBoolean(System.getenv(WORKER_AUDIO_ENV))
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 60_000
        val reader = socket.getInputStream().bufferedReader()
        val writer = socket.getOutputStream().bufferedWriter()
        val backend = LibretroBackend(
            audioEnabledOverride = audioEnabledOverride,
        )

        try {
            while (true) {
                val line = reader.readLine() ?: break
                val request = json.decodeFromString(LibretroWorkerRequest.serializer(), line)
                val response = runBlocking { handleWorkerRequest(request, backend) }
                writer.write(json.encodeToString(response))
                writer.newLine()
                writer.flush()
                if (request.command == "shutdown") {
                    break
                }
            }
        } finally {
            backend.close()
        }
    }
}

private suspend fun handleWorkerRequest(
    request: LibretroWorkerRequest,
    backend: LibretroBackend,
): LibretroWorkerResponse {
    return try {
        when (request.command) {
            "connect" -> {
                val capabilities = if (backend.isConnected) {
                    EmulatorCapabilities(
                        backendName = backend.name,
                        supportsFrames = true,
                        supportsMemoryAccess = true,
                        supportsSaveStates = true,
                    )
                } else {
                    backend.connect()
                }
                LibretroWorkerResponse(id = request.id, capabilities = capabilities)
            }
            "disconnect" -> {
                if (backend.isConnected) backend.disconnect()
                LibretroWorkerResponse(id = request.id)
            }
            "start_session" -> {
                val result = backend.startSession(requireNotNull(request.sessionConfig) { "sessionConfig is required" })
                LibretroWorkerResponse(id = request.id, stepResult = result)
            }
            "close_session" -> {
                val result = backend.closeSession()
                LibretroWorkerResponse(id = request.id, stepResult = result)
            }
            "step" -> {
                val result = backend.step(requireNotNull(request.input) { "input is required" })
                LibretroWorkerResponse(id = request.id, stepResult = result)
            }
            "snapshot" -> {
                val snapshot = backend.snapshot()
                LibretroWorkerResponse(id = request.id, snapshot = snapshot)
            }
            "save_state" -> {
                backend.saveState(requireNotNull(request.name) { "name is required" })
                LibretroWorkerResponse(id = request.id)
            }
            "load_state" -> {
                val result = backend.loadState(requireNotNull(request.name) { "name is required" })
                LibretroWorkerResponse(id = request.id, stepResult = result)
            }
            "list_states" -> {
                LibretroWorkerResponse(id = request.id, states = backend.listStates())
            }
            "read_memory" -> {
                val bytes = backend.readMemory(
                    requireNotNull(request.address) { "address is required" },
                    requireNotNull(request.size) { "size is required" },
                )
                LibretroWorkerResponse(
                    id = request.id,
                    dataBase64 = Base64.getEncoder().encodeToString(bytes),
                )
            }
            "write_memory" -> {
                val bytes = Base64.getDecoder().decode(requireNotNull(request.dataBase64) { "dataBase64 is required" })
                backend.writeMemory(requireNotNull(request.address) { "address is required" }, bytes)
                LibretroWorkerResponse(id = request.id)
            }
            "set_audio_muted" -> {
                backend.audioMuted = request.muted ?: false
                LibretroWorkerResponse(id = request.id)
            }
            "shutdown" -> {
                if (backend.isConnected) backend.disconnect()
                LibretroWorkerResponse(id = request.id)
            }
            else -> LibretroWorkerResponse(id = request.id, ok = false, error = "Unknown command: ${request.command}")
        }
    } catch (t: Throwable) {
        LibretroWorkerResponse(
            id = request.id,
            ok = false,
            error = t.message ?: t::class.simpleName ?: "Worker error",
        )
    }
}

private fun parseWorkerBoolean(raw: String?): Boolean? {
    return when (raw?.trim()?.lowercase()) {
        null, "" -> null
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
}
