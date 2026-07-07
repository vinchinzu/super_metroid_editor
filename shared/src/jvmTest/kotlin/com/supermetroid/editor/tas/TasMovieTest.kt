package com.supermetroid.editor.tas

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TasMovieTest {

    @Test
    fun `frame mnemonic encoding round-trips`() {
        val frame = TasInput.frameOf(TasInput.B, TasInput.RIGHT, TasInput.SHOULDER_R)
        val encoded = TasInput.encodeFrame(frame)
        assertEquals("B......r...R", encoded)
        assertArrayEquals(frame, TasInput.decodeFrame(encoded))
    }

    @Test
    fun `noop frame encodes to dots`() {
        assertEquals("............", TasInput.encodeFrame(TasInput.noop()))
    }

    @Test
    fun `movie json round-trips with metadata`() {
        val movie = TasMovie(
            meta = TasMovieMeta(startState = "ZebesStart", author = "test", rerecordCount = 3),
            frames = listOf(
                TasInput.frameOf(TasInput.RIGHT),
                TasInput.frameOf(TasInput.RIGHT, TasInput.A),
                TasInput.noop(),
            ),
        )
        val restored = TasMovie.fromJson(movie.toJson())
        assertEquals(3, restored.frameCount)
        assertEquals("ZebesStart", restored.meta.startState)
        assertEquals(3, restored.meta.rerecordCount)
        for (i in 0 until 3) {
            assertArrayEquals(movie.frames[i], restored.frames[i])
        }
    }

    @Test
    fun `splice replaces frames and pads with noops`() {
        val movie = TasMovie(frames = listOf(TasInput.frameOf(TasInput.B)))
        val spliced = movie.spliced(2, listOf(TasInput.frameOf(TasInput.A)))
        assertEquals(3, spliced.frameCount)
        assertArrayEquals(TasInput.frameOf(TasInput.B), spliced.frames[0])
        assertArrayEquals(TasInput.noop(), spliced.frames[1])
        assertArrayEquals(TasInput.frameOf(TasInput.A), spliced.frames[2])
    }

    @Test
    fun `bk2 write and read round-trips`(@TempDir dir: File) {
        val movie = TasMovie(
            meta = TasMovieMeta(author = "roundtrip", rerecordCount = 7),
            frames = listOf(
                TasInput.frameOf(TasInput.RIGHT, TasInput.B),
                TasInput.noop(),
                TasInput.frameOf(TasInput.UP, TasInput.SHOULDER_L, TasInput.START),
            ),
        )
        val fakeState = ByteArray(64) { it.toByte() }
        val file = File(dir, "roundtrip.bk2")
        Bk2Io.write(file, movie, fakeState)

        val archive = Bk2Io.read(file)
        assertEquals(3, archive.movie.frameCount)
        assertEquals("roundtrip", archive.movie.meta.author)
        assertEquals(7, archive.movie.meta.rerecordCount)
        for (i in 0 until 3) {
            assertArrayEquals(movie.frames[i], archive.movie.frames[i])
        }
        assertArrayEquals(fakeState, archive.coreState)
    }

    @Test
    fun `reads stable-retro recording from the RL project`() {
        val bk2 = File("../../recordings/SuperMetroid-Snes-ZebesStart-000000.bk2")
        assumeTrue(bk2.isFile, "RL project recording not available")

        val archive = Bk2Io.read(bk2)
        assertTrue(archive.movie.frameCount > 0, "Should parse input frames")
        assertNotNull(archive.coreState, "stable-retro recordings embed Core.bin")
        assertEquals("SuperMetroid-Snes", archive.movie.meta.gameName)
    }

    @Test
    fun `state loader strips gzip transparently`(@TempDir dir: File) {
        val payload = ByteArray(256) { (it * 7).toByte() }
        val gz = File(dir, "test.state")
        java.util.zip.GZIPOutputStream(gz.outputStream()).use { it.write(payload) }
        assertArrayEquals(payload, Bk2Io.loadStateFile(gz))

        val raw = File(dir, "raw.state")
        raw.writeBytes(payload)
        assertArrayEquals(payload, Bk2Io.loadStateFile(raw))
    }
}
