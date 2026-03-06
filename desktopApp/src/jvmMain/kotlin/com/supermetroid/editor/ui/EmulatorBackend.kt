package com.supermetroid.editor.ui

import com.supermetroid.editor.integration.BridgeResponse
import java.io.Closeable
import java.io.File

interface EmulatorBackend : Closeable {
    fun request(
        command: String,
        state: String? = null,
        saveName: String? = null,
        navExportDir: String? = null,
        controlMode: String? = null,
        selectedModel: String? = null,
        action: List<Int> = emptyList(),
        repeat: Int = 1,
        includeFrame: Boolean = true,
        includeTrace: Boolean = true,
    ): BridgeResponse
}

interface EmulatorBackendFactory {
    fun connect(startDir: File = File(System.getProperty("user.dir"))): EmulatorBackend
}

object DefaultEmulatorBackendFactory : EmulatorBackendFactory {
    override fun connect(startDir: File): EmulatorBackend = EmulatorBridgeClient.connect(startDir)
}
