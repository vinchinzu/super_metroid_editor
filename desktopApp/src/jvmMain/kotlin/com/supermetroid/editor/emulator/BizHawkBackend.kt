package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

private const val DEFAULT_PORT = 43884
private const val CONNECT_RETRY_ATTEMPTS = 20
private const val CONNECT_RETRY_DELAY_MS = 500L

@Serializable
internal data class BizHawkCommand(
    val command: String,
    val romPath: String? = null,
    val stateName: String? = null,
    val buttons: List<Int>? = null,
    val repeat: Int = 1,
    val includeFrame: Boolean = true,
    val address: Int? = null,
    val size: Int? = null,
    val data: List<Int>? = null,
)

@Serializable
internal data class BizHawkResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val capabilities: BizHawkCapabilities? = null,
    val session: BizHawkSessionState? = null,
    val snapshot: BizHawkSnapshot? = null,
    val states: List<BizHawkStateEntry>? = null,
    val memoryData: List<Int>? = null,
)

@Serializable
internal data class BizHawkCapabilities(
    val emulator: String = "BizHawk",
    val supportsFrames: Boolean = true,
    val supportsMemoryAccess: Boolean = true,
    val supportsSaveStates: Boolean = true,
)

@Serializable
internal data class BizHawkSessionState(
    val active: Boolean = false,
    val paused: Boolean = true,
    val currentState: String? = null,
    val frameCounter: Int = 0,
)

@Serializable
internal data class BizHawkSnapshot(
    val frameCounter: Int = 0,
    val roomId: Int? = null,
    val health: Int? = null,
    val samusX: Int? = null,
    val samusY: Int? = null,
    val doorTransition: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameRgb24Base64: String? = null,
    val trace: List<BizHawkTracePoint>? = null,
)

@Serializable
internal data class BizHawkTracePoint(
    val frame: Int,
    val roomId: Int,
    val x: Int,
    val y: Int,
)

@Serializable
internal data class BizHawkStateEntry(
    val name: String,
    val path: String,
)

class BizHawkBackend : EmulatorBackend {

    override val name: String = "bizhawk"
    override var isConnected: Boolean = false
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var process: Process? = null
    private var launchScriptFile: File? = null
    private var sessionActive = false

    override suspend fun connect(): EmulatorCapabilities = withContext(Dispatchers.IO) {
        val settings = AppConfig.load()
        val preferredPort = settings.bizhawkPort

        // Try connecting to an already-running BizHawk first.
        if (tryConnectAny(listOf(preferredPort, DEFAULT_PORT).distinct()) == null) {
            // Launch BizHawk if configured
            val bizhawkPath = settings.bizhawkPath ?: detectBizHawk()
            if (bizhawkPath != null) {
                launchBizHawk(bizhawkPath, preferredPort)
                // Retry connection
                var retryConnected = false
                repeat(CONNECT_RETRY_ATTEMPTS) {
                    if (!retryConnected) {
                        delay(CONNECT_RETRY_DELAY_MS)
                        retryConnected = tryConnect(preferredPort)
                    }
                }
                if (!retryConnected) {
                    throw IllegalStateException("Failed to connect to BizHawk on port $preferredPort after launch")
                }
            } else {
                throw IllegalStateException(
                    "Cannot connect to BizHawk on ports ${listOf(preferredPort, DEFAULT_PORT).distinct().joinToString()}. " +
                        "Either start BizHawk with --lua=bridge.lua or set bizhawkPath in settings."
                )
            }
        }

        isConnected = true
        val response = sendCommand(BizHawkCommand(command = "hello"))
        val caps = response.capabilities
        EmulatorCapabilities(
            backendName = "BizHawk",
            supportsFrames = caps?.supportsFrames ?: true,
            supportsMemoryAccess = caps?.supportsMemoryAccess ?: true,
            supportsSaveStates = caps?.supportsSaveStates ?: true,
        )
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        sessionActive = false
        closeTransport()
    }

    override suspend fun startSession(config: SessionConfig): StepResult = withContext(Dispatchers.IO) {
        val response = sendCommand(
            BizHawkCommand(
                command = "load_rom",
                romPath = config.romPath,
                stateName = config.stateName,
            )
        )
        sessionActive = true
        mapStepResult(response)
    }

    override suspend fun closeSession(): StepResult = withContext(Dispatchers.IO) {
        sessionActive = false
        mapStepResult(sendCommand(BizHawkCommand(command = "close_session")))
    }

    override suspend fun step(input: EmulatorInput): StepResult = withContext(Dispatchers.IO) {
        val response = sendCommand(
            BizHawkCommand(
                command = "step",
                buttons = input.buttons,
                repeat = input.repeat,
                includeFrame = input.includeFrame,
            )
        )
        mapStepResult(response)
    }

    override suspend fun snapshot(): GameSnapshot = withContext(Dispatchers.IO) {
        val response = sendCommand(BizHawkCommand(command = "snapshot", includeFrame = true))
        mapSnapshot(response.snapshot)
    }

    override suspend fun saveState(name: String) = withContext(Dispatchers.IO) {
        sendCommand(BizHawkCommand(command = "save_state", stateName = name))
        Unit
    }

    override suspend fun loadState(name: String): StepResult = withContext(Dispatchers.IO) {
        val response = sendCommand(BizHawkCommand(command = "load_state", stateName = name))
        mapStepResult(response)
    }

