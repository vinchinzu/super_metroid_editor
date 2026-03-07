package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

class BizHawkBackendTest {

    @Test
    fun `backend speaks BizHawk JSON protocol over TCP`() = runBlocking {
        val bridge = FakeBizHawkBridge()
        val originalSettings = AppConfig.load()
        AppConfig.save(
            originalSettings.copy(
                emulatorBackend = "bizhawk",
                bizhawkPort = bridge.port,
                bizhawkPath = null,
            )
        )

        val backend = BizHawkBackend()
        try {
            val capabilities = backend.connect()
            assertTrue(backend.isConnected)
            assertEquals("BizHawk", capabilities.backendName)
            assertTrue(capabilities.supportsMemoryAccess)

            val started = backend.startSession(
                SessionConfig(
                    romPath = "/tmp/SuperMetroid.sfc",
                    stateName = "ZebesStart",
                )
            )
            assertTrue(started.session.active)
            assertEquals("ZebesStart", bridge.command("load_rom")?.get("stateName")?.jsonPrimitive?.content)
            assertEquals("/tmp/SuperMetroid.sfc", bridge.command("load_rom")?.get("romPath")?.jsonPrimitive?.content)

            val states = backend.listStates()
            assertEquals(listOf("CheckpointA"), states.map { it.name })

            val memory = backend.readMemory(0x1234, 3)
            assertArrayEquals(byteArrayOf(1, 2, 0xFF.toByte()), memory)

            backend.writeMemory(0x40, byteArrayOf(0x7F, 0x80.toByte()))
            assertEquals(0x40, bridge.lastWriteAddress)
            assertEquals(listOf(0x7F, 0x80), bridge.lastWriteBytes)

            val closed = backend.closeSession()
            assertFalse(closed.session.active)
        } finally {
            backend.close()
            bridge.close()
            AppConfig.save(originalSettings)
        }
    }
}

private class FakeBizHawkBridge : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val server = ServerSocket(0)
    private val commands = mutableListOf<JsonObject>()
    private val serverThread = thread(start = true, isDaemon = true, name = "fake-bizhawk-bridge") {
        try {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                while (true) {
                    val line = reader.readLine() ?: break
                    val command = json.parseToJsonElement(line).jsonObject
                    synchronized(commands) { commands += command }
                    val response = responseFor(command)
                    writer.write(json.encodeToString(JsonObject.serializer(), response))
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (_: SocketException) {
            // Server closed by test teardown.
        }
    }

    val port: Int = server.localPort
    var lastWriteAddress: Int? = null
        private set
    var lastWriteBytes: List<Int> = emptyList()
        private set

    fun command(name: String): JsonObject? {
        return synchronized(commands) {
            commands.firstOrNull { it["command"]?.jsonPrimitive?.content == name }
        }
    }

    override fun close() {
        runCatching { server.close() }
        serverThread.join(1000)
    }

    private fun responseFor(command: JsonObject): JsonObject {
        return when (command["command"]?.jsonPrimitive?.content) {
            "hello" -> buildJsonObject {
                put("ok", true)
                put("message", "BizHawk bridge connected")
                put(
                    "capabilities",
                    buildJsonObject {
                        put("emulator", "BizHawk")
                        put("supportsFrames", true)
                        put("supportsMemoryAccess", true)
                        put("supportsSaveStates", true)
                    }
                )
            }

            "load_rom" -> buildJsonObject {
                put("ok", true)
                put("message", "ROM loaded")
                put("session", session(active = true, currentState = command["stateName"]?.jsonPrimitive?.content, frame = 12))
                put("snapshot", snapshot(frame = 12))
            }

            "list_states" -> buildJsonObject {
                put("ok", true)
                put(
                    "states",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "CheckpointA")
                                put("path", "/tmp/editor_states/CheckpointA.state")
                            }
                        )
                    }
                )
            }

            "read_memory" -> buildJsonObject {
                put("ok", true)
                put("memoryData", JsonArray(listOf(1, 2, 255).map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }

            "write_memory" -> {
                lastWriteAddress = command["address"]?.jsonPrimitive?.content?.toInt()
                lastWriteBytes = command["data"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }.orEmpty()
                buildJsonObject {
                    put("ok", true)
                    put("message", "memory written")
                }
            }

            "close_session" -> buildJsonObject {
                put("ok", true)
                put("message", "Session closed")
                put("session", session(active = false, currentState = null, frame = 12))
                put("states", buildJsonArray {})
            }

            else -> buildJsonObject {
                put("ok", false)
                put("error", "Unhandled command: ${command["command"]?.jsonPrimitive?.content}")
            }
        }
    }

    private fun session(active: Boolean, currentState: String?, frame: Int): JsonObject {
        return buildJsonObject {
            put("active", active)
            put("paused", !active)
            if (currentState != null) put("currentState", currentState)
            put("frameCounter", frame)
        }
    }

    private fun snapshot(frame: Int): JsonObject {
        return buildJsonObject {
            put("frameCounter", frame)
            put("roomId", 0x91F8)
            put("health", 99)
            put("samusX", 128)
            put("samusY", 64)
            put("doorTransition", false)
            put("frameWidth", 0)
            put("frameHeight", 0)
            put("trace", buildJsonArray {})
        }
    }
}
