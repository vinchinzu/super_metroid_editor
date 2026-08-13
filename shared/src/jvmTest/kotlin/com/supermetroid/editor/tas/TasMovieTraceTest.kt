package com.supermetroid.editor.tas

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TasMovieTraceTest {

    @Test
    fun `movie with trace round-trips through JSON`() {
        val frames = listOf(
            TasInput.frameOf(TasInput.B, TasInput.RIGHT),
            TasInput.frameOf(TasInput.A),
            TasInput.noop(),
        )
        val trace = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 0x91F8),
            TasTracePoint(frame = 1, x = 110, y = 200, subX = 0x8000, subY = 0x0000, pose = 0x01, roomId = 0x91F8),
            TasTracePoint(frame = 2, x = 120, y = 200, roomId = 0x91F8),
        )
        val movie = TasMovie(
            meta = TasMovieMeta(startState = "landing_site"),
            frames = frames,
            trace = trace,
        )

        val json = movie.toJson(pretty = true)
        val loaded = TasMovie.fromJson(json)

        assertEquals(3, loaded.frameCount)
        assertEquals(3, loaded.trace.size)
        assertEquals(100, loaded.trace[0].x)
        assertEquals(200, loaded.trace[0].y)
        assertEquals(0x91F8, loaded.trace[0].roomId)
        assertEquals(0x8000, loaded.trace[1].subX)
        assertEquals(0x01, loaded.trace[1].pose)
    }

    @Test
    fun `movie without trace round-trips through JSON`() {
        val frames = listOf(
            TasInput.frameOf(TasInput.B),
            TasInput.noop(),
        )
        val movie = TasMovie(frames = frames)

        val json = movie.toJson()
        val loaded = TasMovie.fromJson(json)

        assertEquals(2, loaded.frameCount)
        assertEquals(0, loaded.trace.size)
    }

    @Test
    fun `trace with sparse frame indices`() {
        val frames = List(100) { TasInput.noop() }
        val trace = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 0x91F8),
            TasTracePoint(frame = 50, x = 150, y = 250, roomId = 0x92FD),
            TasTracePoint(frame = 99, x = 200, y = 300, roomId = 0x93FE),
        )
        val movie = TasMovie(frames = frames, trace = trace)

        val json = movie.toJson()
        val loaded = TasMovie.fromJson(json)

        assertEquals(100, loaded.frameCount)
        assertEquals(3, loaded.trace.size)
        assertEquals(0, loaded.trace[0].frame)
        assertEquals(50, loaded.trace[1].frame)
        assertEquals(99, loaded.trace[2].frame)
    }

    @Test
    fun `trace without explicit frame indices`() {
        val trace = listOf(
            TasTracePoint(x = 100, y = 200, roomId = 0x91F8),
            TasTracePoint(x = 110, y = 200, roomId = 0x91F8),
            TasTracePoint(x = 120, y = 200, roomId = 0x91F8),
        )
        val movie = TasMovie(frames = emptyList(), trace = trace)

        val json = movie.toJson()
        val loaded = TasMovie.fromJson(json)

        assertEquals(3, loaded.trace.size)
        assertNull(loaded.trace[0].frame)
        assertNull(loaded.trace[1].frame)
        assertNull(loaded.trace[2].frame)
    }

    @Test
    fun `withTrace creates new movie with trace`() {
        val original = TasMovie(frames = listOf(TasInput.noop()))
        val trace = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 0x91F8),
        )

        val updated = original.withTrace(trace)

        assertEquals(0, original.trace.size)
        assertEquals(1, updated.trace.size)
        assertEquals(100, updated.trace[0].x)
    }

    @Test
    fun `truncated filters trace by frame`() {
        val frames = List(100) { TasInput.noop() }
        val trace = listOf(
            TasTracePoint(frame = 0, x = 100, y = 200, roomId = 0x91F8),
            TasTracePoint(frame = 50, x = 150, y = 250, roomId = 0x92FD),
            TasTracePoint(frame = 99, x = 200, y = 300, roomId = 0x93FE),
        )
        val movie = TasMovie(frames = frames, trace = trace)

        val truncated = movie.truncated(60)

        assertEquals(60, truncated.frameCount)
        assertEquals(2, truncated.trace.size)
        assertEquals(0, truncated.trace[0].frame)
        assertEquals(50, truncated.trace[1].frame)
    }
}
