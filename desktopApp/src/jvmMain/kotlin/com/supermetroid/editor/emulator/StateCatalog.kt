package com.supermetroid.editor.emulator

import java.io.File

internal object StateCatalog {
    private const val GAME_STATE_DIR = "custom_integrations/SuperMetroid-Snes"

    fun userStateDir(home: File = File(System.getProperty("user.home")).absoluteFile): File {
        return File(home, ".smedit/states/libretro")
    }

    fun searchDirs(
        romPath: String?,
        cwd: File = File(System.getProperty("user.dir")).absoluteFile,
        home: File = File(System.getProperty("user.home")).absoluteFile,
    ): List<File> {
        val dirs = mutableListOf<File>()

        fun addIfPresent(dir: File?) {
            val candidate = dir?.absoluteFile ?: return
            if (!candidate.isDirectory) return
            if (dirs.none { it.absolutePath == candidate.absolutePath }) {
                dirs += candidate
            }
        }

        addIfPresent(romPath?.let { File(it).absoluteFile.parentFile })
        addIfPresent(File(cwd, GAME_STATE_DIR))
        addIfPresent(File(cwd, "../$GAME_STATE_DIR"))
        addIfPresent(userStateDir(home))

        return dirs
    }

    fun listStates(
        romPath: String?,
        cwd: File = File(System.getProperty("user.dir")).absoluteFile,
        home: File = File(System.getProperty("user.home")).absoluteFile,
    ): List<StateInfo> {
        val statesByName = linkedMapOf<String, StateInfo>()

        for (dir in searchDirs(romPath = romPath, cwd = cwd, home = home)) {
            val files = dir.listFiles { file ->
                file.isFile && file.extension.equals("state", ignoreCase = true)
            } ?: continue
            for (file in files.sortedBy { it.name.lowercase() }) {
                statesByName.putIfAbsent(
                    file.nameWithoutExtension,
                    StateInfo(name = file.nameWithoutExtension, path = file.absolutePath),
                )
            }
        }

        return statesByName.values.sortedBy { it.name.lowercase() }
    }

    fun readStateBytes(
        name: String,
        romPath: String?,
        cwd: File = File(System.getProperty("user.dir")).absoluteFile,
        home: File = File(System.getProperty("user.home")).absoluteFile,
    ): ByteArray? {
        for (dir in searchDirs(romPath = romPath, cwd = cwd, home = home)) {
            val stateFile = File(dir, "$name.state")
            if (stateFile.isFile) {
                return stateFile.readBytes()
            }
        }
        return null
    }
}
