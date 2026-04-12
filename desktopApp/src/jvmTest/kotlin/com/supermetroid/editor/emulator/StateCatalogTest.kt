package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class StateCatalogTest {

    @Test
    fun `searchDirs prefers rom local states before project and user states`() {
        val root = Files.createTempDirectory("smedit-states").toFile()
        val cwd = root.resolve("super_metroid_editor").apply { mkdirs() }
        val home = root.resolve("home").apply { mkdirs() }
        val romDir = root.resolve("runtime/custom_integrations/SuperMetroid-Snes").apply { mkdirs() }
        cwd.resolve("custom_integrations/SuperMetroid-Snes").mkdirs()
        home.resolve(".smedit/states/libretro").mkdirs()

        val dirs = StateCatalog.searchDirs(
            romPath = romDir.resolve("rom.sfc").absolutePath,
            cwd = cwd,
            home = home,
        )

        assertEquals(3, dirs.size)
        assertEquals(romDir.absolutePath, dirs[0].absolutePath)
        assertEquals(cwd.resolve("custom_integrations/SuperMetroid-Snes").absolutePath, dirs[1].absolutePath)
        assertEquals(home.resolve(".smedit/states/libretro").absolutePath, dirs[2].absolutePath)
    }

    @Test
    fun `listStates deduplicates by first matching state directory`() {
        val root = Files.createTempDirectory("smedit-catalog").toFile()
        val cwd = root.resolve("super_metroid_editor").apply { mkdirs() }
        val home = root.resolve("home").apply { mkdirs() }
        val romDir = root.resolve("runtime/custom_integrations/SuperMetroid-Snes").apply { mkdirs() }
        val projectDir = cwd.resolve("custom_integrations/SuperMetroid-Snes").apply { mkdirs() }
        val userDir = home.resolve(".smedit/states/libretro").apply { mkdirs() }

        romDir.resolve("ZebesStart.state").writeText("rom", Charsets.UTF_8)
        projectDir.resolve("Start.state").writeText("project", Charsets.UTF_8)
        userDir.resolve("ZebesStart.state").writeText("user", Charsets.UTF_8)
        userDir.resolve("Checkpoint.state").writeText("checkpoint", Charsets.UTF_8)

        val states = StateCatalog.listStates(
            romPath = romDir.resolve("rom.sfc").absolutePath,
            cwd = cwd,
            home = home,
        )

        assertEquals(listOf("Checkpoint", "Start", "ZebesStart"), states.map { it.name })
        assertEquals(romDir.resolve("ZebesStart.state").absolutePath, states.last().path)
    }

    @Test
    fun `readStateBytes falls back from rom local to project and user states`() {
        val root = Files.createTempDirectory("smedit-bytes").toFile()
        val cwd = root.resolve("super_metroid_editor").apply { mkdirs() }
        val home = root.resolve("home").apply { mkdirs() }
        val projectDir = cwd.resolve("custom_integrations/SuperMetroid-Snes").apply { mkdirs() }
        val userDir = home.resolve(".smedit/states/libretro").apply { mkdirs() }

        val projectBytes = byteArrayOf(1, 2, 3)
        val userBytes = byteArrayOf(7, 8, 9)
        projectDir.resolve("ZebesStart.state").writeBytes(projectBytes)
        userDir.resolve("Checkpoint.state").writeBytes(userBytes)

        assertArrayEquals(
            projectBytes,
            StateCatalog.readStateBytes(
                name = "ZebesStart",
                romPath = null,
                cwd = cwd,
                home = home,
            ),
        )
        assertArrayEquals(
            userBytes,
            StateCatalog.readStateBytes(
                name = "Checkpoint",
                romPath = null,
                cwd = cwd,
                home = home,
            ),
        )
        assertTrue(
            StateCatalog.readStateBytes(
                name = "Missing",
                romPath = null,
                cwd = cwd,
                home = home,
            ) == null
        )
    }
}
