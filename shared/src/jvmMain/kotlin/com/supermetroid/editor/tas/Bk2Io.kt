package com.supermetroid.editor.tas

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Read/write BK2 movie archives compatible with the stable-retro recordings
 * used by platformer_common (`bk2_extract.py`, `record_tasker.py`).
 *
 * A BK2 is a zip with:
 * - `Input Log.txt` — `[Input]` header, a key line, then one `|..|XXXXXXXXXXXX|`
 *   line per frame. Button columns are env order *reversed*
 *   (R, L, X, A, Right, Left, Down, Up, Start, Select, Y, B); any char other
 *   than '.' counts as pressed.
 * - `Header.txt` — `Key Value` metadata lines.
 * - `Core.bin` — optional serialized start state of the recording emulator.
 *   Only loadable by the same core build that wrote it; input logs are
 *   portable across emulators, states generally are not.
 */
object Bk2Io {

    /** Mnemonics for bk2 column order (reverse of [TasInput.MNEMONICS]). */
    private val BK2_MNEMONICS = TasInput.MNEMONICS.reversedArray()

    private const val KEY_LINE =
        "P1 R|P1 L|P1 X|P1 A|P1 Right|P1 Left|P1 Down|P1 Up|P1 Start|P1 Select|P1 Y|P1 B|"

    data class Bk2Archive(
        val movie: TasMovie,
        /** Raw serialized core state from Core.bin, if present (gzip already stripped). */
        val coreState: ByteArray?,
    )

    fun read(file: File): Bk2Archive {
        ZipFile(file).use { zip ->
            val inputLog = zip.getEntry("Input Log.txt")
                ?: throw IllegalArgumentException("Not a bk2 movie (no Input Log.txt): $file")
            val frames = zip.getInputStream(inputLog).bufferedReader().useLines { lines ->
                lines.mapNotNull { parseInputLine(it) }.toList()
            }
            val header = zip.getEntry("Header.txt")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText()
            } ?: ""
            val coreState = zip.getEntry("Core.bin")?.let { entry ->
                maybeGunzip(zip.getInputStream(entry).readBytes())
            }
            return Bk2Archive(TasMovie(parseHeader(header), frames), coreState)
        }
    }

    fun write(file: File, movie: TasMovie, coreState: ByteArray? = null) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("Input Log.txt"))
            val log = StringBuilder("[Input]\n").append(KEY_LINE).append('\n')
            for (frame in movie.frames) {
                log.append("|..|")
                for (col in 0 until TasInput.NUM_BUTTONS) {
                    val envIdx = TasInput.NUM_BUTTONS - 1 - col
                    log.append(if (frame[envIdx] != 0) BK2_MNEMONICS[col] else '.')
                }
                log.append("|\n")
            }
            zip.write(log.toString().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Header.txt"))
            zip.write(buildHeader(movie.meta).toByteArray())
            zip.closeEntry()

            if (coreState != null) {
                zip.putNextEntry(ZipEntry("Core.bin"))
                zip.write(coreState)
                zip.closeEntry()
            }
        }
    }

    /**
     * Load a serialized core state from disk, transparently handling both the
     * editor's raw `.state` files and stable-retro's gzip-compressed ones.
     */
    fun loadStateFile(file: File): ByteArray = maybeGunzip(file.readBytes())

    private fun maybeGunzip(bytes: ByteArray): ByteArray {
        val isGzip = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!isGzip) return bytes
        val out = ByteArrayOutputStream(bytes.size * 4)
        GZIPInputStream(bytes.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun parseInputLine(rawLine: String): IntArray? {
        val line = rawLine.trim()
        if (line.isEmpty() || !line.startsWith("|") || line.startsWith("[")) return null
        val groups = line.split('|').filter { it.isNotEmpty() }
        if (groups.size < 2) return null
        val p1 = groups[1]
        if (p1.length < TasInput.NUM_BUTTONS) return null
        val frame = IntArray(TasInput.NUM_BUTTONS)
        for (col in 0 until TasInput.NUM_BUTTONS) {
            if (p1[col] != '.') frame[TasInput.NUM_BUTTONS - 1 - col] = 1
        }
        return frame
    }

    private fun parseHeader(text: String): TasMovieMeta {
        val entries = text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(' ')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1).trim()
            }
            .toMap()
        return TasMovieMeta(
            gameName = entries["GameName"] ?: "SuperMetroid-Snes",
            romSha1 = entries["SHA1"]?.takeIf { it != "?" },
            author = entries["Author"]?.takeIf { it != "?" },
            rerecordCount = entries["rerecordCount"]?.toIntOrNull() ?: 0,
        )
    }

    private fun buildHeader(meta: TasMovieMeta): String = buildString {
        appendLine("MovieVersion Retro")
        appendLine("Author ${meta.author ?: "?"}")
        appendLine("emuVersion ?")
        appendLine("Platform SNES")
        appendLine("GameName ${meta.gameName}")
        appendLine("SHA1 ${meta.romSha1 ?: "?"}")
        appendLine("Core snes9x")
        appendLine("rerecordCount ${meta.rerecordCount}")
    }
}
