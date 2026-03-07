package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorRegistryTest {

    @Test
    fun `registry returns bizhawk backend`() {
        val backend = EmulatorRegistry.create("bizhawk")
        assertNotNull(backend)
        assertEquals("bizhawk", backend.name)
        assertTrue(backend is BizHawkBackend)
        backend.close()
    }

    @Test
    fun `registry returns gym-retro backend`() {
        val backend = EmulatorRegistry.create("gym-retro")
        assertNotNull(backend)
        assertEquals("gym-retro", backend.name)
        assertTrue(backend is GymRetroBackend)
        backend.close()
    }

    @Test
    fun `registry lists available backends`() {
        val backends = EmulatorRegistry.availableBackends()
        assertTrue(backends.contains("bizhawk"))
        assertTrue(backends.contains("gym-retro"))
    }

    @Test
    fun `unknown backend throws`() {
        assertThrows<IllegalArgumentException> {
            EmulatorRegistry.create("nonexistent")
        }
    }
}
