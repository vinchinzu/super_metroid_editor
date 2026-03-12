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
    @Volatile
    var volume: Float = 1.0f

    fun start() {
        val format = AudioFormat(
            SAMPLE_RATE,
            16,       // sample size in bits
            2,        // channels (stereo)
            true,     // signed
            false,    // little-endian (native SNES)
        )
        // 200ms buffer — absorbs GC pauses and timing jitter without noticeable latency
        val bufferBytes = (SAMPLE_RATE * FRAME_SIZE * BUFFER_SECONDS).toInt()
        val sdl = AudioSystem.getSourceDataLine(format)
        sdl.open(format, bufferBytes)
        sdl.start()
        line = sdl
    }

    /**
     * Write interleaved stereo int16 samples to the audio output.
     * Performs partial writes when the buffer can't fit all samples,
     * keeping stereo pairs aligned. Only drops samples when there is
     * truly no room at all, avoiding the crackling caused by discarding
     * entire batches.
     */
    fun writeSamples(samples: ShortArray) {
        if (muted || samples.isEmpty()) return
        val sdl = line ?: return

        val available = sdl.available()
        if (available < FRAME_SIZE) return // truly no room

        // Write as many complete stereo frames as will fit
        val maxSamples = minOf(samples.size, (available / 2) and STEREO_ALIGN_MASK)
        if (maxSamples <= 0) return

        val bytesNeeded = maxSamples * 2
        val bytes = ensureWriteBuffer(bytesNeeded)
        val vol = volume.coerceIn(0f, 1f)
        // Use fixed-point integer math to avoid float rounding artifacts.
        // Scale 0.0-1.0 to 0-256 so we can shift right by 8 instead of float multiply.
        val volInt = (vol * 256).toInt()
        val fullVolume = volInt >= 256
        for (i in 0 until maxSamples) {
            val s = if (fullVolume) samples[i].toInt()
                    else (samples[i].toInt() * volInt) shr 8
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
        // Buffer length in seconds. 200ms gives enough headroom for JVM GC pauses.
        private const val BUFFER_SECONDS = 0.20f
        // Bytes per stereo sample pair (2 channels × 2 bytes per 16-bit sample)
        private const val FRAME_SIZE = 4
        // Mask to keep sample counts aligned to stereo pairs (even count)
        private const val STEREO_ALIGN_MASK = -2 // 0xFFFFFFFE
    }
}
