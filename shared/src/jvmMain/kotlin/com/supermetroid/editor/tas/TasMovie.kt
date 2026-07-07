package com.supermetroid.editor.tas

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * An input movie: one 12-button frame per emulated frame, plus metadata.
 *
 * This is the shared currency between the editor UI, the headless CLI runner,
 * and the Python optimization stack (hill climbing / genetic search). Frames
 * are immutable; edits produce new movies so optimizers can branch cheaply.
 *
 * Native format is JSON (`.tasmovie.json`) with one mnemonic string per frame
 * (see [TasInput.encodeFrame]); BK2 import/export lives in [Bk2Io].
 */
class TasMovie(
    val meta: TasMovieMeta = TasMovieMeta(),
    frames: List<IntArray> = emptyList(),
) {
    val frames: List<IntArray> = frames.map { TasInput.sanitize(it.toList()) }

    val frameCount: Int get() = frames.size

    /** Frame at [index], or a noop past the end (movies are noop-padded conceptually). */
    fun frameAt(index: Int): IntArray =
        if (index in frames.indices) frames[index] else TasInput.noop()

    fun withMeta(meta: TasMovieMeta): TasMovie = TasMovie(meta, frames)

    /** Truncate to [length] frames (rerecord from a mid-movie state). */
    fun truncated(length: Int): TasMovie = TasMovie(meta, frames.take(length))

    fun appended(frame: IntArray): TasMovie = TasMovie(meta, frames + listOf(frame))

    fun appendedAll(newFrames: List<IntArray>): TasMovie = TasMovie(meta, frames + newFrames)

    /** Replace frames [start, start+replacement.size) — the core splice op for optimizers. */
    fun spliced(start: Int, replacement: List<IntArray>): TasMovie {
        require(start >= 0) { "Splice start must be non-negative" }
        val result = frames.toMutableList()
        while (result.size < start + replacement.size) result.add(TasInput.noop())
        for (i in replacement.indices) result[start + i] = replacement[i]
        return TasMovie(meta, result)
    }

    fun toJson(pretty: Boolean = false): String {
        val doc = TasMovieDoc(
            meta = meta,
            buttonOrder = TasInput.BUTTON_ORDER,
            frames = frames.map { TasInput.encodeFrame(it) },
        )
        return (if (pretty) jsonPretty else jsonCompact).encodeToString(doc)
    }

    fun save(file: File, pretty: Boolean = false) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(pretty))
    }

    companion object {
        const val FORMAT = "smedit-tas-1"
        const val FILE_EXTENSION = "tasmovie.json"

        private val jsonPretty = Json { prettyPrint = true; ignoreUnknownKeys = true }
        private val jsonCompact = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): TasMovie {
            val doc = jsonCompact.decodeFromString<TasMovieDoc>(text)
            require(doc.format == FORMAT) { "Unsupported movie format: ${doc.format}" }
            return TasMovie(doc.meta, doc.frames.map { TasInput.decodeFrame(it) })
        }

        fun load(file: File): TasMovie = fromJson(file.readText())
    }
}

@Serializable
data class TasMovieMeta(
    val gameName: String = "SuperMetroid-Snes",
    /** SHA-1 of the ROM the movie was made against (original or edited export). */
    val romSha1: String? = null,
    /** Save state the movie starts from; null means power-on. */
    val startState: String? = null,
    val author: String? = null,
    val rerecordCount: Int = 0,
    val createdAtEpochMs: Long = 0,
    val notes: String? = null,
)

@Serializable
private data class TasMovieDoc(
    val format: String = TasMovie.FORMAT,
    val meta: TasMovieMeta = TasMovieMeta(),
    val buttonOrder: List<String> = TasInput.BUTTON_ORDER,
    val frames: List<String> = emptyList(),
)
