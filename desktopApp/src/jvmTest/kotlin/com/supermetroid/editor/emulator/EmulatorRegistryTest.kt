package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorRegistryTest {

    @Test
    fun `registry returns libretro backend`() {
        val backend = EmulatorRegistry.create("libretro")
        assertNotNull(backend)
        assertEquals("libretro", backend.name)
        assertTrue(backend is LibretroBackend)
        backend.close()
    }

    @Test
    fun `registry lists available backends`() {
        val backends = EmulatorRegistry.availableBackends()
        assertTrue(backends.contains("libretro"))
    }

    @Test
    fun `unknown backend throws`() {
        assertThrows<IllegalArgumentException> {
            EmulatorRegistry.create("nonexistent")
        }
    }
}
