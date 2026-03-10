package com.supermetroid.editor.emulator

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Audio playback for libretro SNES cores via javax.sound.sampled.
 * Format: 16-bit signed stereo PCM at SNES native sample rate (32040 Hz).
 *
 * The audio buffer acts as the natural frame pacer: when the buffer has
 * adequate headroom, writes return immediately. When full, we drop samples
 * rather than block, preventing the emulation loop from stalling.
 */
class LibretroAudioOutput {

    private var line: SourceDataLine? = null
    private var writeBuffer = ByteArray(0)
    @Volatile
    var muted: Boolean = false

    fun start() {
        val format = AudioFormat(
            SAMPLE_RATE,
            16,       // sample size in bits
            2,        // channels (stereo)
            true,     // signed
            false,    // little-endian (native SNES)
        )
        // ~100ms buffer — enough to absorb timing jitter without noticeable latency
        val bufferBytes = (SAMPLE_RATE * 2 * 2 * BUFFER_SECONDS).toInt()
        val sdl = AudioSystem.getSourceDataLine(format)
        sdl.open(format, bufferBytes)
        sdl.start()
        line = sdl
    }

    /**
     * Write interleaved stereo int16 samples to the audio output.
     * Drops samples if the buffer is nearly full to avoid blocking the emulation loop.
     */
    fun writeSamples(samples: ShortArray) {
        if (muted || samples.isEmpty()) return
        val sdl = line ?: return

        val bytesNeeded = samples.size * 2
        val available = sdl.available()

        // If less than half the buffer is available, drop these samples
        // to avoid blocking the emulation thread
        if (available < bytesNeeded) return

        val bytes = ensureWriteBuffer(bytesNeeded)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        sdl.write(bytes, 0, bytesNeeded)
    }

    /**
     * Returns true if the audio buffer has room for more samples,
     * meaning the emulator is running ahead of real-time playback.
     * Used by the frame loop to decide whether to yield time.
     */
    fun hasHeadroom(): Boolean {
        val sdl = line ?: return true
        // Buffer is "ahead" if more than 25% is available
        return sdl.available() > sdl.bufferSize / 4
    }

    fun close() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
    }

    private fun ensureWriteBuffer(size: Int): ByteArray {
        if (writeBuffer.size < size) {
            writeBuffer = ByteArray(size)
        }
        return writeBuffer
    }

    companion object {
        const val SAMPLE_RATE = 32040f
        // Buffer length in seconds. 100ms balances latency vs. jitter absorption.
        private const val BUFFER_SECONDS = 0.10f
    }
}
