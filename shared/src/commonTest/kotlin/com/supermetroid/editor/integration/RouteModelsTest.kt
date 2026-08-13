package com.supermetroid.editor.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RouteModelsTest {

    @Test
    fun testEmptyRoute() {
        val route = TasRoute(name = "test")
        assertEquals("test", route.name)
        assertEquals(0, route.frameCount)
        assertEquals(0, route.inputs.size)
        assertEquals(0, route.positions.size)
    }

    @Test
    fun testAddInput() {
        val route = TasRoute(name = "test")
        val buttons = listOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        val updated = route.withInput(0, buttons)

        assertEquals(1, updated.inputs.size)
        assertEquals(0, updated.inputs[0].frame)
        assertEquals(buttons, updated.inputs[0].buttons)
        assertEquals(1, updated.frameCount)
    }

    @Test
    fun testAddMultipleInputs() {
        var route = TasRoute(name = "test")
        val buttons1 = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val buttons2 = listOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        val buttons3 = listOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)

        route = route.withInput(0, buttons1)
        route = route.withInput(5, buttons2)
        route = route.withInput(10, buttons3)

        assertEquals(3, route.inputs.size)
        assertEquals(11, route.frameCount)
        assertEquals(0, route.inputs[0].frame)
        assertEquals(5, route.inputs[1].frame)
        assertEquals(10, route.inputs[2].frame)
    }

    @Test
    fun testInputsSorted() {
        var route = TasRoute(name = "test")
        val buttons = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        route = route.withInput(10, buttons)
        route = route.withInput(5, buttons)
        route = route.withInput(0, buttons)

        assertEquals(3, route.inputs.size)
        assertEquals(0, route.inputs[0].frame)
        assertEquals(5, route.inputs[1].frame)
        assertEquals(10, route.inputs[2].frame)
    }

    @Test
    fun testReplaceInput() {
        val buttons1 = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val buttons2 = listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        var route = TasRoute(name = "test").withInput(5, buttons1)

        assertEquals(1, route.inputs.size)
        assertEquals(buttons1, route.inputs[0].buttons)

        route = route.withInput(5, buttons2)

        assertEquals(1, route.inputs.size)
        assertEquals(buttons2, route.inputs[0].buttons)
    }

    @Test
    fun testAddPosition() {
        val route = TasRoute(name = "test")
        val updated = route.withPosition(0, 0x91F8, 100, 200)

        assertEquals(1, updated.positions.size)
        assertEquals(0, updated.positions[0].frame)
        assertEquals(0x91F8, updated.positions[0].roomId)
        assertEquals(100, updated.positions[0].x)
        assertEquals(200, updated.positions[0].y)
    }

    @Test
    fun testAddMultiplePositions() {
        var route = TasRoute(name = "test")
        route = route.withPosition(0, 0x91F8, 100, 200)
        route = route.withPosition(5, 0x91F8, 150, 250)
        route = route.withPosition(10, 0x92FD, 50, 100)

        assertEquals(3, route.positions.size)
        assertEquals(11, route.frameCount)
    }

    @Test
    fun testInputAt() {
        var route = TasRoute(name = "test")
        val buttons1 = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val buttons2 = listOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)

        route = route.withInput(0, buttons1)
        route = route.withInput(10, buttons2)

        assertNull(route.inputAt(-1))
        assertEquals(buttons1, route.inputAt(0))
        assertEquals(buttons1, route.inputAt(5))
        assertEquals(buttons1, route.inputAt(9))
        assertEquals(buttons2, route.inputAt(10))
        assertEquals(buttons2, route.inputAt(15))
        assertEquals(buttons2, route.inputAt(100))
    }

    @Test
    fun testPositionAt() {
        var route = TasRoute(name = "test")
        route = route.withPosition(0, 0x91F8, 100, 200)
        route = route.withPosition(10, 0x92FD, 150, 250)

        assertNull(route.positionAt(-1))
        assertNotNull(route.positionAt(0))
        assertEquals(0x91F8, route.positionAt(0)?.roomId)
        assertEquals(0x91F8, route.positionAt(5)?.roomId)
        assertEquals(0x91F8, route.positionAt(9)?.roomId)
        assertEquals(0x92FD, route.positionAt(10)?.roomId)
        assertEquals(0x92FD, route.positionAt(15)?.roomId)
    }

    @Test
    fun testTrimRoute() {
        var route = TasRoute(name = "test")
        val buttons = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        route = route.withInput(0, buttons)
        route = route.withInput(5, buttons)
        route = route.withInput(10, buttons)
        route = route.withInput(15, buttons)
        route = route.withPosition(0, 0x91F8, 100, 200)
        route = route.withPosition(10, 0x92FD, 150, 250)
        route = route.withPosition(20, 0x93FE, 200, 300)

        assertEquals(4, route.inputs.size)
        assertEquals(3, route.positions.size)

        val trimmed = route.trim(10)

        assertEquals(2, trimmed.inputs.size)
        assertEquals(1, trimmed.positions.size)
        assertEquals(10, trimmed.frameCount)
        assertEquals(0, trimmed.inputs[0].frame)
        assertEquals(5, trimmed.inputs[1].frame)
        assertEquals(0, trimmed.positions[0].frame)
    }

    @Test
    fun testRouteMetadata() {
        val metadata = mapOf(
            "author" to "test_user",
            "version" to "1.0",
            "game" to "Super Metroid",
        )
        val route = TasRoute(
            name = "test_route",
            description = "A test route",
            startStateName = "landing_site",
            metadata = metadata,
        )

        assertEquals("test_route", route.name)
        assertEquals("A test route", route.description)
        assertEquals("landing_site", route.startStateName)
        assertEquals("test_user", route.metadata["author"])
        assertEquals("1.0", route.metadata["version"])
    }
}
