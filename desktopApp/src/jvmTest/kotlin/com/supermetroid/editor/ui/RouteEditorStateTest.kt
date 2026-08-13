package com.supermetroid.editor.ui

import com.supermetroid.editor.tas.TasInput
import com.supermetroid.editor.tas.TasMovie
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RouteEditorStateTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `startRecording initializes new movie`() = runTest {
        val state = RouteEditorState()
        state.routeDirectory = tempDir.absolutePath

        state.startRecording(stateName = "test_state", startFrame = 100)

        assertEquals(RoutePlaybackState.RECORDING, state.playbackState)
        assertNotNull(state.currentMovie)
        assertEquals("Recording movie from frame 100", state.statusMessage)
    }

    @Test
    fun `recordFrame captures inputs and trace points`() = runTest {
        val state = RouteEditorState()
        state.routeDirectory = tempDir.absolutePath

        state.startRecording(stateName = null, startFrame = 0)
        state.recordFrame(
            frame = 5,
            buttons = listOf(1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0),
            roomId = 42,
            x = 100,
            y = 200,
        )
        state.stopRecording()

        val movie = state.currentMovie
        assertNotNull(movie)
        assertEquals(6, movie.frameCount)
        assertEquals(1, movie.trace.size)
        val trace = movie.trace.first()
        assertEquals(5, trace.frame)
        assertEquals(100, trace.x)
        assertEquals(200, trace.y)
        assertEquals(42, trace.roomId)
    }

    @Test
    fun `save and load movie round trip`() = runTest {
        val state = RouteEditorState()
        state.routeDirectory = tempDir.absolutePath

        state.startRecording(stateName = "initial", startFrame = 0)
        state.recordFrame(1, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 10, 50, 60)
        state.stopRecording()

        state.saveMovie("test_movie")
        state.clearMovie()
        assertNull(state.currentMovie)

        state.loadMovie("test_movie")
        val loaded = state.currentMovie
        assertNotNull(loaded)
        assertEquals(2, loaded.frameCount)
        assertEquals("initial", loaded.meta.startState)
        assertEquals(1, loaded.trace.size)
    }

    @Test
    fun `playback and scrubbing controls`() = runTest {
        val state = RouteEditorState()
        val movie = TasMovie(
            frames = listOf(
                TasInput.noop(),
                intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                TasInput.noop(),
            ),
        )
        state.currentMovie = movie

        state.startPlayback()
        assertEquals(RoutePlaybackState.PLAYING, state.playbackState)
        assertEquals(0, state.currentFrame)

        state.advancePlaybackFrame()
        assertEquals(1, state.currentFrame)

        state.pausePlayback()
        assertEquals(RoutePlaybackState.PAUSED, state.playbackState)

        state.resumePlayback()
        assertEquals(RoutePlaybackState.PLAYING, state.playbackState)

        state.stopPlayback()
        assertEquals(RoutePlaybackState.IDLE, state.playbackState)
        assertEquals(0, state.currentFrame)
    }

    @Test
    fun `getCurrentInput returns correct frame input`() = runTest {
        val state = RouteEditorState()
        val movie = TasMovie(
            frames = listOf(
                TasInput.noop(),
                intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0),
            ),
        )
        state.currentMovie = movie

        state.seekToFrame(1)
        val input = state.getCurrentInput()
        assertNotNull(input)
        assertEquals(1, input[0])
        assertEquals(1, input[4])
    }

    @Test
    fun `truncateMovie removes frames after cutoff`() = runTest {
        val state = RouteEditorState()
        val movie = TasMovie(
            frames = List(10) { TasInput.noop() },
        )
        state.currentMovie = movie

        state.truncateMovie(5)
        assertEquals(5, state.currentMovie?.frameCount)
    }
}
