package com.supermetroid.editor.ui

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RouteEditorStateTest {

    @Test
    fun `initial state is idle`() {
        val state = RouteEditorState()
        assertEquals(RoutePlaybackState.IDLE, state.playbackState)
        assertNull(state.currentRoute)
        assertEquals(0, state.currentFrame)
    }

    @Test
    fun `start recording creates new route`() {
        val state = RouteEditorState()
        state.startRecording("test_state", 100)

        assertEquals(RoutePlaybackState.RECORDING, state.playbackState)
        assertNotNull(state.currentRoute)
        assertEquals("test_state", state.currentRoute?.startStateName)
        assertEquals(0, state.currentFrame)
    }

    @Test
    fun `stop recording changes state to idle`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.stopRecording()

        assertEquals(RoutePlaybackState.IDLE, state.playbackState)
        assertNotNull(state.currentRoute)
    }

    @Test
    fun `record frame adds input and position`() {
        val state = RouteEditorState()
        state.startRecording("test_state", 0)

        val buttons = listOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        state.recordFrame(0, buttons, 0x91F8, 100, 200)

        val route = state.currentRoute
        assertNotNull(route)
        assertEquals(1, route.inputs.size)
        assertEquals(1, route.positions.size)
        assertEquals(buttons, route.inputs[0].buttons)
        assertEquals(0x91F8, route.positions[0].roomId)
    }

    @Test
    fun `record frame handles relative frames`() {
        val state = RouteEditorState()
        state.startRecording("test_state", 100)

        val buttons = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        state.recordFrame(105, buttons, 0x91F8, 100, 200)

        val route = state.currentRoute
        assertNotNull(route)
        assertEquals(1, route.inputs.size)
        assertEquals(5, route.inputs[0].frame)
    }

    @Test
    fun `start playback sets state to playing`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        val buttons = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        state.recordFrame(0, buttons, null, null, null)
        state.stopRecording()

        state.startPlayback()

        assertEquals(RoutePlaybackState.PLAYING, state.playbackState)
        assertEquals(0, state.currentFrame)
    }

    @Test
    fun `pause playback changes state to paused`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()
        state.startPlayback()

        state.pausePlayback()

        assertEquals(RoutePlaybackState.PAUSED, state.playbackState)
    }

    @Test
    fun `resume playback changes state to playing`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()
        state.startPlayback()
        state.pausePlayback()

        state.resumePlayback()

        assertEquals(RoutePlaybackState.PLAYING, state.playbackState)
    }

    @Test
    fun `step forward increments frame`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(5, listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()

        state.stepForward()

        assertEquals(1, state.currentFrame)
    }

    @Test
    fun `step backward decrements frame`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(5, listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()
        state.currentFrame = 5

        state.stepBackward()

        assertEquals(4, state.currentFrame)
    }

    @Test
    fun `step backward does not go below zero`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()

        state.stepBackward()

        assertEquals(0, state.currentFrame)
    }

    @Test
    fun `seek to frame sets current frame`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(10, listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()

        state.seekToFrame(7)

        assertEquals(7, state.currentFrame)
    }

    @Test
    fun `get current input returns correct input`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        val buttons1 = listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val buttons2 = listOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        state.recordFrame(0, buttons1, null, null, null)
        state.recordFrame(10, buttons2, null, null, null)
        state.stopRecording()

        state.seekToFrame(5)
        assertEquals(buttons1, state.getCurrentInput())

        state.seekToFrame(10)
        assertEquals(buttons2, state.getCurrentInput())
    }

    @Test
    fun `get current position returns correct position`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0x91F8, 100, 200)
        state.recordFrame(10, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0x92FD, 150, 250)
        state.stopRecording()

        state.seekToFrame(5)
        val pos1 = state.getCurrentPosition()
        assertNotNull(pos1)
        assertEquals(0x91F8, pos1.roomId)

        state.seekToFrame(10)
        val pos2 = state.getCurrentPosition()
        assertNotNull(pos2)
        assertEquals(0x92FD, pos2.roomId)
    }

    @Test
    fun `advance playback frame increments current frame`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(10, listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()
        state.startPlayback()

        val initialFrame = state.currentFrame
        state.advancePlaybackFrame()

        assertEquals(initialFrame + 1, state.currentFrame)
        assertEquals(RoutePlaybackState.PLAYING, state.playbackState)
    }

    @Test
    fun `advance playback frame stops at end of route`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()
        state.startPlayback()

        state.advancePlaybackFrame()

        assertEquals(RoutePlaybackState.IDLE, state.playbackState)
    }

    @Test
    fun `clear route removes route and resets state`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()

        state.clearRoute()

        assertNull(state.currentRoute)
        assertEquals(0, state.currentFrame)
        assertEquals(RoutePlaybackState.IDLE, state.playbackState)
    }

    @Test
    fun `trim route reduces frame count`() {
        val state = RouteEditorState()
        state.startRecording("test_state")
        state.recordFrame(0, listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(10, listOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.recordFrame(20, listOf(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, null)
        state.stopRecording()

        state.trimRoute(15)

        val route = state.currentRoute
        assertNotNull(route)
        assertEquals(15, route.frameCount)
        assertEquals(2, route.inputs.size)
    }

    @Test
    fun `save and load route roundtrip`(@TempDir tempDir: File) = runTest {
        val state = RouteEditorState()
        state.routeDirectory = tempDir.absolutePath
        state.startRecording("test_state")
        val buttons = listOf(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
        state.recordFrame(0, buttons, 0x91F8, 100, 200)
        state.stopRecording()

        state.saveRoute("test_route")
        state.clearRoute()
        state.loadRoute("test_route")

        val route = state.currentRoute
        assertNotNull(route)
        assertEquals("test_route", route.name)
        assertEquals(1, route.inputs.size)
        assertEquals(1, route.positions.size)
    }

    @Test
    fun `refresh available routes lists json files`(@TempDir tempDir: File) = runTest {
        val state = RouteEditorState()
        state.routeDirectory = tempDir.absolutePath

        File(tempDir, "route1.json").writeText("{}")
        File(tempDir, "route2.json").writeText("{}")
        File(tempDir, "not_a_route.txt").writeText("{}")

        state.refreshAvailableRoutes()

        assertEquals(2, state.availableRoutes.size)
        assertTrue(state.availableRoutes.contains("route1"))
        assertTrue(state.availableRoutes.contains("route2"))
        assertFalse(state.availableRoutes.contains("not_a_route"))
    }
}
