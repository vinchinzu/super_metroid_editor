package com.supermetroid.editor.emulator

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class LibretroProcessBackendSmokeTest {

    @Test
    fun `worker backend can connect and start a local rom session`() = runBlocking {
        val rom = localRom() ?: run {
            assumeTrue(false, "Local Super Metroid ROM not present")
            return@runBlocking
        }

        val backend = LibretroProcessBackend(audioEnabledOverride = false)
        try {
            val caps = backend.connect()
            assertTrue(caps.supportsFrames)

            val result = backend.startSession(
                SessionConfig(
                    romPath = rom.absolutePath,
                    stateName = "ZebesStart",
                )
            )

            assertTrue(result.session.active)
            assertTrue((result.snapshot.roomId ?: 0) != 0)
        } finally {
            runCatching { backend.close() }
        }
    }

    private fun localRom(): File? {
        val candidates = listOf(
            File("custom_integrations/SuperMetroid-Snes/rom.sfc"),
            File("../custom_integrations/SuperMetroid-Snes/rom.sfc"),
        )
        return candidates.firstOrNull { it.isFile }?.absoluteFile
    }
}
