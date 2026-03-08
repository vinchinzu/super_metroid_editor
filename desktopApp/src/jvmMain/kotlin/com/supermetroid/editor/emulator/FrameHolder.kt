package com.supermetroid.editor.emulator

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Direct frame passing for in-process emulator backends.
 * Bypasses Base64 encoding — LibretroBackend writes frames here,
 * EmulatorWorkspaceState reads them in applySnapshot().
 */
class FrameHolder {
    @Volatile
    var latestFrame: ImageBitmap? = null

    @Volatile
    var frameVersion: Long = 0L

    fun pushFrame(frame: ImageBitmap) {
        latestFrame = frame
        frameVersion++
    }

    fun clear() {
        latestFrame = null
        frameVersion = 0L
    }
}
