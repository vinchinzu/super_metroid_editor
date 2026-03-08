package com.supermetroid.editor.emulator

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Audio playback for libretro SNES cores via javax.sound.sampled.
 * Format: 16-bit signed stereo PCM at SNES native sample rate (32040 Hz).
 */
class LibretroAudioOutput {

    private var line: SourceDataLine? = null
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
        val bufferBytes = (SAMPLE_RATE * 2 * 2 * BUFFER_SECONDS).toInt() // ~100ms buffer
        val sdl = AudioSystem.getSourceDataLine(format)
        sdl.open(format, bufferBytes)
        sdl.start()
        line = sdl
    }

    /**
     * Write interleaved stereo int16 samples to the audio output.
     * Non-blocking within buffer headroom; blocks only when buffer is full.
     */
    fun writeSamples(samples: ShortArray) {
        if (muted || samples.isEmpty()) return
        val sdl = line ?: return

        // Convert ShortArray to byte array (little-endian)
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        sdl.write(bytes, 0, bytes.size)
    }

    fun close() {
        line?.let {
            it.stop()
            it.close()
        }
        line = null
    }

    companion object {
        const val SAMPLE_RATE = 32040f
        private const val BUFFER_SECONDS = 0.1f
    }
}
