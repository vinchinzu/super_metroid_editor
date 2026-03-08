package com.supermetroid.editor.emulator

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.supermetroid.editor.data.AppConfig
import com.supermetroid.editor.libretro.LibretroCore
import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors

/**
 * EmulatorBackend implementation using an in-process libretro SNES core.
 * Video frames pass directly via [frameHolder] — no Base64, no TCP.
 * All libretro calls run on a single dedicated thread.
 */
class LibretroBackend : EmulatorBackend {

    override val name: String = "libretro"
    override var isConnected: Boolean = false
        private set

    val frameHolder = FrameHolder()

    var audioMuted: Boolean
        get() = audio?.muted ?: false
        set(value) { audio?.muted = value }

    private var core: LibretroCore? = null
    private var audio: LibretroAudioOutput? = null
    private val emuThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "libretro-emu").apply { isDaemon = true }
    }

    private var sessionActive = false
    private var frameCounter = 0
    private var currentRomPath: String? = null

    private val stateDir = File(System.getProperty("user.home"), ".smedit/states/libretro").apply { mkdirs() }

    // ── EmulatorBackend interface ──────────────────────────────────────────

    override suspend fun connect(): EmulatorCapabilities {
        val settings = AppConfig.load()
        val corePath = LibretroCoreDiscovery.findCore(settings.libretroCorePath)
            ?: throw IllegalStateException(
                "No SNES libretro core found. Install one:\n" +
                    "  sudo pacman -S libretro-snes9x\n" +
                    "Or set SMEDIT_LIBRETRO_CORE=/path/to/snes9x_libretro.so"
            )

        val c = LibretroCore(corePath)
        onEmuThread { c.init() }
        core = c
        isConnected = true

        val audioEnabled = settings.libretroAudioEnabled
        if (audioEnabled) {
            val a = LibretroAudioOutput()
            a.start()
            audio = a
        }

        val sysInfo = onEmuThread { c.getSystemInfo() }
        return EmulatorCapabilities(
            backendName = "libretro (${sysInfo.getLibraryName() ?: "unknown"})",
            supportsFrames = true,
            supportsMemoryAccess = true,
            supportsSaveStates = true,
        )
    }

    override suspend fun disconnect() {
        sessionActive = false
        onEmuThread { core?.close() }
        core = null
        audio?.close()
        audio = null
        frameHolder.clear()
        isConnected = false
    }

    override suspend fun startSession(config: SessionConfig): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        val romPath = config.romPath
            ?: throw IllegalArgumentException("romPath is required for libretro backend")

        val loaded = onEmuThread { c.loadGame(romPath) }
        if (!loaded) throw IllegalStateException("Failed to load ROM: $romPath")

        currentRomPath = romPath
        frameCounter = 0
        sessionActive = true

        // Load initial state if specified
        if (config.stateName != null) {
            val stateFile = File(stateDir, "${config.stateName}.state")
            if (stateFile.isFile) {
                val data = withContext(Dispatchers.IO) { stateFile.readBytes() }
                onEmuThread { c.unserializeState(data) }
            }
        }

        // Run one frame to populate video/audio
        onEmuThread { c.run() }
        frameCounter++
        pushFrame(c)

        return buildStepResult("Session started")
    }

    override suspend fun closeSession(): StepResult {
        sessionActive = false
        frameHolder.clear()
        return StepResult(
            session = SessionState(active = false, paused = true),
            message = "Session closed",
        )
    }

    override suspend fun step(input: EmulatorInput): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        if (!sessionActive) throw IllegalStateException("No active session")

        onEmuThread {
            c.setInput(0, input.buttons)
            for (i in 0 until input.repeat) {
                c.run()
                frameCounter++
            }
        }

        // Push frame to FrameHolder
        if (input.includeFrame) {
            pushFrame(c)
        }

        // Drain audio
        val audioSamples = onEmuThread { c.drainAudio() }
        audio?.writeSamples(audioSamples)

        return buildStepResult()
    }

    override suspend fun snapshot(): GameSnapshot {
        val c = core ?: throw IllegalStateException("Not connected")
        return buildSnapshot(c)
    }

    override suspend fun saveState(name: String) {
        val c = core ?: throw IllegalStateException("Not connected")
        val data = onEmuThread { c.serializeState() }
        withContext(Dispatchers.IO) {
            stateDir.mkdirs()
            File(stateDir, "$name.state").writeBytes(data)
        }
    }

    override suspend fun loadState(name: String): StepResult {
        val c = core ?: throw IllegalStateException("Not connected")
        val stateFile = File(stateDir, "$name.state")
        if (!stateFile.isFile) throw IllegalStateException("State not found: $name")

        val data = withContext(Dispatchers.IO) { stateFile.readBytes() }
        onEmuThread { c.unserializeState(data) }

        // Run one frame to refresh video
        onEmuThread { c.run() }
        frameCounter++
        pushFrame(c)

        return buildStepResult("Loaded $name")
    }

    override suspend fun listStates(): List<StateInfo> {
        return withContext(Dispatchers.IO) {
            stateDir.listFiles { f -> f.extension == "state" }
                ?.map { StateInfo(name = it.nameWithoutExtension, path = it.absolutePath) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }
    }

    override suspend fun readMemory(address: Int, size: Int): ByteArray {
        val c = core ?: throw IllegalStateException("Not connected")
        return onEmuThread { c.readWram(address, size) }
    }

    override suspend fun writeMemory(address: Int, data: ByteArray) {
        val c = core ?: throw IllegalStateException("Not connected")
        onEmuThread { c.writeWram(address, data) }
    }

    override fun close() {
        sessionActive = false
        core?.let { c ->
            try { emuThread.submit { c.close() }.get() } catch (_: Exception) {}
        }
        core = null
        audio?.close()
        audio = null
        frameHolder.clear()
        isConnected = false
        emuThread.shutdown()
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun pushFrame(c: LibretroCore) {
        val pixels = c.getFrameBuffer()
        val w = c.getFrameWidth()
        val h = c.getFrameHeight()
        if (w <= 0 || h <= 0 || pixels.isEmpty()) return

        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        frameHolder.pushFrame(image.toComposeImageBitmap())
    }

    private fun buildSnapshot(c: LibretroCore): GameSnapshot {
        val wram = try {
            onEmuThreadBlocking { c.readWram(0, 0x2000) }
        } catch (_: Exception) {
            null
        }

        return GameSnapshot(
            frameCounter = frameCounter,
            roomId = wram?.readWord(0x079B),
            gameState = wram?.readWord(0x0998),
            health = wram?.readWord(0x09C2),
            samusX = wram?.readWord(0x0AF6),
            samusY = wram?.readWord(0x0AFA),
            doorTransition = wram?.readWord(0x0998)?.let { it in 0x06..0x0B } ?: false,
            frameWidth = c.getFrameWidth(),
            frameHeight = c.getFrameHeight(),
        )
    }

    private fun buildStepResult(message: String? = null): StepResult {
        val c = core ?: return StepResult(message = message)
        return StepResult(
            session = SessionState(
                active = sessionActive,
                paused = !sessionActive,
                frameCounter = frameCounter,
            ),
            snapshot = buildSnapshot(c),
            message = message,
        )
    }

    private fun <T> onEmuThreadBlocking(action: () -> T): T {
        return emuThread.submit<T> { action() }.get()
    }

    private suspend fun <T> onEmuThread(action: () -> T): T {
        return withContext(Dispatchers.IO) {
            emuThread.submit<T> { action() }.get()
        }
    }

    companion object {
        // Read a little-endian 16-bit word from a byte array
        private fun ByteArray.readWord(offset: Int): Int {
            if (offset + 1 >= size) return 0
            return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
        }
    }
}