    override suspend fun listStates(): List<StateInfo> = withContext(Dispatchers.IO) {
        val response = sendCommand(BizHawkCommand(command = "list_states"))
        response.states?.map { StateInfo(name = it.name, path = it.path) } ?: emptyList()
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray = withContext(Dispatchers.IO) {
        val response = sendCommand(BizHawkCommand(command = "read_memory", address = address, size = size))
        val data = response.memoryData ?: emptyList()
        ByteArray(data.size) { data[it].toByte() }
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) = withContext(Dispatchers.IO) {
        sendCommand(
            BizHawkCommand(
                command = "write_memory",
                address = address,
                data = data.map { it.toInt() and 0xFF },
            )
        )
        Unit
    }

    override fun close() {
        isConnected = false
        sessionActive = false
        closeTransport()
        runCatching { process?.destroy() }
        process = null
        cleanupLaunchScript()
    }

    @Synchronized
    private fun sendCommand(command: BizHawkCommand): BizHawkResponse {
        val w = writer ?: throw IllegalStateException("Not connected to BizHawk")
        val r = reader ?: throw IllegalStateException("Not connected to BizHawk")
        w.write(json.encodeToString(command))
        w.newLine()
        w.flush()
        val line = r.readLine() ?: throw IllegalStateException("BizHawk connection closed unexpectedly")
        val response = json.decodeFromString<BizHawkResponse>(line)
        if (!response.ok) {
            throw IllegalStateException("BizHawk error: ${response.error ?: "unknown"}")
        }
        return response
    }

    private fun tryConnect(port: Int): Boolean {
        return try {
            val s = Socket("127.0.0.1", port)
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryConnectAny(ports: Iterable<Int>): Int? {
        for (port in ports) {
            if (tryConnect(port)) return port
        }
        return null
    }

    private fun launchBizHawk(bizhawkPath: String, port: Int) {
        val bridgeScript = detectBridgeScript()?.let(::File)
            ?: throw IllegalStateException("Cannot find runtime/bizhawk/bridge.lua")
        cleanupLaunchScript()
        val launchScript = createLaunchScript(bridgeScript, port)
        launchScriptFile = launchScript
        process = ProcessBuilder(bizhawkPath, "--lua=${launchScript.absolutePath}")
            .directory(bridgeScript.parentFile)
            .redirectErrorStream(true)
            .start()
        Thread {
            process?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { System.err.println("[BizHawk] $it") }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun detectBizHawk(): String? {
        val commonPaths = listOf(
            "/usr/bin/EmuHawk",
            "/usr/local/bin/EmuHawk",
            "${System.getProperty("user.home")}/BizHawk/EmuHawk",
            "${System.getProperty("user.home")}/.local/share/BizHawk/EmuHawk",
            "C:\\BizHawk\\EmuHawk.exe",
            "${System.getenv("ProgramFiles") ?: "C:\\Program Files"}\\BizHawk\\EmuHawk.exe",
        )
        return commonPaths.firstOrNull { File(it).isFile }
    }

    private fun detectBridgeScript(): String? {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(current, "runtime/bizhawk/bridge.lua")
            if (candidate.isFile) return candidate.absolutePath
            current = current.parentFile ?: return null
        }
        return null
    }

    private fun createLaunchScript(bridgeScript: File, port: Int): File {
        val bridgeDir = bridgeScript.parentFile
        val launchScript = File.createTempFile("smedit-bizhawk-bridge-", ".lua")
        val packagePathEntry = File(bridgeDir, "?.lua").absolutePath
        val projectDir = bridgeDir.parentFile?.parentFile?.absolutePath ?: System.getProperty("user.dir")
        val explicitStateDir = System.getenv("SMEDIT_STATE_DIR")?.trim()?.takeIf { it.isNotEmpty() }
        val stateDirLine = explicitStateDir?.let {
            "SMEDIT_STATE_DIR = ${luaStringLiteral(it)}"
        } ?: "-- no explicit state dir override"
        val script = """
            SMEDIT_BIZHAWK_PORT = $port
            SMEDIT_BIZHAWK_WORKDIR = ${luaStringLiteral(projectDir)}
            $stateDirLine
            package.path = ${luaStringLiteral(packagePathEntry)} .. ";" .. package.path
            dofile(${luaStringLiteral(bridgeScript.absolutePath)})
        """.trimIndent()
        launchScript.writeText(script)
        launchScript.deleteOnExit()
        return launchScript
    }

    private fun luaStringLiteral(value: String): String {
        return buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

    private fun closeTransport() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    private fun cleanupLaunchScript() {
        runCatching { launchScriptFile?.delete() }
        launchScriptFile = null
    }

    private fun mapStepResult(response: BizHawkResponse): StepResult {
        return StepResult(
            session = mapSession(response.session),
            snapshot = mapSnapshot(response.snapshot),
            states = response.states?.map { StateInfo(name = it.name, path = it.path) } ?: emptyList(),
            message = response.message,
        )
    }

    private fun mapSession(session: BizHawkSessionState?): SessionState {
        if (session == null) return SessionState(active = sessionActive)
        return SessionState(
            active = session.active,
            paused = session.paused,
            currentState = session.currentState,
            frameCounter = session.frameCounter,
        )
    }

    private fun mapSnapshot(snapshot: BizHawkSnapshot?): GameSnapshot {
        if (snapshot == null) return GameSnapshot()
        return GameSnapshot(
            frameCounter = snapshot.frameCounter,
            roomId = snapshot.roomId,
            health = snapshot.health,
            samusX = snapshot.samusX,
            samusY = snapshot.samusY,
            doorTransition = snapshot.doorTransition,
            frameWidth = snapshot.frameWidth,
            frameHeight = snapshot.frameHeight,
            frameRgb24Base64 = snapshot.frameRgb24Base64,
            trace = snapshot.trace?.map { TracePoint(it.frame, it.roomId, it.x, it.y) } ?: emptyList(),
        )
    }
}
