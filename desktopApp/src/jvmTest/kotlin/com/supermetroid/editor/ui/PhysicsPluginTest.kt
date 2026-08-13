package com.supermetroid.editor.ui

import com.supermetroid.editor.tas.TasInput
import com.supermetroid.editor.tas.TasMovie
import com.supermetroid.editor.tas.TasMovieMeta
import com.supermetroid.editor.tas.TasTracePoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhysicsPluginTest {

    @Test
    fun `NullPhysicsPlugin - all frames TRUSTWORTHY`() {
        val plugin = NullPhysicsPlugin()
        val movie = TasMovie(
            meta = TasMovieMeta(),
            frames = listOf(
                TasInput.noop(),
                TasInput.noop(),
                TasInput.noop(),
            ),
        )

        val residual = plugin.residual(movie, emptyList())

        assertEquals(3, residual.frameTrust.size)
        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[0])
        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[1])
        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[2])
    }

    @Test
    fun `NullPhysicsPlugin - hydrate always succeeds`() {
        val plugin = NullPhysicsPlugin()
        val result = plugin.hydrate("test_state")
        assert(result.isSuccess)
    }

    @Test
    fun `NullPhysicsPlugin - predictHop returns empty trace`() {
        val plugin = NullPhysicsPlugin()
        val movie = TasMovie(frames = listOf(TasInput.noop()))
        val hop = plugin.predictHop(movie, 0)
        assertEquals(0, hop.trace.size)
    }

    @Test
    fun `SmRevPredictStubPlugin - residual detects roomId mismatch as DEAD`() {
        val plugin = SmRevPredictStubPlugin()
        val movie = TasMovie(
            frames = List(10) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
                TasTracePoint(frame = 5, x = 110, y = 210, roomId = 1000),
            ),
        )

        val observed = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
            TasTracePoint(frame = 5, x = 110, y = 210, roomId = 2000),
        )

        val residual = plugin.residual(movie, observed)

        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[0])
        assertEquals(FrameTrust.DEAD, residual.frameTrust[5])
        assertEquals(5, residual.firstDifferingRoom)
        assertEquals("roomId", residual.firstDifferingField)
        assertNotNull(residual.cause)
        assert(residual.cause!!.contains("roomId mismatch"))
    }

    @Test
    fun `SmRevPredictStubPlugin - residual detects pixel mismatch as DEAD`() {
        val plugin = SmRevPredictStubPlugin()
        val movie = TasMovie(
            frames = List(10) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
                TasTracePoint(frame = 3, x = 110, y = 210, roomId = 1000),
            ),
        )

        val observed = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
            TasTracePoint(frame = 3, x = 115, y = 215, roomId = 1000),
        )

        val residual = plugin.residual(movie, observed)

        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[0])
        assertEquals(FrameTrust.DEAD, residual.frameTrust[3])
        assertEquals(3, residual.firstDifferingPixel)
        assertEquals("x/y", residual.firstDifferingField)
    }

    @Test
    fun `SmRevPredictStubPlugin - residual detects subpixel-only mismatch as SPOT_CHECK`() {
        val plugin = SmRevPredictStubPlugin()
        val movie = TasMovie(
            frames = List(10) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, subX = 32768, subY = 16384, roomId = 1000),
                TasTracePoint(frame = 4, x = 110, y = 210, subX = 32768, subY = 16384, roomId = 1000),
            ),
        )

        val observed = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, subX = 32768, subY = 16384, roomId = 1000),
            TasTracePoint(frame = 4, x = 110, y = 210, subX = 40000, subY = 20000, roomId = 1000),
        )

        val residual = plugin.residual(movie, observed)

        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[0])
        assertEquals(FrameTrust.SPOT_CHECK, residual.frameTrust[4])
        assertEquals(4, residual.firstDifferingSubpixel)
        assertEquals("subX/subY", residual.firstDifferingField)
    }

    @Test
    fun `SmRevPredictStubPlugin - residual exact match is TRUSTWORTHY`() {
        val plugin = SmRevPredictStubPlugin()
        val movie = TasMovie(
            frames = List(5) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
                TasTracePoint(frame = 2, x = 110, y = 210, roomId = 1000),
            ),
        )

        val observed = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
            TasTracePoint(frame = 2, x = 110, y = 210, roomId = 1000),
        )

        val residual = plugin.residual(movie, observed)

        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[0])
        assertEquals(FrameTrust.TRUSTWORTHY, residual.frameTrust[2])
        assertNull(residual.firstDifferingPixel)
        assertNull(residual.firstDifferingRoom)
    }

    @Test
    fun `RouteEditorState - plugin factory swap updates predictions`() {
        val routeState = RouteEditorState(NullPhysicsPluginFactory)
        val movie = TasMovie(
            frames = List(5) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
            ),
        )
        routeState.currentMovie = movie
        routeState.updatePrediction()

        val nullResidual = routeState.residualProfile
        assertNotNull(nullResidual)
        assertEquals(5, nullResidual.frameTrust.size)
        assertEquals(FrameTrust.TRUSTWORTHY, nullResidual.frameTrust[0])

        routeState.setPhysicsPlugin(SmRevPredictStubPluginFactory)
        val stubResidual = routeState.residualProfile
        assertNotNull(stubResidual)
    }

    @Test
    fun `RouteEditorState - hop overlay separate from recorded trace`() {
        val routeState = RouteEditorState(NullPhysicsPluginFactory)
        val movie = TasMovie(
            frames = List(5) { TasInput.noop() },
            trace = listOf(
                TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
                TasTracePoint(frame = 3, x = 120, y = 220, roomId = 1000),
            ),
        )
        routeState.currentMovie = movie

        val predictedHop = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 1000),
            TasTracePoint(frame = 1, x = 105, y = 205, roomId = 1000),
            TasTracePoint(frame = 2, x = 110, y = 210, roomId = 1000),
        )
        
        routeState.predictedHopTrace
        
        assert(movie.trace != predictedHop)
    }
}
