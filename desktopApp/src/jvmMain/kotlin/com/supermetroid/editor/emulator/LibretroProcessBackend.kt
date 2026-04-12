package com.supermetroid.editor.emulator

import com.supermetroid.editor.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

private const val WORKER_MAIN_CLASS = "com.supermetroid.editor.emulator.LibretroWorkerMainKt"
private const val WORKER_PORT_ENV = "SMEDIT_WORKER_PORT"
private const val WORKER_AUDIO_ENV = "SMEDIT_WORKER_AUDIO_ENABLED"

class LibretroProcessBackend(
    private val audioEnabledOverride: Boolean? = null,
) : EmulatorBackend, AudioControllableBackend {

    override val name: String = "libretro"
    override var isConnected: Boolean = false
        private set

    override var audioMuted: Boolean = false
        set(value) {
            field = value
            if (isConnected) {
                runCatching {
                    sendRequestBlocking(
                        LibretroWorkerRequest(
                            id = nextRequestId(),
                            command = "set_audio_muted",
                            muted = value,
                        )
                    )
                }
            }
        }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioLock = Any()
    private val workerLog = ConcurrentLinkedDeque<String>()

    private var process: Process? = null
    private var socket: Socket? = null
    private var writer: java.io.BufferedWriter? = null
    private var reader: java.io.BufferedReader? = null

    override suspend fun connect(): EmulatorCapabilities = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            startWorkerLocked()
            val response = sendRequestLocked(LibretroWorkerRequest(id = nextRequestId(), command = "connect"))
            val capabilities = response.capabilities
                ?: throw IllegalStateException("Worker returned no capabilities")
            isConnected = true
            if (audioMuted) {
                sendRequestLocked(
                    LibretroWorkerRequest(
                        id = nextRequestId(),
                        command = "set_audio_muted",
                        muted = audioMuted,
                    )
                )
            }
            capabilities
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                runCatching {
                    if (isConnected) {
                        sendRequestLocked(LibretroWorkerRequest(id = nextRequestId(), command = "disconnect"))
                    }
                }
                isConnected = false
                stopWorkerLocked()
            }
        }
    }

    override suspend fun startSession(config: SessionConfig): StepResult {
        return requestStepResult("start_session", sessionConfig = config)
    }

    override suspend fun closeSession(): StepResult {
        return requestStepResult("close_session")
    }

    override suspend fun step(input: EmulatorInput): StepResult {
        return requestStepResult("step", input = input)
    }

    override suspend fun snapshot(): GameSnapshot = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            ensureConnectedLocked()
            sendRequestLocked(
                LibretroWorkerRequest(
                    id = nextRequestId(),
                    command = "snapshot",
                )
            ).snapshot ?: throw IllegalStateException("Worker returned no snapshot")
        }
    }

    override suspend fun saveState(name: String) {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                ensureConnectedLocked()
                sendRequestLocked(
                    LibretroWorkerRequest(
                        id = nextRequestId(),
                        command = "save_state",
                        name = name,
                    )
                )
            }
        }
    }

    override suspend fun loadState(name: String): StepResult {
        return requestStepResult("load_state", name = name)
    }

    override suspend fun listStates(): List<StateInfo> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            ensureConnectedLocked()
            sendRequestLocked(
                LibretroWorkerRequest(
                    id = nextRequestId(),
                    command = "list_states",
                )
            ).states
        }
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            ensureConnectedLocked()
            val response = sendRequestLocked(
                LibretroWorkerRequest(
                    id = nextRequestId(),
                    command = "read_memory",
                    address = address,
                    size = size,
                )
            )
            val payload = response.dataBase64 ?: return@synchronized ByteArray(0)
            Base64.getDecoder().decode(payload)
        }
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        withContext(Dispatchers.IO) {
            synchronized(ioLock) {
                ensureConnectedLocked()
                sendRequestLocked(
                    LibretroWorkerRequest(
                        id = nextRequestId(),
                        command = "write_memory",
                        address = address,
                        dataBase64 = Base64.getEncoder().encodeToString(data),
                    )
                )
            }
        }
    }

    override fun close() {
        synchronized(ioLock) {
            runCatching {
                if (process?.isAlive == true) {
                    sendRequestLocked(LibretroWorkerRequest(id = nextRequestId(), command = "shutdown"))
                }
            }
            isConnected = false
            stopWorkerLocked()
        }
    }

    private suspend fun requestStepResult(
        command: String,
        sessionConfig: SessionConfig? = null,
        input: EmulatorInput? = null,
        name: String? = null,
    ): StepResult = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            ensureConnectedLocked()
            sendRequestLocked(
                LibretroWorkerRequest(
                    id = nextRequestId(),
                    command = command,
                    sessionConfig = sessionConfig,
                    input = input,
                    name = name,
                )
            ).stepResult ?: throw IllegalStateException("Worker returned no step result for $command")
        }
    }

    private fun startWorkerLocked() {
        if (process?.isAlive == true && socket?.isConnected == true) return

        stopWorkerLocked()
        workerLog.clear()

        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server.soTimeout = 10_000
        try {
            val builder = ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                WORKER_MAIN_CLASS,
            )
            builder.directory(File(System.getProperty("user.dir")))
            builder.redirectErrorStream(true)
            builder.environment()[WORKER_PORT_ENV] = server.localPort.toString()
            (audioEnabledOverride ?: AppConfig.load().libretroAudioEnabled).let { audioEnabled ->
                builder.environment()[WORKER_AUDIO_ENV] = audioEnabled.toString()
            }

            val started = builder.start()
            drainWorkerLogs(started)
            val accepted = server.accept()
            accepted.soTimeout = 60_000

            process = started
            socket = accepted
            writer = accepted.getOutputStream().bufferedWriter()
            reader = accepted.getInputStream().bufferedReader()
        } catch (t: Throwable) {
            stopWorkerLocked()
            throw IllegalStateException("Failed to start libretro worker${workerLogSuffix()}", t)
        } finally {
            runCatching { server.close() }
        }
    }

    private fun stopWorkerLocked() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        writer = null
        reader = null
        socket = null

        process?.let { child ->
            if (child.isAlive) {
                child.destroy()
                runCatching { child.waitFor() }
                if (child.isAlive) {
                    child.destroyForcibly()
                }
            }
        }
        process = null
    }

    private fun ensureConnectedLocked() {
        if (!isConnected) {
            throw IllegalStateException("Not connected to libretro worker")
        }
    }

    private fun sendRequestBlocking(request: LibretroWorkerRequest): LibretroWorkerResponse {
        return synchronized(ioLock) { sendRequestLocked(request) }
    }

    private fun sendRequestLocked(request: LibretroWorkerRequest): LibretroWorkerResponse {
        val activeProcess = process ?: throw IllegalStateException("Worker process is not running")
        val activeWriter = writer ?: throw IllegalStateException("Worker pipe is not open")
        val activeReader = reader ?: throw IllegalStateException("Worker pipe is not open")

        return try {
            activeWriter.write(json.encodeToString(request))
            activeWriter.newLine()
            activeWriter.flush()

            val line = activeReader.readLine()
                ?: throw IllegalStateException("Worker closed the connection")
            val response = json.decodeFromString(LibretroWorkerResponse.serializer(), line)
            if (!response.ok) {
                throw IllegalStateException(response.error ?: "Worker command failed: ${request.command}")
            }
            response
        } catch (t: Throwable) {
            isConnected = false
            val exitCode = if (activeProcess.isAlive) null else runCatching { activeProcess.exitValue() }.getOrNull()
            throw IllegalStateException(
                buildString {
                    append("Libretro worker request failed")
                    append(" (command=${request.command}")
                    exitCode?.let { append(", exitCode=$it") }
                    append(")")
                    append(workerLogSuffix())
                },
                t,
            )
        }
    }

    private fun drainWorkerLogs(child: Process) {
        Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { appendWorkerLog(it) }
                }
            } catch (_: java.io.IOException) {
                // Normal during worker shutdown when the redirected stream closes.
            }
        }, "libretro-worker-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendWorkerLog(line: String) {
        workerLog += line
        while (workerLog.size > 80) {
            workerLog.poll()
        }
    }

    private fun workerLogSuffix(): String {
        if (workerLog.isEmpty()) return ""
        return "\nWorker log tail:\n" + workerLog.joinToString("\n")
    }

    private fun javaExecutable(): String {
        val binName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return File(System.getProperty("java.home"), "bin/$binName").absolutePath
    }

    private fun nextRequestId(): String = UUID.randomUUID().toString()
}
