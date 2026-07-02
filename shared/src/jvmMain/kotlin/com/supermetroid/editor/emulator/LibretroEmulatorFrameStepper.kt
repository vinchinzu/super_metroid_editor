package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCore

/**
 * Thin instrumentation boundary around a loaded libretro core.
 *
 * Callers own the core lifecycle. Every method must be called on the same
 * thread used for the underlying [LibretroCore].
 */
class LibretroEmulatorFrameStepper(
    private val core: LibretroCore,
) : FrameStepper {
    private var nextFrameNumber: Long = 0

    override val emulatorCore: String
        get() {
            val info = core.getSystemInfo()
            return listOfNotNull(info.getLibraryName(), info.getLibraryVersion())
                .joinToString(separator = " ")
                .ifBlank { "unknown-libretro-core" }
        }

    override fun resetFrameCounter(firstFrameNumber: Long) {
        require(firstFrameNumber >= 0) { "firstFrameNumber must be non-negative" }
        nextFrameNumber = firstFrameNumber
    }

    override fun advanceFrame(inputBits: Int): SuperMetroidFrameState {
        val normalizedInput = SnesInputBits.normalize(inputBits)
        core.setInput(0, SnesInputBits.toButtonList(normalizedInput))
        core.run()
        nextFrameNumber++
        val wram = readSystemRam(0, core.systemRamSize())
        return SuperMetroidWram.frameState(wram)
    }

    override fun runOneFrame(inputBits: Int): FrameRecord {
        val normalizedInput = SnesInputBits.normalize(inputBits)
        val frameNumber = nextFrameNumber
        core.setInput(0, SnesInputBits.toButtonList(normalizedInput))
        core.run()
        nextFrameNumber++
        val wram = readSystemRam(0, core.systemRamSize())
        return FrameRecord(
            frameNumber = frameNumber,
            inputBits = normalizedInput,
            systemRamHash = Sha256.hex(wram),
            frameState = SuperMetroidWram.frameState(wram),
        )
    }

    override fun saveState(): ByteArray = core.serializeState()

    override fun loadState(state: ByteArray) {
        core.unserializeState(state)
    }

    override fun readSystemRam(offset: Int, length: Int): ByteArray = core.readWram(offset, length)
}
