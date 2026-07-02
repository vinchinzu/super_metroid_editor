package com.supermetroid.editor.emulator

import java.io.File

object RomHashResolver {
    fun findRomForHash(expectedHash: String): File? {
        val candidates = buildList {
            System.getenv("SMEDIT_ROM_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(6) {
                add(File(dir, "custom_integrations/SuperMetroid-Snes/rom-v1.0.sfc"))
                add(File(dir, "custom_integrations/SuperMetroid-Snes/rom.sfc"))
                add(File(dir, "test-resources/Super Metroid (JU) [!].smc"))
                dir = dir.parentFile ?: return@repeat
            }
        }
        return candidates.distinct().firstOrNull { file ->
            file.isFile && Sha256.hex(file.readBytes()) == expectedHash
        }
    }

    fun resolveRom(expectedHash: String): File? =
        findRomForHash(expectedHash)
            ?: System.getenv("SMEDIT_ROM_PATH")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { File(it).absoluteFile }
                ?.takeIf { it.isFile }
}
